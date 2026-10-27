package com.rkdesign.rkcamera;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.List;

/**
 * Mode Malam / Bulan: beberapa jepretan beruntun disejajarkan (phase correlation) lalu dirata-rata
 * untuk menekan noise, dicerahkan, dan dipertajam. Prinsipnya sama dengan "night mode" di HP lain,
 * tetapi dikerjakan sepenuhnya oleh software tanpa sensor khusus.
 */
final class NightStacker {
    private static final int OUT_SIDE = 2048;
    private static final int ALIGN_SIDE = 640;

    private NightStacker() { }

    static Bitmap merge(List<byte[]> jpegs, List<Integer> rotations, float soft, boolean moon) throws Exception {
        if (!OpenCVLoader.initLocal()) throw new IllegalStateException("Mesin gambar tidak dapat dimuat");
        Mat acc = null;
        Mat refSmall = null;
        int used = 0;
        int outW = 0, outH = 0;
        try {
            for (int i = 0; i < jpegs.size(); i++) {
                Bitmap bmp = decodeFrame(jpegs.get(i), rotations.get(i), soft);
                if (bmp == null) continue;
                if (acc == null) {
                    outW = bmp.getWidth();
                    outH = bmp.getHeight();
                } else if (bmp.getWidth() != outW || bmp.getHeight() != outH) {
                    Bitmap s = Bitmap.createScaledBitmap(bmp, outW, outH, true);
                    if (s != bmp) bmp.recycle();
                    bmp = s;
                }
                Mat rgba = new Mat();
                Utils.bitmapToMat(bmp, rgba);
                bmp.recycle();

                Mat small = smallGray(rgba);
                Mat aligned = rgba;
                if (refSmall == null) {
                    refSmall = small;
                    acc = Mat.zeros(rgba.size(), CvType.CV_32FC4);
                } else {
                    double[] shift = bestShift(refSmall, small);
                    small.release();
                    if (shift == null) {
                        rgba.release();
                        continue;
                    }
                    double k = outW / (double) refSmall.cols();
                    Mat m = new Mat(2, 3, CvType.CV_64F);
                    m.put(0, 0, 1, 0, shift[0] * k, 0, 1, shift[1] * k);
                    aligned = new Mat();
                    Imgproc.warpAffine(rgba, aligned, m, rgba.size(), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE);
                    rgba.release();
                    m.release();
                }
                Mat f = new Mat();
                aligned.convertTo(f, CvType.CV_32FC4);
                aligned.release();
                Core.add(acc, f, acc);
                f.release();
                used++;
            }
            if (acc == null || used == 0) throw new IllegalStateException("Tidak ada frame yang dapat digabung");
            Mat avg = new Mat();
            acc.convertTo(avg, CvType.CV_8UC4, 1.0 / used);
            try {
                brighten(avg, moon);
                sharpen(avg, moon);
                Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
                Utils.matToBitmap(avg, out);
                return out;
            } finally {
                avg.release();
            }
        } finally {
            if (acc != null) acc.release();
            if (refSmall != null) refSmall.release();
        }
    }

