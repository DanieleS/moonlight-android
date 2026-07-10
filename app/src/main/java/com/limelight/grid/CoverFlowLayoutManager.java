package com.limelight.grid;

import android.content.Context;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * A horizontal layout manager that grows the cover nearest the centre and shrinks the rest,
 * the way a console library's carousel does. The transform is purely visual — scale, fade
 * and draw order — so the layout positions, and therefore the d-pad and the context menu,
 * behave like any other horizontal list.
 *
 * The tiles are the same tiles the grid uses; only their scale changes here.
 */
public class CoverFlowLayoutManager extends LinearLayoutManager {
    private static final float CENTER_SCALE = 1.2f;
    private static final float SIDE_SCALE = 0.72f;
    private static final float SIDE_FADE = 0.55f;

    public CoverFlowLayoutManager(Context context) {
        super(context, HORIZONTAL, false);
    }

    @Override
    public boolean supportsPredictiveItemAnimations() {
        return false;
    }

    @Override
    public int scrollHorizontallyBy(int dx, RecyclerView.Recycler recycler, RecyclerView.State state) {
        int scrolled = super.scrollHorizontallyBy(dx, recycler, state);
        transformChildren();
        return scrolled;
    }

    @Override
    public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
        super.onLayoutChildren(recycler, state);
        transformChildren();
    }

    private void transformChildren() {
        float mid = getWidth() / 2f;
        float falloff = getWidth() / 2f;
        if (falloff <= 0) {
            return;
        }
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            float childMid = (getDecoratedLeft(child) + getDecoratedRight(child)) / 2f;
            float t = Math.min(1f, Math.abs(mid - childMid) / falloff);   // 0 at centre, 1 at the edge
            float scale = CENTER_SCALE + (SIDE_SCALE - CENTER_SCALE) * t;
            child.setScaleX(scale);
            child.setScaleY(scale);
            child.setAlpha(1f - SIDE_FADE * t);
            child.setTranslationZ(-t);                                     // the centre cover draws on top
        }
    }

    @Override
    public boolean onRequestChildFocus(RecyclerView parent, RecyclerView.State state,
                                       View child, View focused) {
        // Bring the focused cover to the centre rather than merely scrolling it into view.
        int childMid = (getDecoratedLeft(child) + getDecoratedRight(child)) / 2;
        parent.smoothScrollBy(childMid - getWidth() / 2, 0);
        return true;
    }
}
