package com.liskovsoft.smartyoutubetv2.tv.ui.playback.ambilight;

import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.RequiresApi;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;

/**
 * Ambilight для плеєра: знімає кадр відео через PixelCopy.request,
 * усереднює кольори по краях і шле на ESP32 по UDP пакет [R,G,B, R,G,B, ...].
 *
 * Кількість LED на ESP = довжина пакета / 3, тому розкладка нижче має збігатися з GEOM у прошивці.
 * Порядок LED (як у прошивці): початок — лівий нижній кут, за годинниковою стрілкою:
 * ліва сторона вгору -> верх вправо -> права вниз -> низ вліво.
 * Для 32/58: ліва 0-31, верх 32-89, права 90-121, низ 122-179.
 */
@RequiresApi(Build.VERSION_CODES.N)
public class AmbilightController {
    private static final String TAG = "AmbilightController";

    // ---- Налаштування ----
    private static final String HOST = "192.168.31.5";
    private static final int PORT = 2132;
    private static final int FPS = 10;
    private static final int V_LEDS = 32;          // LED на кожній вертикальній стороні
    private static final int H_LEDS = 58;          // LED на кожній горизонтальній стороні
    private static final float DEPTH_X = 0.10f;    // глибина зони збоку, частка ширини кадру
    private static final float DEPTH_Y = 0.10f;    // глибина зони зверху/знизу, частка висоти кадру
    private static final float NEW_FRAME_WEIGHT = 0.7f; // 1 = без згладжування, менше = плавніше
    // ----------------------

    private static final int INTERVAL_MS = 1000 / FPS;
    private static final int CAP_W = 160;          // розмір зменшеного кадру, у який копіюється відео
    private static final int CAP_H = 90;
    private static final int LED_COUNT = 2 * V_LEDS + 2 * H_LEDS;
    private static final long COPY_TIMEOUT_MS = 1000;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private HandlerThread mThread;
    private Handler mBg;

    private volatile boolean mRunning;
    private volatile View mRoot;
    private volatile SurfaceView mSurfaceView;

    // Усе нижче використовується лише з фонового потоку
    private final Bitmap mBitmap = Bitmap.createBitmap(CAP_W, CAP_H, Bitmap.Config.ARGB_8888);
    private final int[] mPixels = new int[CAP_W * CAP_H];
    private final int[] mX0 = new int[LED_COUNT];
    private final int[] mX1 = new int[LED_COUNT];
    private final int[] mY0 = new int[LED_COUNT];
    private final int[] mY1 = new int[LED_COUNT];
    private final float[] mSmooth = new float[LED_COUNT * 3];
    private final byte[] mPacket = new byte[LED_COUNT * 3];
    private boolean mHasPrevious;
    private boolean mCopyInFlight;
    private long mCopyStartedAt;
    private long mNextTickAt;
    private long mLastResolveAt;
    private int mFailures;
    private DatagramSocket mSocket;
    private DatagramPacket mDatagram;

    public AmbilightController(View root) {
        mRoot = root;
        buildLayout();
    }

    public void start() {
        if (mRunning) {
            return;
        }
        mRunning = true;
        mThread = new HandlerThread("ambilight");
        mThread.start();
        mBg = new Handler(mThread.getLooper());
        resolveSurfaceView();
        mNextTickAt = SystemClock.uptimeMillis();
        mBg.post(mTick);
    }

    /** Викликати з головного потоку. */
    public void stop() {
        if (!mRunning) {
            return;
        }
        mRunning = false;
        mRoot = null;
        mSurfaceView = null;
        mBg.removeCallbacks(mTick);
        mBg.post(new Runnable() {
            @Override
            public void run() {
                // Один чорний кадр, щоб стрічка погасла одразу, а не за таймаутом ESP
                Arrays.fill(mPacket, (byte) 0);
                send();
                if (mSocket != null) {
                    mSocket.close();
                    mSocket = null;
                }
            }
        });
        mThread.quitSafely();
    }

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            if (!mRunning) {
                return;
            }
            requestFrame();

