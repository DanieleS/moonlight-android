package com.limelight.ui;

import android.content.Context;
import android.util.AttributeSet;

import androidx.recyclerview.widget.RecyclerView;

/**
 * A RecyclerView that wraps its rows until it would grow past {@link #setMaxHeightPx}, then caps
 * there and scrolls — the same bargain {@link MaxHeightScrollView} strikes for a menu's column,
 * for a grid that cannot be handed to a scroll view.
 */
public class MaxHeightRecyclerView extends RecyclerView {

    private int maxHeightPx = 0;

    public MaxHeightRecyclerView(Context context) {
        super(context);
    }

    public MaxHeightRecyclerView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MaxHeightRecyclerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setMaxHeightPx(int maxHeightPx) {
        this.maxHeightPx = maxHeightPx;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        if (maxHeightPx > 0) {
            heightSpec = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST);
        }
        super.onMeasure(widthSpec, heightSpec);
    }
}
