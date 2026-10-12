package com.limelight.binding.video;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;

import com.limelight.LimeLog;

/**
 * Keeps a video decoder alive between two connections while the stream reconnects.
 *
 * Moonlight ties a MediaCodec to one connection: setup() creates it, cleanup() releases it. A
 * reconnect brings the same stream back with the same parameters, and a new codec would cost a
 * black screen (connecting a new codec to the surface blanks it until its first frame) plus the
 * setup time. So while Game reconnects, the renderer's cleanup() flushes its codec and parks it
 * here, the surface keeps showing the last frame, and the next renderer's setup() takes the codec
 * back when the decoder, size, format and surface all match. A codec that does not fit is
 * released and the renderer builds its own.
 *
 * One codec at most. Once closed, nothing can be parked: a renderer still finishing an abandoned
 * attempt then releases its codec the usual way.
 */
public class DecoderParking {
    // A codec handed back to a renderer, with the format it was configured with
    static final class Taken {
        final MediaCodec codec;
        final MediaFormat configuredFormat;

        Taken(MediaCodec codec, MediaFormat configuredFormat) {
            this.codec = codec;
            this.configuredFormat = configuredFormat;
        }
    }

    private MediaCodec codec;
    private String codecName;
    private int width, height, videoFormat;
    private Surface surface;
    private MediaFormat configuredFormat;
    private boolean open;

    // A reconnect begins: renderers may park their codec from now on
    public synchronized void open() {
        open = true;
    }

    // The reconnect is over: release whatever is parked and refuse to park anything more
    public void close(String why) {
        MediaCodec toRelease;
        synchronized (this) {
            open = false;
            toRelease = codec;
            clearLocked();
        }
        release(toRelease, why);
    }

    // Returns false when the parking is closed, in which case the caller still owns the codec
    synchronized boolean park(MediaCodec codec, String codecName, int width, int height, int videoFormat,
                              Surface surface, MediaFormat configuredFormat) {
        if (!open) {
            return false;
        }
        if (this.codec != null && this.codec != codec) {
            // Only one stream runs at a time, so this should never happen, but never leak a codec
            release(this.codec, "replaced by another");
        }
        this.codec = codec;
        this.codecName = codecName;
        this.width = width;
        this.height = height;
        this.videoFormat = videoFormat;
        this.surface = surface;
        this.configuredFormat = configuredFormat;
        LimeLog.info("Decoder kept for reconnect: " + codecName + " " + width + "x" + height);
        return true;
    }

    // The parked codec if it fits the new stream, or null. A codec that does not fit is released.
    Taken take(String codecName, int width, int height, int videoFormat, Surface surface) {
        MediaCodec toRelease;
        synchronized (this) {
            if (codec == null) {
                return null;
            }
            toRelease = codec;
            if (codecName.equals(this.codecName) && width == this.width && height == this.height &&
                    videoFormat == this.videoFormat && surface == this.surface) {
                LimeLog.info("Decoder reused after reconnect: " + codecName + " " + width + "x" + height);
                Taken taken = new Taken(codec, configuredFormat);
                clearLocked();
                return taken;
            }
            clearLocked();
        }
        release(toRelease, "the new stream differs");
        return null;
    }

    private void clearLocked() {
        codec = null;
        codecName = null;
        surface = null;
        configuredFormat = null;
    }

    private static void release(MediaCodec codec, String why) {
        if (codec == null) {
            return;
        }
        try {
            codec.release();
        } catch (RuntimeException e) {
            // Already released or broken: nothing left to give back
        }
        LimeLog.info("Kept decoder released: " + why);
    }
}
