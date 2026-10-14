package com.limelight.binding.video;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

public class DecoderParkingTest {
    private static final String CODEC = "c2.qti.hevc.decoder.low_latency";
    private static final int HEVC = 0x0100;

    private DecoderParking parking;
    private MediaCodec codec;
    private MediaFormat format;
    private Surface surface;
    private final byte[] hdr = {1, 2, 3};

    @Before
    public void setUp() {
        parking = new DecoderParking();
        codec = mock(MediaCodec.class);
        format = mock(MediaFormat.class);
        surface = mock(Surface.class);
    }

    private boolean park(MediaCodec c) {
        return parking.park(c, CODEC, 1920, 1080, HEVC, surface, format, hdr);
    }

    @Test
    public void nothingIsParkedOutsideAReconnect() {
        // Not opened yet: the renderer keeps its codec and releases it itself
        assertFalse(park(codec));
        verify(codec, never()).release();
        assertNull(parking.take(CODEC, 1920, 1080, HEVC, surface));
    }

    @Test
    public void aMatchingStreamTakesTheCodecBackOnce() {
        parking.open();
        assertTrue(park(codec));

        DecoderParking.Taken taken = parking.take(CODEC, 1920, 1080, HEVC, surface);
        assertNotNull(taken);
        assertSame(codec, taken.codec);
        assertSame(format, taken.configuredFormat);
        assertArrayEquals(hdr, taken.hdrMetadata);
        verify(codec, never()).release();

        // It is handed out once; nothing is left behind to release
        assertNull(parking.take(CODEC, 1920, 1080, HEVC, surface));
        parking.close("test");
        verify(codec, never()).release();
    }

    @Test
    public void aCodecThatDoesNotFitIsReleased() {
        parking.open();
        assertTrue(park(codec));
        assertNull(parking.take(CODEC, 1280, 720, HEVC, surface));
        verify(codec).release();
    }

    @Test
    public void aDifferentDecoderFormatOrSurfaceDoesNotFitEither() {
        parking.open();

        assertTrue(park(codec));
        assertNull(parking.take("c2.qti.avc.decoder", 1920, 1080, HEVC, surface));
        verify(codec).release();

        MediaCodec second = mock(MediaCodec.class);
        assertTrue(park(second));
        assertNull(parking.take(CODEC, 1920, 1080, 0x0001, surface));
        verify(second).release();

        MediaCodec third = mock(MediaCodec.class);
        assertTrue(park(third));
        assertNull(parking.take(CODEC, 1920, 1080, HEVC, mock(Surface.class)));
        verify(third).release();
    }

    @Test
    public void closingReleasesWhatIsParkedAndRefusesMore() {
        parking.open();
        assertTrue(park(codec));
        parking.close("the reconnect ended");
        verify(codec).release();

        // A renderer finishing an abandoned attempt keeps its codec and releases it itself
        MediaCodec late = mock(MediaCodec.class);
        assertFalse(park(late));
        verify(late, never()).release();
    }

    @Test
    public void parkingAnotherCodecReleasesTheFirst() {
        parking.open();
        assertTrue(park(codec));
        MediaCodec other = mock(MediaCodec.class);
        assertTrue(park(other));
        verify(codec).release();

        DecoderParking.Taken taken = parking.take(CODEC, 1920, 1080, HEVC, surface);
        assertNotNull(taken);
        assertSame(other, taken.codec);
    }
}
