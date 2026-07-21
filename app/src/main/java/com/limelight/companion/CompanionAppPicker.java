package com.limelight.companion;

import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.R;
import com.limelight.grid.AutofitGridLayoutManager;
import com.limelight.grid.GridSpacingItemDecoration;
import com.limelight.ui.MaxHeightRecyclerView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The grid of installed apps a game's companion is chosen from.
 *
 * A grid rather than a list because an app is recognised by its icon long before its name is
 * read, and the list runs to every launchable app on the device. It wears the menu sheet's
 * panel and the same focus grammar, so it belongs to the menus it is opened from.
 *
 * Labels are cheap and are read up front to sort by; icons are not, so they are decoded on a
 * background thread and dropped into their tiles as they arrive.
 */
public final class CompanionAppPicker {

    /** The fraction of the screen the grid may fill before it starts scrolling. */
    private static final float MAX_HEIGHT_FRACTION = 0.66f;

    private CompanionAppPicker() {
    }

    /** One app on offer: its identity and label now, its icon once the decode catches up. */
    private static final class Entry {
        final ComponentName component;
        final String label;
        Drawable icon;

        Entry(ComponentName component, String label) {
            this.component = component;
            this.label = label;
        }
    }

    /**
     * Show the picker for a game. The pick is stored against the game and handed to
     * {@code onChosen}; the "None" row, offered only when there is something to clear,
     * forgets it instead.
     */
    public static void show(Activity host, String appUuid, String appName,
                            CompanionAppLauncher.OnChosen onChosen) {
        final PackageManager pm = host.getPackageManager();
        List<Entry> entries = launchableApps(host, pm);
        final ComponentName current = CompanionApps.get(host, appUuid, appName);

        Dialog dialog = new Dialog(host, R.style.MenuSheetDialog);
        LayoutInflater inflater = LayoutInflater.from(dialog.getContext());
        View panel = inflater.inflate(R.layout.companion_app_picker, null);

        ((TextView) panel.findViewById(R.id.companionPickerTitle))
                .setText(R.string.companion_app_pick_title);

        if (current != null) {
            // Clearing comes first so it is always in reach, and stays a row: it is an action,
            // not an app, and has no icon to be recognised by.
            LinearLayout noneHolder = panel.findViewById(R.id.companionPickerNone);
            View row = inflater.inflate(R.layout.menu_sheet_row, noneHolder, false);
            ((TextView) row.findViewById(R.id.menuRowLabel)).setText(R.string.companion_app_none);
            row.setOnClickListener(v -> {
                dialog.dismiss();
                CompanionApps.clear(host, appUuid, appName);
                Toast.makeText(host, R.string.companion_app_cleared, Toast.LENGTH_SHORT).show();
            });
            noneHolder.addView(row);
        }

        MaxHeightRecyclerView grid = panel.findViewById(R.id.companionPickerGrid);
        int columnWidthPx = host.getResources().getDimensionPixelSize(R.dimen.companion_app_column);
        int spacingPx = host.getResources().getDimensionPixelSize(R.dimen.companion_app_spacing);
        grid.setLayoutManager(new AutofitGridLayoutManager(host, columnWidthPx));
        grid.addItemDecoration(new GridSpacingItemDecoration(spacingPx));
        int half = spacingPx / 2;
        grid.setPadding(half, half, half, half);
        grid.setMaxHeightPx((int) (host.getResources().getDisplayMetrics().heightPixels
                * MAX_HEIGHT_FRACTION));

        Adapter adapter = new Adapter(entries, current, component -> {
            dialog.dismiss();
            CompanionApps.set(host, appUuid, appName, component);
            if (onChosen != null) {
                onChosen.onChosen(component);
            }
        });
        grid.setAdapter(adapter);

        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            WindowManager.LayoutParams lp = window.getAttributes();
            int width = host.getResources().getDisplayMetrics().widthPixels;
            lp.width = Math.min(
                    host.getResources().getDimensionPixelSize(R.dimen.companion_picker_max_width),
                    (int) (width * 0.92f));
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.CENTER;
            window.setAttributes(lp);
            window.setDimAmount(0.6f);
        }

