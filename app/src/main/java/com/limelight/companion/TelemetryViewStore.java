package com.limelight.companion;

import android.content.Context;

import com.limelight.LimeLog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Finds the HTML view that renders a given game's telemetry.
 *
 * <h3>Why this is an interface with one implementation</h3>
 *
 * How views reach a device is not settled — a folder to drop files into, zips built in CI, a
 * dedicated repo. What a renderer needs is settled: given the profile that produced the telemetry,
 * hand me the page that draws it. Keeping that behind one method means the packaging decision can
 * be made later without touching the renderer or the transport.
 *
 * <h3>Keyed on the profile, not the app</h3>
 *
 * A view is written against a <em>profile</em> — the same artefact that decided which values exist
 * and what they are called — so the profile name is what identifies it. The app name would be the
 * wrong key: on a Vibepollo host that is whatever the user typed when they added the game, which
 * differs between machines and says nothing about the shape of the values.
 */
public interface TelemetryViewStore {

    /**
     * The view for {@code profile}, or null if there is none installed.
     *
     * @param profile  profile name as the host reported it
     * @param contract contract version the profile declared, or null if it declared none
     */
    String find(String profile, Integer contract);

    /**
     * Views kept as files in a directory on this device.
     *
     * <p>Deliberately the first backend: it mirrors how scry profiles are handled host-side today —
     * dropped into a folder by hand — so a view can be written and tried without any distribution
     * mechanism existing yet.
     */
    class LocalFolder implements TelemetryViewStore {

        /** Under getExternalFilesDir, so it can be filled over USB without root. */
        private static final String DIRECTORY = "telemetry-views";

        private final File root;

        public LocalFolder(Context context) {
            this.root = context.getExternalFilesDir(DIRECTORY);
        }

        @Override
        public String find(String profile, Integer contract) {
            if (root == null || profile == null || profile.isEmpty()) {
                return null;
            }

            String safe = sanitise(profile);
            if (safe.isEmpty()) {
                return null;
            }

            // A contract-specific view wins over a general one, so that a profile which changes the
            // shape of its values can ship a new view without breaking the old pairing.
            if (contract != null) {
                String versioned = read(new File(root, safe + "@" + contract + ".html"));
                if (versioned != null) {
                    return versioned;
                }
            }
            return read(new File(root, safe + ".html"));
        }

        /**
         * Reduce a host-supplied name to something that cannot escape the directory.
         *
         * <p>The profile name arrives over the network, so it is treated as data and never as a
         * path: anything that is not plainly a name is replaced, which also means a lookup can fail
         * but can never read a file that was not put there to be read.
         */
        private static String sanitise(String profile) {
            StringBuilder out = new StringBuilder(profile.length());
            for (int i = 0; i < profile.length(); i++) {
                char c = profile.charAt(i);
                if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == ' ') {
                    out.append(c);
                } else {
                    out.append('_');
                }
            }
            return out.toString().trim();
        }

        private static String read(File file) {
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
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                LimeLog.warning("Telemetry: could not read view " + file.getName() + ": " + e.getMessage());
                return null;
            }
        }
    }
}
