package com.limelight.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.provider.DocumentsContract;
import android.text.TextUtils;

import androidx.documentfile.provider.DocumentFile;

import com.limelight.LimeLog;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Keeps a folder of launcher files in step with a host's app list, for frontends that build their
 * library by scanning a folder — Cocoon and its kin.
 *
 * Only one host is ever exported. The frontend flattens whatever it scans into a single list, so a
 * second host would only contribute entries indistinguishable from the first's: the same titles,
 * twice, with nothing on screen to say which machine each belongs to. A folder per host would hide
 * that no better, since the folders themselves are flattened away too.
 *
 * A file's name is the game's identity as far as the frontend is concerned: rename it and the
 * frontend drops the old entry and scrapes a new one from scratch, losing the artwork and metadata
 * it had gathered. So a file is written once and then left alone for as long as its title holds,
 * and a sync that has nothing to say writes nothing at all — an identical rewrite still moves the
 * bytes, and the frontend hashes them to decide what it is looking at.
 */
public final class CocoonSync {

    /** Matches the {@code acceptedFilenameRegex} of the Ratatoskr player in the platform file. */
    private static final String EXTENSION = ".ratatoskr";

    /** Stamped into every file we write, and the only reason we would ever delete one. */
    private static final String GENERATED_BY = "ratatoskr-sync";

    private static final String MIME_TYPE = "application/octet-stream";

    public static final String ENABLED_PREF_STRING = "checkbox_cocoon_sync";
    public static final String FOLDER_PREF_STRING = "cocoon_sync_folder";
    public static final String HOST_PREF_STRING = "cocoon_sync_host";

    private CocoonSync() {
    }

    /** The chosen folder as a path a person would recognise, for the settings screen. */
    public static String describeFolder(Uri treeUri) {
        String documentId = DocumentsContract.getTreeDocumentId(treeUri);
        if (documentId == null) {
            return treeUri.toString();
        }

        // Tree ids read "primary:Roms/moonlight"; the volume means nothing to the reader.
        int colon = documentId.indexOf(':');
        String path = colon >= 0 ? documentId.substring(colon + 1) : documentId;
        return path.isEmpty() ? documentId : path;
    }

    /** Whether {@code computer} is the host the user chose to export. */
    public static boolean isDesignatedHost(Context context, ComputerDetails computer) {
        if (computer == null || computer.uuid == null) {
            return false;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return computer.uuid.equalsIgnoreCase(prefs.getString(HOST_PREF_STRING, ""));
    }

    /**
     * Brings the folder in line with {@code apps}, on the calling thread.
     *
     * Does nothing at all unless the user turned the sync on, picked a folder, and named this host
     * as the one to export. An empty app list is taken as a failed fetch rather than a host with no
     * games, since the two are indistinguishable here and only one of them is worth wiping a
     * library for.
     */
    public static void sync(Context context, ComputerDetails computer, List<NvApp> apps) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (!prefs.getBoolean(ENABLED_PREF_STRING, false)) {
            return;
        }

        String folder = prefs.getString(FOLDER_PREF_STRING, "");
        if (TextUtils.isEmpty(folder) || !isDesignatedHost(context, computer)) {
            return;
        }

        if (apps == null || apps.isEmpty()) {
            LimeLog.warning("CocoonSync: empty app list, leaving the folder alone");
            return;
        }

        DocumentFile dir = DocumentFile.fromTreeUri(context, Uri.parse(folder));
        if (dir == null || !dir.isDirectory() || !dir.canWrite()) {
            LimeLog.warning("CocoonSync: cannot write to the chosen folder");
            return;
        }

        try {
            reconcile(context, dir, computer, apps);
        } catch (Exception e) {
            // A frontend's library is not worth crashing a connection over.
            LimeLog.severe("CocoonSync: sync failed: " + e.getMessage());
        }
    }

