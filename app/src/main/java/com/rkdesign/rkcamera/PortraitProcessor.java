package com.rkdesign.rkcamera;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.media.FaceDetector;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

/**
 * Efek Potret: pisahkan objek dari latar (GrabCut dari titik ketuk / wajah / tengah gambar),
 * lalu kaburkan latar dengan kekuatan yang naik menurut jarak dari objek (mirip kedalaman).
 * Ini pemisahan 2D, bukan peta kedalaman asli seperti sensor di iPhone, jadi tepi rambut
 * atau objek rumit bisa kurang rapi.
 */
final class PortraitProcessor {
    private static final int MASK_SIDE = 480;
    private static final int OUT_SIDE = 2560;

    private PortraitProcessor() { }

    /**
     * @param src      gambar tegak
     * @param tapX     titik subjek 0..1 (negatif = otomatis: wajah, lalu tengah gambar)
     * @param tapY     titik subjek 0..1
     * @param strength 0 halus, 1 sedang, 2 kuat
     */
    static Bitmap apply(Bitmap src, float tapX, float tapY, int strength) throws Exception {
        if (!OpenCVLoader.initLocal()) throw new IllegalStateException("Mesin gambar tidak dapat dimuat");
        int sw = src.getWidth(), sh = src.getHeight();
        float outK = Math.min(1f, OUT_SIDE / (float) Math.max(sw, sh));
        int outW = Math.max(2, Math.round(sw * outK));
        int outH = Math.max(2, Math.round(sh * outK));

        float maskK = Math.min(1f, MASK_SIDE / (float) Math.max(sw, sh));
        int mw = Math.max(8, Math.round(sw * maskK));
        int mh = Math.max(8, Math.round(sh * maskK));
        Bitmap small = Bitmap.createScaledBitmap(src, mw, mh, true);

        Mat rgba = new Mat(), rgb = new Mat(), mask = new Mat(), bgd = new Mat(), fgd = new Mat();
        Mat fg = new Mat(), dist = new Mat(), inv = new Mat();
        Mat base = new Mat(), tiny = new Mat(), blurLow = new Mat(), blurHigh = new Mat();
        Mat maskFull = new Mat(), depthFull = new Mat(), out = new Mat();
        try {
            Utils.bitmapToMat(small, rgba);
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB);

            // Titik awal subjek
            float cx, cy, radX, radY;
            boolean tapped = tapX >= 0f && tapY >= 0f;
            float[] face = tapped ? null : detectFace(small);
            if (tapped) {
                cx = tapX * mw; cy = tapY * mh;
                radX = radY = 0.20f * Math.min(mw, mh);
            } else if (face != null) {
                float e = face[2];
                cx = face[0]; cy = face[1] + e * 2.2f;
                radX = Math.max(e * 2.6f, mw * 0.12f);
                radY = Math.max(e * 4.2f, mh * 0.18f);
            } else {
                cx = mw * 0.5f; cy = mh * 0.5f;
                radX = mw * 0.22f; radY = mh * 0.30f;
            }

            mask.create(mh, mw, CvType.CV_8UC1);
            mask.setTo(new Scalar(Imgproc.GC_PR_BGD));
            int border = Math.max(3, Math.round(Math.min(mw, mh) * 0.03f));
            Imgproc.rectangle(mask, new Point(0, 0), new Point(mw - 1, border), new Scalar(Imgproc.GC_BGD), -1);
            Imgproc.rectangle(mask, new Point(0, mh - 1 - border), new Point(mw - 1, mh - 1), new Scalar(Imgproc.GC_BGD), -1);
            Imgproc.rectangle(mask, new Point(0, 0), new Point(border, mh - 1), new Scalar(Imgproc.GC_BGD), -1);
            Imgproc.rectangle(mask, new Point(mw - 1 - border, 0), new Point(mw - 1, mh - 1), new Scalar(Imgproc.GC_BGD), -1);
            Imgproc.ellipse(mask, new Point(cx, cy), new Size(radX, radY), 0, 0, 360, new Scalar(Imgproc.GC_PR_FGD), -1);
            Imgproc.ellipse(mask, new Point(cx, cy), new Size(radX * 0.35, radY * 0.35), 0, 0, 360, new Scalar(Imgproc.GC_FGD), -1);

            boolean grabOk = true;
            try {
                Imgproc.grabCut(rgb, mask, new Rect(), bgd, fgd, 4, Imgproc.GC_INIT_WITH_MASK);
            } catch (Exception e) {
                grabOk = false;
            }
            Mat m1 = new Mat(), m3 = new Mat();
            try {
                Core.compare(mask, new Scalar(Imgproc.GC_FGD), m1, Core.CMP_EQ);
                Core.compare(mask, new Scalar(Imgproc.GC_PR_FGD), m3, Core.CMP_EQ);
                Core.bitwise_or(m1, m3, fg);
            } finally {
                m1.release(); m3.release();
            }
            fg = largestComponent(fg, mw * mh);
            double area = Core.countNonZero(fg);
            if (!grabOk || area < mw * mh * 0.02 || area > mw * mh * 0.92) {
                fg.setTo(new Scalar(0));
                Imgproc.ellipse(fg, new Point(cx, cy), new Size(radX, radY), 0, 0, 360, new Scalar(255), -1);
            }
            Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(5, 5));
            Imgproc.morphologyEx(fg, fg, Imgproc.MORPH_CLOSE, kernel);
            kernel.release();
            Imgproc.GaussianBlur(fg, fg, new Size(0, 0), Math.max(1.5, Math.min(mw, mh) * 0.006));

