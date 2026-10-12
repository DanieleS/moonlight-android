package com.limelight.binding.video;

import com.limelight.nvstream.jni.MoonBridge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PyroWaveSupportTest {
    private static final int MEDIACODEC_FORMATS =
            MoonBridge.VIDEO_FORMAT_H264 | MoonBridge.VIDEO_FORMAT_H265 | MoonBridge.VIDEO_FORMAT_AV1_MAIN8;

    @Test
    public void vulkan13MatchesTheEncodingOfVkApiVersion() {
        // VK_MAKE_API_VERSION(0, 1, 3, 0), as FEATURE_VULKAN_HARDWARE_VERSION reports it
        assertEquals(0x00403000, PyroWaveSupport.VULKAN_1_3);
    }

    @Test
    public void candidateNeedsArm64ProcessAndVulkan13() {
        String[] arm64 = {"arm64-v8a", "armeabi-v7a", "armeabi"};
        assertTrue(PyroWaveSupport.isCandidateDevice(arm64, true, true));
        assertFalse(PyroWaveSupport.isCandidateDevice(arm64, true, false));
        // A 32-bit process on a 64-bit device loads the library without the renderer
        assertFalse(PyroWaveSupport.isCandidateDevice(arm64, false, true));
    }

    @Test
    public void otherAbisAreNeverCandidates() {
        assertFalse(PyroWaveSupport.isCandidateDevice(new String[] {"x86_64", "x86", "arm64-v8a"}, true, true));
        assertFalse(PyroWaveSupport.isCandidateDevice(new String[] {"armeabi-v7a"}, false, true));
        assertFalse(PyroWaveSupport.isCandidateDevice(new String[0], true, true));
        assertFalse(PyroWaveSupport.isCandidateDevice(null, true, true));
    }

    @Test
    public void pyroWaveIsAddedOnTopOfTheMediaCodecFormats() {
        int formats = PyroWaveSupport.addPyroWave(MEDIACODEC_FORMATS, true, true);
        assertEquals(MEDIACODEC_FORMATS, formats & ~MoonBridge.VIDEO_FORMAT_MASK_PYROWAVE);
        assertEquals(MoonBridge.VIDEO_FORMAT_PYROWAVE, formats & MoonBridge.VIDEO_FORMAT_MASK_PYROWAVE);
        assertTrue(PyroWaveSupport.isPyroWave(formats));
    }

    @Test
    public void pyroWaveIsOptInAndNeedsSupport() {
        assertEquals(MEDIACODEC_FORMATS, PyroWaveSupport.addPyroWave(MEDIACODEC_FORMATS, false, true));
        assertEquals(MEDIACODEC_FORMATS, PyroWaveSupport.addPyroWave(MEDIACODEC_FORMATS, true, false));
        assertFalse(PyroWaveSupport.isPyroWave(MEDIACODEC_FORMATS));
    }

    @Test
    public void onlyThe8BitProfileIsOffered() {
        // 0x020000/0x040000/0x080000 are 4:4:4, HDR10 and HDR10 4:4:4, which the renderer
        // cannot display yet
        int formats = PyroWaveSupport.addPyroWave(0, true, true);
        assertEquals(0x010000, formats);
        assertEquals(0, formats & MoonBridge.VIDEO_FORMAT_MASK_10BIT);
    }

    @Test
    public void negotiatedConventionalFormatsAreNotPyroWave() {
        assertFalse(PyroWaveSupport.isPyroWave(MoonBridge.VIDEO_FORMAT_H264));
        assertFalse(PyroWaveSupport.isPyroWave(MoonBridge.VIDEO_FORMAT_H265_MAIN10));
        assertFalse(PyroWaveSupport.isPyroWave(MoonBridge.VIDEO_FORMAT_AV1_MAIN10));
    }

    @Test
    public void cleanBitrateFollowsVibepolloGuidance() {
        // 1.6 bits per pixel: Themaister's reference point of about 200 Mbps at 1080p60
        assertEquals(199_066, PyroWaveSupport.cleanBitrateKbps(1920, 1080, 60));
        // 1080p at 120 Hz needs more than the 300 Mbps the bitrate slider reaches
        assertEquals(398_131, PyroWaveSupport.cleanBitrateKbps(1920, 1080, 120));
    }

    @Test
    public void cleanBitrateDoesNotOverflow() {
        assertEquals(Integer.MAX_VALUE, PyroWaveSupport.cleanBitrateKbps(65535, 65535, 1000));
    }
}
