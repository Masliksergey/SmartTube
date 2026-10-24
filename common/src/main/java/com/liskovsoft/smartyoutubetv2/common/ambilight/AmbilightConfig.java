package com.liskovsoft.smartyoutubetv2.common.ambilight;

import android.content.Context;

/**
 * Незмінний знімок налаштувань Ambilight на момент старту (щоб налаштування не змінювались посеред кадру).
 */
public final class AmbilightConfig {
    public final boolean enabled;
    public final String host;
    public final int port;
    public final int ledsH;
    public final int ledsV;
    public final int ledCount;
    public final int fps;
    public final long intervalMs;
    /** Вага нового кадру в згладжуванні: 0.1 (плавно) ... 1.0 (миттєво). */
    public final float newFrameWeight;
    public final int marginPercent;
    public final int depthPercent;
    public final int startPoint;
    public final boolean clockwise;
    /** Множник яскравості 0.05 ... 1.0. */
    public final float brightness;

    private AmbilightConfig(AmbilightPrefs p) {
        enabled = p.isEnabled();
        host = p.getHost();
        port = p.getPort();
        ledsH = p.getLedsH();
        ledsV = p.getLedsV();
        ledCount = 2 * (ledsH + ledsV);
        fps = p.getFps();
        intervalMs = 1000L / fps;
        newFrameWeight = p.getReaction() / 10f;
        marginPercent = p.getMargin();
        depthPercent = p.getDepth();
        startPoint = p.getStartPoint();
        clockwise = p.isClockwise();
        brightness = p.getBrightness() / 100f;
    }

    public static AmbilightConfig load(Context context) {
        return new AmbilightConfig(new AmbilightPrefs(context));
    }
}
