package com.limelight.grid;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.R;

import java.util.ArrayList;

public abstract class GenericGridAdapter<T> extends RecyclerView.Adapter<GenericGridAdapter.GridItemHolder> {
    /** A tap on a tile, reported with the tile's view and adapter position. */
    public interface OnItemClickListener {
        void onClick(View view, int position);
    }

    protected final Context context;
    private int layoutId;
    final ArrayList<T> itemList = new ArrayList<>();
    private final LayoutInflater inflater;
    private OnItemClickListener clickListener;

    GenericGridAdapter(Context context, int layoutId) {
        this.context = context;
        this.layoutId = layoutId;
        this.inflater = LayoutInflater.from(context);
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.clickListener = listener;
    }

    void setLayoutId(int layoutId) {
        if (layoutId != this.layoutId) {
            this.layoutId = layoutId;

            // The layout id doubles as the view type, so changing it here keeps the pool
            // from handing an old-layout holder to a new-layout position.
            notifyDataSetChanged();
        }
    }

    public void clear() {
        itemList.clear();
    }

    // Kept so the activities can read the list without reaching for RecyclerView's own
    // getItemCount / holder lookups, as they did against the old BaseAdapter.
    public int getCount() {
        return itemList.size();
    }

    public Object getItem(int i) {
        return itemList.get(i);
    }

    @Override
    public int getItemCount() {
        return itemList.size();
    }

    @Override
    public int getItemViewType(int position) {
        return layoutId;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    public abstract void populateView(View parentView, ImageView imgView, ProgressBar prgView, TextView txtView, ImageView overlayView, T obj);

    @NonNull
    @Override
    public GridItemHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // viewType is the layout id; see getItemViewType.
        return new GridItemHolder(inflater.inflate(viewType, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull GridItemHolder holder, int position) {
        populateView(holder.itemView, holder.image, holder.spinner, holder.text, holder.overlay,
                itemList.get(position));

        holder.itemView.setOnClickListener(v -> {
            if (clickListener == null) {
                return;
            }
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) {
                clickListener.onClick(v, pos);
            }
        });
    }

    static class GridItemHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        final ImageView overlay;
        final TextView text;
        final ProgressBar spinner;

        GridItemHolder(View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.grid_image);
            overlay = itemView.findViewById(R.id.grid_overlay);
            text = itemView.findViewById(R.id.grid_text);
            // Only the PC tile carries a spinner; null on the app tiles, which never read it.
            spinner = itemView.findViewById(R.id.grid_spinner);

            // Focusable for the d-pad, long-clickable so the framework raises the context
            // menu through ContextMenuRecyclerView, exactly as an AbsListView child was.
            itemView.setFocusable(true);
            itemView.setClickable(true);
            itemView.setLongClickable(true);
        }
    }
}