        // The decode outlives nothing: it is stopped when the dialog goes, so a dismissed picker
        // does not keep a thread reading icons for tiles no one will see.
        IconLoader loader = new IconLoader(pm, entries, adapter);
        dialog.setOnDismissListener(d -> loader.stop());
        dialog.show();
        loader.start();

        // Land the gamepad on the first thing in the panel.
        View none = panel.findViewById(R.id.companionPickerNone);
        if (none instanceof ViewGroup && ((ViewGroup) none).getChildCount() > 0) {
            ((ViewGroup) none).getChildAt(0).requestFocus();
        } else {
            // The grid has no tiles yet and would swallow the focus without passing it on, so
            // the first tile is claimed once a layout pass has produced one.
            grid.post(() -> {
                RecyclerView.ViewHolder first = grid.findViewHolderForAdapterPosition(0);
                if (first != null) {
                    first.itemView.requestFocus();
                } else {
                    grid.requestFocus();
                }
            });
        }
    }

    /** Every launchable app but ours, sorted by name. Labels only — icons come later. */
    private static List<Entry> launchableApps(Activity host, PackageManager pm) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        String self = host.getPackageName();

        List<Entry> entries = new ArrayList<>();
        for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
            // Opening ourselves beside ourselves is never the intent.
            if (info.activityInfo == null || self.equals(info.activityInfo.packageName)) {
                continue;
            }
            entries.add(new Entry(
                    new ComponentName(info.activityInfo.packageName, info.activityInfo.name),
                    info.loadLabel(pm).toString()));
        }

        final Collator collator = Collator.getInstance();
        Collections.sort(entries, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return collator.compare(a.label, b.label);
            }
        });
        return entries;
    }

    /**
     * Decodes the entries' icons one at a time off the main thread, telling the adapter about
     * each as it lands. Icons come from disk and there is one per installed app, so doing this
     * while building the tiles would stall the picker's opening.
     */
    private static final class IconLoader {
        private final PackageManager pm;
        private final List<Entry> entries;
        private final Adapter adapter;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private volatile boolean stopped;

        IconLoader(PackageManager pm, List<Entry> entries, Adapter adapter) {
            this.pm = pm;
            this.entries = entries;
            this.adapter = adapter;
        }

        void start() {
            executor.execute(() -> {
                for (int i = 0; i < entries.size(); i++) {
                    if (stopped) {
                        return;
                    }
                    Entry entry = entries.get(i);
                    Drawable icon;
                    try {
                        icon = pm.getActivityIcon(entry.component);
                    } catch (PackageManager.NameNotFoundException e) {
                        icon = pm.getDefaultActivityIcon();
                    }
                    final Drawable decoded = icon;
                    final int position = i;
                    main.post(() -> {
                        if (stopped) {
                            return;
                        }
                        entry.icon = decoded;
                        adapter.notifyItemChanged(position);
                    });
                }
            });
        }

        void stop() {
            stopped = true;
            executor.shutdownNow();
        }
    }

    private static final class Adapter extends RecyclerView.Adapter<Adapter.Tile> {
        private final List<Entry> entries;
        private final ComponentName current;
        private final CompanionAppLauncher.OnChosen onPick;

        Adapter(List<Entry> entries, ComponentName current, CompanionAppLauncher.OnChosen onPick) {
            this.entries = entries;
            this.current = current;
            this.onPick = onPick;
        }

        static final class Tile extends RecyclerView.ViewHolder {
            final ImageView icon;
            final ImageView check;
            final TextView label;

            Tile(View view) {
                super(view);
                icon = view.findViewById(R.id.companionAppIcon);
                check = view.findViewById(R.id.companionAppCheck);
                label = view.findViewById(R.id.companionAppLabel);
            }
        }

        @NonNull
        @Override
        public Tile onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Tile(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.companion_app_tile, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Tile tile, int position) {
            Entry entry = entries.get(position);
            tile.label.setText(entry.label);
            // Null until the decode reaches this one, which also clears a recycled tile's icon.
            tile.icon.setImageDrawable(entry.icon);
            tile.check.setVisibility(
                    entry.component.equals(current) ? View.VISIBLE : View.GONE);
            tile.itemView.setOnClickListener(v -> onPick.onChosen(entry.component));
        }

        @Override
        public int getItemCount() {
            return entries.size();
        }
    }
}
