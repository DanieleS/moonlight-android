package com.limelight.binding.video;

/**
 * One second's worth of streaming statistics, as measured by
 * {@link MediaCodecDecoderRenderer}.
 *
 * The perf overlay drawn over the stream needs no more than a preformatted string, but the
 * companion surface lays the numbers out itself, so they travel unformatted.
 */
public class PerfStats {

    /** Bandwidth is unavailable on platforms where TrafficStats is unsupported, and on the first sample. */
    public static final float BANDWIDTH_UNAVAILABLE = -1;

    public final int width;
    public final int height;
    public final String decoder;

    public final float totalFps;
    public final float receivedFps;
    public final float renderedFps;

    /** Percentage of frames dropped by the network, 0-100. */
    public final float networkDropsPercent;
    /** Kilobytes per second across both directions, or {@link #BANDWIDTH_UNAVAILABLE}. */
    public final float bandwidthKbps;

    public final int networkLatencyMs;
    public final int networkLatencyVarianceMs;
    public final float decodeTimeMs;

    /** Host processing latencies are only reported by some hosts; false means the three fields are unset. */
    public final boolean hasHostProcessingLatency;
    public final float minHostProcessingLatencyMs;
    public final float maxHostProcessingLatencyMs;
    public final float avgHostProcessingLatencyMs;

    public PerfStats(int width, int height, String decoder,
                     float totalFps, float receivedFps, float renderedFps,
                     float networkDropsPercent, float bandwidthKbps,
                     int networkLatencyMs, int networkLatencyVarianceMs, float decodeTimeMs,
                     boolean hasHostProcessingLatency,
                     float minHostProcessingLatencyMs,
                     float maxHostProcessingLatencyMs,
                     float avgHostProcessingLatencyMs) {
        this.width = width;
        this.height = height;
        this.decoder = decoder;
        this.totalFps = totalFps;
        this.receivedFps = receivedFps;
        this.renderedFps = renderedFps;
        this.networkDropsPercent = networkDropsPercent;
        this.bandwidthKbps = bandwidthKbps;
        this.networkLatencyMs = networkLatencyMs;
        this.networkLatencyVarianceMs = networkLatencyVarianceMs;
        this.decodeTimeMs = decodeTimeMs;
        this.hasHostProcessingLatency = hasHostProcessingLatency;
        this.minHostProcessingLatencyMs = minHostProcessingLatencyMs;
        this.maxHostProcessingLatencyMs = maxHostProcessingLatencyMs;
        this.avgHostProcessingLatencyMs = avgHostProcessingLatencyMs;
    }

    public boolean hasBandwidth() {
        return bandwidthKbps != BANDWIDTH_UNAVAILABLE;
    }
}
