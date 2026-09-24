package com.limelight.companion;

import android.content.Context;

import com.limelight.LimeLog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Finds the HTML view that renders a given game's telemetry.
 *
 * <h3>Keyed on the contract, not the profile or the app</h3>
 *
 * A view is written against a <em>contract</em>: which values exist, what they are called, and of
 * what type. Many profiles implement one contract, one per build of a game, so the profile's label
 * is the wrong key, and renaming it must never unbind a view. The app name is wronger still: on a
 * Vibepollo host it is whatever the user typed when they added the game. See scry's
 * {@code docs/contracts-and-views.md}.
 *
 * <h3>Where views come from</h3>
 *
 * ratatoskr-telemetry-views builds each view into one HTML file and lists them in an
 * {@code index.json}: the contract id, the caret range of versions the view reads, the file, and
 * its sha256. The same index is read from two places, in this order ({@link Chain}):
 * <ol>
 *   <li>{@link LocalFolder}, filled by hand over USB with {@code npm run push}, so a view can be
 *   tried on the device before it is published;</li>
 *   <li>{@link Published}, the index on GitHub Pages, from which only the view for the contract
 *   actually announced is downloaded.</li>
 * </ol>
 *
 * <p>{@link #find} may touch the disk and the network, so it is never called on the main thread.
 */
public interface TelemetryViewStore {

    /**
     * The view for {@code contract}, or null if none reads it. Blocking.
     */
    String find(Contract contract);

    /** A contract as the host announces it: an id such as {@code sea-of-stars} and a version. */
    final class Contract {
        public final String id;
        public final int major;
        public final int minor;

        private Contract(String id, int major, int minor) {
            this.id = id;
            this.major = major;
            this.minor = minor;
        }

        /**
         * The contract a snapshot announces, or null when it names none a view could be picked by.
         *
         * <p>A host with scry 0.1.0-alpha.4 or later sends {@code contract: {id, version}}. An older
         * one sends a bare integer, the major alone, with no id: a view cannot be chosen from that,
         * so it counts as no contract at all, the same as a profile that declares none.
         */
        public static Contract from(JSONObject snapshot) {
            JSONObject contract = snapshot.optJSONObject("contract");
            if (contract == null) {
                return null;
            }
            String id = contract.optString("id", "");
            int[] version = parseVersion(contract.optString("version", ""));
            if (id.isEmpty() || version == null) {
                return null;
            }
            return new Contract(id, version[0], version[1]);
        }

        @Override
        public String toString() {
            return id + " " + major + "." + minor;
        }

        /** {@code "2.1"} as {major, minor}, or null for anything that is not exactly that. */
        static int[] parseVersion(String text) {
            String[] parts = text.split("\\.", -1);
            if (parts.length != 2) {
                return null;
            }
            try {
                int major = Integer.parseInt(parts[0]);
                int minor = Integer.parseInt(parts[1]);
                return major >= 0 && minor >= 0 ? new int[] {major, minor} : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /** Reading an {@code index.json}, wherever it came from. */
    final class Index {
        /** The only index layout this reader understands. */
        private static final int FORMAT = 1;

        private Index() {
        }

        /** The index in {@code bytes}, or null if it is not one this app can read. */
        static JSONObject parse(byte[] bytes, String from) {
            if (bytes == null) {
                return null;
            }
            try {
                JSONObject index = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                if (index.optInt("format", 0) != FORMAT) {
                    LimeLog.warning("Telemetry: the view index at " + from + " has a format this app does not read");
                    return null;
                }
                return index;
            } catch (JSONException e) {
                LimeLog.warning("Telemetry: the view index at " + from + " is not valid JSON");
                return null;
            }
        }

        /**
         * The entry whose declared range includes the announced version. When several do, the one
         * with the highest lower bound wins: it was written against the most recent minor, so it
         * knows the most about what that version carries.
         *
         * <p>There is no fallback to a view for some other version. A view drawn against a contract
         * it was not written for shows values under the wrong names, or none, with nothing to say
         * so; showing the stats instead is the honest answer.
         */
        static JSONObject pick(JSONObject index, Contract contract) {
            JSONArray views = index != null ? index.optJSONArray("views") : null;
            if (views == null) {
                return null;
            }

            JSONObject best = null;
            int[] bestMin = null;
            for (int i = 0; i < views.length(); i++) {
                JSONObject view = views.optJSONObject(i);
                if (view == null || !contract.id.equals(view.optString("contract", ""))) {
                    continue;
                }
                String range = view.optString("range", "");
                // Caret is the only kind the views repository writes: "^2.1" is every version from
                // 2.1 up to, not including, 3.0.
                int[] min = range.startsWith("^") ? Contract.parseVersion(range.substring(1)) : null;
                if (min == null || !satisfies(min, contract)) {
                    continue;
                }
                if (!isPlainFileName(view.optString("file", ""))) {
                    LimeLog.warning("Telemetry: the view index names a file that is not a plain name");
                    continue;
                }
                if (bestMin == null || compare(min, bestMin) > 0) {
                    best = view;
                    bestMin = min;
                }
            }
            return best;
        }

        /** Whether {@code bytes} are the file the entry describes. */
        static boolean matches(JSONObject entry, byte[] bytes) {
            String expected = entry.optString("sha256", "");
            return bytes != null && !expected.isEmpty() && expected.equalsIgnoreCase(sha256(bytes));
        }

        private static boolean satisfies(int[] min, Contract version) {
            int[] announced = {version.major, version.minor};
            return compare(announced, min) >= 0 && announced[0] < min[0] + 1;
        }

        private static int compare(int[] a, int[] b) {
            return a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]);
        }

        /**
         * The index comes from outside the app, so the file it names is treated as data, never as a
         * path: anything but a plain name ending in {@code .html} is refused. A lookup can fail, but
         * it can never read or write a file that was not meant for it.
         */
        private static boolean isPlainFileName(String name) {
            return name.endsWith(".html") && !name.contains("/") && !name.contains("\\")
                    && !name.startsWith(".");
        }

        private static String sha256(byte[] bytes) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
                StringBuilder hex = new StringBuilder(digest.length * 2);
                for (byte b : digest) {
                    hex.append(String.format("%02x", b));
                }
                return hex.toString();
            } catch (NoSuchAlgorithmException e) {
                // Every Android runtime ships SHA-256; this cannot happen.
                throw new IllegalStateException(e);
            }
        }
    }

    /** The first store that has a view for the contract wins. */
    final class Chain implements TelemetryViewStore {
        private final TelemetryViewStore[] stores;

        public Chain(TelemetryViewStore... stores) {
            this.stores = stores;
        }

        @Override
        public String find(Contract contract) {
            for (TelemetryViewStore store : stores) {
                String html = store.find(contract);
                if (html != null) {
                    return html;
                }
            }
            return null;
        }
    }

    /**
     * Views pushed onto the device by hand, next to their {@code index.json}.
     *
     * <p>Checked first, so a view can be tried on the device before it is published; with nothing
     * pushed, it simply finds nothing and the published views are used.
     */
    final class LocalFolder implements TelemetryViewStore {

        /** Under getExternalFilesDir, so it can be filled over USB without root. */
        private static final String DIRECTORY = "telemetry-views";

        private final File root;

        public LocalFolder(Context context) {
            this.root = context.getExternalFilesDir(DIRECTORY);
        }

        @Override
        public String find(Contract contract) {
            if (root == null || contract == null) {
                return null;
            }
            File indexFile = new File(root, "index.json");
            JSONObject entry = Index.pick(Index.parse(Files.read(indexFile), indexFile.getPath()), contract);
            if (entry == null) {
                return null;
            }
            String file = entry.optString("file");
            byte[] bytes = Files.read(new File(root, file));
            if (bytes == null) {
                return null;
            }
            // The index is written by the same build that wrote the view. A file that no longer
            // matches it was replaced by hand or cut short on the way.
            if (!Index.matches(entry, bytes)) {
                LimeLog.warning("Telemetry: " + file + " does not match its hash in the local index");
                return null;
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /**
     * The views published to GitHub Pages by ratatoskr-telemetry-views.
     *
     * <p>Only the index and the view for the contract actually announced are downloaded, never the
     * whole catalogue. Both are kept in the app's private files, so a view downloaded once keeps
     * working without a network. The index is fetched again at most once a day, and also when it
     * does not know a contract a game announces, since that contract's view may have been published
     * since; but only once per contract per run of the app, so a game with no view does not ask on
     * every snapshot.
     */
    final class Published implements TelemetryViewStore {

        private static final String BASE_URL = "https://danieles.github.io/ratatoskr-telemetry-views/";
        private static final String DIRECTORY = "telemetry-views-published";
        private static final String INDEX = "index.json";
        private static final long INDEX_MAX_AGE_MS = TimeUnit.DAYS.toMillis(1);
        /** Far above any real view (the Sea of Stars one is 134 KB), and a bound on a bad response. */
        private static final long MAX_BYTES = 8L * 1024 * 1024;

        private final File root;
        private final OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build();
        /** Contracts the index was already refetched for in this run, found or not. */
        private final Set<String> refetchedFor = new HashSet<>();

        public Published(Context context) {
            this.root = new File(context.getFilesDir(), DIRECTORY);
        }

        @Override
        public synchronized String find(Contract contract) {
            if (contract == null || (!root.isDirectory() && !root.mkdirs())) {
                return null;
            }

            File indexFile = new File(root, INDEX);
            boolean fresh = indexFile.isFile()
                    && System.currentTimeMillis() - indexFile.lastModified() < INDEX_MAX_AGE_MS;
            JSONObject index = fresh ? Index.parse(Files.read(indexFile), indexFile.getPath()) : fetchIndex(indexFile);
            JSONObject entry = Index.pick(index, contract);

            if (entry == null && refetchedFor.add(contract.toString())) {
                JSONObject refreshed = fetchIndex(indexFile);
                if (refreshed != null) {
                    entry = Index.pick(refreshed, contract);
                }
            }
            if (entry == null) {
                return null;
            }

            String file = entry.optString("file");
            File cached = new File(root, file);
            byte[] bytes = Files.read(cached);
            if (!Index.matches(entry, bytes)) {
                bytes = download(BASE_URL + file);
                if (!Index.matches(entry, bytes)) {
                    // Pages caches for a few minutes, so a new index can briefly name a view the CDN
                    // still serves the old copy of. Nothing is kept; the next resolve tries again.
                    LimeLog.warning("Telemetry: the published " + file + " does not match its hash in the index");
                    return null;
                }
                Files.writeAtomically(cached, bytes);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }

        /**
         * Fetch the index and keep it. Without a network the copy already kept is used, however old,
         * and a failed fetch touches nothing.
         */
        private JSONObject fetchIndex(File indexFile) {
            byte[] bytes = download(BASE_URL + INDEX);
            JSONObject index = Index.parse(bytes, BASE_URL + INDEX);
            if (index != null) {
                Files.writeAtomically(indexFile, bytes);
                return index;
            }
            return Index.parse(Files.read(indexFile), indexFile.getPath());
        }

        private byte[] download(String url) {
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = http.newCall(request).execute()) {
                ResponseBody body = response.body();
                if (!response.isSuccessful() || body == null) {
                    LimeLog.info("Telemetry: " + url + " answered " + response.code());
                    return null;
                }
                return Files.readBounded(body.byteStream(), MAX_BYTES);
            } catch (IOException e) {
                LimeLog.info("Telemetry: could not fetch " + url + ": " + e.getMessage());
                return null;
            }
        }
    }

    /** File helpers, by hand rather than with java.nio.file, which is API 26 and this app supports 21. */
    final class Files {
        private Files() {
        }

        static byte[] read(File file) {
            if (!file.isFile()) {
                return null;
            }
            try (FileInputStream in = new FileInputStream(file)) {
                return readBounded(in, Long.MAX_VALUE);
            } catch (IOException e) {
                LimeLog.warning("Telemetry: could not read " + file.getName() + ": " + e.getMessage());
                return null;
            }
        }

        static byte[] readBounded(InputStream in, long max) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(chunk)) != -1) {
                total += read;
                if (total > max) {
                    throw new IOException("larger than " + max + " bytes");
                }
                out.write(chunk, 0, read);
            }
            return out.toByteArray();
        }

        /** Written next to the target and renamed over it, so a reader never sees half a file. */
        static void writeAtomically(File target, byte[] bytes) {
            File partial = new File(target.getPath() + ".part");
            try (FileOutputStream out = new FileOutputStream(partial)) {
                out.write(bytes);
            } catch (IOException e) {
                LimeLog.warning("Telemetry: could not write " + target.getName() + ": " + e.getMessage());
                partial.delete();
                return;
            }
            if (!partial.renameTo(target)) {
                partial.delete();
            }
        }
    }
}
