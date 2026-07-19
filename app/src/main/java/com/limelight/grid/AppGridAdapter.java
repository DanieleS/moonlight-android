package com.limelight.grid;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.limelight.AppView;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.grid.assets.CachedAppAssetLoader;
import com.limelight.grid.assets.DiskAssetLoader;
import com.limelight.grid.assets.MemoryAssetLoader;
import com.limelight.grid.assets.NetworkAssetLoader;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.preferences.PreferenceConfiguration;

import com.limelight.nvstream.http.AppMetadata;
import com.limelight.utils.AppLaunchHistory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SuppressWarnings("unchecked")
public class AppGridAdapter extends GenericGridAdapter<AppView.AppObject> {
    private static final int ART_WIDTH_PX = 300;

    // The width of the art itself, which is now the width of the whole cell: the space
    // between tiles belongs to the grid. Keep these in step with @layout/app_grid_item.
    private static final int SMALL_WIDTH_DP = 100;
    private static final int LARGE_WIDTH_DP = 150;

    private final ComputerDetails computer;
    private final String uniqueId;
    private final boolean showHiddenApps;

    private CachedAppAssetLoader loader;
    private Set<Integer> hiddenAppIds = new HashSet<>();
    private ArrayList<AppView.AppObject> allApps = new ArrayList<>();

    private AppSortOrder sortOrder = AppSortOrder.HOST;
    private Map<String, AppMetadata> appMetadata = Collections.emptyMap();
    private final AppLaunchHistory launchHistory;

    public AppGridAdapter(Context context, PreferenceConfiguration prefs, ComputerDetails computer, String uniqueId, boolean showHiddenApps) {
        super(context, getLayoutIdForPreferences(prefs));

        this.computer = computer;
        this.uniqueId = uniqueId;
        this.showHiddenApps = showHiddenApps;
        this.launchHistory = new AppLaunchHistory(context, computer != null ? computer.uuid : null);

        updateLayoutWithPreferences(context, prefs);
    }

    /** Changes the order the library is listed in, re-sorting what is already on screen. */
    public void setSortOrder(AppSortOrder newSortOrder) {
        if (newSortOrder == null || newSortOrder == sortOrder) {
            return;
        }
        this.sortOrder = newSortOrder;
        resort();
    }

    public AppSortOrder getSortOrder() {
        return sortOrder;
    }

    /**
     * Hands over the Playnite metadata the host served. It arrives after the apps do, so the
     * orders that rank by it re-sort when it lands.
     */
    public void setAppMetadata(Map<String, AppMetadata> metadata) {
        this.appMetadata = metadata == null ? Collections.<String, AppMetadata>emptyMap() : metadata;
        if (sortOrder == AppSortOrder.RELEASE || sortOrder == AppSortOrder.SCORE) {
            resort();
        }
    }

    private void resort() {
        sortList(allApps);
        sortList(itemList);
        notifyDataSetChanged();
    }

    public void updateHiddenApps(Set<Integer> newHiddenAppIds, boolean hideImmediately) {
        this.hiddenAppIds.clear();
        this.hiddenAppIds.addAll(newHiddenAppIds);

        if (hideImmediately) {
            // Reconstruct the itemList with the new hidden app set
            itemList.clear();
            for (AppView.AppObject app : allApps) {
                app.isHidden = hiddenAppIds.contains(app.app.getAppId());

                if (!app.isHidden || showHiddenApps) {
                    itemList.add(app);
                }
            }
        }
        else {
            // Just update the isHidden state to show the correct UI indication
            for (AppView.AppObject app : allApps) {
                app.isHidden = hiddenAppIds.contains(app.app.getAppId());
            }
        }

        notifyDataSetChanged();
    }

    private boolean coverflowLayout;

    private static int getLayoutIdForPreferences(PreferenceConfiguration prefs) {
        if (prefs.smallIconMode) {
            return R.layout.app_grid_item_small;
        }
        else {
            return R.layout.app_grid_item;
        }
    }

    // The layout the tiles should have right now: the carousel cover if the carousel is
    // showing, otherwise the grid cell the preferences ask for.
    private int currentLayoutId(PreferenceConfiguration prefs) {
        return coverflowLayout ? R.layout.app_coverflow_item : getLayoutIdForPreferences(prefs);
    }

    // Swap the tile between the grid cell and the larger carousel cover; both carry the
    // same view ids, so populateView is unchanged.
    public void setCoverflowLayout(boolean coverflow, PreferenceConfiguration prefs) {
        this.coverflowLayout = coverflow;
        setLayoutId(currentLayoutId(prefs));
    }

    public void updateLayoutWithPreferences(Context context, PreferenceConfiguration prefs) {
        int dpi = context.getResources().getDisplayMetrics().densityDpi;
        int dp;

        if (prefs.smallIconMode) {
            dp = SMALL_WIDTH_DP;
        }
        else {
            dp = LARGE_WIDTH_DP;
        }

        double scalingDivisor = ART_WIDTH_PX / (dp * (dpi / 160.0));
        if (scalingDivisor < 1.0) {
            // We don't want to make them bigger before draw-time
            scalingDivisor = 1.0;
        }
        LimeLog.info("Art scaling divisor: " + scalingDivisor);

        if (loader != null) {
            // Cancel operations on the old loader
            cancelQueuedOperations();
        }

        this.loader = new CachedAppAssetLoader(computer, scalingDivisor,
                new NetworkAssetLoader(context, uniqueId),
                new MemoryAssetLoader(),
                new DiskAssetLoader(context),
                BitmapFactory.decodeResource(context.getResources(), R.drawable.no_app_image));

        // This will trigger the view to reload with the new layout
        setLayoutId(currentLayoutId(prefs));
    }

    public void cancelQueuedOperations() {
        loader.cancelForegroundLoads();
        loader.cancelBackgroundLoads();
        loader.freeCacheMemory();
    }

    private void sortList(List<AppView.AppObject> list) {
        Collections.sort(list, sortOrder.comparator(appMetadata, launchHistory));
    }

    public void addApp(AppView.AppObject app) {
        // Update hidden state
        app.isHidden = hiddenAppIds.contains(app.app.getAppId());

        // Always add the app to the all apps list
        allApps.add(app);
        sortList(allApps);

        // Add the app to the adapter data if it's not hidden
        if (showHiddenApps || !app.isHidden) {
            // Queue a request to fetch this bitmap into cache
            loader.queueCacheLoad(app.app);

            // Add the app to our sorted list
            itemList.add(app);
            sortList(itemList);
        }
    }

    public void removeApp(AppView.AppObject app) {
        itemList.remove(app);
        allApps.remove(app);
    }

    @Override
    public void clear() {
        super.clear();
        allApps.clear();
    }

    @Override
    public void populateView(View parentView, ImageView imgView, TextView txtView, ImageView overlayView, AppView.AppObject obj) {
        // Let the cached asset loader handle it
        loader.populateImageView(obj.app, imgView, txtView);

        // The scrim is a view of its own rather than a background on the mask, so that it
        // can carry the same rounded corners as the art it is dimming.
        View runningScrim = parentView.findViewById(R.id.grid_running_scrim);

        if (obj.isRunning) {
            // Show the play button overlay
            overlayView.setImageResource(R.drawable.ic_play);
            overlayView.setVisibility(View.VISIBLE);
            runningScrim.setVisibility(View.VISIBLE);
        }
        else {
            overlayView.setVisibility(View.GONE);
            runningScrim.setVisibility(View.GONE);
        }

        if (obj.isHidden) {
            parentView.setAlpha(0.40f);
        }
        else {
            parentView.setAlpha(1.0f);
        }
    }
}
