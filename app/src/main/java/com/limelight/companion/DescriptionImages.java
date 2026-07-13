package com.limelight.companion;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Html;
import android.util.Base64;
import android.util.LruCache;
import android.view.View;

import com.limelight.LimeLog;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Draws the images a game's description embeds.
 *
 * {@link Html#fromHtml} wants a drawable for every {@code <img>}, and wants it there and then; the
 * images live on the web. So the first parse gets nothing back and the bytes are fetched in the
 * background; once they land the description is parsed again, and this time the image draws.
 *
 * Most of what Steam puts in a description is an animated GIF, and animating them is not a flourish
 * — several open on a fade from black, so a still first frame is a black rectangle. They are played
 * where the platform can (API 28+), and shown as their first frame where it cannot.
 */
class DescriptionImages implements Html.ImageGetter {

    /** Raised on the main thread once an image has arrived and the description is worth redrawing. */
    interface Callback {
        void onImageLoaded();
    }

    /** Downloaded bytes, kept across panels so a game revisited draws at once. */
    private static final LruCache<String, byte[]> BYTES = new LruCache<String, byte[]>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, byte[] value) {
            return value.length;
        }
    };

    private static final ExecutorService LOADER = Executors.newFixedThreadPool(2);
    private static final int TIMEOUT_MS = 8000;

    /** The view the images are drawn in: an animating frame invalidates it, nothing else. */
    private final View target;
    private final Callback callback;
    private final Handler mainThread = new Handler(Looper.getMainLooper());

    /**
     * The drawables of the description on screen. They are bound to {@link #target} and, when
     * animated, running, so they belong to this panel and to the current game only.
     */
    private final Map<String, Drawable> drawables = new HashMap<>();

    /** Sources being fetched, and sources not worth fetching again. */
    private final Set<String> inFlight = new HashSet<>();
    private final Set<String> failed = new HashSet<>();

    /** The box an image is drawn into, set by the panel before each parse. */
    private int maxWidthPx = 1;
    private int maxHeightPx = 1;

    DescriptionImages(View target, Callback callback) {
        this.target = target;
        this.callback = callback;
    }

    /** The space the description has: images are scaled to fit it, never to overflow it. */
    void setBounds(int maxWidthPx, int maxHeightPx) {
        this.maxWidthPx = Math.max(1, maxWidthPx);
        this.maxHeightPx = Math.max(1, maxHeightPx);
    }

    /** A different game is spotlighted: stop the old animations and let their frames go. */
    void reset() {
        for (Drawable drawable : drawables.values()) {
            stop(drawable);
        }
        drawables.clear();
    }

    /** Nothing is on screen worth animating (a stream took the panel), or it is again. */
    void setAnimating(boolean animating) {
        for (Drawable drawable : drawables.values()) {
            if (animating) {
                start(drawable);
            } else {
                stop(drawable);
            }
        }
    }

    @Override
    public Drawable getDrawable(String source) {
        Drawable drawable = drawables.get(source);
        if (drawable != null) {
            return drawable;
        }

        byte[] bytes = BYTES.get(source);
        if (bytes != null) {
            drawable = build(bytes);
            if (drawable != null) {
                drawables.put(source, drawable);
                return drawable;
            }
            failed.add(source);
        } else if (!failed.contains(source)) {
            fetch(source);
        }

        // Nothing to show yet. A zero-sized box holds no space, so an image that never arrives
        // leaves no gap where it would have been.
        Drawable nothing = new ColorDrawable(Color.TRANSPARENT);
        nothing.setBounds(0, 0, 0, 0);
        return nothing;
    }

    private void fetch(String source) {
        if (!inFlight.add(source)) {
            return;
        }

        LOADER.execute(() -> {
            byte[] bytes = null;
            try {
                bytes = read(source);
            } catch (Exception e) {
                LimeLog.warning("Companion: failed to load description image " + source + ": " + e);
            }

            final byte[] loaded = bytes;
            mainThread.post(() -> {
                inFlight.remove(source);
                if (loaded == null) {
                    failed.add(source);
                    return;
                }
                BYTES.put(source, loaded);
                callback.onImageLoaded();
            });
        });
    }

    /** Decode to fit the description's column, animated if the platform and the image allow it. */
    private Drawable build(byte[] bytes) {
        Drawable drawable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? decodeScaled(bytes)
                : decodeFirstFrame(bytes);
        if (drawable == null) {
            return null;
        }

        drawable.setBounds(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        if (drawable instanceof AnimatedImageDrawable) {
            // A TextView will not invalidate for a drawable it does not own, so the frames are
            // pumped through to it by hand.
            drawable.setCallback(animationCallback);
            ((AnimatedImageDrawable) drawable).setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
            start(drawable);
        }
        return drawable;
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private Drawable decodeScaled(byte[] bytes) {
        try {
            return ImageDecoder.decodeDrawable(
                    ImageDecoder.createSource(ByteBuffer.wrap(bytes)),
                    (decoder, info, source) -> {
                        int width = info.getSize().getWidth();
                        int height = info.getSize().getHeight();
                        float scale = Math.min(
                                maxWidthPx / (float) width,
                                maxHeightPx / (float) height);
                        if (scale < 1f) {
                            decoder.setTargetSize(
                                    Math.max(1, Math.round(width * scale)),
                                    Math.max(1, Math.round(height * scale)));
                        }
                    });
        } catch (Exception e) {
            LimeLog.warning("Companion: failed to decode description image: " + e);
            return null;
        }
    }

    private Drawable decodeFirstFrame(byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, bounds.outWidth / maxWidthPx);
        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (decoded == null) {
            return null;
        }

        float scale = Math.min(
                maxWidthPx / (float) decoded.getWidth(),
                maxHeightPx / (float) decoded.getHeight());
        if (scale < 1f) {
            decoded = Bitmap.createScaledBitmap(decoded,
                    Math.max(1, Math.round(decoded.getWidth() * scale)),
                    Math.max(1, Math.round(decoded.getHeight() * scale)),
                    true);
        }
        return new BitmapDrawable(target.getResources(), decoded);
    }

    private final Drawable.Callback animationCallback = new Drawable.Callback() {
        @Override
        public void invalidateDrawable(Drawable who) {
            target.invalidate();
        }

        @Override
        public void scheduleDrawable(Drawable who, Runnable what, long when) {
            target.postDelayed(what, Math.max(0, when - SystemClock.uptimeMillis()));
        }

        @Override
        public void unscheduleDrawable(Drawable who, Runnable what) {
            target.removeCallbacks(what);
        }
    };

    private static void start(Drawable drawable) {
        if (drawable instanceof Animatable && !((Animatable) drawable).isRunning()) {
            ((Animatable) drawable).start();
        }
    }

    private static void stop(Drawable drawable) {
        if (drawable instanceof Animatable && ((Animatable) drawable).isRunning()) {
            ((Animatable) drawable).stop();
        }
    }

    private static byte[] read(String source) throws Exception {
        // Playnite inlines the odd small image rather than linking it.
        if (source.startsWith("data:")) {
            int comma = source.indexOf(',');
            if (comma < 0 || !source.substring(0, comma).endsWith("base64")) {
                throw new IllegalArgumentException("unsupported data URI");
            }
            return Base64.decode(source.substring(comma + 1), Base64.DEFAULT);
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(source).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        try (InputStream in = connection.getInputStream()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } finally {
            connection.disconnect();
        }
    }
}
