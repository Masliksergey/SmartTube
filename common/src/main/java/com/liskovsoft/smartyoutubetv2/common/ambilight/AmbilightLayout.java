package com.liskovsoft.smartyoutubetv2.common.ambilight;

/**
 * Відповідність між номером LED на стрічці (порядок байтів у пакеті) і ділянкою зменшеного кадру.
 *
 * Внутрішнє "кільце" позицій іде за годинниковою стрілкою від лівого нижнього кута:
 * ліва сторона вгору (V) -> верх вправо (H) -> права вниз (V) -> низ вліво (H).
 * Реальна стрічка починається з обраної точки і йде в обраному напрямку.
 */
public final class AmbilightLayout {
    public static final int SIDE_LEFT = 0;
    public static final int SIDE_TOP = 1;
    public static final int SIDE_RIGHT = 2;
    public static final int SIDE_BOTTOM = 3;

    public static final String[] SIDE_NAMES = {"Ліва сторона", "Верхня сторона", "Права сторона", "Нижня сторона"};

    /** Порядок: за годинниковою стрілкою від лівого нижнього кута. */
    public static final String[] START_POINT_NAMES = {
            "Лівий нижній кут",
            "Середина лівої сторони",
            "Лівий верхній кут",
            "Середина верхньої сторони",
            "Правий верхній кут",
            "Середина правої сторони",
            "Правий нижній кут",
            "Середина нижньої сторони"
    };

    /** Розмір зменшеного кадру, у який PixelCopy стискає відео. */
    public static final int CAP_W = 192;
    public static final int CAP_H = 108;

    public final int ledCount;
    public final int[] x0;
    public final int[] x1;
    public final int[] y0;
    public final int[] y1;

    private final int mLedsH;
    private final int mLedsV;
    private final int[] mRingPos;

    public AmbilightLayout(AmbilightConfig c) {
        mLedsH = c.ledsH;
        mLedsV = c.ledsV;
        ledCount = c.ledCount;
        x0 = new int[ledCount];
        x1 = new int[ledCount];
        y0 = new int[ledCount];
        y1 = new int[ledCount];
        mRingPos = new int[ledCount];

        int startPos = startPosition(c.startPoint);
        for (int i = 0; i < ledCount; i++) {
            mRingPos[i] = c.clockwise
                    ? mod(startPos + i, ledCount)
                    : mod(startPos - 1 - i, ledCount);
        }

        int marginPx = Math.round(CAP_H * c.marginPercent / 100f);
        int regionTop = marginPx;
        int regionBottom = CAP_H - marginPx;
        int regionH = Math.max(1, regionBottom - regionTop);
        int depthX = Math.max(1, Math.round(CAP_W * c.depthPercent / 100f));
        int depthY = Math.max(1, Math.round(regionH * c.depthPercent / 100f));

        for (int i = 0; i < ledCount; i++) {
            int p = mRingPos[i];
            int[] s;
            if (p < mLedsV) { // ліва, знизу вгору
                s = span(regionH, mLedsV, mLedsV - 1 - p);
                set(i, 0, depthX, regionTop + s[0], regionTop + s[1]);
            } else if (p < mLedsV + mLedsH) { // верх, зліва направо
                s = span(CAP_W, mLedsH, p - mLedsV);
                set(i, s[0], s[1], regionTop, regionTop + depthY);
            } else if (p < 2 * mLedsV + mLedsH) { // права, зверху вниз
                s = span(regionH, mLedsV, p - mLedsV - mLedsH);
                set(i, CAP_W - depthX, CAP_W, regionTop + s[0], regionTop + s[1]);
            } else { // низ, справа наліво
                s = span(CAP_W, mLedsH, mLedsH - 1 - (p - 2 * mLedsV - mLedsH));
                set(i, s[0], s[1], regionBottom - depthY, regionBottom);
            }
        }
    }

    /** Позиція на кільці LED з даним номером на стрічці. */
    public int ringPosition(int ledIndex) {
        return mRingPos[ledIndex];
    }

    /** Перша позиція кільця на стороні. */
    public int sideStart(int side) {
        switch (side) {
            case SIDE_LEFT: return 0;
            case SIDE_TOP: return mLedsV;
            case SIDE_RIGHT: return mLedsV + mLedsH;
            default: return 2 * mLedsV + mLedsH;
        }
    }

    /** Позиція кільця після останньої на стороні. */
    public int sideEnd(int side) {
        switch (side) {
            case SIDE_LEFT: return mLedsV;
            case SIDE_TOP: return mLedsV + mLedsH;
            case SIDE_RIGHT: return 2 * mLedsV + mLedsH;
            default: return 2 * mLedsV + 2 * mLedsH;
        }
    }

    private int startPosition(int startPoint) {
        switch (startPoint) {
            case 0: return 0;                                   // лівий нижній кут
            case 1: return mLedsV / 2;                          // середина лівої
            case 2: return mLedsV;                              // лівий верхній кут
            case 3: return mLedsV + mLedsH / 2;                 // середина верху
            case 4: return mLedsV + mLedsH;                     // правий верхній кут
            case 5: return mLedsV + mLedsH + mLedsV / 2;        // середина правої
            case 6: return 2 * mLedsV + mLedsH;                 // правий нижній кут
            default: return 2 * mLedsV + mLedsH + mLedsH / 2;   // середина низу
        }
    }

    private void set(int led, int ax0, int ax1, int ay0, int ay1) {
        x0[led] = ax0;
        x1[led] = ax1;
        y0[led] = ay0;
        y1[led] = ay1;
    }

    /** Відрізок [from, to) для сегмента index з count по довжині size; мінімум 1 піксель. */
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

    private static int mod(int value, int n) {
        int r = value % n;
        return r < 0 ? r + n : r;
    }
}
