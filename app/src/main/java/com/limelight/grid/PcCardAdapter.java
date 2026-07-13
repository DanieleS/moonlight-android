package com.limelight.grid;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.limelight.PcView;
import com.limelight.R;
import com.limelight.grid.assets.CachedAppAssetLoader;
import com.limelight.grid.assets.DiskAssetLoader;
import com.limelight.grid.assets.ScaledBitmap;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.PairingManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The PCs, one card each.
 *
 * A card carries what the old icon left the user to guess: the state of the host, whether it
 * is paired, the address it was reached on, and the game it is running. When it is running
 * one, the card borrows that game's art — as a cover in the leading slot, and bled across the
 * card behind the text.
 *
 * The art is only ever read from the disk cache the library already fills, and only off the
 * main thread. A PC whose library has never been opened simply shows the host glyph.
 */
public class PcCardAdapter extends RecyclerView.Adapter<PcCardAdapter.PcCardHolder> {
    /** A tap on a card, reported with the card's view and adapter position. */
    public interface OnItemClickListener {
        void onClick(View view, int position);
    }

    /** A tap on the card's action, which is a different thing from a tap on the card. */
    public interface OnActionClickListener {
        void onClick(PcView.ComputerObject computer, int action);
    }

    public static final int ACTION_NONE = 0;
    public static final int ACTION_WAKE = 1;
    public static final int ACTION_PAIR = 2;
    public static final int ACTION_RESUME = 3;

    private final Context context;
    private final LayoutInflater inflater;
    private final ArrayList<PcView.ComputerObject> itemList = new ArrayList<>();

    private final DiskAssetLoader assetLoader;
    private final ExecutorService coverExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // A handful of hosts, one cover each. This is not the library.
    private final LruCache<String, Bitmap> coverCache = new LruCache<>(8);

    private OnItemClickListener clickListener;
    private OnActionClickListener actionListener;
    private boolean itemsFocusableInTouchMode;

