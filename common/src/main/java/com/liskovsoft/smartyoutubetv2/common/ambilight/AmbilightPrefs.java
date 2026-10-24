package com.liskovsoft.smartyoutubetv2.common.ambilight;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Налаштування Ambilight (окреме сховище SharedPreferences, не залежить від решти налаштувань SmartTube).
 */
public final class AmbilightPrefs {
    private static final String PREFS_NAME = "ambilight_prefs";

    public static final String DEFAULT_HOST = "192.168.31.5";
    public static final int DEFAULT_PORT = 2132;
    public static final int DEFAULT_LEDS_H = 58;
    public static final int DEFAULT_LEDS_V = 32;
    public static final int DEFAULT_FPS = 10;
    public static final int DEFAULT_REACTION = 7;
    public static final int DEFAULT_MARGIN = 0;
    public static final int DEFAULT_DEPTH = 10;
    public static final int DEFAULT_START_POINT = 0;
    public static final int DEFAULT_BRIGHTNESS = 100;

    /** Прошивка ESP тримає не більше 480 LED у кадрі: 2 * (H + V) має вміститись. */
    public static final int MAX_TOTAL_LEDS = 480;

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_HOST = "host";
    private static final String KEY_PORT = "port";
    private static final String KEY_LEDS_H = "leds_h";
    private static final String KEY_LEDS_V = "leds_v";
    private static final String KEY_FPS = "fps";
    private static final String KEY_REACTION = "reaction";
    private static final String KEY_MARGIN = "margin";
    private static final String KEY_DEPTH = "depth";
    private static final String KEY_START_POINT = "start_point";
    private static final String KEY_CLOCKWISE = "clockwise";
    private static final String KEY_BRIGHTNESS = "brightness";

    private final SharedPreferences mPrefs;

    public AmbilightPrefs(Context context) {
        mPrefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean isEnabled() {
        return mPrefs.getBoolean(KEY_ENABLED, true);
    }

    public void setEnabled(boolean enabled) {
        mPrefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public String getHost() {
        String host = mPrefs.getString(KEY_HOST, DEFAULT_HOST);
        return host == null || host.trim().isEmpty() ? DEFAULT_HOST : host.trim();
    }

    public void setHost(String host) {
        mPrefs.edit().putString(KEY_HOST, host.trim()).apply();
    }

    public int getPort() {
        return clamp(mPrefs.getInt(KEY_PORT, DEFAULT_PORT), 1, 65535);
    }

    public void setPort(int port) {
        mPrefs.edit().putInt(KEY_PORT, clamp(port, 1, 65535)).apply();
    }

    public int getLedsH() {
        return clamp(mPrefs.getInt(KEY_LEDS_H, DEFAULT_LEDS_H), 1, 239);
    }

    public void setLedsH(int count) {
        mPrefs.edit().putInt(KEY_LEDS_H, clamp(count, 1, 239)).apply();
    }

    public int getLedsV() {
        return clamp(mPrefs.getInt(KEY_LEDS_V, DEFAULT_LEDS_V), 1, 239);
    }

    public void setLedsV(int count) {
        mPrefs.edit().putInt(KEY_LEDS_V, clamp(count, 1, 239)).apply();
    }

    public int getFps() {
        return clamp(mPrefs.getInt(KEY_FPS, DEFAULT_FPS), 5, 30);
    }

    public void setFps(int fps) {
        mPrefs.edit().putInt(KEY_FPS, clamp(fps, 5, 30)).apply();
    }

    /** 1 = дуже плавно, 10 = миттєво. */
    public int getReaction() {
        return clamp(mPrefs.getInt(KEY_REACTION, DEFAULT_REACTION), 1, 10);
    }

    public void setReaction(int reaction) {
        mPrefs.edit().putInt(KEY_REACTION, clamp(reaction, 1, 10)).apply();
    }

    /** Відступ зверху і знизу кадру у відсотках висоти (відсікання чорних смуг). */
    public int getMargin() {
        return clamp(mPrefs.getInt(KEY_MARGIN, DEFAULT_MARGIN), 0, 40);
    }

    public void setMargin(int percent) {
        mPrefs.edit().putInt(KEY_MARGIN, clamp(percent, 0, 40)).apply();
    }

    /** Глибина зони захвату у відсотках розміру кадру. */
    public int getDepth() {
        return clamp(mPrefs.getInt(KEY_DEPTH, DEFAULT_DEPTH), 2, 30);
    }

    public void setDepth(int percent) {
        mPrefs.edit().putInt(KEY_DEPTH, clamp(percent, 2, 30)).apply();
    }

    /** 0..7: кути і середини сторін, див. {@link AmbilightLayout#START_POINT_NAMES}. */
    public int getStartPoint() {
        return clamp(mPrefs.getInt(KEY_START_POINT, DEFAULT_START_POINT), 0, 7);
    }

    public void setStartPoint(int point) {
        mPrefs.edit().putInt(KEY_START_POINT, clamp(point, 0, 7)).apply();
    }

    public boolean isClockwise() {
        return mPrefs.getBoolean(KEY_CLOCKWISE, true);
    }

    public void setClockwise(boolean clockwise) {
        mPrefs.edit().putBoolean(KEY_CLOCKWISE, clockwise).apply();
    }

    public int getBrightness() {
        return clamp(mPrefs.getInt(KEY_BRIGHTNESS, DEFAULT_BRIGHTNESS), 5, 100);
    }

    public void setBrightness(int percent) {
        mPrefs.edit().putInt(KEY_BRIGHTNESS, clamp(percent, 5, 100)).apply();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
