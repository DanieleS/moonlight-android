package com.limelight.stats;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.utils.CacheHelper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.StringReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Box art for the games the statistics name, by app UUID.
 *
 * <p>The statistics speak in UUIDs while the library's covers are cached by app id, so the UUIDs
 * are looked up in the app list the library cached on its last visit. The cover comes from the
 * library's own cache when it is there, and from the host otherwise — not written back, so the
 * library stays the only thing that manages that cache.
 */
public class CoverLoader {
    private static final int MAX_WIDTH_PX = 300;

    private final Context context;
    private final HostSession session;
    private final String pcUuid;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache = new LruCache<>(24);
    private Map<String, NvApp> appsByUuid;

    public CoverLoader(Context context, HostSession session, String pcUuid) {
        this.context = context.getApplicationContext();
        this.session = session;
        this.pcUuid = pcUuid;
    }

    /** Show the game's cover in {@code view}, or leave the placeholder when there is none. */
    public void load(ImageView view, String appUuid) {
        final String key = appUuid == null ? "" : appUuid.toUpperCase();
        view.setTag(R.id.coverAppUuid, key);
        Bitmap cached = cache.get(key);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageResource(R.drawable.connection_cover_placeholder);
        if (key.isEmpty() || pcUuid == null) {
            return;
        }
        executor.execute(() -> {
            Bitmap bitmap = fetch(key);
            if (bitmap == null) {
                return;
            }
            main.post(() -> {
                cache.put(key, bitmap);
                if (key.equals(view.getTag(R.id.coverAppUuid))) {
                    view.setImageBitmap(bitmap);
                }
            });
        });
    }

    /** The app the library knows by this UUID, from its cached list; null when it has none. */
    public synchronized NvApp findApp(String appUuid) {
        if (appsByUuid == null) {
            appsByUuid = new HashMap<>();
            try {
                String raw = CacheHelper.readInputStreamToString(
                        CacheHelper.openCacheFileForInput(context.getCacheDir(), "applist", pcUuid));
                List<NvApp> apps = NvHTTP.getAppListByReader(new StringReader(raw));
                for (NvApp app : apps) {
                    if (app.getAppUUID() != null) {
                        appsByUuid.put(app.getAppUUID().toUpperCase(), app);
                    }
                }
            } catch (Exception e) {
                LimeLog.warning("Stats: no cached app list: " + e.getMessage());
            }
        }
        return appUuid == null ? null : appsByUuid.get(appUuid.toUpperCase());
    }

    private Bitmap fetch(String uuid) {
        NvApp app = findApp(uuid);
        if (app == null) {
            return null;
        }
        File file = CacheHelper.openPath(false, context.getCacheDir(), "boxart", pcUuid, app.getAppId() + ".png");
        try {
            if (file.exists()) {
                return decode(new java.io.FileInputStream(file));
            }
            return decode(session.http().getBoxArt(app));
        } catch (Exception e) {
            LimeLog.warning("Stats: no cover for " + uuid + ": " + e.getMessage());
            return null;
        }
    }

    private static Bitmap decode(InputStream in) throws java.io.IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            int read;
            while ((read = stream.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            byte[] bytes = buffer.toByteArray();
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= MAX_WIDTH_PX) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
