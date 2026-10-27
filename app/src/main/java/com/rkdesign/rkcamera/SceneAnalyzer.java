package com.rkdesign.rkcamera;

import android.graphics.Bitmap;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

/** Analisis ringan pada frame preview: tingkat terang adegan dan deteksi bulan (lingkaran terang di langit gelap). */
final class SceneAnalyzer {
    static final class Result {
        float meanLuma;        // 0..1
        boolean moon;
        float moonX, moonY;    // 0..1 relatif terhadap preview
    }

    private SceneAnalyzer() { }

    static Result analyze(Bitmap preview, boolean checkMoon) {
        Result r = new Result();
        if (preview == null) return r;
        if (!OpenCVLoader.initLocal()) return r;
        Mat rgba = new Mat(), gray = new Mat(), small = new Mat(), bin = new Mat(), hierarchy = new Mat();
        try {
            Utils.bitmapToMat(preview, rgba);
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
            double k = Math.min(1.0, 192.0 / Math.max(gray.cols(), gray.rows()));
            Imgproc.resize(gray, small, new Size(Math.max(8, Math.round(gray.cols() * k)), Math.max(8, Math.round(gray.rows() * k))), 0, 0, Imgproc.INTER_AREA);
            r.meanLuma = (float) (Core.mean(small).val[0] / 255.0);
            if (!checkMoon || r.meanLuma > 0.35f) return r;

            Imgproc.GaussianBlur(small, small, new Size(5, 5), 0);
            Imgproc.threshold(small, bin, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
            List<MatOfPoint> contours = new ArrayList<>();
            Imgproc.findContours(bin.clone(), contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
            double total = small.cols() * (double) small.rows();
            double bestArea = 0;
            for (MatOfPoint c : contours) {
                double area = Imgproc.contourArea(c);
                if (area < total * 0.004 || area > total * 0.40) continue;
                MatOfPoint2f c2 = new MatOfPoint2f(c.toArray());
                double peri = Imgproc.arcLength(c2, true);
                c2.release();
                if (peri <= 0) continue;
                double circ = 4.0 * Math.PI * area / (peri * peri);
                if (circ < 0.78) continue;
                org.opencv.imgproc.Moments mo = Imgproc.moments(c);
                if (mo.get_m00() <= 0) continue;
                double cx = mo.get_m10() / mo.get_m00();
                double cy = mo.get_m01() / mo.get_m00();
                // Bagian dalam harus terang dan sekelilingnya gelap.
                Mat region = Mat.zeros(small.size(), org.opencv.core.CvType.CV_8UC1);
                List<MatOfPoint> one = new ArrayList<>();
                one.add(c);
                Imgproc.drawContours(region, one, -1, new Scalar(255), -1);
                Scalar inside = Core.mean(small, region);
                Mat outer = new Mat();
                Core.bitwise_not(region, outer);
                Scalar outside = Core.mean(small, outer);
                region.release();
                outer.release();
                if (inside.val[0] / 255.0 > 0.55 && outside.val[0] / 255.0 < 0.25 && area > bestArea) {
                    bestArea = area;
                    r.moon = true;
                    r.moonX = (float) (cx / small.cols());
                    r.moonY = (float) (cy / small.rows());
                }
            }
            for (MatOfPoint c : contours) c.release();
        } catch (Exception ignored) {
        } finally {
            rgba.release(); gray.release(); small.release(); bin.release(); hierarchy.release();
        }
        return r;
    }
}
