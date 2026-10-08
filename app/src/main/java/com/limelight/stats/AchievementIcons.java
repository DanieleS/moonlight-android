package com.limelight.stats;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.nvstream.http.Achievement;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Achievement icons, kept in memory for as long as the app runs.
 *
 * <p>Most are public HTTPS URLs on a store's CDN (Steam's, mostly), which are fetched with a plain
 * client: they are not the host's to serve, and the paired connection would refuse a foreign
 * certificate anyway. The ones SuccessStory keeps on disk arrive as a host-relative
 * {@code /appachievementicon?...} path and go through the paired NvHTTP connection instead.
 *
 * <p>The views they land in are recycled, in lists and in the toast, so each request is tied to
 * its view by a tag and dropped if the view has moved on to another icon by the time it loads.
 * An icon that failed once is not asked for again this run; the trophy stands in for it.
 */
public final class AchievementIcons {
    // Icons are 64-256 px squares; a few hundred of them fit in this comfortably.
    private static final int CACHE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_SIDE_PX = 256;

    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(CACHE_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final Set<String> failed = Collections.synchronizedSet(new HashSet<>());
    private static final ExecutorService executor = Executors.newFixedThreadPool(3);
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static OkHttpClient webClient;

    private static final ColorMatrixColorFilter GREY;

    static {
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0f);
        // A little darker as well, as CouchPilot does (grayscale, brightness 0.7).
        ColorMatrix dim = new ColorMatrix();
        dim.setScale(0.7f, 0.7f, 0.7f, 1f);
        matrix.postConcat(dim);
        GREY = new ColorMatrixColorFilter(matrix);
    }

    private AchievementIcons() {
    }

    /**
     * Show an achievement's icon in {@code view}: the trophy at once, the real icon when it has
     * loaded. Locked ones without a locked icon of their own are greyed out.
     */
    public static void load(ImageView view, Achievement achievement, HostSession session) {
        final String url = achievement.getDisplayIcon();
        final boolean grey = achievement.needsGreyedIcon();
        view.setTag(R.id.achievementIconUrl, url);

        Bitmap cached = url == null ? null : cache.get(url);
        if (cached != null) {
            showBitmap(view, cached, grey);
            return;
        }
        showPlaceholder(view);
        if (url == null || failed.contains(url)) {
            return;
        }

        executor.execute(() -> {
            Bitmap bitmap = fetch(url, session);
            if (bitmap == null) {
                failed.add(url);
                return;
            }
            cache.put(url, bitmap);
            main.post(() -> {
                if (url.equals(view.getTag(R.id.achievementIconUrl))) {
                    showBitmap(view, bitmap, grey);
                }
            });
        });
    }

    private static void showPlaceholder(ImageView view) {
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        view.setImageResource(R.drawable.ic_trophy);
        // The trophy is a glyph, tinted like the app's other glyphs, not a picture to grey out.
        view.setColorFilter(null);
    }

    private static void showBitmap(ImageView view, Bitmap bitmap, boolean grey) {
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setColorFilter(grey ? GREY : null);
        view.setImageBitmap(bitmap);
    }

    private static Bitmap fetch(String url, HostSession session) {
        try (InputStream in = open(url, session)) {
            if (in == null) {
                return null;
            }
            return decode(readCapped(in));
        } catch (Exception e) {
            LimeLog.warning("Achievement icon " + url + ": " + e.getMessage());
            return null;
        }
    }

    private static InputStream open(String url, HostSession session) throws IOException {
        if (url.startsWith("/appachievementicon?")) {
            return session == null ? null : session.http().getAchievementIcon(url);
        }
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            return null;
        }
        Response response = webClient().newCall(new Request.Builder().url(url).build()).execute();
        ResponseBody body = response.body();
        if (!response.isSuccessful() || body == null) {
            response.close();
            return null;
        }
        return body.byteStream();
    }

    private static synchronized OkHttpClient webClient() {
        if (webClient == null) {
            webClient = new OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build();
        }
        return webClient;
    }

    private static byte[] readCapped(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[16 * 1024];
        int read;
        while ((read = in.read(chunk)) != -1) {
            out.write(chunk, 0, read);
            if (out.size() > MAX_BYTES) {
                throw new IOException("Icon too large");
            }
        }
        return out.toByteArray();
    }

    private static Bitmap decode(byte[] bytes) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= MAX_SIDE_PX && bounds.outHeight / (sample * 2) >= MAX_SIDE_PX) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }
}
