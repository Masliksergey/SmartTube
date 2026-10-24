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

import com.liskovsoft.smartyoutubetv2.common.ambilight.AmbilightConfig;
import com.liskovsoft.smartyoutubetv2.common.ambilight.AmbilightLayout;
import com.liskovsoft.smartyoutubetv2.common.ambilight.AmbilightUdp;

import java.io.IOException;
import java.util.Arrays;

/**
 * Ambilight для плеєра: знімає кадр відео через PixelCopy.request,
 * усереднює кольори по краях і шле на ESP32 по UDP пакет [R,G,B, R,G,B, ...].
 *
 * Усі параметри (кількість LED, IP, порт, FPS, відступи, початок і напрямок стрічки, яскравість)
 * береться з налаштувань Ambilight на момент створення контролера.
 */
@RequiresApi(Build.VERSION_CODES.N)
public class AmbilightController {
    private static final String TAG = "AmbilightController";
    private static final long COPY_TIMEOUT_MS = 1000;

    private final AmbilightConfig mConfig;
    private final AmbilightLayout mLayout;
    private final AmbilightUdp mUdp;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private HandlerThread mThread;
    private Handler mBg;

    private volatile boolean mRunning;
    private volatile View mRoot;
    private volatile SurfaceView mSurfaceView;

    // Усе нижче використовується лише з фонового потоку
    private final Bitmap mBitmap = Bitmap.createBitmap(AmbilightLayout.CAP_W, AmbilightLayout.CAP_H, Bitmap.Config.ARGB_8888);
    private final int[] mPixels = new int[AmbilightLayout.CAP_W * AmbilightLayout.CAP_H];
    private final float[] mSmooth;
    private final byte[] mPacket;
    private boolean mHasPrevious;
    private boolean mCopyInFlight;
    private long mCopyStartedAt;
    private long mNextTickAt;
    private long mLastResolveAt;
    private int mFailures;

    public AmbilightController(View root) {
        mRoot = root;
        mConfig = AmbilightConfig.load(root.getContext());
        mLayout = new AmbilightLayout(mConfig);
        mUdp = new AmbilightUdp(mConfig.host, mConfig.port);
        mSmooth = new float[mConfig.ledCount * 3];
        mPacket = new byte[mConfig.ledCount * 3];
    }

    public void start() {
        if (mRunning || !mConfig.enabled) {
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
                mUdp.close();
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

            mNextTickAt += mConfig.intervalMs;
            long now = SystemClock.uptimeMillis();
            if (mNextTickAt < now) {
                mNextTickAt = now + mConfig.intervalMs;
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
        final int w = AmbilightLayout.CAP_W;
        mBitmap.getPixels(mPixels, 0, w, 0, 0, w, AmbilightLayout.CAP_H);

        for (int i = 0; i < mConfig.ledCount; i++) {
            long r = 0, g = 0, b = 0;
            int count = 0;
            for (int y = mLayout.y0[i]; y < mLayout.y1[i]; y++) {
                int row = y * w;
                for (int x = mLayout.x0[i]; x < mLayout.x1[i]; x++) {
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
            float k = mConfig.newFrameWeight;
            r = mSmooth[o] + (r - mSmooth[o]) * k;
            g = mSmooth[o + 1] + (g - mSmooth[o + 1]) * k;
            b = mSmooth[o + 2] + (b - mSmooth[o + 2]) * k;
        }
        mSmooth[o] = r;
        mSmooth[o + 1] = g;
        mSmooth[o + 2] = b;
        // Яскравість застосовується лише до вихідного кадру, згладжування працює на повних значеннях
        mPacket[o] = (byte) Math.round(r * mConfig.brightness);
        mPacket[o + 1] = (byte) Math.round(g * mConfig.brightness);
        mPacket[o + 2] = (byte) Math.round(b * mConfig.brightness);
    }

    private void send() {
        try {
            mUdp.send(mPacket);
        } catch (IOException e) {
            logFailure("UDP send: " + e);
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

    private void logFailure(String message) {
        mFailures++;
        if (mFailures == 1 || mFailures % 50 == 0) {
            Log.w(TAG, message + " (підряд помилок: " + mFailures + ")");
        }
    }
}
