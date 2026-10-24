package com.liskovsoft.smartyoutubetv2.common.ambilight;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.IOException;
import java.util.Arrays;

/**
 * Тест стрічки: усі LED червоні, зелені, сині, потім кожна сторона окремо.
 * На стороні перший LED (за напрямком стрічки) червоний, останній синій, решта слабко білі.
 */
public final class AmbilightTester {
    public interface Listener {
        /** Головний потік. Поточний крок. */
        void onStep(String label);

        /** Головний потік. Тест завершено або зупинено. */
        void onFinished();
    }

    private static final long STEP_MS = 2500;
    private static final long FRAME_INTERVAL_MS = 100; // ESP гасить стрічку, якщо кадрів немає кілька секунд
    private static final String[] STEP_NAMES = {
            "Усі LED: червоний",
            "Усі LED: зелений",
            "Усі LED: синій",
            "Ліва сторона: перший червоний, останній синій",
            "Верхня сторона: перший червоний, останній синій",
            "Права сторона: перший червоний, останній синій",
            "Нижня сторона: перший червоний, останній синій"
    };

    private final Context mContext;
    private final Listener mListener;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private volatile boolean mRunning;
    private Thread mThread;

    public AmbilightTester(Context context, Listener listener) {
        mContext = context.getApplicationContext();
        mListener = listener;
    }

    public boolean isRunning() {
        return mRunning;
    }

    public void start() {
        if (mRunning) {
            return;
        }
        mRunning = true;

        final AmbilightConfig config = AmbilightConfig.load(mContext);
        final AmbilightLayout layout = new AmbilightLayout(config);

        mThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runTest(config, layout);
            }
        }, "ambilight-test");
        mThread.start();
    }

    /** Зупиняє тест; чорний кадр відправляє сам потік тесту. */
    public void stop() {
        mRunning = false;
    }

    private void runTest(AmbilightConfig config, AmbilightLayout layout) {
        AmbilightUdp udp = new AmbilightUdp(config.host, config.port);
        try {
            for (int step = 0; step < STEP_NAMES.length && mRunning; step++) {
                final String label = STEP_NAMES[step];
                mMain.post(new Runnable() {
                    @Override
                    public void run() {
                        mListener.onStep(label);
                    }
                });

                byte[] frame = buildFrame(step, config, layout);
                long end = SystemClock.uptimeMillis() + STEP_MS;
                while (mRunning && SystemClock.uptimeMillis() < end) {
                    udp.send(frame);
                    SystemClock.sleep(FRAME_INTERVAL_MS);
                }
            }
        } catch (IOException e) {
            // Немає зв'язку з ESP: тест просто завершується
        } finally {
            mRunning = false;
            try {
                udp.send(new byte[config.ledCount * 3]); // чорний кадр
            } catch (IOException ignored) {
            }
            udp.close();
            mMain.post(new Runnable() {
                @Override
                public void run() {
                    mListener.onFinished();
                }
            });
        }
    }

    private static byte[] buildFrame(int step, AmbilightConfig config, AmbilightLayout layout) {
        byte[] frame = new byte[config.ledCount * 3];
        int bright = Math.round(255 * config.brightness);
        int dim = Math.round(60 * config.brightness);

        if (step < 3) {
            for (int i = 0; i < config.ledCount; i++) {
                put(frame, i, step == 0 ? bright : 0, step == 1 ? bright : 0, step == 2 ? bright : 0);
            }
            return frame;
        }

        int side = step - 3;
        int start = layout.sideStart(side);
        int end = layout.sideEnd(side); // не включно
        // "Перший" — той, до якого стрічка доходить першим за своїм напрямком
        int first = config.clockwise ? start : end - 1;
        int last = config.clockwise ? end - 1 : start;

        Arrays.fill(frame, (byte) 0);
        for (int i = 0; i < config.ledCount; i++) {
            int pos = layout.ringPosition(i);
            if (pos < start || pos >= end) {
                continue;
            }
            if (pos == first) {
                put(frame, i, bright, 0, 0);
            } else if (pos == last) {
                put(frame, i, 0, 0, bright);
            } else {
                put(frame, i, dim, dim, dim);
            }
        }
        return frame;
    }

    private static void put(byte[] frame, int led, int r, int g, int b) {
        frame[led * 3] = (byte) r;
        frame[led * 3 + 1] = (byte) g;
        frame[led * 3 + 2] = (byte) b;
    }
}
