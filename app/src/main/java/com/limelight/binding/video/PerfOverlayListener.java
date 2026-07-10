package com.limelight.binding.video;

public interface PerfOverlayListener {
    /**
     * @param text  the stats preformatted for the overlay drawn over the stream
     * @param stats the same measurements unformatted, for surfaces that lay them out themselves
     */
    void onPerfUpdate(final String text, final PerfStats stats);
}
