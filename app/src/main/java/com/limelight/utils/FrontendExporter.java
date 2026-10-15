package com.limelight.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import com.limelight.AppView;
import com.limelight.LimeLog;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes one game entry file per host app into a frontend's ROMs folder and, when given the
 * ES-DE folder, registers the Zefiro system there and copies cached cover art.
 *
 * Both folders come from the Storage Access Framework, so no storage permission is needed.
 */
public final class FrontendExporter {
    private static final String ENTRY_MIME = "application/octet-stream";

    private final Context context;
    private final ContentResolver resolver;
    private final ComputerDetails computer;

    public FrontendExporter(Context context, ComputerDetails computer) {
        this.context = context.getApplicationContext();
        this.resolver = context.getContentResolver();
        this.computer = computer;
    }

    /** Apps from the cached host list, without the hidden ones and the input-only entries. */
    public List<NvApp> loadApps() {
        List<NvApp> apps = new ArrayList<>();
        try (InputStream in = CacheHelper.openCacheFileForInput(context.getCacheDir(), "applist", computer.uuid)) {
            String raw = CacheHelper.readInputStreamToString(in);
            if (raw.isEmpty()) {
                return apps;
            }
            Set<String> hidden = context.getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, Context.MODE_PRIVATE)
                    .getStringSet(computer.uuid, new HashSet<String>());
            for (NvApp app : NvHTTP.getAppListByReader(new StringReader(raw))) {
                if (app.isRemoteInput() || app.isRemoteMonitor() || app.isVirtualDisplay()) {
                    continue;
                }
                if (!hidden.contains(Integer.toString(app.getAppId()))) {
                    apps.add(app);
                }
            }
        } catch (IOException | XmlPullParserException e) {
            LimeLog.info("No cached app list for frontend export: " + e.getMessage());
        }
        return apps;
    }

    /** Returns the number of entries written. The ES-DE tree may be null, the ROMs one may not. */
    public int export(List<NvApp> apps, Uri romsTree, Uri esdeTree) throws IOException {
        Uri romsRoot = treeRoot(romsTree);
        Uri systemDir = directory(romsTree, romsRoot, FrontendEntry.ES_SYSTEM_NAME);
        Map<String, Uri> existing = listChildren(romsTree, systemDir);

        Uri coversDir = null;
        Map<String, Uri> existingCovers = new HashMap<>();
        if (esdeTree != null) {
            registerEsDeSystem(esdeTree);
            Uri esdeRoot = treeRoot(esdeTree);
            Uri media = directory(esdeTree, esdeRoot, "downloaded_media");
            Uri systemMedia = directory(esdeTree, media, FrontendEntry.ES_SYSTEM_NAME);
            coversDir = directory(esdeTree, systemMedia, "covers");
            existingCovers = listChildren(esdeTree, coversDir);
        }

        int written = 0;
        for (NvApp app : apps) {
            String base = entryBaseName(app, existing);
            String fileName = base + FrontendEntry.EXTENSION;
            Uri entry = existing.get(fileName);
            if (entry == null) {
                entry = create(systemDir, ENTRY_MIME, fileName);
                existing.put(fileName, entry);
            }
            writeText(entry, ShortcutHelper.buildLauncherFileContent(computer, app, null));
            written++;

            if (coversDir != null && !existingCovers.containsKey(base + ".png")) {
                copyCover(app, coversDir, base + ".png");
            }
        }
        return written;
    }

    /**
     * Keeps the plain game name unless another host already exported a game with that name
     * into the same folder, in which case the host name is added.
     */
    private String entryBaseName(NvApp app, Map<String, Uri> existing) {
        String base = FrontendEntry.fileBaseName(app.getAppName());
        Uri current = existing.get(base + FrontendEntry.EXTENSION);
        if (current == null || belongsToThisHost(current)) {
            return base;
        }
        return FrontendEntry.fileBaseName(app.getAppName() + " (" + computer.name + ")");
    }

    private boolean belongsToThisHost(Uri entry) {
        try (InputStream in = resolver.openInputStream(entry)) {
            if (in == null) {
                return false;
            }
            Map<String, String> values = FrontendEntry.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
            return computer.uuid.equalsIgnoreCase(values.get(ShortcutHelper.KEY_HOST_UUID));
        } catch (IOException | SecurityException e) {
            // Not one of our entries: never overwrite it
            return false;
        }
    }

    private void registerEsDeSystem(Uri esdeTree) throws IOException {
        Uri custom = directory(esdeTree, treeRoot(esdeTree), "custom_systems");
        Map<String, Uri> files = listChildren(esdeTree, custom);
        mergeXml(custom, files, "es_systems.xml", existing -> FrontendEntry.mergeEsSystems(existing, "Zefiro"));
        mergeXml(custom, files, "es_find_rules.xml",
                existing -> FrontendEntry.mergeEsFindRules(existing, context.getPackageName()));
    }

    private interface XmlMerge {
        String merge(String existing);
    }

    private void mergeXml(Uri dir, Map<String, Uri> files, String name, XmlMerge merge) throws IOException {
        Uri file = files.get(name);
        String existing = null;
        if (file != null) {
            try (InputStream in = resolver.openInputStream(file)) {
                existing = in == null ? null : CacheHelper.readInputStreamToString(in);
            }
        } else {
            file = create(dir, "text/xml", name);
        }
        writeText(file, merge.merge(existing));
    }

    /** Copies the library's cached box art, which is keyed by app ID. */
    private void copyCover(NvApp app, Uri coversDir, String name) {
        File cached = CacheHelper.openPath(false, context.getCacheDir(), "boxart",
                computer.uuid, app.getAppId() + ".png");
        if (!cached.isFile()) {
            return;
        }
        try (InputStream in = new FileInputStream(cached);
             OutputStream out = resolver.openOutputStream(create(coversDir, "image/png", name), "wt")) {
            if (out == null) {
                return;
            }
            byte[] buffer = new byte[16384];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
        } catch (IOException | SecurityException e) {
            // Covers are a convenience; ES-DE's scraper can still fetch them
            LimeLog.warning("Unable to copy cover for " + app.getAppName() + ": " + e.getMessage());
        }
    }

    private void writeText(Uri file, String text) throws IOException {
        try (OutputStream out = resolver.openOutputStream(file, "wt")) {
            if (out == null) {
                throw new IOException("Unable to write " + file);
            }
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static Uri treeRoot(Uri tree) {
        return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
    }

    private Uri directory(Uri tree, Uri parent, String name) throws IOException {
        Uri dir = listChildren(tree, parent).get(name);
        return dir != null ? dir : create(parent, Document.MIME_TYPE_DIR, name);
    }

    private Uri create(Uri parent, String mime, String name) throws IOException {
        Uri created = DocumentsContract.createDocument(resolver, parent, mime, name);
        if (created == null) {
            throw new IOException("Unable to create " + name);
        }
        return created;
    }

    private Map<String, Uri> listChildren(Uri tree, Uri parent) throws IOException {
        Map<String, Uri> children = new HashMap<>();
        Uri query = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent));
        try (Cursor cursor = resolver.query(query, new String[]{
                Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor == null) {
                throw new IOException("Unable to list folder");
            }
            while (cursor.moveToNext()) {
                children.put(cursor.getString(1), DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)));
            }
        }
        return children;
    }
}
