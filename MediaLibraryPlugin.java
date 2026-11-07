package com.yousef.gallery;

import android.Manifest;
import android.content.ContentResolver;
import android.app.Dialog;
import android.database.Cursor;
import android.content.ContentUris;
import android.graphics.Color;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/**
 * Lists every photo / video on the phone (that's where Samsung Gallery gets its
 * albums from: each album is a folder, i.e. a MediaStore "bucket").
 */
@CapacitorPlugin(
    name = "MediaLibrary",
    permissions = {
        @Permission(alias = "media", strings = { Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO }),
        @Permission(alias = "legacy", strings = { Manifest.permission.READ_EXTERNAL_STORAGE })
    }
)
public class MediaLibraryPlugin extends Plugin {

    private String alias() {
        return Build.VERSION.SDK_INT >= 33 ? "media" : "legacy";
    }

    @PluginMethod
    public void getMedia(PluginCall call) {
        if (getPermissionState(alias()) != PermissionState.GRANTED) {
            requestPermissionForAlias(alias(), call, "permissionCallback");
            return;
        }
        query(call);
    }

    @PermissionCallback
    private void permissionCallback(PluginCall call) {
        if (getPermissionState(alias()) == PermissionState.GRANTED) {
            query(call);
        } else {
            call.reject("PERMISSION_DENIED");
        }
    }

