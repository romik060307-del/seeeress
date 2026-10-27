package com.rkdesign.rkcamera;

import android.Manifest;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Galeri RK Camera AI: kisi foto dan video hasil kamera; ketuk foto untuk membuka editor. */
public class GalleryActivity extends AppCompatActivity {
    private static final class Item {
        final Uri uri;
        final boolean video;
        final long date;
        Item(Uri uri, boolean video, long date) { this.uri = uri; this.video = video; this.date = date; }
    }

    private static final class Holder {
        final ImageView image;
        final TextView badge;
        Holder(ImageView image, TextView badge) { this.image = image; this.badge = badge; }
    }

    private final ArrayList<Item> items = new ArrayList<>();
    private final ExecutorService loader = Executors.newFixedThreadPool(2);
    private final ExecutorService lister = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> cache = new LruCache<>(80);
    private GridView grid;
    private TextView emptyText;
    private BaseAdapter adapter;
    private int cellSize;
    private ActivityResultLauncher<String[]> permissionLauncher;
    private boolean askedPermission = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        permissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), r -> reload());

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int gap = dp(2);
        cellSize = (dm.widthPixels - gap * 2) / 3;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), 0, dp(12), 0);
        TextView back = text("‹", 30, Color.WHITE, false);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(52)));
        TextView title = text("Galeri RK Camera", 18, Color.WHITE, true);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(52), 1f));
        TextView system = text("Galeri HP", 14, Color.rgb(255, 214, 10), true);
        system.setGravity(Gravity.CENTER);
        system.setOnClickListener(v -> ImageUtil.openSystemGallery(this));
        bar.addView(system, new LinearLayout.LayoutParams(-2, dp(52)));
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(52)));

        FrameLayout body = new FrameLayout(this);
        grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setHorizontalSpacing(gap);
        grid.setVerticalSpacing(gap);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        adapter = new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public Object getItem(int position) { return items.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                FrameLayout cell;
                Holder holder;
                if (convertView == null) {
                    cell = new FrameLayout(GalleryActivity.this);
                    cell.setLayoutParams(new AbsListView.LayoutParams(cellSize, cellSize));
                    ImageView iv = new ImageView(GalleryActivity.this);
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    iv.setBackgroundColor(Color.rgb(28, 28, 30));
                    cell.addView(iv, new FrameLayout.LayoutParams(-1, -1));
                    TextView badge = text("▶", 14, Color.WHITE, true);
                    badge.setPadding(dp(6), dp(2), dp(6), dp(2));
                    cell.addView(badge, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START));
                    holder = new Holder(iv, badge);
                    cell.setTag(holder);
                } else {
                    cell = (FrameLayout) convertView;
                    holder = (Holder) cell.getTag();
                }
                final Item item = items.get(position);
                holder.badge.setVisibility(item.video ? View.VISIBLE : View.GONE);
                final String key = item.uri.toString();
                final ImageView iv = holder.image;
                iv.setTag(key);
                Bitmap cached = cache.get(key);
                if (cached != null) {
                    iv.setImageBitmap(cached);
                } else {
                    iv.setImageDrawable(null);
                    loader.execute(() -> {
                        Bitmap bmp = makeThumb(item);
                        if (bmp == null) return;
                        cache.put(key, bmp);
                        runOnUiThread(() -> { if (key.equals(iv.getTag())) iv.setImageBitmap(bmp); });
                    });
                }
                return cell;
            }
        };
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> open(items.get(position)));
        body.addView(grid, new FrameLayout.LayoutParams(-1, -1));
        emptyText = text("Belum ada foto atau video dari RK Camera AI.", 14, Color.rgb(142, 142, 147), false);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setVisibility(View.GONE);
        body.addView(emptyText, new FrameLayout.LayoutParams(-1, -1));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        reload();
    }

    @Override protected void onDestroy() {
        loader.shutdownNow();
        lister.shutdownNow();
        super.onDestroy();
    }

    private String[] missingReadPermissions() {
        // Aplikasi ini bertarget lama (SDK 28): READ_EXTERNAL_STORAGE sudah cukup, termasuk di Android baru.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
        }
        return new String[0];
    }

    private void reload() {
        String[] missing = missingReadPermissions();
        if (missing.length > 0 && !askedPermission) {
            askedPermission = true;
            permissionLauncher.launch(missing);
            return;
        }
        lister.execute(() -> {
            List<Item> list = new ArrayList<>();
            for (int k = 0; k < 2; k++) {
                Uri collection = k == 0 ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                try (Cursor c = getContentResolver().query(collection,
                        new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DISPLAY_NAME},
                        MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?", new String[]{"RKCamera_%"},
                        MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
                    if (c == null) continue;
                    while (c.moveToNext()) {
                        String name = c.getString(2);
                        if (name != null && name.contains("_RAW_")) continue;
                        list.add(new Item(ContentUris.withAppendedId(collection, c.getLong(0)), k == 1, c.getLong(1)));
                    }
                } catch (Exception ignored) { }
            }
            Collections.sort(list, (a, b) -> Long.compare(b.date, a.date));
            runOnUiThread(() -> {
                items.clear();
                items.addAll(list);
                adapter.notifyDataSetChanged();
                emptyText.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    private Bitmap makeThumb(Item item) {
        try {
            if (item.video) {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                    r.setDataSource(this, item.uri);
                    Bitmap frame = r.getFrameAtTime(0);
                    if (frame == null) return null;
                    float k = Math.min(1f, 320f / Math.max(frame.getWidth(), frame.getHeight()));
                    if (k >= 1f) return frame;
                    Bitmap small = Bitmap.createScaledBitmap(frame, Math.max(1, Math.round(frame.getWidth() * k)),
                            Math.max(1, Math.round(frame.getHeight() * k)), true);
                    if (small != frame) frame.recycle();
                    return small;
                } finally {
                    try { r.release(); } catch (Exception ignored) { }
                }
            }
            return ImageUtil.decodeUpright(getContentResolver(), item.uri, 320);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private void open(Item item) {
        try {
            if (item.video) {
                Intent view = new Intent(Intent.ACTION_VIEW);
                view.setDataAndType(item.uri, "video/mp4");
                view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(view);
            } else {
                Intent edit = new Intent(this, EditorActivity.class);
                edit.putExtra("uri", item.uri.toString());
                startActivity(edit);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Tidak dapat membuka media ini.", Toast.LENGTH_SHORT).show();
        }
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

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
