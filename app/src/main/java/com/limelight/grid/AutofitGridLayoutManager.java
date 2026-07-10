package com.limelight.grid;

import android.content.Context;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * A GridLayoutManager that picks its own column count from the available width, the way
 * GridView's numColumns="auto_fit" did. The span is recomputed on every layout pass, so
 * the grid reflows across rotations and different panels without being told a fixed count.
 */
public class AutofitGridLayoutManager extends GridLayoutManager {
    private final int columnWidthPx;

    public AutofitGridLayoutManager(Context context, int columnWidthPx) {
        super(context, 1);
        this.columnWidthPx = Math.max(1, columnWidthPx);
    }

    @Override
    public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
        int usable = getWidth() - getPaddingLeft() - getPaddingRight();
        if (usable > 0) {
            setSpanCount(Math.max(1, usable / columnWidthPx));
        }
        super.onLayoutChildren(recycler, state);
    }
}