    private static Bitmap decodeFrame(byte[] jpeg, int rotation, float soft) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
        if (bounds.outWidth <= 0) return null;
        int want = Math.round(OUT_SIDE * Math.max(1f, soft));
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= want) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
        if (bmp == null) return null;
        if (rotation % 360 != 0) {
            Matrix m = new Matrix();
            m.postRotate(rotation);
            Bitmap r = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (r != bmp) bmp.recycle();
            bmp = r;
        }
        if (soft > 1.02f) {
            int cw = Math.max(16, Math.round(bmp.getWidth() / soft));
            int ch = Math.max(16, Math.round(bmp.getHeight() / soft));
            int x = Math.max(0, (bmp.getWidth() - cw) / 2);
            int y = Math.max(0, (bmp.getHeight() - ch) / 2);
            Bitmap c = Bitmap.createBitmap(bmp, x, y, Math.min(cw, bmp.getWidth() - x), Math.min(ch, bmp.getHeight() - y));
            if (c != bmp) bmp.recycle();
            bmp = c;
        }
        int longSide = Math.max(bmp.getWidth(), bmp.getHeight());
        if (longSide > OUT_SIDE) {
            float k = OUT_SIDE / (float) longSide;
            Bitmap s = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(bmp.getWidth() * k)),
                    Math.max(1, Math.round(bmp.getHeight() * k)), true);
            if (s != bmp) bmp.recycle();
            bmp = s;
        }
        return bmp;
    }

    /** Gambar abu-abu kecil (float 0..1) untuk mencari pergeseran antar frame. */
    private static Mat smallGray(Mat rgba) {
        Mat gray = new Mat(), small = new Mat(), f = new Mat();
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
        double k = Math.min(1.0, ALIGN_SIDE / (double) Math.max(gray.cols(), gray.rows()));
        Imgproc.resize(gray, small, new Size(Math.max(8, Math.round(gray.cols() * k)), Math.max(8, Math.round(gray.rows() * k))), 0, 0, Imgproc.INTER_AREA);
        small.convertTo(f, CvType.CV_32F, 1.0 / 255.0);
        gray.release();
        small.release();
        return f;
    }

    /**
     * Cari pergeseran (dalam piksel gambar kecil) yang menyejajarkan frame dengan acuan. Tanda hasil
     * phaseCorrelate bisa terbalik antar versi, jadi dua kandidat diuji dan yang paling cocok dipilih.
     * Mengembalikan null bila pergeseran terlalu besar atau tidak ada yang cocok.
     */
    private static double[] bestShift(Mat ref, Mat img) {
        Point p = Imgproc.phaseCorrelate(ref, img);
        double sx = p.x, sy = p.y;
        if (Math.abs(sx) > ref.cols() * 0.08 || Math.abs(sy) > ref.rows() * 0.08) return null;
        double[][] candidates = {{0, 0}, {sx, sy}, {-sx, -sy}};
        double bestErr = Double.MAX_VALUE;
        double[] best = null;
        for (double[] c : candidates) {
            Mat m = new Mat(2, 3, CvType.CV_64F);
            m.put(0, 0, 1, 0, c[0], 0, 1, c[1]);
            Mat shifted = new Mat(), diff = new Mat();
            Imgproc.warpAffine(img, shifted, m, img.size(), Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE);
            Core.absdiff(ref, shifted, diff);
            double err = Core.mean(diff).val[0];
            m.release(); shifted.release(); diff.release();
            if (err < bestErr) { bestErr = err; best = c; }
        }
        return best;
    }

    /** Cerahkan adegan gelap dengan kurva gamma sederhana. */
    private static void brighten(Mat rgba, boolean moon) {
        Mat gray = new Mat();
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
        double mean = Core.mean(gray).val[0] / 255.0;
        gray.release();
        double target = moon ? 0.18 : 0.42;
        double gamma = 1.0;
        if (mean > 0.005 && mean < target) gamma = Math.max(moon ? 0.80 : 0.55, Math.log(target) / Math.log(mean));
        if (gamma >= 0.99) return;
        Mat lut = new Mat(1, 256, CvType.CV_8UC1);
        byte[] data = new byte[256];
        for (int i = 0; i < 256; i++) data[i] = (byte) Math.round(255.0 * Math.pow(i / 255.0, gamma));
        lut.put(0, 0, data);
        Mat mapped = new Mat();
        Core.LUT(rgba, lut, mapped);
        mapped.copyTo(rgba);
        mapped.release();
        lut.release();
    }

    private static void sharpen(Mat rgba, boolean moon) {
        Mat blur = new Mat();
        double sigma = moon ? 2.2 : 1.4;
        double amount = moon ? 1.9 : 1.45;
        Imgproc.GaussianBlur(rgba, blur, new Size(0, 0), sigma);
        Core.addWeighted(rgba, amount, blur, 1.0 - amount, 0, rgba);
        blur.release();
    }
}