    private static void reconcile(Context context, DocumentFile dir, ComputerDetails computer,
                                  List<NvApp> apps) {
        // Every file in the folder we wrote for this host, grouped by the app it points at. The
        // frontend's handle on a game is the file's name, so an app can end up with more than one
        // of our files — an old name left over from a rename — and all of them are ours to manage.
        // Anything else in the folder (another host's entries, files put there by hand) is not ours
        // to touch, but its name is still taken, so we leave those names alone as well.
        List<DocumentFile> managed = new ArrayList<>();
        Map<DocumentFile, String> managedContent = new HashMap<>();
        Map<String, List<DocumentFile>> managedByIdentity = new HashMap<>();
        Set<String> foreignNames = new HashSet<>();

        for (DocumentFile file : dir.listFiles()) {
            String name = file.getName();
            if (name == null || !name.endsWith(EXTENSION) || !file.isFile()) {
                continue;
            }

            String content = read(context, file);
            Map<String, String> keys = content != null ? parse(content) : null;
            if (keys == null || !GENERATED_BY.equals(keys.get(ShortcutHelper.KEY_GENERATED_BY)) ||
                    !computer.uuid.equalsIgnoreCase(keys.get(ShortcutHelper.KEY_HOST_UUID))) {
                foreignNames.add(name);
                continue;
            }

            String identity = identityOf(keys.get(ShortcutHelper.KEY_APP_UUID), keys.get(ShortcutHelper.KEY_APP_ID));
            if (identity == null) {
                foreignNames.add(name);
                continue;
            }

            managed.add(file);
            managedContent.put(file, content);
            // computeIfAbsent is API 24; minSdk here is 21, so grow the list by hand.
            List<DocumentFile> forIdentity = managedByIdentity.get(identity);
            if (forIdentity == null) {
                forIdentity = new ArrayList<>();
                managedByIdentity.put(identity, forIdentity);
            }
            forIdentity.add(file);
        }

        List<String> written = new ArrayList<>();
        // Tracked by file, not by identity: on a rename the file under the old name and the one
        // under the new name share an identity, but only the current-named one is kept — the old
        // one falls through to the removal below rather than being spared by its identity.
        Set<DocumentFile> kept = new HashSet<>();

        for (NvApp app : apps) {
            String identity = identityOf(app.getAppUUID(), app.getAppId() > 0 ? String.valueOf(app.getAppId()) : null);
            String fileName = fileNameFor(app);
            if (identity == null || fileName == null) {
                continue;
            }

            String content = ShortcutHelper.buildLauncherFileContent(computer, app, GENERATED_BY);

            // Reuse the file that already carries this game's current title, if we have one; any
            // other files for the same identity are stale names left to the removal pass.
            DocumentFile existing = null;
            List<DocumentFile> candidates = managedByIdentity.get(identity);
            if (candidates != null) {
                for (DocumentFile candidate : candidates) {
                    if (fileName.equals(candidate.getName())) {
                        existing = candidate;
                        break;
                    }
                }
            }

            if (existing != null) {
                kept.add(existing);
                if (!content.equals(managedContent.get(existing))) {
                    write(context, existing, content);
                }
                continue;
            }

            // Either new, or the title changed under us — which the frontend reads as a different
            // game whatever we do, so the old file has nothing left to offer.
            if (foreignNames.contains(fileName)) {
                LimeLog.warning("CocoonSync: not overwriting a file we did not write: " + fileName);
                continue;
            }

            DocumentFile created = dir.createFile(MIME_TYPE, fileName);
            if (created == null) {
                LimeLog.warning("CocoonSync: could not create " + fileName);
                continue;
            }

            if (write(context, created, content)) {
                kept.add(created);
                written.add(fileName);
            } else {
                created.delete();
            }
        }

        int removed = 0;
        for (DocumentFile file : managed) {
            if (!kept.contains(file) && file.delete()) {
                removed++;
            }
        }

        if (!written.isEmpty() || removed > 0) {
            LimeLog.info("CocoonSync: " + written.size() + " written, " + removed + " removed");
        }
    }

    /**
     * What identifies an app across syncs. The UUID is the app's own; the ID is the host's handle
     * for it, and is all a Sunshine of a certain age offers.
     */
    private static String identityOf(String appUUID, String appId) {
        if (!TextUtils.isEmpty(appUUID)) {
            return "uuid:" + appUUID.toUpperCase(Locale.ROOT);
        }
        if (!TextUtils.isEmpty(appId)) {
            return "id:" + appId;
        }
        return null;
    }

    /**
     * The frontend takes a game's title from the file's name, so the name has to carry it whole,
     * minus what a filesystem will not hold. Names that reduce to nothing are skipped rather than
     * turned into something meaningless.
     */
    private static String fileNameFor(NvApp app) {
        String name = app.getAppName();
        if (name == null) {
            return null;
        }

        String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", " ").replaceAll("\\s+", " ").trim();
        return cleaned.isEmpty() ? null : cleaned + EXTENSION;
    }

    private static Map<String, String> parse(String content) {
        Map<String, String> keys = new HashMap<>();

        for (String line : content.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || !line.startsWith("[")) {
                continue;
            }

            int separator = line.indexOf(' ');
            if (separator <= 0 || separator >= line.length() - 1) {
                continue;
            }

            String key = line.substring(0, separator).trim();
            if (!key.endsWith("]")) {
                continue;
            }

            keys.put(key.substring(1, key.length() - 1), line.substring(separator + 1).trim());
        }

        return keys;
    }

    private static String read(Context context, DocumentFile file) {
        try (InputStream in = context.getContentResolver().openInputStream(file.getUri());
             BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (IOException | SecurityException e) {
            LimeLog.warning("CocoonSync: could not read " + file.getName() + ": " + e.getMessage());
            return null;
        }
    }

    private static boolean write(Context context, DocumentFile file, String content) {
        // "wt" truncates: without it a shorter entry would leave the tail of the longer one behind.
        try (OutputStream out = context.getContentResolver().openOutputStream(file.getUri(), "wt")) {
            if (out == null) {
                return false;
            }
            out.write(content.getBytes("UTF-8"));
            return true;
        } catch (IOException | SecurityException e) {
            LimeLog.warning("CocoonSync: could not write " + file.getName() + ": " + e.getMessage());
            return false;
        }
    }
}
