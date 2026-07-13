package com.limelight;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.google.android.material.button.MaterialButton;
import com.limelight.companion.CompanionDisplayManager;
import com.limelight.companion.CompanionState;
import com.limelight.binding.PlatformBinding;
import com.limelight.binding.input.ControllerHandler;
import com.limelight.nvstream.http.AppMetadata;
import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.grid.AppGridAdapter;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.profiles.ProfilesManager;
import com.limelight.ui.AdapterFragment;
import com.limelight.ui.AdapterFragmentCallbacks;
import com.limelight.utils.CacheHelper;
import com.limelight.utils.Dialog;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.SpinnerDialog;
import com.limelight.utils.UiHelper;

import android.app.Activity;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.ContextMenu;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView.AdapterContextMenuInfo;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.limelight.grid.AutofitGridLayoutManager;
import com.limelight.grid.CoverFlowLayoutManager;
import com.limelight.grid.GridSpacingItemDecoration;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.xmlpull.v1.XmlPullParserException;

public class AppView extends AppCompatActivity implements AdapterFragmentCallbacks {
    private AppGridAdapter appGridAdapter;
    private String uuidString;
    private ShortcutHelper shortcutHelper;

    private ComputerDetails computer;
    private ComputerManagerService.ApplistPoller poller;
    private SpinnerDialog blockingLoadSpinner;
    private String lastRawApplist;
    private int lastRunningAppId;
    private boolean suspendGridUpdates;
    private boolean inForeground;
    private boolean showHiddenApps;
    private HashSet<Integer> hiddenAppIds = new HashSet<>();

    // The carousel is the front door; the grid is "all games", one button away.
    private boolean coverflowMode = true;
    private int coverflowCentered = -1;

    // The host's "Virtual Display" shortcut is kept out of the library and offered as a
    // dedicated app-bar action instead. Null until the host advertises it.
    private NvApp virtualDisplayApp;

    // Playnite-enriched metadata keyed by upper-cased app UUID, shown on the companion panel.
    // Empty on stock hosts; fetched once per visit.
    private Map<String, AppMetadata> appMetadata = Collections.emptyMap();
    private boolean metadataFetchStarted;

    // The app id currently spotlighted on the companion, so a slow background load can tell it is
    // still wanted before applying. Backgrounds are cached across scrolls.
    private int browsingAppId = -1;
    private final java.util.HashMap<Integer, Bitmap> backgroundCache = new java.util.HashMap<>();

    private PreferenceConfiguration prefConfig;

    private final static int START_OR_RESUME_ID = 1;
    private final static int QUIT_ID = 2;
    private final static int START_WITH_QUIT = 4;
    private final static int VIEW_DETAILS_ID = 5;
    private final static int CREATE_SHORTCUT_ID = 6;
    private final static int EXPORT_LAUNCHER_FILE_ID = 7;
    private final static int HIDE_APP_ID = 8;
    private final static int START_WITH_VDISPLAY = 20;
    private final static int START_WITH_QUIT_VDISPLAY = 21;

    public final static String HIDDEN_APPS_PREF_FILENAME = "HiddenApps";

    public final static String NAME_EXTRA = "Name";
    public final static String UUID_EXTRA = "UUID";
    public final static String NEW_PAIR_EXTRA = "NewPair";
    public final static String SHOW_HIDDEN_APPS_EXTRA = "ShowHiddenApps";

    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder localBinder =
                    ((ComputerManagerService.ComputerManagerBinder)binder);

