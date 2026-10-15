package com.limelight.utils;

import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class FrontendEntryTest {
    private static Map<String, String> parse(String text) throws IOException {
        return FrontendEntry.parse(new StringReader(text));
    }

    @Test
    public void readsLauncherFileContent() throws IOException {
        Map<String, String> values = parse("# Artemis app entry\n" +
                "[host_uuid] 1234\n[host_name] Desk\n[app_uuid] ABCD\n[app_id] 42\n[app_name] Steam Big Picture\n");
        assertEquals("1234", values.get(ShortcutHelper.KEY_HOST_UUID));
        assertEquals("Desk", values.get(ShortcutHelper.KEY_HOST_NAME));
        assertEquals("ABCD", values.get(ShortcutHelper.KEY_APP_UUID));
        assertEquals("42", values.get(ShortcutHelper.KEY_APP_ID));
        assertEquals("Steam Big Picture", values.get(ShortcutHelper.KEY_APP_NAME));
    }

    @Test
    public void toleratesCrlfAndBlankValues() throws IOException {
        Map<String, String> values = parse("[host_name] Desk\r\n[app_uuid]\r\n\r\n[APP_NAME] Hades\r\n");
        assertEquals("Desk", values.get(ShortcutHelper.KEY_HOST_NAME));
        assertEquals("Hades", values.get(ShortcutHelper.KEY_APP_NAME));
        assertFalse(values.containsKey(ShortcutHelper.KEY_APP_UUID));
    }

    @Test
    public void rejectsFilesThatAreNotEntries() {
        String[] bad = {
                "PK\u0003\u0004 binary",
                "[app_name] No host here\n",
                "[] empty key\n",
                "",
        };
        for (String text : bad) {
            try {
                parse(text);
                fail("accepted: " + text);
            } catch (IOException expected) {
                // expected
            }
        }
    }

    @Test
    public void rejectsOversizedFiles() {
        StringBuilder sb = new StringBuilder("[host_name] Desk\n");
        while (sb.length() <= FrontendEntry.MAX_CHARS) {
            sb.append("# padding padding padding padding padding padding\n");
        }
        try {
            parse(sb.toString());
            fail("accepted oversized entry");
        } catch (IOException expected) {
            // expected
        }
    }

    @Test
    public void fileNamesAreSafeForSdCards() {
        assertEquals("Half-Life 2 Episode One", FrontendEntry.fileBaseName("Half-Life 2: Episode One"));
        assertEquals("a b", FrontendEntry.fileBaseName("a/\\b"));
        assertEquals("Game", FrontendEntry.fileBaseName("..."));
        assertEquals("Game", FrontendEntry.fileBaseName(null));
        assertEquals("Desktop", FrontendEntry.fileBaseName(".Desktop. "));
        assertTrue(FrontendEntry.fileBaseName(new String(new char[300]).replace('\0', 'x')).length() <= 120);
    }

    @Test
    public void createsEsDeFilesWhenMissing() {
        String systems = FrontendEntry.mergeEsSystems(null, "Zefiro & Co");
        assertTrue(systems.startsWith("<?xml"));
        assertTrue(systems.contains("<name>zefiro</name>"));
        assertTrue(systems.contains("<fullname>Zefiro &amp; Co</fullname>"));
        assertTrue(systems.contains("%EMULATOR_ZEFIRO%"));
        assertTrue(systems.contains("%DATA%=%ROMPROVIDER%"));
        assertTrue(systems.trim().endsWith("</systemList>"));

        String rules = FrontendEntry.mergeEsFindRules("", "dev.kylobyte.zefiro");
        assertTrue(rules.contains("<entry>dev.kylobyte.zefiro/com.limelight.ShortcutTrampoline</entry>"));
        assertTrue(rules.trim().endsWith("</ruleList>"));
    }

    @Test
    public void keepsOtherCustomSystemsAndReplacesOurs() {
        String other = "    <system>\n        <name>n64</name>\n        <path>/sdcard/n64</path>\n    </system>\n";
        String existing = "<?xml version=\"1.0\"?>\n<systemList>\n" + other + "</systemList>\n";

        String once = FrontendEntry.mergeEsSystems(existing, "Old name");
        assertTrue(once.contains(other));
        assertTrue(once.contains("Old name"));

        String twice = FrontendEntry.mergeEsSystems(once, "New name");
        assertTrue(twice.contains(other));
        assertFalse(twice.contains("Old name"));
        assertEquals(1, count(twice, "<name>zefiro</name>"));
        assertEquals(1, count(twice, "</systemList>"));
    }

    @Test
    public void keepsOtherFindRulesAndReplacesOurs() {
        String existing = "<ruleList>\n    <emulator name=\"SPECCY\">\n        <rule type=\"androidpackage\">\n" +
                "            <entry>a/b</entry>\n        </rule>\n    </emulator>\n</ruleList>";
        String once = FrontendEntry.mergeEsFindRules(existing, "dev.kylobyte.zefiro");
        String twice = FrontendEntry.mergeEsFindRules(once, "dev.kylobyte.zefiro.root");
        assertTrue(twice.contains("<emulator name=\"SPECCY\">"));
        assertTrue(twice.contains("dev.kylobyte.zefiro.root/"));
        assertEquals(1, count(twice, "name=\"ZEFIRO\""));
        assertEquals(2, count(twice, "</emulator>"));
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
