package com.limelight.preferences;

import com.limelight.nvstream.jni.MoonBridge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class VideoFecPreferenceTest {
    @Test
    public void offLeavesTheHostInCharge() {
        assertEquals(MoonBridge.FEC_DISABLED, PreferenceConfiguration.videoFecMode("off"));
    }

    @Test
    public void autoAsksTheHostToAdapt() {
        assertEquals(MoonBridge.FEC_AUTOMATIC, PreferenceConfiguration.videoFecMode("auto"));
    }

    @Test
    public void percentagesAreFixed() {
        assertEquals(MoonBridge.FEC_FIXED, PreferenceConfiguration.videoFecMode("20"));
        assertEquals(20, PreferenceConfiguration.videoFecPercentage("20"));
        assertEquals(MoonBridge.FEC_FIXED, PreferenceConfiguration.videoFecMode("0"));
        assertEquals(100, PreferenceConfiguration.videoFecPercentage("100"));
    }

    @Test
    public void anythingElseIsOff() {
        assertEquals(MoonBridge.FEC_DISABLED, PreferenceConfiguration.videoFecMode("101"));
        assertEquals(MoonBridge.FEC_DISABLED, PreferenceConfiguration.videoFecMode("-1"));
        assertEquals(MoonBridge.FEC_DISABLED, PreferenceConfiguration.videoFecMode(""));
        assertEquals(MoonBridge.FEC_DISABLED, PreferenceConfiguration.videoFecMode(null));
        assertEquals(-1, PreferenceConfiguration.videoFecPercentage("auto"));
    }
}
