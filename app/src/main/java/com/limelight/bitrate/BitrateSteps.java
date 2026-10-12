package com.limelight.bitrate;

import java.util.Locale;
import java.util.TreeSet;

/**
 * The arithmetic around a live bitrate change: what to send the host for a bitrate as the settings
 * mean it, which bitrates the in-game menu offers, and how to print one.
 */
public final class BitrateSteps {

    /**
     * The bitrates the in-game menu offers, in kbps, alongside the one chosen in the settings and
     * the one the stream is at.
     */
    static final int[] LADDER_KBPS = {
            5000, 10000, 15000, 20000, 30000, 40000, 50000, 60000, 80000, 100000, 150000
    };

    /**
     * The host's default share of the stream given to forward error correction. Vibepollo, like
     * Sunshine, takes it out of the bitrate a stream starts with, but not out of one changed live.
     */
    static final int HOST_FEC_PERCENT = 20;

    /**
     * moonlight-common-c asks for high-quality audio, stereo or surround, from this stream bitrate
     * up (RtspConnection.c), unless the device's Opus decoder is slow, which a phone-class SoC's
     * is not.
     */
    static final int HIGH_QUALITY_AUDIO_THRESHOLD_KBPS = 15000;

    private BitrateSteps() {
    }

    /**
     * The encoder bitrate to ask the host for, given a bitrate as the settings mean it: the whole
     * stream, video, error correction, audio and overhead together.
     *
     * <p>When a stream starts, the host turns that budget into an encoder bitrate itself: less the
     * share kept for error correction, less the audio, less 500 kbps of packet and control overhead
     * (rtsp.cpp). Its live {@code /bitrate} endpoint skips all that and hands the number straight to
     * the encoder. Sending the settings' number as it is would run the encoder a fifth or so above
     * where the stream started, and a step "down" from the start would be a step up. So the same
     * sum is done here, with the host's default error-correction share, which is the one this end
     * can know.
     *
     * @param audioChannels the stream's audio channel count
     * @param startKbps the bitrate the stream started with, which decided its audio quality
     */
    public static int toEncoderKbps(int wireKbps, int audioChannels, int startKbps) {
        // Written as the host writes it, so the truncation lands the same way.
        long kbps = (long) (wireKbps / (100f / (100 - HOST_FEC_PERCENT)));
        boolean highQualityAudio = startKbps >= HIGH_QUALITY_AUDIO_THRESHOLD_KBPS;
        long audioKbps = (long) (highQualityAudio ? 256 : 96) * audioChannels;
        kbps -= Math.min(audioKbps, kbps / 5);
        kbps -= Math.min(500, kbps / 10);
        return (int) Math.max(1, kbps);
    }

    /** Every bitrate the in-game menu offers, lowest first, without repeats. */
    public static int[] menuChoices(int configuredKbps, int currentKbps) {
        TreeSet<Integer> choices = new TreeSet<>();
        for (int kbps : LADDER_KBPS) {
            choices.add(kbps);
        }
        if (configuredKbps > 0) {
            choices.add(configuredKbps);
        }
        if (currentKbps > 0) {
            choices.add(currentKbps);
        }
        int[] result = new int[choices.size()];
        int i = 0;
        for (int kbps : choices) {
            result[i++] = kbps;
        }
        return result;
    }

    /** A bitrate in Mbps for people: "40", or "37.5" when it isn't whole. */
    public static String formatMbps(int kbps, Locale locale) {
        if (kbps % 1000 == 0) {
            return String.valueOf(kbps / 1000);
        }
        return String.format(locale, "%.1f", kbps / 1000f);
    }
}
