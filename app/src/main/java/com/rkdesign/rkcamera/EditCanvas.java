package com.rkdesign.rkcamera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Kanvas editor: menampilkan gambar (fit), menerima ketukan untuk memilih objek, atau coretan kuas untuk masker hapus objek. */
final class EditCanvas extends View {
    interface TapListener { void onTap(float nx, float ny); }

    static final int MODE_VIEW = 0, MODE_TAP = 1, MODE_BRUSH = 2;

    private Bitmap bitmap;
    private Bitmap maskBitmap;
    private Canvas maskCanvas;
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint overlayPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint brushPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private int mode = MODE_VIEW;
    private float brushFraction = 0.04f;   // diameter kuas relatif terhadap sisi terpendek gambar
    private float lastX = -1f, lastY = -1f;
    private float downX, downY;
    private boolean moved;
    private float markerX = -1f, markerY = -1f;
    private TapListener tapListener;

    EditCanvas(Context context) {
        super(context);
        overlayPaint.setAlpha(150);
        brushPaint.setColor(Color.rgb(255, 59, 48));
        brushPaint.setStyle(Paint.Style.STROKE);
        brushPaint.setStrokeCap(Paint.Cap.ROUND);
        brushPaint.setStrokeJoin(Paint.Join.ROUND);
        markerPaint.setColor(Color.rgb(255, 214, 10));
        markerPaint.setStyle(Paint.Style.STROKE);
        markerPaint.setStrokeWidth(5f);
    }

    void setTapListener(TapListener l) { tapListener = l; }

    void setMode(int mode) {
        this.mode = mode;
        markerX = markerY = -1f;
        invalidate();
    }

    void setBrushFraction(float f) { brushFraction = f; }

    void setImage(Bitmap b) {
        boolean sizeChanged = bitmap == null || b == null || bitmap.getWidth() != b.getWidth() || bitmap.getHeight() != b.getHeight();
        bitmap = b;
        if (b != null && (sizeChanged || maskBitmap == null)) {
            maskBitmap = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.ARGB_8888);
            maskCanvas = new Canvas(maskBitmap);
        }
        computeRect();
        invalidate();
    }

    Bitmap getMask() { return maskBitmap; }

    boolean hasMask() {
        if (maskBitmap == null) return false;
        int w = maskBitmap.getWidth(), h = maskBitmap.getHeight();
        int stepX = Math.max(1, w / 64), stepY = Math.max(1, h / 64);
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                if (Color.alpha(maskBitmap.getPixel(x, y)) > 0) return true;
            }
        }
        return false;
    }

    void clearMask() {
        if (maskBitmap != null) maskBitmap.eraseColor(Color.TRANSPARENT);
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        computeRect();
    }

    private void computeRect() {
        if (bitmap == null || getWidth() == 0 || getHeight() == 0) { dst.setEmpty(); return; }
        float s = Math.min(getWidth() / (float) bitmap.getWidth(), getHeight() / (float) bitmap.getHeight());
        float w = bitmap.getWidth() * s, h = bitmap.getHeight() * s;
        float l = (getWidth() - w) / 2f, t = (getHeight() - h) / 2f;
        dst.set(l, t, l + w, t + h);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (bitmap == null || dst.isEmpty()) return;
        canvas.drawBitmap(bitmap, null, dst, bitmapPaint);
        if (mode == MODE_BRUSH && maskBitmap != null) canvas.drawBitmap(maskBitmap, null, dst, overlayPaint);
        if (mode == MODE_TAP && markerX >= 0f) {
            canvas.drawCircle(markerX, markerY, 46f, markerPaint);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (bitmap == null || dst.isEmpty() || mode == MODE_VIEW) return false;
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x; downY = y; moved = false;
                lastX = x; lastY = y;
                if (mode == MODE_BRUSH) drawStroke(x, y, x, y);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(x - downX) > 12f || Math.abs(y - downY) > 12f) moved = true;
                if (mode == MODE_BRUSH) drawStroke(lastX, lastY, x, y);
                lastX = x; lastY = y;
                return true;
            case MotionEvent.ACTION_UP:
                if (mode == MODE_TAP && !moved && dst.contains(x, y)) {
                    markerX = x; markerY = y;
                    invalidate();
                    if (tapListener != null) tapListener.onTap((x - dst.left) / dst.width(), (y - dst.top) / dst.height());
                }
                return true;
            default:
                return true;
        }
    }

    private void drawStroke(float x1, float y1, float x2, float y2) {
        float sx = bitmap.getWidth() / dst.width();
        float ix1 = (x1 - dst.left) * sx, iy1 = (y1 - dst.top) * sx;
        float ix2 = (x2 - dst.left) * sx, iy2 = (y2 - dst.top) * sx;
        brushPaint.setStrokeWidth(Math.max(6f, brushFraction * Math.min(bitmap.getWidth(), bitmap.getHeight())));
        maskCanvas.drawLine(ix1, iy1, ix2, iy2, brushPaint);
        invalidate();
    }
}