            mNextTickAt += INTERVAL_MS;
            long now = SystemClock.uptimeMillis();
            if (mNextTickAt < now) {
                mNextTickAt = now + INTERVAL_MS;
            }
            mBg.postAtTime(this, mNextTickAt);
        }
    };

    private void requestFrame() {
        long now = SystemClock.uptimeMillis();

        if (mCopyInFlight) {
            if (now - mCopyStartedAt < COPY_TIMEOUT_MS) {
                return;
            }
            mCopyInFlight = false; // запит завис — пробуємо знову
        }

        SurfaceView sv = mSurfaceView;
        if (sv == null || sv.getWidth() <= 0 || sv.getHeight() <= 0 || !sv.getHolder().getSurface().isValid()) {
            if (now - mLastResolveAt > 1000) { // перешукати SurfaceView (плеєр міг перестворитись)
                mLastResolveAt = now;
                mMain.post(new Runnable() {
                    @Override
                    public void run() {
                        resolveSurfaceView();
                    }
                });
            }
            return;
        }

        mCopyInFlight = true;
        mCopyStartedAt = now;
        try {
            PixelCopy.request(sv, mBitmap, mCopyListener, mBg);
        } catch (RuntimeException e) {
            mCopyInFlight = false;
            logFailure("PixelCopy.request: " + e);
        }
    }

    private final PixelCopy.OnPixelCopyFinishedListener mCopyListener = new PixelCopy.OnPixelCopyFinishedListener() {
        @Override
        public void onPixelCopyFinished(int result) {
            mCopyInFlight = false;
            if (!mRunning) {
                return;
            }
            if (result == PixelCopy.SUCCESS) {
                mFailures = 0;
                buildAndSend();
            } else {
                logFailure("PixelCopy result=" + result + " (3 = немає даних, 4 = surface недійсний)");
            }
        }
    };

    private void buildAndSend() {
        mBitmap.getPixels(mPixels, 0, CAP_W, 0, 0, CAP_W, CAP_H);

        for (int i = 0; i < LED_COUNT; i++) {
            long r = 0, g = 0, b = 0;
            int count = 0;
            for (int y = mY0[i]; y < mY1[i]; y++) {
                int row = y * CAP_W;
                for (int x = mX0[i]; x < mX1[i]; x++) {
                    int p = mPixels[row + x];
                    r += (p >> 16) & 0xFF;
                    g += (p >> 8) & 0xFF;
                    b += p & 0xFF;
                    count++;
                }
            }
            if (count == 0) {
                count = 1;
            }
            smoothAndStore(i, (float) r / count, (float) g / count, (float) b / count);
        }

        mHasPrevious = true;
        send();
    }

    private void smoothAndStore(int led, float r, float g, float b) {
        int o = led * 3;
        if (mHasPrevious) {
            r = mSmooth[o] + (r - mSmooth[o]) * NEW_FRAME_WEIGHT;
            g = mSmooth[o + 1] + (g - mSmooth[o + 1]) * NEW_FRAME_WEIGHT;
            b = mSmooth[o + 2] + (b - mSmooth[o + 2]) * NEW_FRAME_WEIGHT;
        }
        mSmooth[o] = r;
        mSmooth[o + 1] = g;
        mSmooth[o + 2] = b;
        mPacket[o] = (byte) Math.round(r);
        mPacket[o + 1] = (byte) Math.round(g);
        mPacket[o + 2] = (byte) Math.round(b);
    }

    private void send() {
        try {
            if (mSocket == null) {
                mSocket = new DatagramSocket();
                mDatagram = new DatagramPacket(mPacket, mPacket.length, InetAddress.getByName(HOST), PORT);
            }
            mSocket.send(mDatagram);
        } catch (IOException e) {
            logFailure("UDP send: " + e);
            if (mSocket != null) {
                mSocket.close();
                mSocket = null;
            }
        }
    }

    /** Головний потік: шукає SurfaceView з відео в ієрархії фрагмента. */
    private void resolveSurfaceView() {
        View root = mRoot;
        if (root == null) {
            return;
        }
        mSurfaceView = findBestSurfaceView(root, null);
    }

    private static SurfaceView findBestSurfaceView(View view, SurfaceView best) {
        if (view instanceof SurfaceView) {
            SurfaceView sv = (SurfaceView) view;
            if (sv.getHolder().getSurface().isValid() && sv.getWidth() > 0 && sv.getHeight() > 0) {
                if (best == null || sv.getWidth() * sv.getHeight() > best.getWidth() * best.getHeight()) {
                    best = sv;
                }
            }
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                best = findBestSurfaceView(group.getChildAt(i), best);
            }
        }
        return best;
    }

    /** Прямокутники вибірки в зменшеному кадрі для кожного LED. */
    private void buildLayout() {
        int depthX = Math.max(1, Math.round(CAP_W * DEPTH_X));
        int depthY = Math.max(1, Math.round(CAP_H * DEPTH_Y));
        int led = 0;

        // Ліва сторона, знизу вгору
        for (int k = 0; k < V_LEDS; k++, led++) {
            setRect(led, 0, depthX, span(CAP_H, V_LEDS, V_LEDS - 1 - k)[0], span(CAP_H, V_LEDS, V_LEDS - 1 - k)[1]);
        }
        // Верх, зліва направо
        for (int k = 0; k < H_LEDS; k++, led++) {
            setRect(led, span(CAP_W, H_LEDS, k)[0], span(CAP_W, H_LEDS, k)[1], 0, depthY);
        }
        // Права сторона, зверху вниз
        for (int k = 0; k < V_LEDS; k++, led++) {
            setRect(led, CAP_W - depthX, CAP_W, span(CAP_H, V_LEDS, k)[0], span(CAP_H, V_LEDS, k)[1]);
        }
        // Низ, справа наліво
        for (int k = 0; k < H_LEDS; k++, led++) {
            setRect(led, span(CAP_W, H_LEDS, H_LEDS - 1 - k)[0], span(CAP_W, H_LEDS, H_LEDS - 1 - k)[1], CAP_H - depthY, CAP_H);
        }
    }

    /** Відрізок [from, to) для сегмента index із count по довжині size; мінімум 1 піксель. */
    private static int[] span(int size, int count, int index) {
        int from = index * size / count;
        int to = (index + 1) * size / count;
        if (to <= from) {
            to = from + 1;
        }
        if (to > size) {
            to = size;
            from = Math.max(0, to - 1);
        }
        return new int[]{from, to};
    }

    private void setRect(int led, int x0, int x1, int y0, int y1) {
        mX0[led] = x0;
        mX1[led] = x1;
        mY0[led] = y0;
        mY1[led] = y1;
    }

    private void logFailure(String message) {
        mFailures++;
        if (mFailures == 1 || mFailures % 50 == 0) {
            Log.w(TAG, message + " (підряд помилок: " + mFailures + ")");
        }
    }
}