    private void query(PluginCall call) {
        Cursor c = null;
        try {
            ContentResolver cr = getContext().getContentResolver();
            Uri uri = MediaStore.Files.getContentUri("external");
            final boolean favs = Build.VERSION.SDK_INT >= 30;
            String[] base = {
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DURATION,
                MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT
            };
            String[] proj = base;
            if (favs) { // "favorite" flag (Android 11+)
                proj = java.util.Arrays.copyOf(base, base.length + 1);
                proj[base.length] = MediaStore.MediaColumns.IS_FAVORITE;
            }
            String sel = MediaStore.Files.FileColumns.MEDIA_TYPE + "=? OR " + MediaStore.Files.FileColumns.MEDIA_TYPE + "=?";
            String[] args = {
                String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE),
                String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
            };
            c = cr.query(uri, proj, sel, args, MediaStore.MediaColumns.DATE_ADDED + " DESC");
            JSArray items = new JSArray();
            if (c != null) {
                while (c.moveToNext()) {
                    String name = c.getString(1);
                    if (name == null) continue;
                    JSObject o = new JSObject();
                    o.put("id", c.getLong(0));
                    o.put("n", name);
                    o.put("m", c.getString(2));
                    o.put("k", c.getInt(3) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO ? "v" : "i");
                    o.put("r", c.getString(4));
                    o.put("f", c.getString(5));
                    o.put("a", c.getLong(6));
                    o.put("s", c.getLong(7));
                    o.put("d", c.getLong(8));
                    o.put("w", c.getInt(9));
                    o.put("h", c.getInt(10));
                    if (favs && c.getInt(11) == 1) o.put("v", 1);
                    items.put(o);
                }
            }
            JSObject ret = new JSObject();
            ret.put("items", items);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("QUERY_FAILED: " + e.getMessage());
        } finally {
            if (c != null) c.close();
        }
    }

    /** Build thumbnails in the background so the feed never waits on them. items = ["v:12","i:34",...] */
    @PluginMethod
    public void prewarm(PluginCall call) {
        try {
            JSArray arr = call.getArray("items");
            java.util.List<String> list = new java.util.ArrayList<String>();
            if (arr != null) for (int i = 0; i < arr.length(); i++) list.add(arr.getString(i));
            MediaWebViewClient.ThumbCache.prewarm(getContext(), list);
        } catch (Exception ignored) {
            // best effort
        }
        call.resolve();
    }

    /** Plays a video with Android's own player, straight from the file: starts instantly, with sound. */
    @PluginMethod
    public void playVideo(final PluginCall call) {
        final long id = call.getData().optLong("id", -1);
        if (id < 0) { call.reject("NO_ID"); return; }
        final android.app.Activity act = getActivity();
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    final Dialog d = new Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
                    FrameLayout root = new FrameLayout(act);
                    root.setBackgroundColor(Color.BLACK);
                    final VideoView vv = new VideoView(act);
                    root.addView(vv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
                    MediaController mc = new MediaController(act);
                    mc.setAnchorView(vv);
                    vv.setMediaController(mc);
                    float dp = act.getResources().getDisplayMetrics().density;
                    TextView x = new TextView(act);
                    x.setText("\u2715");
                    x.setTextColor(Color.WHITE);
                    x.setTextSize(22);
                    x.setGravity(Gravity.CENTER);
                    x.setBackgroundColor(0x66000000);
                    FrameLayout.LayoutParams xp = new FrameLayout.LayoutParams((int) (48 * dp), (int) (48 * dp), Gravity.TOP | Gravity.END);
                    xp.setMargins(0, (int) (28 * dp), (int) (12 * dp), 0);
                    root.addView(x, xp);
                    x.setOnClickListener(new android.view.View.OnClickListener() {
                        @Override
                        public void onClick(android.view.View v) { d.dismiss(); }
                    });
                    vv.setOnPreparedListener(new android.media.MediaPlayer.OnPreparedListener() {
                        @Override
                        public void onPrepared(android.media.MediaPlayer mp) {
                            mp.setLooping(true);
                            vv.start();
                        }
                    });
                    vv.setOnErrorListener(new android.media.MediaPlayer.OnErrorListener() {
                        @Override
                        public boolean onError(android.media.MediaPlayer mp, int what, int extra) {
                            d.dismiss();
                            return true;
                        }
                    });
                    d.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                        @Override
                        public void onDismiss(android.content.DialogInterface di) { vv.stopPlayback(); }
                    });
                    d.setContentView(root);
                    vv.setVideoURI(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id));
                    d.show();
                } catch (Exception ignored) {
                    // nothing to play
                }
            }
        });
        call.resolve();
    }

    /* ---------- muted feed previews: a native player laid over the post on screen ---------- */
    private TextureView feedTv;
    private MediaPlayer feedMp;
    private Surface feedSurface;

    @PluginMethod
    public void feedPlay(final PluginCall call) {
        final long id = call.getData().optLong("id", -1);
        final double x = call.getData().optDouble("x", 0);
        final double y = call.getData().optDouble("y", 0);
        final double w = call.getData().optDouble("w", 0);
        final double h = call.getData().optDouble("h", 0);
        if (id >= 0 && w >= 10 && h >= 10) {
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        stopFeed();
                        startFeed(id, x, y, w, h);
                    } catch (Exception e) {
                        stopFeed();
                    }
                }
            });
        }
        call.resolve();
    }

    @PluginMethod
    public void feedStop(PluginCall call) {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() { stopFeed(); }
        });
        call.resolve();
    }

    @Override
    protected void handleOnPause() {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() { stopFeed(); }
        });
    }

    private void stopFeed() {
        if (feedMp != null) {
            try { feedMp.setOnErrorListener(null); feedMp.release(); } catch (Exception ignored) { /* already gone */ }
            feedMp = null;
        }
        if (feedSurface != null) { feedSurface.release(); feedSurface = null; }
        if (feedTv != null) {
            feedTv.setSurfaceTextureListener(null);
            android.view.ViewParent par = feedTv.getParent();
            if (par instanceof ViewGroup) ((ViewGroup) par).removeView(feedTv);
            feedTv = null;
        }
    }

    private void startFeed(long id, double x, double y, double w, double h) {
        final android.app.Activity act = getActivity();
        final View wv = getBridge().getWebView();
        final ViewGroup content = (ViewGroup) act.findViewById(android.R.id.content);
        final float dp = act.getResources().getDisplayMetrics().density;
        int[] a = new int[2];
        int[] b = new int[2];
        wv.getLocationOnScreen(a);
        content.getLocationOnScreen(b);
        final int pw = Math.round((float) (w * dp));
        final int ph = Math.round((float) (h * dp));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(pw, ph, Gravity.TOP | Gravity.START);
        lp.leftMargin = a[0] - b[0] + Math.round((float) (x * dp));
        lp.topMargin = a[1] - b[1] + Math.round((float) (y * dp));

        final TextureView tv = new TextureView(act);
        tv.setAlpha(0f); // invisible until the first frame, so the thumbnail underneath never flashes
        tv.setClickable(false);
        tv.setFocusable(false);
        final float radius = 15 * dp;
        tv.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius); }
        });
        tv.setClipToOutline(true);
        final Uri uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
        tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int sw, int sh) {
                if (feedTv != tv) return;
                try {
                    MediaPlayer mp = new MediaPlayer();
                    feedMp = mp;
                    feedSurface = new Surface(st);
                    mp.setSurface(feedSurface);
                    mp.setDataSource(act, uri);
                    mp.setVolume(0f, 0f);
                    mp.setLooping(true);
                    mp.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
                        @Override
                        public void onVideoSizeChanged(MediaPlayer m, int vw, int vh) {
                            if (vw > 0 && vh > 0) cover(tv, pw, ph, vw, vh);
                        }
                    });
                    mp.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                        @Override
                        public boolean onInfo(MediaPlayer m, int what, int extra) {
                            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) tv.setAlpha(1f);
                            return false;
                        }
                    });
                    mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                        @Override
                        public void onPrepared(MediaPlayer m) { m.start(); }
                    });
                    mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                        @Override
                        public boolean onError(MediaPlayer m, int what, int extra) {
                            stopFeed();
                            return true;
                        }
                    });
                    mp.prepareAsync();
                } catch (Exception e) {
                    stopFeed();
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int sw, int sh) {}

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {}
        });
        feedTv = tv;
        content.addView(tv, lp);
    }

    /** Crop-to-fill, same as object-fit:cover on the thumbnail underneath. */
    private static void cover(TextureView tv, int vw, int vh, int mw, int mh) {
        float vr = (float) mw / mh, r = (float) vw / vh;
        Matrix m = new Matrix();
        if (vr > r) m.setScale(vr / r, 1f, vw / 2f, vh / 2f);
        else m.setScale(1f, r / vr, vw / 2f, vh / 2f);
        tv.setTransform(m);
    }
}
