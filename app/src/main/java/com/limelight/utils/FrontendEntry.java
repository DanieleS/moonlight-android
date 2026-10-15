package com.limelight.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Helpers for the game entry files that ES-DE, Daijisho, Pegasus and other Android frontends
 * launch: the ES-DE custom system and find rule that point at {@link com.limelight.ShortcutTrampoline},
 * safe file names for the entries, and a reader for the entries already in a folder.
 *
 * <p>The entries themselves are the ".art" files {@link ShortcutHelper#buildLauncherFileContent}
 * writes, and the trampoline reads them back from the ACTION_VIEW content URI the frontend passes.
 */
public final class FrontendEntry {
    public static final String EXTENSION = ".art";

    /** Entries are a few hundred bytes; anything larger is not one of ours. */
    public static final int MAX_CHARS = 16 * 1024;

    public static final String ES_SYSTEM_NAME = "zefiro";
    public static final String ES_EMULATOR_NAME = "ZEFIRO";

    private FrontendEntry() {}

    /**
     * Reads an entry's "[key] value" lines. Malformed lines are rejected so a file that is not an
     * entry is never mistaken for one of ours (and overwritten).
     */
    public static Map<String, String> parse(Reader source) throws IOException {
        BufferedReader reader = new BufferedReader(source);
        Map<String, String> values = new HashMap<>();
        int total = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            total += line.length() + 1;
            if (total > MAX_CHARS) {
                throw new IOException("Entry file is too large");
            }
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int close = line.indexOf(']');
            if (!line.startsWith("[") || close < 2) {
                throw new IOException("Not a game entry file");
            }
            String key = line.substring(1, close).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(close + 1).trim();
            if (!value.isEmpty()) {
                values.put(key, value);
            }
        }
        if (!values.containsKey(ShortcutHelper.KEY_HOST_UUID) && !values.containsKey(ShortcutHelper.KEY_HOST_NAME)) {
            throw new IOException("Entry names no host");
        }
        return values;
    }

    /** A file name that works on FAT/exFAT SD cards and in frontends' file lists. */
    public static String fileBaseName(String appName) {
        String name = appName == null ? "" : appName;
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || "\\/:*?\"<>|".indexOf(c) >= 0) {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        String base = sb.toString().replaceAll("\\s+", " ").trim();
        // Leading dots hide files; trailing dots and spaces are dropped by FAT
        base = base.replaceAll("^[.\\s]+", "").replaceAll("[.\\s]+$", "");
        if (base.length() > 120) {
            base = base.substring(0, 120).trim();
        }
        return base.isEmpty() ? "Game" : base;
    }

    public static String esSystemBlock(String hostLabel) {
        return "    <system>\n" +
                "        <name>" + ES_SYSTEM_NAME + "</name>\n" +
                "        <fullname>" + xmlEscape(hostLabel) + "</fullname>\n" +
                "        <path>%ROMPATH%/" + ES_SYSTEM_NAME + "</path>\n" +
                "        <extension>" + EXTENSION + " " + EXTENSION.toUpperCase(Locale.ROOT) + "</extension>\n" +
                "        <command label=\"Zefiro\">%EMULATOR_" + ES_EMULATOR_NAME + "% %ACTIVITY_CLEAR_TASK% %ACTIVITY_CLEAR_TOP% " +
                "%ACTION%=android.intent.action.VIEW %DATA%=%ROMPROVIDER%</command>\n" +
                "        <platform>pc</platform>\n" +
                "        <theme>windows</theme>\n" +
                "    </system>\n";
    }

    public static String esFindRuleBlock(String packageName) {
        return "    <emulator name=\"" + ES_EMULATOR_NAME + "\">\n" +
                "        <!-- Zefiro game streaming client -->\n" +
                "        <rule type=\"androidpackage\">\n" +
                "            <entry>" + xmlEscape(packageName) + "/com.limelight.ShortcutTrampoline</entry>\n" +
                "        </rule>\n" +
                "    </emulator>\n";
    }

    /** Adds or replaces our system in an existing ES-DE custom_systems/es_systems.xml. */
    public static String mergeEsSystems(String existing, String hostLabel) {
        return mergeBlock(existing, "systemList", "<system>", "</system>",
                "<name>" + ES_SYSTEM_NAME + "</name>", esSystemBlock(hostLabel));
    }

    /** Adds or replaces our emulator in an existing ES-DE custom_systems/es_find_rules.xml. */
    public static String mergeEsFindRules(String existing, String packageName) {
        return mergeBlock(existing, "ruleList", "<emulator", "</emulator>",
                "name=\"" + ES_EMULATOR_NAME + "\"", esFindRuleBlock(packageName));
    }

    private static String mergeBlock(String existing, String root, String open, String close,
                                     String marker, String block) {
        String closeRoot = "</" + root + ">";
        if (existing == null || !existing.contains(closeRoot)) {
            return "<?xml version=\"1.0\"?>\n<" + root + ">\n" + block + closeRoot + "\n";
        }
        int markerAt = existing.indexOf(marker);
        if (markerAt >= 0) {
            int start = existing.lastIndexOf(open, markerAt);
            int end = existing.indexOf(close, markerAt);
            if (start >= 0 && end >= 0) {
                // Replace from the start of our block's line through the end of its closing line
                int lineStart = existing.lastIndexOf('\n', start) + 1;
                int lineEnd = existing.indexOf('\n', end);
                lineEnd = lineEnd < 0 ? existing.length() : lineEnd + 1;
                return existing.substring(0, lineStart) + block + existing.substring(lineEnd);
            }
        }
        int insertAt = existing.lastIndexOf(closeRoot);
        int lineStart = existing.lastIndexOf('\n', insertAt) + 1;
        if (existing.substring(lineStart, insertAt).trim().isEmpty()) {
            insertAt = lineStart;
        }
        String before = existing.substring(0, insertAt);
        if (!before.endsWith("\n")) {
            before += "\n";
        }
        return before + block + existing.substring(insertAt);
    }

    static String xmlEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
