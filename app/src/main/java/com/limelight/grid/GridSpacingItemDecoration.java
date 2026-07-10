package com.limelight.grid;

import android.graphics.Rect;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

/**
 * Even spacing between grid tiles. Half the gap is placed on each side of every cell, so
 * neighbours are one full gap apart; pair it with the same half-gap as padding on the
 * RecyclerView and the outer edge matches the inner ones. This is what GridView's
 * horizontalSpacing / verticalSpacing did.
 */
public class GridSpacingItemDecoration extends RecyclerView.ItemDecoration {
    private final int half;

    public GridSpacingItemDecoration(int spacingPx) {
        this.half = spacingPx / 2;
    }

    @Override
    public void getItemOffsets(Rect outRect, View view, RecyclerView parent,
                               RecyclerView.State state) {
        outRect.set(half, half, half, half);
    }
}
