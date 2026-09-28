package edu.osu.pcv.marslogger.streaming;

import android.content.SharedPreferences;

/**
 * Streaming options from the settings screen. Missing or invalid values fall back to the
 * defaults, so a bad entry never keeps the streamers from starting.
 */
public final class StreamingSettings {

    public static final String KEY_VIDEO_PORT = "prefVideoPort";
    public static final String KEY_SENSOR_PORT = "prefSensorPort";
    public static final String KEY_JPEG_QUALITY = "prefJpegQuality";

    // Ports below 1024 are reserved for the system.
    public static final int MIN_PORT = 1024;
    public static final int MAX_PORT = 65535;

    private StreamingSettings() {
    }

    public static int videoPort(SharedPreferences prefs) {
        return readPort(prefs, KEY_VIDEO_PORT, VideoStreamer.DEFAULT_PORT);
    }

    public static int sensorPort(SharedPreferences prefs) {
        return readPort(prefs, KEY_SENSOR_PORT, SensorStreamer.DEFAULT_PORT);
    }

    public static int jpegQuality(SharedPreferences prefs) {
        int quality = readInt(prefs, KEY_JPEG_QUALITY, VideoStreamer.DEFAULT_JPEG_QUALITY);
        return quality >= 1 && quality <= 100 ? quality : VideoStreamer.DEFAULT_JPEG_QUALITY;
    }

    public static boolean isValidPort(String value) {
        try {
            int port = Integer.parseInt(value.trim());
            return port >= MIN_PORT && port <= MAX_PORT;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static int readPort(SharedPreferences prefs, String key, int fallback) {
        String value = prefs.getString(key, null);
        return value != null && isValidPort(value) ? Integer.parseInt(value.trim()) : fallback;
    }

    private static int readInt(SharedPreferences prefs, String key, int fallback) {
        try {
            return Integer.parseInt(prefs.getString(key, String.valueOf(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