            // Wait in a separate thread to avoid stalling the UI
            new Thread() {
                @Override
                public void run() {
                    // Wait for the binder to be ready
                    localBinder.waitForReady();

                    // Get the computer object
                    computer = localBinder.getComputer(uuidString);
                    if (computer == null) {
                        finish();
                        return;
                    }

                    // Add a launcher shortcut for this PC (forced, since this is user interaction)
                    shortcutHelper.createAppViewShortcut(computer, true, getIntent().getBooleanExtra(NEW_PAIR_EXTRA, false));
                    shortcutHelper.reportComputerShortcutUsed(computer);

                    try {
                        appGridAdapter = new AppGridAdapter(AppView.this,
                                PreferenceConfiguration.readPreferences(AppView.this),
                                computer, localBinder.getUniqueId(),
                                showHiddenApps);
                    } catch (Exception e) {
                        e.printStackTrace();
                        finish();
                        return;
                    }

                    appGridAdapter.updateHiddenApps(hiddenAppIds, true);

                    // Now make the binder visible. We must do this after appGridAdapter
                    // is set to prevent us from reaching updateUiWithServerinfo() and
                    // touching the appGridAdapter prior to initialization.
                    managerBinder = localBinder;

                    // Load the app grid with cached data (if possible).
                    // This must be done _before_ startComputerUpdates()
                    // so the initial serverinfo response can update the running
                    // icon.
                    populateAppGridWithCache();

                    // Start updates
                    startComputerUpdates();

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || isChangingConfigurations()) {
                                return;
                            }

                            // Despite my best efforts to catch all conditions that could
                            // cause the activity to be destroyed when we try to commit
                            // I haven't been able to, so we have this try-catch block.
                            try {
                                getFragmentManager().beginTransaction()
                                        .replace(R.id.appFragmentContainer, new AdapterFragment())
                                        .commitAllowingStateLoss();
                            } catch (IllegalStateException e) {
                                e.printStackTrace();
                            }
                        }
                    });
                }
            }.start();
        }

        public void onServiceDisconnected(ComponentName className) {
            managerBinder = null;
        }
    };

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        this.prefConfig = PreferenceConfiguration.readPreferences(this);

        // If appGridAdapter is initialized, let it know about the configuration change.
        // If not, it will pick it up when it initializes.
        if (appGridAdapter != null) {
            // Update the app grid adapter to create grid items with the correct layout
            appGridAdapter.updateLayoutWithPreferences(this, this.prefConfig);

            try {
                // Reinflate the app grid itself to pick up the layout change
                getFragmentManager().beginTransaction()
                        .replace(R.id.appFragmentContainer, new AdapterFragment())
                        .commitAllowingStateLoss();
            } catch (IllegalStateException e) {
                e.printStackTrace();
            }
        }
    }

    private void startComputerUpdates() {
        // Don't start polling if we're not bound or in the foreground
        if (managerBinder == null || !inForeground) {
            return;
        }

        managerBinder.startPolling(new ComputerManagerListener() {
            @Override
            public void notifyComputerUpdated(final ComputerDetails details) {
                // Do nothing if updates are suspended
                if (suspendGridUpdates) {
                    return;
                }

                // Don't care about other computers
                if (!details.uuid.equalsIgnoreCase(uuidString)) {
                    return;
                }

                if (details.state == ComputerDetails.State.OFFLINE) {
                    // The PC is unreachable now
                    AppView.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // Display a toast to the user and quit the activity
                            Toast.makeText(AppView.this, R.string.lost_connection, Toast.LENGTH_SHORT).show();
                            finish();
                        }
                    });

                    return;
                }

                // Close immediately if the PC is no longer paired
                if (details.state == ComputerDetails.State.ONLINE && details.pairState != PairingManager.PairState.PAIRED) {
                    AppView.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // Disable shortcuts referencing this PC for now
                            shortcutHelper.disableComputerShortcut(details,
                                    getResources().getString(R.string.scut_not_paired));

                            // Display a toast to the user and quit the activity
                            Toast.makeText(AppView.this, R.string.scut_not_paired, Toast.LENGTH_SHORT).show();
                            finish();
                        }
                    });

                    return;
                }

                // App list is the same or empty
                if (details.rawAppList == null || details.rawAppList.equals(lastRawApplist)) {

                    // Let's check if the running app ID changed
                    if (details.runningGameId != lastRunningAppId) {
                        // Update the currently running game using the app ID
                        lastRunningAppId = details.runningGameId;
                        updateUiWithServerinfo(details);
                    }

                    return;
                }

                lastRunningAppId = details.runningGameId;
                lastRawApplist = details.rawAppList;

                try {
                    updateUiWithAppList(NvHTTP.getAppListByReader(new StringReader(details.rawAppList)));
                    updateUiWithServerinfo(details);

                    if (blockingLoadSpinner != null) {
                        blockingLoadSpinner.dismiss();
                        blockingLoadSpinner = null;
                    }
                } catch (XmlPullParserException | IOException e) {
                    e.printStackTrace();
                }
            }
        });

        if (poller == null) {
            poller = managerBinder.createAppListPoller(computer);
        }
        poller.start();
    }

    private void stopComputerUpdates() {
        if (poller != null) {
            poller.stop();
        }

        if (managerBinder != null) {
            managerBinder.stopPolling();
        }

        if (appGridAdapter != null) {
            appGridAdapter.cancelQueuedOperations();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Assume we're in the foreground when created to avoid a race
        // between binding to CMS and onResume()
        inForeground = true;

        shortcutHelper = new ShortcutHelper(this);

        UiHelper.setLocale(this);

        setContentView(R.layout.activity_app_view);

        // Allow floating expanded PiP overlays while browsing apps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false);
        }

        UiHelper.notifyNewRootView(this);

        // Setup the profiles button
        findViewById(R.id.profilesButton)
            .setOnClickListener(v -> startActivity(new Intent(this, ProfilesActivity.class)));

        // Toggle between the carousel and the "all games" grid, from the bar or from the
        // always-visible hint that advertises the gamepad shortcut for the same thing.
        findViewById(R.id.viewModeButton)
            .setOnClickListener(v -> toggleViewMode());
        findViewById(R.id.viewModeHint)
            .setOnClickListener(v -> toggleViewMode());

        // Turn the companion screen back on after a back gesture closed it, or off again.
        findViewById(R.id.companionButton)
            .setOnClickListener(v -> {
                CompanionDisplayManager.toggle();
                refreshCompanionButton();
            });

        // Start the host's virtual display straight from the bar.
        findViewById(R.id.virtualDisplayButton)
            .setOnClickListener(v -> launchVirtualDisplay());

        // The library is a full-screen console surface: no system bars, and no app bar over
        // the carousel. The bar returns, and pushes the grid down, only in the grid.
        enterImmersive();
        applyChromeForMode();
        refreshViewModeControls();
        refreshCompanionButton();
        refreshVirtualDisplayButton();

        showHiddenApps = getIntent().getBooleanExtra(SHOW_HIDDEN_APPS_EXTRA, false);
        uuidString = getIntent().getStringExtra(UUID_EXTRA);

        SharedPreferences hiddenAppsPrefs = getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE);
        for (String hiddenAppIdStr : hiddenAppsPrefs.getStringSet(uuidString, new HashSet<String>())) {
            hiddenAppIds.add(Integer.parseInt(hiddenAppIdStr));
        }

        String computerName = getIntent().getStringExtra(NAME_EXTRA);

        TextView label = findViewById(R.id.appListText);
        setTitle(computerName);
        label.setText(computerName);

        this.prefConfig = PreferenceConfiguration.readPreferences(this);

        // Bind to the computer manager service
        bindService(new Intent(this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);
    }

    private void updateHiddenApps(boolean hideImmediately) {
        HashSet<String> hiddenAppIdStringSet = new HashSet<>();

        for (Integer hiddenAppId : hiddenAppIds) {
            hiddenAppIdStringSet.add(hiddenAppId.toString());
        }

        getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .edit()
                .putStringSet(uuidString, hiddenAppIdStringSet)
                .apply();

        appGridAdapter.updateHiddenApps(hiddenAppIds, hideImmediately);
    }

    private void populateAppGridWithCache() {
        try {
            // Try to load from cache
            lastRawApplist = CacheHelper.readInputStreamToString(CacheHelper.openCacheFileForInput(getCacheDir(), "applist", uuidString));
            List<NvApp> applist = NvHTTP.getAppListByReader(new StringReader(lastRawApplist));
            updateUiWithAppList(applist);
            LimeLog.info("Loaded applist from cache");
        } catch (IOException | XmlPullParserException e) {
            if (lastRawApplist != null) {
                LimeLog.warning("Saved applist corrupted: "+lastRawApplist);
                e.printStackTrace();
            }
            LimeLog.info("Loading applist from the network");
            // We'll need to load from the network
            loadAppsBlocking();
        }
    }

    private void loadAppsBlocking() {
        blockingLoadSpinner = SpinnerDialog.displayDialog(this, getResources().getString(R.string.applist_refresh_title),
                getResources().getString(R.string.applist_refresh_msg), true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        SpinnerDialog.closeDialogs(this);
        Dialog.closeDialogs();

        // Stop spotlighting a game on the companion panel once we leave the library.
        CompanionState.getInstance().clearBrowsing();

        if (managerBinder != null) {
            unbindService(serviceConnection);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Display a decoder crash notification if we've returned after a crash
        UiHelper.showDecoderCrashDialog(this);

        inForeground = true;
        startComputerUpdates();

        MaterialButton profilesButton = findViewById(R.id.profilesButton);
        // User report Samsung and Xiaomi devices have this problem
        // Why just these two brands have the most problems?
        if (profilesButton == null) {
            return;
        }
        // With no active profile the button is its icon alone, as the other actions are.
        profilesButton.setText(ProfilesManager.getInstance().getActiveName());

        refreshCompanionButton();
    }

    // The companion toggle is only offered where the feature is on; its icon follows whether a
    // panel is currently up, so the user can tell they have turned it back on.
    private void refreshCompanionButton() {
        ImageButton companionButton = findViewById(R.id.companionButton);
        if (companionButton == null) {
            return;
        }
        if (!CompanionDisplayManager.isConfigured(this)) {
            companionButton.setVisibility(View.GONE);
            return;
        }
        boolean showing = CompanionDisplayManager.isShowing();
        companionButton.setVisibility(View.VISIBLE);
        companionButton.setImageResource(showing ? R.drawable.ic_companion_on : R.drawable.ic_companion_off);
        companionButton.setContentDescription(getString(
                showing ? R.string.action_companion_hide : R.string.action_companion_show));
    }

    // Remember the host's Virtual Display shortcut (or clear it) and keep its bar button in step.
    private void setVirtualDisplayApp(NvApp app) {
        virtualDisplayApp = app;
        refreshVirtualDisplayButton();
    }

    // The Virtual Display action only appears where the host advertises the shortcut.
    private void refreshVirtualDisplayButton() {
        ImageButton virtualDisplayButton = findViewById(R.id.virtualDisplayButton);
        if (virtualDisplayButton == null) {
            return;
        }
        virtualDisplayButton.setVisibility(virtualDisplayApp != null ? View.VISIBLE : View.GONE);
    }

    private void launchVirtualDisplay() {
        if (virtualDisplayApp == null || computer == null || managerBinder == null) {
            return;
        }

        // The Virtual Display entry is itself a virtual-display session, so it is launched
        // directly rather than through the "use virtual display" preference.
        final NvApp app = virtualDisplayApp;
        Runnable start = () -> ServerHelper.doStart(AppView.this, app, computer, managerBinder, false);

        // Launching it would tear down whatever is already streaming, so confirm first.
        if (lastRunningAppId != 0 && lastRunningAppId != app.getAppId()) {
            UiHelper.displayQuitConfirmationDialog(this, start, null);
        } else {
            start.run();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        inForeground = false;
        stopComputerUpdates();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == ShortcutHelper.REQUEST_CODE_EXPORT_ART_FILE) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                ShortcutHelper.writeArtFileToUri(this, uri);
            } else {
                // Clear the content if the user cancelled or if there was an error before this point
                ShortcutHelper.artFileContentToExport = null;
                // Show "File export cancelled." toast only if the user explicitly cancelled.
                if (resultCode == Activity.RESULT_CANCELED) {
                    Toast.makeText(this, R.string.file_export_cancelled, Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        AppObject selectedApp = (AppObject) appGridAdapter.getItem(info.position);

        menu.setHeaderTitle(selectedApp.app.getAppName());

        if (lastRunningAppId == 0) {
            if (prefConfig.useVirtualDisplay) {
                menu.add(Menu.NONE, START_OR_RESUME_ID, 1, getResources().getString(R.string.applist_menu_start_primarydisplay));
            } else {
                menu.add(Menu.NONE, START_WITH_VDISPLAY, 1, getResources().getString(R.string.applist_menu_start_vdisplay));
            }
        } else {
            if (lastRunningAppId == selectedApp.app.getAppId()) {
                menu.add(Menu.NONE, START_OR_RESUME_ID, 1, getResources().getString(R.string.applist_menu_resume));
                menu.add(Menu.NONE, QUIT_ID, 2, getResources().getString(R.string.applist_menu_quit));
            }
            else {
                if (prefConfig.useVirtualDisplay) {
                    menu.add(Menu.NONE, START_WITH_QUIT_VDISPLAY, 1, getResources().getString(R.string.applist_menu_quit_and_start));
                    menu.add(Menu.NONE, START_WITH_QUIT, 2, getResources().getString(R.string.applist_menu_quit_and_start_primarydisplay));
                } else{
                    menu.add(Menu.NONE, START_WITH_QUIT, 1, getResources().getString(R.string.applist_menu_quit_and_start));
                    menu.add(Menu.NONE, START_WITH_QUIT_VDISPLAY, 2, getResources().getString(R.string.applist_menu_quit_and_start_vdisplay));
                }
            }
        }

        // Only show the hide checkbox if this is not the currently running app or it's already hidden
        if (lastRunningAppId != selectedApp.app.getAppId() || selectedApp.isHidden) {
            MenuItem hideAppItem = menu.add(Menu.NONE, HIDE_APP_ID, 3, getResources().getString(R.string.applist_menu_hide_app));
            hideAppItem.setCheckable(true);
            hideAppItem.setChecked(selectedApp.isHidden);
        }

        menu.add(Menu.NONE, VIEW_DETAILS_ID, 4, getResources().getString(R.string.applist_menu_details));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Only add an option to create shortcut if box art is loaded
            // and when we're in grid-mode (not list-mode).
            ImageView appImageView = info.targetView.findViewById(R.id.grid_image);
            if (appImageView != null) {
                // We have a grid ImageView, so we must be in grid-mode
                BitmapDrawable drawable = (BitmapDrawable)appImageView.getDrawable();
                if (drawable != null && drawable.getBitmap() != null) {
                    // We have a bitmap loaded too
                    menu.add(Menu.NONE, CREATE_SHORTCUT_ID, 5, getResources().getString(R.string.applist_menu_scut));
                }
            }
        }

        menu.add(Menu.NONE, EXPORT_LAUNCHER_FILE_ID, 6, getResources().getString(R.string.applist_menu_export_launcher));
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
        final AppObject app = (AppObject) appGridAdapter.getItem(info.position);
        int itemId = item.getItemId();
        switch (itemId) {
            case START_WITH_QUIT:
            case START_WITH_QUIT_VDISPLAY: {
                boolean withVDiaplay = itemId == START_WITH_QUIT_VDISPLAY;
                if (withVDiaplay && !(computer.vDisplaySupported && computer.vDisplayDriverReady)) {
                    UiHelper.displayVdisplayConfirmationDialog(
                        AppView.this,
                        computer,
                        () -> UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                            @Override
                            public void run() {
                                ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, true);
                            }
                        }, null),
                        null
                    );
                } else {
                    // Display a confirmation dialog first
                    UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                        @Override
                        public void run() {
                            ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, withVDiaplay);
                        }
                    }, null);
                }
                return true;
            }

            case START_OR_RESUME_ID:
            case START_WITH_VDISPLAY: {
                boolean withVDiaplay = itemId == START_WITH_VDISPLAY;
                if (withVDiaplay && !(computer.vDisplaySupported && computer.vDisplayDriverReady)) {
                    UiHelper.displayVdisplayConfirmationDialog(
                            AppView.this,
                            computer,
                            () -> ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, true),
                            null
                    );
                } else {
                    // Resume is the same as start for us
                    ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, withVDiaplay);
                }
                return true;
            }

            case QUIT_ID: {
                // Display a confirmation dialog first
                UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                    @Override
                    public void run() {
                        suspendGridUpdates = true;
                        ServerHelper.doQuit(AppView.this, computer,
                                app.app, managerBinder, new Runnable() {
                                    @Override
                                    public void run() {
                                        // Trigger a poll immediately
                                        suspendGridUpdates = false;
                                        if (poller != null) {
                                            poller.pollNow();
                                        }
                                    }
                                });
                    }
                }, null);
                return true;
            }

            case VIEW_DETAILS_ID: {
                Dialog.displayDialog(AppView.this, getResources().getString(R.string.title_details), app.app.toString(), false);
                return true;
            }

            case HIDE_APP_ID: {
                if (item.isChecked()) {
                    // Transitioning hidden to shown
                    hiddenAppIds.remove(app.app.getAppId());
                } else {
                    // Transitioning shown to hidden
                    hiddenAppIds.add(app.app.getAppId());
                }
                updateHiddenApps(false);
                return true;
            }

            case CREATE_SHORTCUT_ID: {
                ImageView appImageView = info.targetView.findViewById(R.id.grid_image);
                Bitmap appBits = ((BitmapDrawable) appImageView.getDrawable()).getBitmap();
                if (!shortcutHelper.createPinnedGameShortcut(computer, app.app, appBits)) {
                    Toast.makeText(AppView.this, getResources().getString(R.string.unable_to_pin_shortcut), Toast.LENGTH_LONG).show();
                }
                return true;
            }

            case EXPORT_LAUNCHER_FILE_ID: {
                if (app.app.getAppUUID() == null || (app.app.getAppUUID() != null && app.app.getAppUUID().isEmpty())) {
                    UiHelper.displayConfirmationDialog(
                            AppView.this,
                            getResources().getString(R.string.title_export_sunshine_launcher_file),
                            getResources().getString(R.string.message_export_sunshine_launcher_file),
                            getResources().getString(R.string.proceed),
                            getResources().getString(R.string.cancel),
                            () -> shortcutHelper.exportLauncherFile(computer, app.app),
                            null
                    );
                } else {
                    shortcutHelper.exportLauncherFile(computer, app.app);
                }
                return true;
            }

            default: {
                return super.onContextItemSelected(item);
            }
        }
    }

    private void updateUiWithServerinfo(final ComputerDetails details) {
        AppView.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                boolean updated = false;

                    // Look through our current app list to tag the running app
                for (int i = 0; i < appGridAdapter.getCount(); i++) {
                    AppObject existingApp = (AppObject) appGridAdapter.getItem(i);

                    // There can only be one or zero apps running.
                    if (existingApp.isRunning &&
                            existingApp.app.getAppId() == details.runningGameId) {
                        // This app was running and still is, so we're done now
                        return;
                    }
                    else if (existingApp.app.getAppId() == details.runningGameId) {
                        // This app wasn't running but now is
                        existingApp.isRunning = true;
                        updated = true;
                    }
                    else if (existingApp.isRunning) {
                        // This app was running but now isn't
                        existingApp.isRunning = false;
                        updated = true;
                    }
                    else {
                        // This app wasn't running and still isn't
                    }
                }

                if (updated) {
                    appGridAdapter.notifyDataSetChanged();
                }
            }
        });
    }

    private void updateUiWithAppList(final List<NvApp> rawAppList) {
        AppView.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // Pull the host's Virtual Display shortcut out of the library; it is offered as
                // a dedicated app-bar action instead of appearing as a cover.
                final List<NvApp> appList = new ArrayList<>();
                NvApp foundVirtualDisplay = null;
                for (NvApp app : rawAppList) {
                    if (app.isVirtualDisplay()) {
                        foundVirtualDisplay = app;
                    } else {
                        appList.add(app);
                    }
                }
                setVirtualDisplayApp(foundVirtualDisplay);

                boolean updated = false;

                // First handle app updates and additions
                for (NvApp app : appList) {
                    boolean foundExistingApp = false;

                    // Try to update an existing app in the list first
                    for (int i = 0; i < appGridAdapter.getCount(); i++) {
                        AppObject existingApp = (AppObject) appGridAdapter.getItem(i);
                        if (existingApp.app.getAppId() == app.getAppId()) {
                            // Found the app; update its properties
                            if (!existingApp.app.getAppName().equals(app.getAppName())) {
                                existingApp.app.setAppName(app.getAppName());
                                updated = true;
                            }

                            foundExistingApp = true;
                            break;
                        }
                    }

                    if (!foundExistingApp) {
                        // This app must be new
                        appGridAdapter.addApp(new AppObject(app));

                        // We could have a leftover shortcut from last time this PC was paired
                        // or if this app was removed then added again. Enable those shortcuts
                        // again if present.
                        shortcutHelper.enableAppShortcut(computer, app);

                        updated = true;
                    }
                }

                // Next handle app removals
                int i = 0;
                while (i < appGridAdapter.getCount()) {
                    boolean foundExistingApp = false;
                    AppObject existingApp = (AppObject) appGridAdapter.getItem(i);

                    // Check if this app is in the latest list
                    for (NvApp app : appList) {
                        if (existingApp.app.getAppId() == app.getAppId()) {
                            foundExistingApp = true;
                            break;
                        }
                    }

                    // This app was removed in the latest app list
                    if (!foundExistingApp) {
                        shortcutHelper.disableAppShortcut(computer, existingApp.app, getString(R.string.app_removed_from_pc));
                        appGridAdapter.removeApp(existingApp);
                        updated = true;

                        // Check this same index again because the item at i+1 is now at i after
                        // the removal
                        continue;
                    }

                    // Move on to the next item
                    i++;
                }

                if (updated) {
                    appGridAdapter.notifyDataSetChanged();
                }

                // Now that we have apps, pull their metadata for the companion panel (once).
                fetchAppMetadata();
            }
        });
    }

    @Override
    public int getAdapterFragmentLayoutId() {
        if (coverflowMode) {
            return R.layout.app_coverflow_view;
        }
        return PreferenceConfiguration.readPreferences(AppView.this).smallIconMode ?
                    R.layout.app_grid_view_small : R.layout.app_grid_view;
    }

    @Override
    public void receiveRecyclerView(RecyclerView recyclerView) {
        recyclerView.setAdapter(appGridAdapter);
        appGridAdapter.setOnItemClickListener(this::onAppClicked);

        if (coverflowMode) {
            setupCoverflow(recyclerView);
        } else {
            setupGrid(recyclerView);
            UiHelper.applyStatusBarPadding(recyclerView);
        }

        registerForContextMenu(recyclerView);
        recyclerView.requestFocus();
    }

    // A gamepad (built into the Thor, or plugged into a phone) means the user drives the
    // carousel with a d-pad, so the covers should keep focus through a touch entry. A touch-only
    // phone leaves this off.
    private static boolean isGamepadConnected() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device != null && ControllerHandler.isGameControllerDevice(device)) {
                return true;
            }
        }
        return false;
    }

    private void setupGrid(RecyclerView recyclerView) {
        appGridAdapter.setCoverflowLayout(false, prefConfig);
        appGridAdapter.setItemsFocusableInTouchMode(false);
        boolean small = PreferenceConfiguration.readPreferences(this).smallIconMode;
        int columnWidthPx = Math.round((small ? 100 : 150) * getResources().getDisplayMetrics().density);
        int spacingPx = getResources().getDimensionPixelSize(
                small ? R.dimen.tile_spacing_small : R.dimen.tile_spacing);

        recyclerView.setLayoutManager(new AutofitGridLayoutManager(this, columnWidthPx));
        recyclerView.addItemDecoration(new GridSpacingItemDecoration(spacingPx));
        int half = spacingPx / 2;
        // The view-mode hint floats over the bottom-right corner, so the last row is given room
        // to scroll clear of it rather than ending up underneath it.
        int hintClearancePx = Math.round(64 * getResources().getDisplayMetrics().density);
        recyclerView.setPadding(half, half, half, half + hintClearancePx);
        recyclerView.setClipToPadding(false);

        // The grid has no centre to follow, so the companion panel spotlights whatever the tile
        // the user is on: the focused one. Tiles are recycled, so the listener rides along with
        // them rather than being set once.
        recyclerView.addOnChildAttachStateChangeListener(
                new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(View view) {
                view.setOnFocusChangeListener((v, hasFocus) -> {
                    if (!hasFocus) {
                        return;
                    }
                    int pos = recyclerView.getChildAdapterPosition(v);
                    if (pos != RecyclerView.NO_POSITION && pos < appGridAdapter.getCount()) {
                        pushCompanionBrowsing(((AppObject) appGridAdapter.getItem(pos)).app);
                    }
                });
            }

            @Override
            public void onChildViewDetachedFromWindow(View view) {
                view.setOnFocusChangeListener(null);
            }
        });
    }

    private void setupCoverflow(RecyclerView recyclerView) {
        appGridAdapter.setCoverflowLayout(true, prefConfig);
        appGridAdapter.setItemsFocusableInTouchMode(isGamepadConnected());
        coverflowCentered = -1;
        float density = getResources().getDisplayMetrics().density;
        int itemWidthPx = Math.round(180 * density);
        int gapPx = Math.round(24 * density);

        recyclerView.setLayoutManager(new CoverFlowLayoutManager(this));
        recyclerView.addItemDecoration(new GridSpacingItemDecoration(gapPx));
        new LinearSnapHelper().attachToRecyclerView(recyclerView);

        // The first and last covers must be able to reach the centre, so pad the list by half a
        // screen minus half a cover. This has to wait for a real width: a bare post() can fire
        // while the RecyclerView is still zero-width, which pads by nothing and leaves the first
        // cover stranded at the left edge instead of centred. A layout-change listener is added
        // safely whether or not the view is attached yet, and fires with the real bounds.
        recyclerView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                int width = right - left;
                if (width == 0) {
                    return;
                }
                recyclerView.removeOnLayoutChangeListener(this);

                int side = Math.max(0, (width - itemWidthPx) / 2);
                recyclerView.setPadding(side, recyclerView.getPaddingTop(),
                        side, recyclerView.getPaddingBottom());

                // With that padding the first cover rests dead centre; land the focus on it
                // rather than on wherever the framework put it. One more pass so the padding has
                // taken effect and the holder exists.
                recyclerView.post(() -> {
                    recyclerView.scrollToPosition(0);
                    RecyclerView.ViewHolder holder =
                            recyclerView.findViewHolderForAdapterPosition(0);
                    if (holder != null) {
                        holder.itemView.requestFocus();
                    }
                });
            }
        });

        // The backdrop and title are siblings of the RecyclerView's container, not of the
        // RecyclerView itself, so resolve them from the fragment's view tree.
        View root = recyclerView.getRootView();
        ImageView backdrop = root.findViewById(R.id.coverflowBackdrop);
        TextView title = root.findViewById(R.id.coverflowTitle);
        TextView count = root.findViewById(R.id.coverflowCount);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            backdrop.setRenderEffect(RenderEffect.createBlurEffect(64f, 64f, Shader.TileMode.CLAMP));
        }
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                updateCoverflowCenter(rv, backdrop, title, count);
            }
        });
        recyclerView.post(() -> updateCoverflowCenter(recyclerView, backdrop, title, count));
    }

    // Follow whichever cover is nearest the centre: name it, count it, and blur it behind.
    private void updateCoverflowCenter(RecyclerView rv, ImageView backdrop, TextView title, TextView count) {
        int center = rv.getWidth() / 2;
        int bestPos = RecyclerView.NO_POSITION;
        View bestChild = null;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < rv.getChildCount(); i++) {
            View c = rv.getChildAt(i);
            int mid = (c.getLeft() + c.getRight()) / 2;
            int dist = Math.abs(mid - center);
            if (dist < bestDist) {
                bestDist = dist;
                bestChild = c;
                bestPos = rv.getChildAdapterPosition(c);
            }
        }
        if (bestPos == RecyclerView.NO_POSITION || bestPos == coverflowCentered) {
            return;
        }
        coverflowCentered = bestPos;
        AppObject app = (AppObject) appGridAdapter.getItem(bestPos);
        title.setText(app.app.getAppName());
        count.setText(getString(R.string.coverflow_count, bestPos + 1, appGridAdapter.getCount()));
        ImageView art = bestChild.findViewById(R.id.grid_image);
        if (art != null && art.getDrawable() != null) {
            backdrop.setImageDrawable(art.getDrawable());
        }
        pushCompanionBrowsing(app.app);
    }

    // Spotlight the centred game on the companion panel, with whatever metadata the host gave us.
    private void pushCompanionBrowsing(NvApp app) {
        AppMetadata metadata = null;
        String uuid = app.getAppUUID();
        if (uuid != null && !uuid.isEmpty()) {
            metadata = appMetadata.get(uuid.toUpperCase());
        }
        browsingAppId = app.getAppId();
        CompanionState.getInstance().setBrowsing(app.getAppName(), metadata);

        // The hero backdrop, if this game has one, follows asynchronously.
        if (metadata != null && metadata.hasBackground()) {
            loadCompanionBackground(app.getAppId());
        }
    }

    // Fetch (or reuse) the spotlighted game's background art and hand it to the companion, but
    // only if that game is still the one centred by the time it is ready.
    private void loadCompanionBackground(final int appId) {
        Bitmap cached = backgroundCache.get(appId);
        if (cached != null) {
            if (browsingAppId == appId) {
                CompanionState.getInstance().setBrowsingBackground(cached);
            }
            return;
        }
        if (computer == null || managerBinder == null) {
            return;
        }
        new Thread(() -> {
            Bitmap bitmap = null;
            try {
                NvHTTP http = new NvHTTP(
                        ServerHelper.getCurrentAddressFromComputer(computer),
                        computer.httpsPort,
                        managerBinder.getUniqueId(),
                        computer.serverCert,
                        PlatformBinding.getCryptoProvider(AppView.this));
                bitmap = decodeSampled(http.getBackgroundArt(appId), 1600);
            } catch (Exception e) {
                LimeLog.warning("Failed to fetch background for app " + appId + ": " + e.getMessage());
            }
            final Bitmap loaded = bitmap;
            if (loaded == null) {
                return;
            }
            runOnUiThread(() -> {
                backgroundCache.put(appId, loaded);
                if (browsingAppId == appId) {
                    CompanionState.getInstance().setBrowsingBackground(loaded);
                }
            });
        }).start();
    }

    // Decode a stream down to at most maxWidth px wide, so a 4K hero image doesn't blow up memory.
    private static Bitmap decodeSampled(java.io.InputStream in, int maxWidth) throws IOException {
        try (java.io.InputStream stream = in) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            int read;
            while ((read = stream.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            byte[] bytes = buffer.toByteArray();

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);

            int sample = 1;
            while (bounds.outWidth / sample > maxWidth) {
                sample *= 2;
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        }
    }

    // Pull Playnite-enriched metadata once per visit. It's a fork-only endpoint, so this quietly
    // yields nothing on stock hosts; when it arrives we refresh whatever cover is centred.
    private void fetchAppMetadata() {
        if (metadataFetchStarted || computer == null || managerBinder == null) {
            return;
        }
        metadataFetchStarted = true;
        new Thread(() -> {
            try {
                NvHTTP http = new NvHTTP(
                        ServerHelper.getCurrentAddressFromComputer(computer),
                        computer.httpsPort,
                        managerBinder.getUniqueId(),
                        computer.serverCert,
                        PlatformBinding.getCryptoProvider(AppView.this));
                final Map<String, AppMetadata> fetched = http.getAppMetadata();
                runOnUiThread(() -> {
                    appMetadata = fetched;
                    // Re-push whatever is spotlighted — centred in the carousel, focused in the
                    // grid — so it picks up its freshly-arrived metadata.
                    for (int i = 0; i < appGridAdapter.getCount(); i++) {
                        AppObject app = (AppObject) appGridAdapter.getItem(i);
                        if (app != null && app.app.getAppId() == browsingAppId) {
                            pushCompanionBrowsing(app.app);
                            break;
                        }
                    }
                });
            } catch (Exception e) {
                LimeLog.warning("Failed to fetch app metadata: " + e.getMessage());
            }
        }).start();
    }

    private void onAppClicked(View view, int pos) {
        AppObject app = (AppObject) appGridAdapter.getItem(pos);

        // Only open the context menu if something is running, otherwise start it
        if (lastRunningAppId != 0) {
            if (prefConfig.resumeWithoutConfirm && lastRunningAppId == app.app.getAppId()) {
                ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, prefConfig.useVirtualDisplay);
            } else {
                openContextMenu(view);
            }
        } else {
            if (prefConfig.useVirtualDisplay && !(computer.vDisplaySupported && computer.vDisplayDriverReady)) {
                UiHelper.displayVdisplayConfirmationDialog(
                        AppView.this,
                        computer,
                        () -> ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, true),
                        null
                );
            } else {
                ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, prefConfig.useVirtualDisplay);
            }
        }
    }

    private void toggleViewMode() {
        coverflowMode = !coverflowMode;
        refreshViewModeControls();
        applyChromeForMode();

        try {
            getFragmentManager().beginTransaction()
                    .replace(R.id.appFragmentContainer, new AdapterFragment())
                    .commitAllowingStateLoss();
        } catch (IllegalStateException e) {
            e.printStackTrace();
        }
    }

    // The bar action and the on-screen hint both name the view you would get by using them, so
    // they always say the same thing.
    private void refreshViewModeControls() {
        int label = coverflowMode ? R.string.action_all_games : R.string.action_carousel;

        ImageButton button = findViewById(R.id.viewModeButton);
        if (button != null) {
            button.setImageResource(
                    coverflowMode ? R.drawable.ic_view_grid : R.drawable.ic_view_carousel);
            button.setContentDescription(getString(label));
        }

        TextView hint = findViewById(R.id.viewModeHintLabel);
        if (hint != null) {
            hint.setText(label);
        }
    }

    private void enterImmersive() {
        // Draw edge to edge and paint the bar regions transparent, so nothing grey is left
        // where the status bar used to sit once the bars are hidden.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);

        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());

        // notifyNewRootView pads the content by the status bar inset, and the window's grey
        // background shows through that padding. Consume the insets so the content reaches
        // the very edge and nothing grey is left at the top.
        View content = findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            v.setPadding(0, 0, 0, 0);
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(content);
    }

    // The app bar is gone in the carousel, so the covers own the whole screen; it returns
    // in the grid, where the mode toggle and profiles need to be at hand.
    private void applyChromeForMode() {
        View topBar = findViewById(R.id.topBar);
        if (topBar != null) {
            topBar.setVisibility(coverflowMode ? View.GONE : View.VISIBLE);
        }
    }

    // Return focus to the cover at the centre, rather than to the first one, when leaving
    // the revealed bar.
    private void focusCenteredCover() {
        View grid = findViewById(R.id.fragmentView);
        if (!(grid instanceof RecyclerView)) {
            return;
        }
        RecyclerView rv = (RecyclerView) grid;
        RecyclerView.ViewHolder holder = rv.findViewHolderForAdapterPosition(
                coverflowCentered >= 0 ? coverflowCentered : 0);
        if (holder != null) {
            holder.itemView.requestFocus();
        } else {
            rv.requestFocus();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // The system bars come back on a swipe or when returning from another screen;
            // put them away again.
            enterImmersive();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Y switches between the carousel and the grid from anywhere in the library. This is the
        // shortcut the on-screen hint advertises.
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_Y
                && event.getRepeatCount() == 0) {
            toggleViewMode();
            return true;
        }

        // In the carousel the app bar is hidden. Up reveals it; down from it hides it again,
        // so its actions stay reachable without stealing space from the covers.
        if (coverflowMode && event.getAction() == KeyEvent.ACTION_DOWN) {
            View topBar = findViewById(R.id.topBar);
            if (topBar != null && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP
                    && topBar.getVisibility() != View.VISIBLE) {
                topBar.setVisibility(View.VISIBLE);
                topBar.requestFocus();
                return true;
            }
            if (topBar != null && event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN
                    && topBar.getVisibility() == View.VISIBLE && topBar.hasFocus()) {
                topBar.setVisibility(View.GONE);
                focusCenteredCover();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    public static class AppObject {
        public final NvApp app;
        public boolean isRunning;
        public boolean isHidden;

        public AppObject(NvApp app) {
            if (app == null) {
                throw new IllegalArgumentException("app must not be null");
            }
            this.app = app;
        }

        @Override
        public String toString() {
            return app.getAppName();
        }
    }
}