    public PcCardAdapter(Context context) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        this.assetLoader = new DiskAssetLoader(context);
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.clickListener = listener;
    }

    /**
     * Make cards hold focus in touch mode, as the library's tiles do: with a gamepad present, a
     * screen entered by touch must still have a card focused, or the first d-pad press is spent
     * reclaiming focus rather than moving. Touch-only devices leave it off so a tap does not
     * strand a ring on a card.
     */
    public void setItemsFocusableInTouchMode(boolean focusable) {
        if (this.itemsFocusableInTouchMode != focusable) {
            this.itemsFocusableInTouchMode = focusable;
            notifyDataSetChanged();
        }
    }

    public void setOnActionClickListener(OnActionClickListener listener) {
        this.actionListener = listener;
    }

    public void addComputer(PcView.ComputerObject computer) {
        itemList.add(computer);
        Collections.sort(itemList, new Comparator<PcView.ComputerObject>() {
            @Override
            public int compare(PcView.ComputerObject lhs, PcView.ComputerObject rhs) {
                return lhs.details.name.toLowerCase().compareTo(rhs.details.name.toLowerCase());
            }
        });
    }

    public boolean removeComputer(PcView.ComputerObject computer) {
        return itemList.remove(computer);
    }

    // Kept so PcView can read the list as it did against the old BaseAdapter.
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
    public long getItemId(int position) {
        return position;
    }

    @NonNull
    @Override
    public PcCardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new PcCardHolder(inflater.inflate(R.layout.pc_card_item, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull PcCardHolder holder, int position) {
        final PcView.ComputerObject computer = itemList.get(position);
        final ComputerDetails details = computer.details;

        holder.name.setText(details.name);

        final boolean online = details.state == ComputerDetails.State.ONLINE;
        final boolean paired = details.pairState == PairingManager.PairState.PAIRED;
        final boolean checking = details.state == ComputerDetails.State.UNKNOWN;
        final boolean inGame = online && paired && details.runningGameId != 0;

        // The state, as a dot and a line: online and paired is the only state worth the signal.
        final int dotColor;
        final StringBuilder status = new StringBuilder();
        if (checking) {
            dotColor = R.color.content_faint;
            status.append(context.getString(R.string.pcview_status_checking));
        }
        else if (!online) {
            dotColor = R.color.content_faint;
            status.append(context.getString(R.string.pcview_status_offline));
        }
        else if (!paired) {
            dotColor = R.color.content_muted;
            status.append(context.getString(R.string.pcview_status_unpaired));
        }
        else {
            dotColor = R.color.signal;
            status.append(context.getString(R.string.pcview_status_online));
        }

        if (online && details.activeAddress != null) {
            status.append(" · ").append(details.activeAddress.address);
        }

        holder.dot.setBackgroundTintList(
                ColorStateList.valueOf(ContextCompat.getColor(context, dotColor)));
        holder.status.setText(status);

        holder.spinner.setVisibility(checking ? View.VISIBLE : View.GONE);
        holder.glyph.setVisibility(checking || inGame ? View.GONE : View.VISIBLE);
        holder.glyph.setAlpha(online ? 1.0f : 0.4f);

        if (inGame) {
            String running = computer.runningAppName;
            holder.running.setText(running != null
                    ? context.getString(R.string.pcview_status_running, running)
                    : context.getString(R.string.pcview_status_running_unnamed));
            holder.running.setVisibility(View.VISIBLE);
            bindArt(holder, computer);
        }
        else {
            holder.running.setVisibility(View.GONE);
            clearArt(holder);
        }

        // The one thing this host is waiting for. Everything else stays in the context menu.
        final int action;
        if (inGame) {
            action = ACTION_RESUME;
        }
        else if (online && !paired) {
            action = ACTION_PAIR;
        }
        else if (!online && !checking && details.macAddress != null) {
            action = ACTION_WAKE;
        }
        else {
            action = ACTION_NONE;
        }

        if (action == ACTION_NONE) {
            holder.action.setVisibility(View.GONE);
        }
        else {
            holder.action.setVisibility(View.VISIBLE);
            holder.action.setText(actionLabel(action));
            holder.action.setOnClickListener(v -> {
                if (actionListener != null) {
                    actionListener.onClick(computer, action);
                }
            });
        }

        holder.itemView.setFocusableInTouchMode(itemsFocusableInTouchMode);

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

    private int actionLabel(int action) {
        switch (action) {
            case ACTION_RESUME:
                return R.string.pcview_action_resume;
            case ACTION_PAIR:
                return R.string.pcview_action_pair;
            case ACTION_WAKE:
                return R.string.pcview_action_wake;
            default:
                return R.string.pcview_action_resume;
        }
    }

    private void clearArt(PcCardHolder holder) {
        holder.coverKey = null;
        holder.cover.setVisibility(View.GONE);
        holder.ambient.setVisibility(View.GONE);
        holder.ambientScrim.setVisibility(View.GONE);
    }

    /**
     * The art of the game the host is in: its cover in the leading slot, and its hero image bled
     * across the card behind the text.
     *
     * The cover comes from the disk cache the library fills; the hero image is fetched from the
     * host by PcView, and is missing for a game that has none, on a host that does not serve
     * them, and for the moment before it arrives. When it is missing the cover stands in for it,
     * which is what this screen showed before there were hero images at all.
     */
    private void bindArt(PcCardHolder holder, PcView.ComputerObject computer) {
        final ComputerDetails details = computer.details;
        final String key = details.uuid + ":" + details.runningGameId;
        holder.coverKey = key;

        final Bitmap cover = coverCache.get(key);
        applyArt(holder, cover, computer.runningBackground);

        if (cover != null) {
            return;
        }

        final int appId = details.runningGameId;
        coverExecutor.execute(() -> {
            ScaledBitmap loaded = assetLoader.loadBitmapFromCache(
                    new CachedAppAssetLoader.LoaderTuple(details, new NvApp(null, null, appId, false)), 1);
            if (loaded == null || loaded.bitmap == null) {
                return;
            }

            final Bitmap bitmap = loaded.bitmap;
            mainHandler.post(() -> {
                coverCache.put(key, bitmap);

                // The card may have been recycled onto another host while we were reading.
                if (key.equals(holder.coverKey)) {
                    applyArt(holder, bitmap, computer.runningBackground);
                }
            });
        });
    }

    private void applyArt(PcCardHolder holder, Bitmap cover, Bitmap background) {
        if (cover != null) {
            holder.cover.setImageBitmap(cover);
            holder.cover.setVisibility(View.VISIBLE);
            holder.glyph.setVisibility(View.GONE);
        }
        else {
            // A game we have no cover for still gets the host glyph rather than an empty slot.
            holder.cover.setVisibility(View.GONE);
            holder.glyph.setVisibility(View.VISIBLE);
        }

        Bitmap ambient = background != null ? background : cover;
        if (ambient != null) {
            holder.ambient.setImageBitmap(ambient);
            holder.ambient.setVisibility(View.VISIBLE);
            holder.ambientScrim.setVisibility(View.VISIBLE);
        }
        else {
            holder.ambient.setVisibility(View.GONE);
            holder.ambientScrim.setVisibility(View.GONE);
        }
    }

    static class PcCardHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView status;
        final TextView running;
        final View dot;
        final ImageView glyph;
        final ImageView cover;
        final ImageView ambient;
        final View ambientScrim;
        final ProgressBar spinner;
        final MaterialButton action;

        /** The host and game this card's art was last requested for; see bindCover. */
        String coverKey;

        PcCardHolder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.pc_card_name);
            status = itemView.findViewById(R.id.pc_card_status);
            running = itemView.findViewById(R.id.pc_card_running);
            dot = itemView.findViewById(R.id.pc_card_dot);
            glyph = itemView.findViewById(R.id.pc_card_glyph);
            cover = itemView.findViewById(R.id.pc_card_cover);
            ambient = itemView.findViewById(R.id.pc_card_ambient);
            ambientScrim = itemView.findViewById(R.id.pc_card_ambient_scrim);
            spinner = itemView.findViewById(R.id.pc_card_spinner);
            action = itemView.findViewById(R.id.pc_card_action);

            // The art bleeds to the card's edge, so the card has to clip to its own corners.
            itemView.setClipToOutline(true);

            // Focusable for the d-pad, long-clickable so the framework raises the context menu
            // through ContextMenuRecyclerView, exactly as an AbsListView child was.
            itemView.setFocusable(true);
            itemView.setClickable(true);
            itemView.setLongClickable(true);
        }
    }
}
