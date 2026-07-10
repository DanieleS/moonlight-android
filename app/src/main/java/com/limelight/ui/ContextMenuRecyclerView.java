package com.limelight.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.ContextMenu;
import android.view.View;
import android.widget.AdapterView;

import androidx.recyclerview.widget.RecyclerView;

/**
 * A RecyclerView that hands the framework an {@link AdapterView.AdapterContextMenuInfo}
 * when a child is long-pressed, the way an AbsListView does.
 *
 * The library screens grew up on GridView, so their onCreateContextMenu and
 * onContextItemSelected read the long-pressed position out of an AdapterContextMenuInfo.
 * RecyclerView carries no such thing, so we rebuild it here from the child's adapter
 * position, and those two methods stay exactly as they were.
 */
public class ContextMenuRecyclerView extends RecyclerView {
    private ContextMenu.ContextMenuInfo contextMenuInfo;

    public ContextMenuRecyclerView(Context context) {
        super(context);
    }

    public ContextMenuRecyclerView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ContextMenuRecyclerView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected ContextMenu.ContextMenuInfo getContextMenuInfo() {
        return contextMenuInfo;
    }

    private boolean recordChild(View child) {
        int position = getChildAdapterPosition(child);
        if (position < 0 || getAdapter() == null) {
            return false;
        }
        contextMenuInfo = new AdapterView.AdapterContextMenuInfo(
                child, position, getAdapter().getItemId(position));
        return true;
    }

    @Override
    public boolean showContextMenuForChild(View originalView) {
        return recordChild(originalView) && super.showContextMenuForChild(originalView);
    }

    @Override
    public boolean showContextMenuForChild(View originalView, float x, float y) {
        return recordChild(originalView) && super.showContextMenuForChild(originalView, x, y);
    }
}
