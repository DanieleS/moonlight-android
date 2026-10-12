package com.limelight.binding.video;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;

import com.limelight.nvstream.jni.MoonBridge;

/**
 * When to offer PyroWave, the intra-only GPU wavelet codec Vibepollo can stream instead of
 * H.264/HEVC/AV1. MediaCodec cannot decode it: frames go from moonlight-core straight to a native
 * Vulkan renderer (jni/pyrowave-renderer), which only exists in the arm64-v8a library and needs a
 * Vulkan 1.3 GPU. Everywhere else the stream stays on MediaCodec.
 */
public final class PyroWaveSupport {
    /** VK_API_VERSION_1_3, as PackageManager.FEATURE_VULKAN_HARDWARE_VERSION encodes it. */
    static final int VULKAN_1_3 = (1 << 22) | (3 << 12);

    /**
     * Bits per pixel at which Vibepollo's guidance calls 8-bit 4:2:0 PyroWave visually clean
     * (Themaister's reference point: 200 Mbps at 1080p60).
     */
    static final double CLEAN_BITS_PER_PIXEL = 1.6;

    /** SCM_PYROWAVE in /serverinfo's ServerCodecModeSupport: the host can stream 8-bit 4:2:0 PyroWave. */
    static final int SCM_PYROWAVE = 0x00800000;

    // Set when the native renderer failed to start in this process: PyroWave is then no longer
    // offered, so the next stream falls back to MediaCodec instead of failing the same way.
    private static volatile boolean startFailed;

    private PyroWaveSupport() {}

    /**
     * The cheap half of the support check, from what the system declares: a 64-bit ARM process
     * and a GPU whose driver claims Vulkan 1.3. {@link MoonBridge#pyroWaveIsSupported()} does
     * the rest by creating a decoder, which costs a Vulkan device, so callers check this first.
     */
    public static boolean isCandidateDevice(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            // FEATURE_VULKAN_HARDWARE_VERSION does not exist before Android 7.0
            return false;
        }
        return isCandidateDevice(Build.SUPPORTED_ABIS, Process.is64Bit(),
                context.getPackageManager().hasSystemFeature(
                        PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, VULKAN_1_3));
    }

    static boolean isCandidateDevice(String[] supportedAbis, boolean process64Bit, boolean hasVulkan13) {
        // The process runs the device's primary ABI, and only arm64-v8a ships the renderer
        return process64Bit && hasVulkan13 &&
                supportedAbis != null && supportedAbis.length > 0 && "arm64-v8a".equals(supportedAbis[0]);
    }

    /**
     * The video formats to offer the host. PyroWave goes on top of the MediaCodec formats, which
     * stay as the fallback: moonlight-common-c picks PyroWave whenever the host advertises it with
     * the bitstream revision this client decodes, and one of the others otherwise.
     */
    public static int addPyroWave(int supportedVideoFormats, boolean enabled, boolean supported) {
        if (!enabled || !supported) {
            return supportedVideoFormats;
        }
        // 8-bit 4:2:0 only: the renderer has no 10-bit planes or HDR output yet
        return supportedVideoFormats | MoonBridge.VIDEO_FORMAT_PYROWAVE;
    }

    public static boolean isPyroWave(int videoFormat) {
        return (videoFormat & MoonBridge.VIDEO_FORMAT_MASK_PYROWAVE) != 0;
    }

    /** Whether this stream offers PyroWave and the host says it can stream it. */
    public static boolean hostOffersPyroWave(int supportedVideoFormats, int serverCodecModeSupport) {
        return isPyroWave(supportedVideoFormats) && (serverCodecModeSupport & SCM_PYROWAVE) != 0;
    }

    /**
     * The video formats to negotiate with this host. Only 8-bit PyroWave is offered, but asking
     * a PyroWave host for HDR at launch lets Vibepollo encode the session in 10 bits (its
     * per-client "10-bit SDR" option), and the PyroWave bitstream does not carry its bit depth.
     * So when the host can stream PyroWave, the 10-bit formats go, and with them the HDR request;
     * a fallback to another codec is then SDR too.
     */
    public static int formatsForHost(int supportedVideoFormats, int serverCodecModeSupport) {
        if (!hostOffersPyroWave(supportedVideoFormats, serverCodecModeSupport)) {
            return supportedVideoFormats;
        }
        return supportedVideoFormats & ~MoonBridge.VIDEO_FORMAT_MASK_10BIT;
    }

    /** The bitrate at which PyroWave looks clean at this resolution and frame rate, in Kbps. */
    public static int cleanBitrateKbps(int width, int height, float fps) {
        double kbps = (double) width * height * fps * CLEAN_BITS_PER_PIXEL / 1000.0;
        return (int) Math.min(Integer.MAX_VALUE, Math.round(kbps));
    }

    /**
     * The bitrate a PyroWave stream asks for, in Kbps: the PyroWave bitrate setting, or the clean
     * bitrate for this resolution and frame rate when it is 0 (automatic). The regular bitrate
     * stays for the codecs PyroWave falls back to.
     */
    public static int bitrateKbps(int preferenceKbps, int width, int height, float fps) {
        return preferenceKbps > 0 ? preferenceKbps : cleanBitrateKbps(width, height, fps);
    }

    /** Called when the native renderer could not start: stop offering PyroWave in this process. */
    public static void recordStartFailure() {
        startFailed = true;
    }

    public static boolean hasStartFailed() {
        return startFailed;
    }

    /**
     * Warms the cached Vulkan probe on a background thread, so that starting a stream with
     * PyroWave enabled does not create a Vulkan device on the UI thread.
     */
    public static void probeInBackground(Context context) {
        if (!isCandidateDevice(context)) {
            return;
        }
        Thread probe = new Thread(MoonBridge::pyroWaveIsSupported, "PyroWave probe");
        probe.setPriority(Thread.MIN_PRIORITY);
        probe.start();
    }
}
