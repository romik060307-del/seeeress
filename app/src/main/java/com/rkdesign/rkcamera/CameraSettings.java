package com.rkdesign.rkcamera;

import android.content.Context;
import android.content.SharedPreferences;

/** Pengaturan kamera yang disimpan di HP (SharedPreferences). */
final class CameraSettings {
    private final SharedPreferences prefs;

    CameraSettings(Context context) {
        prefs = context.getSharedPreferences("rkcamera_settings", Context.MODE_PRIVATE);
    }

    /** 0 = Mati, 1 = Standar, 2 = Maks (lihat VideoStabilizer). */
    int stabilizer() { return prefs.getInt("stabilizer", VideoStabilizer.STANDARD); }
    void setStabilizer(int value) { prefs.edit().putInt("stabilizer", value).apply(); }

    boolean horizonLock() { return prefs.getBoolean("horizon_lock", false); }
    void setHorizonLock(boolean value) { prefs.edit().putBoolean("horizon_lock", value).apply(); }

    int lookIndex() {
        int v = prefs.getInt("look", 0);
        return v < 0 || v >= IPhoneLook.ALL.length ? 0 : v;
    }
    void setLookIndex(int value) { prefs.edit().putInt("look", value).apply(); }

    /** 0 = HD 720p, 1 = FHD 1080p. */
    int videoQuality() { return prefs.getInt("video_quality", 0); }
    void setVideoQuality(int value) { prefs.edit().putInt("video_quality", value).apply(); }

    /** 0 = halus, 1 = sedang, 2 = kuat (blur latar mode Potret). */
    int portraitBlur() { return prefs.getInt("portrait_blur", 1); }
    void setPortraitBlur(int value) { prefs.edit().putInt("portrait_blur", value).apply(); }

    /** 0 = Galeri RK (dengan editor), 1 = galeri bawaan HP. */
    int galleryMode() { return prefs.getInt("gallery_mode", 0); }
    void setGalleryMode(int value) { prefs.edit().putInt("gallery_mode", value).apply(); }

    boolean grid() { return prefs.getBoolean("grid", false); }
    void setGrid(boolean value) { prefs.edit().putBoolean("grid", value).apply(); }
}
