package com.limelight.companion;

import android.content.Context;

import com.limelight.LimeLog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Finds the HTML view that renders a given game's telemetry.
 *
 * <h3>Why this is an interface with one implementation</h3>
 *
 * How views reach a device is not settled: a folder to drop files into today, an index fetched from
 * the views repository later. What a renderer needs is settled: given the contract the telemetry
 * follows, hand me the page that draws it. Keeping that behind one method means the packaging
 * decision can be made later without touching the renderer or the transport.
 *
 * <h3>Keyed on the contract, not the profile or the app</h3>
 *
 * A view is written against a <em>contract</em>: which values exist, what they are called, and of
 * what type. Many profiles implement one contract, one per build of a game, so the profile's label
 * is the wrong key, and renaming it must never unbind a view. The app name is wronger still: on a
 * Vibepollo host it is whatever the user typed when they added the game. See scry's
 * {@code docs/contracts-and-views.md}.
 */
public interface TelemetryViewStore {

    /**
     * The view for {@code contract}, or null if none installed reads it.
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

    /**
     * Views kept as files in a directory on this device, listed by the {@code index.json} the
     * views repository builds next to them.
     *
     * <p>Deliberately the first backend: it mirrors how scry profiles are handled host-side today,
     * dropped into a folder by hand ({@code npm run push} in ratatoskr-telemetry-views), so a view
     * can be written and tried without any distribution mechanism existing yet.
     */
    class LocalFolder implements TelemetryViewStore {

        /** Under getExternalFilesDir, so it can be filled over USB without root. */
        private static final String DIRECTORY = "telemetry-views";
        private static final String INDEX = "index.json";
        /** The only index layout this reader understands. */
        private static final int INDEX_FORMAT = 1;

        private final File root;

        public LocalFolder(Context context) {
            this.root = context.getExternalFilesDir(DIRECTORY);
        }

        /**
         * The view whose declared range includes the announced version. When several do, the one
         * with the highest lower bound wins: it was written against the most recent minor, so it
         * knows the most about what that version carries.
         *
         * <p>There is no fallback to a view for some other version. A view drawn against a contract
         * it was not written for shows values under the wrong names, or none, with nothing to say
         * so; showing the stats instead is the honest answer.
         */
        @Override
        public String find(Contract contract) {
            if (root == null || contract == null) {
                return null;
            }

            JSONObject index = readIndex();
            if (index == null) {
                return null;
            }

            JSONArray views = index.optJSONArray("views");
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
                int[] min = caretMinimum(view.optString("range", ""));
                if (min == null || !satisfies(min, contract)) {
                    continue;
                }
                if (bestMin == null || compare(min, bestMin) > 0) {
                    best = view;
                    bestMin = min;
                }
            }
            if (best == null) {
                return null;
            }

            String file = best.optString("file", "");
            if (!isPlainFileName(file)) {
                LimeLog.warning("Telemetry: index names a view file that is not a plain name: " + file);
                return null;
            }
            byte[] bytes = readBytes(new File(root, file));
            if (bytes == null) {
                return null;
            }
            // The index is written by the same build that wrote the view. A file that no longer
            // matches it was replaced by hand or cut short on the way, and is not the view the
            // index describes.
            String expected = best.optString("sha256", "");
            if (!expected.isEmpty() && !expected.equalsIgnoreCase(sha256(bytes))) {
                LimeLog.warning("Telemetry: " + file + " does not match its hash in the index");
                return null;
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }

        private JSONObject readIndex() {
            byte[] bytes = readBytes(new File(root, INDEX));
            if (bytes == null) {
                return null;
            }
            try {
                JSONObject index = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                if (index.optInt("format", 0) != INDEX_FORMAT) {
                    LimeLog.warning("Telemetry: " + INDEX + " has a format this app does not read");
                    return null;
                }
                return index;
            } catch (JSONException e) {
                LimeLog.warning("Telemetry: " + INDEX + " is not valid JSON");
                return null;
            }
        }

        /**
         * The lower bound of a caret range, {@code "^2.1"} being every version from 2.1 up to, not
         * including, 3.0. Caret is the only kind the views repository writes.
         */
        private static int[] caretMinimum(String range) {
            return range.startsWith("^") ? Contract.parseVersion(range.substring(1)) : null;
        }

        private static boolean satisfies(int[] min, Contract version) {
            int[] announced = {version.major, version.minor};
            return compare(announced, min) >= 0 && announced[0] < min[0] + 1;
        }

        private static int compare(int[] a, int[] b) {
            return a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]);
        }

        /**
         * The index arrives on the device from outside the app, so the file it names is treated as
         * data, never as a path: anything but a plain name ending in {@code .html} is refused, which
         * means a lookup can fail but can never read a file that was not put there to be read.
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

        private static byte[] readBytes(File file) {
            if (!file.isFile()) {
                return null;
            }
            // Read by hand rather than with java.nio.file, which is API 26 and this app supports 21.
            try (FileInputStream in = new FileInputStream(file)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) != -1) {
                    out.write(chunk, 0, read);
                }
                return out.toByteArray();
            } catch (IOException e) {
                LimeLog.warning("Telemetry: could not read " + file.getName() + ": " + e.getMessage());
                return null;
            }
        }
    }
}
