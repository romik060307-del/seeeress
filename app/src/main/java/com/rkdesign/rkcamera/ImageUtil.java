package com.rkdesign.rkcamera;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;

/** Fungsi bantu gambar: dekode tegak (EXIF), rotasi, dan simpan JPEG ke galeri. */
final class ImageUtil {
    private ImageUtil() { }

    /** Ubah nilai EXIF orientation menjadi derajat rotasi searah jarum jam. */
    static int exifDegrees(String orientation) {
        if (orientation == null) return 0;
        switch (orientation.trim()) {
            case "6": return 90;
            case "3": return 180;
            case "8": return 270;
            default: return 0;
        }
    }

    static Bitmap rotate(Bitmap src, int degrees) {
        if (src == null || degrees % 360 == 0) return src;
        Matrix m = new Matrix();
        m.postRotate(degrees);
        Bitmap out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        if (out != src) src.recycle();
        return out;
    }

    /** Dekode gambar dari Uri, diperkecil bila sisi terpanjang melebihi maxSide, dan diputar tegak sesuai EXIF. */
    static Bitmap decodeUpright(ContentResolver cr, Uri uri, int maxSide) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("Tidak dapat membuka gambar");
            BitmapFactory.decodeStream(in, null, bounds);
        }
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp;
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("Tidak dapat membuka gambar");
            bmp = BitmapFactory.decodeStream(in, null, opts);
        }
        if (bmp == null) throw new IllegalStateException("Format gambar tidak didukung");
        int degrees = 0;
        try (InputStream in = cr.openInputStream(uri)) {
            if (in != null) {
                int o = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (o == ExifInterface.ORIENTATION_ROTATE_90) degrees = 90;
                else if (o == ExifInterface.ORIENTATION_ROTATE_180) degrees = 180;
                else if (o == ExifInterface.ORIENTATION_ROTATE_270) degrees = 270;
            }
        } catch (Exception ignored) { }
        bmp = rotate(bmp, degrees);
        int longSide = Math.max(bmp.getWidth(), bmp.getHeight());
        if (longSide > maxSide) {
            float k = maxSide / (float) longSide;
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(bmp.getWidth() * k)),
                    Math.max(1, Math.round(bmp.getHeight() * k)), true);
            if (scaled != bmp) bmp.recycle();
            bmp = scaled;
        }
        return bmp;
    }

    /** Buka aplikasi galeri bawaan HP. */
    static void openSystemGallery(Context context) {
        try {
            Intent view = new Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(view);
        } catch (Exception first) {
            try {
                Intent picker = new Intent(Intent.ACTION_GET_CONTENT);
                picker.setType("image/*");
                Intent chooser = Intent.createChooser(picker, "Pilih foto");
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(chooser);
            } catch (Exception second) {
                Toast.makeText(context, "Tidak ada aplikasi galeri yang dapat dibuka.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** Simpan bitmap sebagai JPEG ke DCIM/RK Camera AI. Mengembalikan Uri, atau null bila gagal. */
    static Uri saveJpeg(Context context, Bitmap bmp, String displayName) {
        ContentResolver cr = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/RK Camera AI");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        }
        Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) return null;
        boolean ok = false;
        try (OutputStream out = cr.openOutputStream(uri, "w")) {
            if (out == null) throw new IllegalStateException("Tidak dapat membuka tujuan penyimpanan");
            ok = bmp.compress(Bitmap.CompressFormat.JPEG, 95, out);
            out.flush();
        } catch (Exception e) {
            ok = false;
        } finally {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues finish = new ContentValues();
                finish.put(MediaStore.MediaColumns.IS_PENDING, ok ? 0 : 1);
                try { cr.update(uri, finish, null, null); } catch (Exception ignored) { }
            }
            if (!ok) { try { cr.delete(uri, null, null); } catch (Exception ignored) { } }
        }
        return ok ? uri : null;
    }
}
