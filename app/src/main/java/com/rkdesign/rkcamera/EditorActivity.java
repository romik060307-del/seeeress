package com.rkdesign.rkcamera;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.photo.Photo;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Editor foto: filter bergaya iPhone, blur latar (pilih objek), dan hapus objek dengan kuas. */
public class EditorActivity extends AppCompatActivity {
    private static final int YELLOW = Color.rgb(255, 214, 10);
    private static final int TOOL_NONE = 0, TOOL_FILTER = 1, TOOL_BLUR = 2, TOOL_ERASE = 3;
    private static final int MAX_UNDO = 3;

    private interface BitmapJob { Bitmap run() throws Exception; }
    private interface ResultHandler { void onResult(Bitmap result); }
    private interface SegmentListener { void onSelected(int index); }

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final ArrayList<Bitmap> undoStack = new ArrayList<>();
    private EditCanvas canvas;
    private TextView statusText;
    private LinearLayout panel;
    private final ArrayList<TextView> toolChips = new ArrayList<>();
    private Bitmap working;
    private Bitmap filterBase;
    private boolean filterPushed = false;
    private boolean busy = false;
    private int tool = TOOL_NONE;
    private int blurStrength = 1;
    private int filterIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        String uriText = getIntent().getStringExtra("uri");
        if (uriText == null) { finish(); return; }
        final Uri uri = Uri.parse(uriText);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), 0, dp(14), 0);
        TextView close = text("Tutup", 15, Color.WHITE, false);
        close.setOnClickListener(v -> finish());
        bar.addView(close, new LinearLayout.LayoutParams(-2, dp(48)));
        TextView title = text("Editor", 17, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1f));
        TextView save = text("Simpan", 15, YELLOW, true);
        save.setOnClickListener(v -> saveResult());
        bar.addView(save, new LinearLayout.LayoutParams(-2, dp(48)));
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(48)));

        canvas = new EditCanvas(this);
        root.addView(canvas, new LinearLayout.LayoutParams(-1, 0, 1f));

        statusText = text("", 12, Color.rgb(170, 170, 176), false);
        statusText.setGravity(Gravity.CENTER);
        root.addView(statusText, new LinearLayout.LayoutParams(-1, dp(26)));

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(2), dp(14), dp(6));
        root.addView(panel, new LinearLayout.LayoutParams(-1, -2));

        HorizontalScrollView toolScroll = new HorizontalScrollView(this);
        toolScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout toolRow = new LinearLayout(this);
        toolRow.setOrientation(LinearLayout.HORIZONTAL);
        toolRow.setPadding(dp(10), dp(6), dp(10), dp(10));
        String[] names = {"FILTER", "BLUR LATAR", "HAPUS OBJEK", "URUNGKAN"};
        for (int i = 0; i < names.length; i++) {
            TextView chip = text(names[i], 13, Color.WHITE, true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(16), 0, dp(16), 0);
            chip.setBackground(pill(Color.rgb(44, 44, 46)));
            final int index = i;
            chip.setOnClickListener(v -> onToolChip(index));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(38));
            lp.rightMargin = dp(8);
            toolRow.addView(chip, lp);
            toolChips.add(chip);
        }
        toolScroll.addView(toolRow);
        root.addView(toolScroll, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);

        canvas.setTapListener((nx, ny) -> {
            if (tool == TOOL_BLUR) startBlur(nx, ny);
        });
        loadImage(uri);
    }

    @Override protected void onDestroy() {
        exec.shutdown();
        super.onDestroy();
    }

    private void loadImage(final Uri uri) {
        setStatus("Memuat foto…");
        busy = true;
        exec.execute(() -> {
            Bitmap loaded = null;
            String error = null;
            try {
                loaded = ImageUtil.decodeUpright(getContentResolver(), uri, 2400);
            } catch (Exception | OutOfMemoryError e) {
                error = e.getClass().getSimpleName();
            }
            final Bitmap result = loaded;
            final String err = error;
            runOnUiThread(() -> {
                busy = false;
                if (result == null) {
                    toast("Foto tidak dapat dibuka" + (err == null ? "" : " (" + err + ")"));
                    finish();
                    return;
                }
                working = result;
                canvas.setImage(result);
                setStatus("Pilih alat di bawah");
            });
        });
    }

    // ------------------------------------------------------------------ alat

    private void onToolChip(int index) {
        if (index == 3) { undo(); return; }
        selectTool(index + 1);
    }

    private void selectTool(int newTool) {
        tool = newTool;
        for (int i = 0; i < 3; i++) {
            boolean on = (i + 1) == tool;
            toolChips.get(i).setBackground(pill(on ? YELLOW : Color.rgb(44, 44, 46)));
            toolChips.get(i).setTextColor(on ? Color.BLACK : Color.WHITE);
        }
        panel.removeAllViews();
        canvas.setMode(EditCanvas.MODE_VIEW);
        if (tool == TOOL_FILTER) {
            filterBase = working;
            filterPushed = false;
            panel.addView(hint("Pilih filter bergaya iPhone."));
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            LinearLayout row = new LinearLayout(this);
            for (int i = 0; i < IPhoneLook.ALL.length; i++) {
                TextView chip = text(IPhoneLook.ALL[i].name, 12, Color.WHITE, true);
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(dp(14), 0, dp(14), 0);
                chip.setBackground(pill(Color.rgb(58, 58, 60)));
                final int idx = i;
                chip.setOnClickListener(v -> applyFilter(idx));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(34));
                lp.rightMargin = dp(8);
                row.addView(chip, lp);
            }
            hs.addView(row);
            panel.addView(hs, new LinearLayout.LayoutParams(-1, dp(40)));
        } else if (tool == TOOL_BLUR) {
            canvas.setMode(EditCanvas.MODE_TAP);
            panel.addView(hint("Ketuk objek yang ingin tetap tajam; latar di belakangnya dikaburkan."));
            panel.addView(segmented(new String[]{"Halus", "Sedang", "Kuat"}, blurStrength, i -> blurStrength = i),
                    new LinearLayout.LayoutParams(-1, dp(38)));
        } else if (tool == TOOL_ERASE) {
            canvas.setMode(EditCanvas.MODE_BRUSH);
            canvas.setBrushFraction(0.04f);
            panel.addView(hint("Warnai objek yang ingin dihapus, lalu tekan Hapus."));
            panel.addView(segmented(new String[]{"Kuas kecil", "Sedang", "Besar"}, 1, i -> {
                float[] f = {0.02f, 0.04f, 0.07f};
                canvas.setBrushFraction(f[i]);
            }), new LinearLayout.LayoutParams(-1, dp(38)));
            LinearLayout buttons = new LinearLayout(this);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            TextView clear = text("Bersihkan coretan", 14, Color.WHITE, true);
            clear.setGravity(Gravity.CENTER);
            clear.setBackground(pill(Color.rgb(58, 58, 60)));
            clear.setOnClickListener(v -> canvas.clearMask());
            TextView erase = text("Hapus", 14, Color.BLACK, true);
            erase.setGravity(Gravity.CENTER);
            erase.setBackground(pill(YELLOW));
            erase.setOnClickListener(v -> startErase());
            LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp(40), 1f);
            lp1.rightMargin = dp(8);
            lp1.topMargin = dp(8);
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(40), 1f);
            lp2.topMargin = dp(8);
            buttons.addView(clear, lp1);
            buttons.addView(erase, lp2);
            panel.addView(buttons, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    private void applyFilter(int index) {
        filterIndex = index;
        final Bitmap base = filterBase != null ? filterBase : working;
        if (base == null) return;
        final IPhoneLook look = IPhoneLook.ALL[index];
        runTask("Menerapkan " + look.name + "…", () -> {
            Bitmap copy = base.copy(Bitmap.Config.ARGB_8888, true);
            look.applyToBitmap(copy);
            return copy;
        }, result -> {
            if (!filterPushed) {
                pushUndo(filterBase);
                filterPushed = true;
            } else if (working != null && working != filterBase) {
                working.recycle();
            }
            working = result;
            canvas.setImage(result);
        });
    }

    private void startBlur(final float nx, final float ny) {
        final Bitmap src = working;
        if (src == null) return;
        final int strength = blurStrength;
        runTask("Memisahkan objek dari latar…", () -> PortraitProcessor.apply(src, nx, ny, strength), this::commit);
    }

    private void startErase() {
        final Bitmap src = working;
        if (src == null) return;
        if (!canvas.hasMask()) { toast("Warnai dulu objek yang ingin dihapus."); return; }
        final Bitmap mask = canvas.getMask().copy(Bitmap.Config.ARGB_8888, false);
        runTask("Menghapus objek…", () -> inpaint(src, mask), result -> {
            commit(result);
            canvas.clearMask();
        });
    }

    private Bitmap inpaint(Bitmap src, Bitmap maskBmp) throws Exception {
        if (!OpenCVLoader.initLocal()) throw new IllegalStateException("Mesin gambar tidak dapat dimuat");
        Mat rgba = new Mat(), rgb = new Mat(), m = new Mat(), alpha = new Mat(), bin = new Mat(), res = new Mat(), resRgba = new Mat();
        try {
            Utils.bitmapToMat(src, rgba);
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB);
            Utils.bitmapToMat(maskBmp, m);
            Core.extractChannel(m, alpha, 3);
            Imgproc.threshold(alpha, bin, 10, 255, Imgproc.THRESH_BINARY);
            Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(9, 9));
            Imgproc.dilate(bin, bin, kernel);
            kernel.release();
            Photo.inpaint(rgb, bin, res, 6, Photo.INPAINT_TELEA);
            Imgproc.cvtColor(res, resRgba, Imgproc.COLOR_RGB2RGBA);
            Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(resRgba, out);
            return out;
        } finally {
            rgba.release(); rgb.release(); m.release(); alpha.release(); bin.release(); res.release(); resRgba.release();
        }
    }

    private void commit(Bitmap result) {
        pushUndo(working);
        working = result;
        filterBase = result;
        canvas.setImage(result);
    }

    private void pushUndo(Bitmap b) {
        if (b == null) return;
        undoStack.add(b);
        while (undoStack.size() > MAX_UNDO) {
            Bitmap old = undoStack.remove(0);
            if (old != working && old != filterBase && !old.isRecycled()) old.recycle();
        }
    }

    private void undo() {
        if (busy) return;
        if (undoStack.isEmpty()) { toast("Tidak ada langkah untuk diurungkan."); return; }
        working = undoStack.remove(undoStack.size() - 1);
        filterBase = working;
        filterPushed = false;
        canvas.setImage(working);
        setStatus("Dibatalkan satu langkah");
    }

    private void runTask(String message, final BitmapJob job, final ResultHandler handler) {
        if (busy || working == null) return;
        busy = true;
        setStatus(message);
        exec.execute(() -> {
            Bitmap result = null;
            String error = null;
            try {
                result = job.run();
            } catch (Exception | OutOfMemoryError e) {
                error = e.getClass().getSimpleName();
            }
            final Bitmap res = result;
            final String err = error;
            runOnUiThread(() -> {
                busy = false;
                if (res != null) {
                    handler.onResult(res);
                    setStatus("Selesai");
                } else {
                    setStatus("Gagal");
                    toast("Pemrosesan gagal" + (err == null ? "" : " (" + err + ")"));
                }
            });
        });
    }

    private void saveResult() {
        if (busy || working == null) return;
        busy = true;
        setStatus("Menyimpan…");
        final Bitmap toSave = working;
        exec.execute(() -> {
            String name = "RKCamera_EDIT_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new java.util.Date()) + ".jpg";
            final Uri saved = ImageUtil.saveJpeg(this, toSave, name);
            runOnUiThread(() -> {
                busy = false;
                if (saved != null) {
                    setStatus("Tersimpan di Galeri");
                    toast("Foto hasil edit tersimpan di Galeri.");
                } else {
                    setStatus("Gagal menyimpan");
                    toast("Foto hasil edit gagal disimpan.");
                }
            });
        });
    }

    // ------------------------------------------------------------------ helper tampilan

    private LinearLayout segmented(String[] options, int selected, SegmentListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(44, 44, 46));
        bg.setCornerRadius(dp(10));
        row.setBackground(bg);
        row.setPadding(dp(3), dp(3), dp(3), dp(3));
        final TextView[] cells = new TextView[options.length];
        for (int i = 0; i < options.length; i++) {
            TextView t = text(options[i], 13, Color.WHITE, false);
            t.setGravity(Gravity.CENTER);
            cells[i] = t;
            final int index = i;
            t.setOnClickListener(v -> {
                paint(cells, index);
                listener.onSelected(index);
            });
            row.addView(t, new LinearLayout.LayoutParams(0, -1, 1f));
        }
        paint(cells, selected);
        return row;
    }

    private void paint(TextView[] cells, int selected) {
        for (int i = 0; i < cells.length; i++) {
            boolean on = i == selected;
            if (on) {
                GradientDrawable g = new GradientDrawable();
                g.setColor(Color.rgb(99, 99, 102));
                g.setCornerRadius(dp(8));
                cells[i].setBackground(g);
            } else {
                cells[i].setBackground(null);
            }
            cells[i].setTypeface(Typeface.DEFAULT, on ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private GradientDrawable pill(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(18));
        return g;
    }

    private TextView hint(String s) {
        TextView t = text(s, 12, Color.rgb(142, 142, 147), false);
        t.setGravity(Gravity.START);
        t.setPadding(dp(2), dp(2), dp(2), dp(6));
        return t;
    }

    private TextView text(String s, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private void setStatus(String s) { if (statusText != null) statusText.setText(s); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