            // Jarak dari objek: makin jauh, makin kabur.
            Core.compare(fg, new Scalar(100), inv, Core.CMP_LT);
            Imgproc.distanceTransform(inv, dist, Imgproc.DIST_L2, 3);
            double maxDist = Math.max(1.0, 0.30 * Math.max(mw, mh));
            dist.convertTo(dist, CvType.CV_8UC1, 255.0 / maxDist);

            // Latar: perkecil, kaburkan dua tingkat, perbesar.
            Bitmap baseBmp = outK < 1f ? Bitmap.createScaledBitmap(src, outW, outH, true) : src;
            Utils.bitmapToMat(baseBmp, base);
            if (baseBmp != src) baseBmp.recycle();
            double shrink = 4.0;
            Imgproc.resize(base, tiny, new Size(Math.max(2, outW / shrink), Math.max(2, outH / shrink)), 0, 0, Imgproc.INTER_AREA);
            double[] sigmas = {2.0, 4.0, 7.0};
            double sHigh = sigmas[Math.max(0, Math.min(2, strength))] * (Math.max(outW, outH) / 2560.0 + 0.4);
            double sLow = sHigh * 0.45;
            Mat tmp = new Mat();
            Imgproc.GaussianBlur(tiny, tmp, new Size(0, 0), sLow);
            Imgproc.resize(tmp, blurLow, new Size(outW, outH), 0, 0, Imgproc.INTER_LINEAR);
            Imgproc.GaussianBlur(tiny, tmp, new Size(0, 0), sHigh);
            Imgproc.resize(tmp, blurHigh, new Size(outW, outH), 0, 0, Imgproc.INTER_LINEAR);
            tmp.release();

            Imgproc.resize(fg, maskFull, new Size(outW, outH), 0, 0, Imgproc.INTER_LINEAR);
            Imgproc.resize(dist, depthFull, new Size(outW, outH), 0, 0, Imgproc.INTER_LINEAR);

            out.create(outH, outW, CvType.CV_8UC4);
            byte[] rb = new byte[outW * 4], lb = new byte[outW * 4], hb = new byte[outW * 4], ob = new byte[outW * 4];
            byte[] mb = new byte[outW], db = new byte[outW];
            for (int y = 0; y < outH; y++) {
                base.get(y, 0, rb);
                blurLow.get(y, 0, lb);
                blurHigh.get(y, 0, hb);
                maskFull.get(y, 0, mb);
                depthFull.get(y, 0, db);
                for (int x = 0; x < outW; x++) {
                    float a = (mb[x] & 0xFF) / 255f;
                    float d = (db[x] & 0xFF) / 255f;
                    int i = x * 4;
                    for (int c = 0; c < 3; c++) {
                        float bg = (lb[i + c] & 0xFF) * (1f - d) + (hb[i + c] & 0xFF) * d;
                        float v = (rb[i + c] & 0xFF) * a + bg * (1f - a);
                        ob[i + c] = (byte) Math.max(0, Math.min(255, Math.round(v)));
                    }
                    ob[i + 3] = (byte) 255;
                }
                out.put(y, 0, ob);
            }
            Bitmap result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(out, result);
            return result;
        } finally {
            if (!small.isRecycled()) small.recycle();
            rgba.release(); rgb.release(); mask.release(); bgd.release(); fgd.release();
            fg.release(); dist.release(); inv.release();
            base.release(); tiny.release(); blurLow.release(); blurHigh.release();
            maskFull.release(); depthFull.release(); out.release();
        }
    }

    /** Ambil komponen terhubung terbesar dari masker biner. */
    private static Mat largestComponent(Mat binary, int totalPixels) {
        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Mat copy = binary.clone();
        Imgproc.findContours(copy, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
        copy.release();
        hierarchy.release();
        int best = -1;
        double bestArea = 0;
        for (int i = 0; i < contours.size(); i++) {
            double a = Imgproc.contourArea(contours.get(i));
            if (a > bestArea) { bestArea = a; best = i; }
        }
        Mat result = Mat.zeros(binary.size(), CvType.CV_8UC1);
        if (best >= 0) {
            List<MatOfPoint> one = new ArrayList<>();
            one.add(contours.get(best));
            Imgproc.drawContours(result, one, -1, new Scalar(255), -1);
        }
        for (MatOfPoint c : contours) c.release();
        binary.release();
        return result;
    }

    /** Deteksi wajah bawaan Android; hasil {x, y, jarakMata} atau null. */
    private static float[] detectFace(Bitmap small) {
        try {
            int w = small.getWidth() & ~1;
            int h = small.getHeight();
            Bitmap b565 = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
            new android.graphics.Canvas(b565).drawBitmap(small, 0, 0, null);
            FaceDetector.Face[] faces = new FaceDetector.Face[1];
            int n = new FaceDetector(w, h, 1).findFaces(b565, faces);
            b565.recycle();
            if (n > 0 && faces[0] != null && faces[0].confidence() > 0.4f) {
                PointF mid = new PointF();
                faces[0].getMidPoint(mid);
                return new float[]{mid.x, mid.y, Math.max(4f, faces[0].eyesDistance())};
            }
        } catch (Exception ignored) { }
        return null;
    }
}
