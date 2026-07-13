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
 * {@link Html#fromHtml} wants a drawable for every {@code <img>}, and wants it there and then, on
 * the thread that parses; the images live on the web, and decoding one — a Steam GIF is hundreds of
 * frames — is far too much to do on the main thread while the library is being scrolled. So a parse
 * never decodes: it either hands back a drawable that is already standing by, or hands back nothing
 * and asks for one. Fetching and decoding both happen on {@link #LOADER}, and once a drawable is
 * ready the description is parsed again, and this time the image draws.
 *
 * Decoded drawables outlive the description they were parsed for, so scrolling back to a game
 * already seen draws it at once instead of decoding it again.
 *
 * Most of what Steam puts in a description is an animated GIF, and animating them is not a flourish
 * — several open on a fade from black, so a still first frame is a black rectangle. They are played
 * where the platform can (API 28+), and shown as their first frame where it cannot.
 *
 * Main thread only, but for the body of a {@link #LOADER} task.
 */
class DescriptionImages implements Html.ImageGetter {

    /** Raised on the main thread once an image is ready and the description is worth redrawing. */
    interface Callback {
        void onImageLoaded();
    }

    /** Downloaded bytes, kept across panels so a game revisited need not be fetched again. */
    private static final LruCache<String, byte[]> BYTES = new LruCache<String, byte[]>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, byte[] value) {
            return value.length;
        }
    };

    private static final ExecutorService LOADER = Executors.newFixedThreadPool(2);
    private static final int TIMEOUT_MS = 8000;
    private static final int DECODED_BUDGET_BYTES = 24 * 1024 * 1024;

    /** The view the images are drawn in: an animating frame invalidates it, nothing else. */
    private final View target;
    private final Callback callback;
    private final Handler mainThread = new Handler(Looper.getMainLooper());

    /** The drawables of the description on screen: the ones a spotlight change has to let go of. */
    private final Map<String, Drawable> drawables = new HashMap<>();

    /**
     * Drawables ready to be drawn, whether or not they are on screen now. They are bound to
     * {@link #target}, so this rides with the panel rather than being shared between panels.
     */
    private final LruCache<String, Drawable> decoded =
            new LruCache<String, Drawable>(DECODED_BUDGET_BYTES) {
        @Override
        protected int sizeOf(String key, Drawable value) {
            // An animated drawable holds more than one frame, but how many is its own business;
            // a frame is the closest we can get to its weight from out here.
            return Math.max(1, value.getIntrinsicWidth() * value.getIntrinsicHeight() * 4);
        }

        @Override
        protected void entryRemoved(boolean evicted, String key, Drawable old, Drawable now) {
            // An evicted drawable that is still in the description on screen is still being drawn:
            // it has only lost its place in the cache, so leave it running.
            if (!drawables.containsValue(old)) {
                stop(old);
            }
        }
    };

    /** Sources being fetched or decoded, and sources not worth asking for again. */
    private final Set<String> inFlight = new HashSet<>();
    private final Set<String> failed = new HashSet<>();

    /** The box an image is drawn into, set by the panel before each parse. */
    private int maxWidthPx = 1;
    private int maxHeightPx = 1;

    /** Whether what we draw is on screen and so worth animating. */
    private boolean animating = true;

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
        // The drawables themselves stay in the cache: the game they belong to is one scroll away.
        drawables.clear();
    }

    /** Nothing is on screen worth animating (a stream took the panel), or it is again. */
    void setAnimating(boolean animating) {
        this.animating = animating;
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
        Drawable drawn = drawables.get(source);
        if (drawn != null) {
            return drawn;
        }

        // Decoding is never done here: a parse runs on the main thread, and the library is being
        // scrolled on it. Either one is standing by, or one is asked for and this parse draws a gap.
        String key = key(source);
        Drawable ready = decoded.get(key);
        if (ready != null) {
            drawables.put(source, ready);
            if (animating) {
                start(ready);
            }
            return ready;
        }

        if (!failed.contains(source)) {
            request(source, key);
        }

        // Nothing to show yet. A zero-sized box holds no space, so an image that never arrives
        // leaves no gap where it would have been.
        Drawable nothing = new ColorDrawable(Color.TRANSPARENT);
        nothing.setBounds(0, 0, 0, 0);
        return nothing;
    }

    /** Fetch the bytes if we haven't got them, decode them, and put the drawable within reach. */
    private void request(String source, String key) {
        if (!inFlight.add(key)) {
            return;
        }

        // The box is read here, on the main thread, and carried into the task: it is what the
        // drawable is keyed on, and it must not be read from under the decoder as the panel resizes.
        final int width = maxWidthPx;
        final int height = maxHeightPx;

        LOADER.execute(() -> {
            Drawable built = null;
            try {
                byte[] bytes = BYTES.get(source);
                if (bytes == null) {
                    bytes = read(source);
                    BYTES.put(source, bytes);
                }
                built = build(bytes, width, height);
            } catch (Exception e) {
                LimeLog.warning("Companion: failed to load description image " + source + ": " + e);
            }

            final Drawable drawable = built;
            mainThread.post(() -> {
                inFlight.remove(key);
                if (drawable == null) {
                    failed.add(source);
                    return;
                }
                decoded.put(key, drawable);
                callback.onImageLoaded();
            });
        });
    }

    /**
     * Decode to fit the description's column, animated if the platform and the image allow it.
     * Runs on {@link #LOADER}: the drawable it returns has been built but not yet shown, and is
     * handed to the main thread to be drawn and played.
     */
    private Drawable build(byte[] bytes, int width, int height) {
        Drawable drawable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? decodeScaled(bytes, width, height)
                : decodeFirstFrame(bytes, width, height);
        if (drawable == null) {
            return null;
        }

        drawable.setBounds(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        if (drawable instanceof AnimatedImageDrawable) {
            // A TextView will not invalidate for a drawable it does not own, so the frames are
            // pumped through to it by hand. It is not started here: it is started when a parse
            // puts it on screen, and only then.
            drawable.setCallback(animationCallback);
            ((AnimatedImageDrawable) drawable).setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
        }
        return drawable;
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private Drawable decodeScaled(byte[] bytes, int maxWidth, int maxHeight) {
        try {
            return ImageDecoder.decodeDrawable(
                    ImageDecoder.createSource(ByteBuffer.wrap(bytes)),
                    (decoder, info, source) -> {
                        int width = info.getSize().getWidth();
                        int height = info.getSize().getHeight();
                        float scale = Math.min(
                                maxWidth / (float) width,
                                maxHeight / (float) height);
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

    private Drawable decodeFirstFrame(byte[] bytes, int maxWidth, int maxHeight) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, bounds.outWidth / maxWidth);
        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (decoded == null) {
            return null;
        }

        float scale = Math.min(
                maxWidth / (float) decoded.getWidth(),
                maxHeight / (float) decoded.getHeight());
        if (scale < 1f) {
            decoded = Bitmap.createScaledBitmap(decoded,
                    Math.max(1, Math.round(decoded.getWidth() * scale)),
                    Math.max(1, Math.round(decoded.getHeight() * scale)),
                    true);
        }
        return new BitmapDrawable(target.getResources(), decoded);
    }

    /** A drawable is only good for the box it was decoded for, so the box is part of its name. */
    private String key(String source) {
        return maxWidthPx + "x" + maxHeightPx + ":" + source;
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
