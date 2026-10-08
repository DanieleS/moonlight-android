package com.limelight.utils;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CaptureToolTest {
    private static final short WIN = 0x5B, ALT = 0xA4, PRTSCN = 0x2C;

    @Test
    public void gameBarShortcuts() {
        CaptureTool t = CaptureTool.GAME_BAR;
        assertArrayEquals(new short[]{WIN, ALT, PRTSCN}, t.keysFor(CaptureTool.Action.SCREENSHOT));
        assertArrayEquals(new short[]{WIN, ALT, 'R'}, t.keysFor(CaptureTool.Action.TOGGLE_RECORDING));
        assertArrayEquals(new short[]{WIN, ALT, 'G'}, t.keysFor(CaptureTool.Action.SAVE_REPLAY));
    }

    @Test
    public void nvidiaShortcuts() {
        CaptureTool t = CaptureTool.NVIDIA;
        assertArrayEquals(new short[]{ALT, 0x70}, t.keysFor(CaptureTool.Action.SCREENSHOT));
        assertArrayEquals(new short[]{ALT, 0x78}, t.keysFor(CaptureTool.Action.TOGGLE_RECORDING));
        assertArrayEquals(new short[]{ALT, 0x79}, t.keysFor(CaptureTool.Action.SAVE_REPLAY));
    }

    @Test
    public void offSendsNothing() {
        assertFalse(CaptureTool.OFF.isEnabled());
        assertNull(CaptureTool.OFF.keysFor(CaptureTool.Action.SCREENSHOT));
        assertTrue(CaptureTool.GAME_BAR.isEnabled());
    }

    @Test
    public void preferenceValuesRoundTrip() {
        for (CaptureTool t : CaptureTool.values()) {
            assertEquals(t, CaptureTool.fromPreference(t.getPreferenceValue()));
        }
        assertEquals(CaptureTool.GAME_BAR, CaptureTool.fromPreference(null));
        assertEquals(CaptureTool.GAME_BAR, CaptureTool.fromPreference("obs"));
    }
}
