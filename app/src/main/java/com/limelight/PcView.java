package com.limelight;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.UnknownHostException;

import com.google.android.material.button.MaterialButton;
import com.limelight.binding.PlatformBinding;
import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.grid.PcCardAdapter;
import com.limelight.grid.assets.DiskAssetLoader;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.nvstream.http.PairingManager.PairState;
import com.limelight.nvstream.wol.WakeOnLanSender;
import com.limelight.preferences.AddComputerManually;
import com.limelight.preferences.GlPreferences;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.StreamSettings;
import com.limelight.profiles.ProfilesManager;
import com.limelight.ui.AdapterFragment;
import com.limelight.ui.AdapterFragmentCallbacks;
import com.limelight.utils.CacheHelper;
import com.limelight.utils.Dialog;
import com.limelight.utils.HelpLauncher;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.UiHelper;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.View.OnClickListener;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Toast;
import android.widget.AdapterView.AdapterContextMenuInfo;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import org.xmlpull.v1.XmlPullParserException;

import java.io.InputStream;
import java.io.StringReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class PcView extends AppCompatActivity implements AdapterFragmentCallbacks {
    /** The PC whose library was last opened from here. See maybeEnterLastPc. */
    private static final String LAST_PC_PREF_STRING = "last_computer_uuid";

    /**
     * Set by AppView when the user asks for this screen from the library: they came here to look
     * at their PCs, so this launch must not send them straight back.
     */
    public static final String SKIP_AUTO_ENTER_EXTRA = "SkipAutoEnter";

    /** Games named from a host's app list, keyed by host and app id; see resolveRunningAppName. */
    private final HashMap<String, String> runningAppNames = new HashMap<>();

    /** Hero images already asked of a host, keyed the same way; see fetchRunningBackground. */
    private final HashSet<String> backgroundsRequested = new HashSet<>();
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();

    private View noPcFoundLayout;
    private RecyclerView pcRecyclerView;
    private PcCardAdapter pcCardAdapter;
    private ShortcutHelper shortcutHelper;
    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private boolean freezeUpdates, runningPolling, inForeground, completeOnCreateCalled;
    private boolean autoEnterArmed;
    private ComputerDetails.AddressTuple pendingPairingAddress;
    private String pendingPairingPin, pendingPairingPassphrase;
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

                    // Now make the binder visible
                    managerBinder = localBinder;

                    // Start updates
                    startComputerUpdates();

                    // Force a keypair to be generated early to avoid discovery delays
                    new AndroidCryptoProvider(PcView.this).getClientCertificate();
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

        // Only reinitialize views if completeOnCreate() was called
        // before this callback. If it was not, completeOnCreate() will
        // handle initializing views with the config change accounted for.
        // This is not prone to races because both callbacks are invoked
        // in the main thread.
        if (completeOnCreateCalled) {
            // Reinitialize views just in case orientation changed
            initializeViews();
        }

        refreshProfileButton();
    }

    private final static int PAIR_ID = 2;
    private final static int UNPAIR_ID = 3;
    private final static int WOL_ID = 4;
    private final static int DELETE_ID = 5;
    private final static int RESUME_ID = 6;
    private final static int QUIT_ID = 7;
    private final static int VIEW_DETAILS_ID = 8;
    private final static int FULL_APP_LIST_ID = 9;
    private final static int TEST_NETWORK_ID = 10;
    private final static int GAMESTREAM_EOL_ID = 11;
    private final static int OPEN_MANAGEMENT_PAGE_ID = 20;
    private final static int PAIR_ID_OTP = 21;

    private void initializeViews() {
        setContentView(R.layout.activity_pc_view);

        UiHelper.notifyNewRootView(this);

        // Allow floating expanded PiP overlays while browsing PCs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false);
        }

        // Set default preferences if we've never been run
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

        // Setup the list view
        ImageButton settingsButton = findViewById(R.id.settingsButton);
        ImageButton addComputerButton = findViewById(R.id.manuallyAddPc);
        ImageButton helpButton = findViewById(R.id.helpButton);
        MaterialButton profilesButton = findViewById(R.id.profilesButton);

        settingsButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(PcView.this, StreamSettings.class));
            }
        });
        addComputerButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(PcView.this, AddComputerManually.class);
                startActivity(i);
            }
        });
        helpButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                HelpLauncher.launchSetupGuide(PcView.this);
            }
        });
        profilesButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(PcView.this, ProfilesActivity.class));
            }
        });

        // Amazon review didn't like the help button because the wiki was not entirely
        // navigable via the Fire TV remote (though the relevant parts were). Let's hide
        // it on Fire TV.
        if (getPackageManager().hasSystemFeature("amazon.hardware.fire_tv")) {
            helpButton.setVisibility(View.GONE);
        }

        getFragmentManager().beginTransaction()
            .replace(R.id.pcFragmentContainer, new AdapterFragment())
            .commitAllowingStateLoss();

        noPcFoundLayout = findViewById(R.id.no_pc_found_layout);
        if (pcCardAdapter.getCount() == 0) {
            noPcFoundLayout.setVisibility(View.VISIBLE);
        }
        else {
            noPcFoundLayout.setVisibility(View.INVISIBLE);
        }
        pcCardAdapter.notifyDataSetChanged();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Assume we're in the foreground when created to avoid a race
        // between binding to CMS and onResume()
        inForeground = true;

        // Create a GLSurfaceView to fetch GLRenderer unless we have
        // a cached result already.
        final GlPreferences glPrefs = GlPreferences.readPreferences(this);
        if (!glPrefs.savedFingerprint.equals(Build.FINGERPRINT) || glPrefs.glRenderer.isEmpty()) {
            GLSurfaceView surfaceView = new GLSurfaceView(this);
            surfaceView.setRenderer(new GLSurfaceView.Renderer() {
                @Override
                public void onSurfaceCreated(GL10 gl10, EGLConfig eglConfig) {
                    // Save the GLRenderer string so we don't need to do this next time
                    glPrefs.glRenderer = gl10.glGetString(GL10.GL_RENDERER);
                    glPrefs.savedFingerprint = Build.FINGERPRINT;
                    glPrefs.writePreferences();

                    LimeLog.info("Fetched GL Renderer: " + glPrefs.glRenderer);

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            completeOnCreate();
                        }
                    });
                }

                @Override
                public void onSurfaceChanged(GL10 gl10, int i, int i1) {
                }

                @Override
                public void onDrawFrame(GL10 gl10) {
                }
            });
            setContentView(surfaceView);
        }
        else {
            LimeLog.info("Cached GL Renderer: " + glPrefs.glRenderer);
            completeOnCreate();
        }

        Intent intent = getIntent();

        String hostname = intent.getStringExtra("hostname");
        int port = intent.getIntExtra("port", NvHTTP.DEFAULT_HTTP_PORT);
        pendingPairingPin = intent.getStringExtra("pin");
        pendingPairingPassphrase = intent.getStringExtra("passphrase");

        if (hostname != null && pendingPairingPin != null && pendingPairingPassphrase != null) {
            pendingPairingAddress = new ComputerDetails.AddressTuple(hostname, port);
        } else {
            pendingPairingPin = null;
            pendingPairingPassphrase = null;
        }
    }

    private void completeOnCreate() {
        completeOnCreateCalled = true;

        shortcutHelper = new ShortcutHelper(this);

        UiHelper.setLocale(this);

        // Bind to the computer manager service
        bindService(new Intent(PcView.this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);

        pcCardAdapter = new PcCardAdapter(this);

        // Armed for this launch only: once we have gone through to a library, or once the user
        // has touched this screen, the choice is theirs and we stay out of it.
        autoEnterArmed = PreferenceConfiguration.readPreferences(this).autoEnterLastPc
                && !getIntent().getBooleanExtra(SKIP_AUTO_ENTER_EXTRA, false);

        initializeViews();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        // Reached from the library's own action: the user wants to be here.
        if (intent.getBooleanExtra(SKIP_AUTO_ENTER_EXTRA, false)) {
            autoEnterArmed = false;
        }
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();

        // The user is driving. Do not pull the screen out from under them.
        autoEnterArmed = false;
    }

    /**
     * Go straight to the library of the PC whose library was opened last, when that PC is one of
     * the ones we have just found, and it is up and paired.
     *
     * This screen is a gate rather than a destination, and this is what stops it from being a
     * toll on every launch. Back from the library lands here, disarmed, so choosing another PC
     * costs one press.
     */
    private void maybeEnterLastPc() {
        if (!autoEnterArmed || !inForeground || pendingPairingAddress != null) {
            return;
        }

        String lastUuid = PreferenceManager.getDefaultSharedPreferences(this)
                .getString(LAST_PC_PREF_STRING, null);
        if (lastUuid == null) {
            return;
        }

        for (int i = 0; i < pcCardAdapter.getCount(); i++) {
            ComputerObject computer = (ComputerObject) pcCardAdapter.getItem(i);
            if (!lastUuid.equals(computer.details.uuid)) {
                continue;
            }

            // Found it, but it is not ready yet. Stay armed: it may still come up while the
            // user is looking at this screen, and going in then is exactly what they asked for.
            if (computer.details.state != ComputerDetails.State.ONLINE ||
                computer.details.pairState != PairState.PAIRED) {
                return;
            }

            autoEnterArmed = false;
            doAppList(computer.details, false, false);
            return;
        }
    }

    private void startComputerUpdates() {
        // Only allow polling to start if we're bound to CMS, polling is not already running,
        // and our activity is in the foreground.
        if (managerBinder != null && !runningPolling && inForeground) {
            freezeUpdates = false;
            managerBinder.startPolling(new ComputerManagerListener() {
                @Override
                public void notifyComputerUpdated(final ComputerDetails details) {
                    if (!freezeUpdates) {
                        // Name the running game here, on the polling thread: it means parsing the
                        // host's app list, which has no business happening on the main thread.
                        final String runningAppName = resolveRunningAppName(details);

                        PcView.this.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                updateComputer(details, runningAppName);
                            }
                        });

                        // Add a launcher shortcut for this PC (off the main thread to prevent ANRs)
                        if (details.pairState == PairState.PAIRED) {
                            shortcutHelper.createAppViewShortcutForOnlineHost(details);
//                        } else
                        }
                            if (pendingPairingAddress != null) {
                            if (
                                details.state == ComputerDetails.State.ONLINE &&
                                details.activeAddress.equals(pendingPairingAddress)
                            ) {
                                PcView.this.runOnUiThread(() -> {
                                    doPair(details, pendingPairingPin, pendingPairingPassphrase);
                                    pendingPairingAddress = null;
                                    pendingPairingPin = null;
                                    pendingPairingPassphrase = null;
                                });
                            }
                        }
                    }
                }
            });
            runningPolling = true;
        }
    }

    private void stopComputerUpdates(boolean wait) {
        if (managerBinder != null) {
            if (!runningPolling) {
                return;
            }

            freezeUpdates = true;

            managerBinder.stopPolling();

            if (wait) {
                managerBinder.waitForPollingStopped();
            }

            runningPolling = false;
        }
    }

    private void refreshProfileButton() {
        MaterialButton profilesButton = findViewById(R.id.profilesButton);
        // User report Samsung and Xiaomi devices have this problem
        // Why just these two brands have the most problems?
        if (profilesButton == null) {
            return;
        }
        // With no active profile the button is its icon alone, as the other actions are.
        profilesButton.setText(ProfilesManager.getInstance().getActiveName());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (managerBinder != null) {
            unbindService(serviceConnection);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Display a decoder crash notification if we've returned after a crash
        UiHelper.showDecoderCrashDialog(this);

        refreshProfileButton();

        inForeground = true;
        startComputerUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();

        inForeground = false;
        stopComputerUpdates(false);
    }

    @Override
    protected void onStop() {
        super.onStop();

        Dialog.closeDialogs();
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        stopComputerUpdates(false);

        // Call superclass
        super.onCreateContextMenu(menu, v, menuInfo);

        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        ComputerObject computer = (ComputerObject) pcCardAdapter.getItem(info.position);

        // Add a header with PC status details
        menu.clearHeader();
        String headerTitle = computer.details.name + " - ";
        switch (computer.details.state)
        {
            case ONLINE:
                headerTitle += getResources().getString(R.string.pcview_menu_header_online);
                break;
            case OFFLINE:
                menu.setHeaderIcon(R.drawable.ic_pc_offline);
                headerTitle += getResources().getString(R.string.pcview_menu_header_offline);
                break;
            case UNKNOWN:
                headerTitle += getResources().getString(R.string.pcview_menu_header_unknown);
                break;
        }

        menu.setHeaderTitle(headerTitle);

        // Inflate the context menu
        if (computer.details.state == ComputerDetails.State.OFFLINE ||
            computer.details.state == ComputerDetails.State.UNKNOWN) {
            menu.add(Menu.NONE, WOL_ID, 1, getResources().getString(R.string.pcview_menu_send_wol));
            menu.add(Menu.NONE, GAMESTREAM_EOL_ID, 2, getResources().getString(R.string.pcview_menu_eol));
        }
        else if (computer.details.pairState != PairState.PAIRED) {
            menu.add(Menu.NONE, PAIR_ID_OTP, 1, getResources().getString(R.string.pcview_menu_pair_pc_otp));
            menu.add(Menu.NONE, PAIR_ID, 2, getResources().getString(R.string.pcview_menu_pair_pc));
            if (computer.details.nvidiaServer) {
                menu.add(Menu.NONE, GAMESTREAM_EOL_ID, 3, getResources().getString(R.string.pcview_menu_eol));
            } else {
                menu.add(Menu.NONE, OPEN_MANAGEMENT_PAGE_ID, 3, getResources().getString(R.string.pcview_menu_open_management_page));
            }
        }
        else {
            if (computer.details.runningGameId != 0) {
                menu.add(Menu.NONE, RESUME_ID, 1, getResources().getString(R.string.applist_menu_resume));
                menu.add(Menu.NONE, QUIT_ID, 2, getResources().getString(R.string.applist_menu_quit));
            }

            if (computer.details.nvidiaServer) {
                menu.add(Menu.NONE, GAMESTREAM_EOL_ID, 3, getResources().getString(R.string.pcview_menu_eol));
            } else {
                menu.add(Menu.NONE, OPEN_MANAGEMENT_PAGE_ID, 3, getResources().getString(R.string.pcview_menu_open_management_page));
            }

            menu.add(Menu.NONE, FULL_APP_LIST_ID, 4, getResources().getString(R.string.pcview_menu_app_list));
        }

        menu.add(Menu.NONE, TEST_NETWORK_ID, 5, getResources().getString(R.string.pcview_menu_test_network));
        menu.add(Menu.NONE, DELETE_ID, 6, getResources().getString(R.string.pcview_menu_delete_pc));
        menu.add(Menu.NONE, VIEW_DETAILS_ID, 7,  getResources().getString(R.string.pcview_menu_details));
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        // For some reason, this gets called again _after_ onPause() is called on this activity.
        // startComputerUpdates() manages this and won't actual start polling until the activity
        // returns to the foreground.
        startComputerUpdates();
    }

    private void doPair(final ComputerDetails computer, String otp, String passphrase) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.pair_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(PcView.this, getResources().getString(R.string.pairing), Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn;
                String message;
                boolean success = false;
                try {
                    // Stop updates and wait while pairing
                    stopComputerUpdates(true);

                    httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                            computer.httpsPort, managerBinder.getUniqueId(), computer.serverCert,
                            PlatformBinding.getCryptoProvider(PcView.this));
                    if (httpConn.getPairState() == PairState.PAIRED) {
                        // Don't display any toast, but open the app list
                        message = null;
                        success = true;
                    }
                    else {
                        String pinStr = otp;
                        if (pinStr == null) {
                            pinStr = PairingManager.generatePinString();
                        }

                        // Spin the dialog off in a thread because it blocks
                        if (passphrase == null) {
                            Dialog.displayDialog(PcView.this, getResources().getString(R.string.pair_pairing_title),
                                    getResources().getString(R.string.pair_pairing_msg)+" "+pinStr+"\n\n"+
                                            getResources().getString(R.string.pair_pairing_help), false);
                        } else {
                            Dialog.displayDialog(PcView.this, getResources().getString(R.string.pair_pairing_title),
                                    getResources().getString(R.string.pair_otp_pairing_msg)+"\n\n"+
                                            getResources().getString(R.string.pair_otp_pairing_help), false);
                        }

                        PairingManager pm = httpConn.getPairingManager();

                        PairState pairState = pm.pair(httpConn.getServerInfo(true), pinStr, passphrase);
                        if (pairState == PairState.PIN_WRONG) {
                            message = getResources().getString(R.string.pair_incorrect_pin);
                        }
                        else if (pairState == PairState.FAILED) {
                            if (computer.runningGameId != 0) {
                                message = getResources().getString(R.string.pair_pc_ingame);
                            }
                            else {
                                message = getResources().getString(R.string.pair_fail);
                            }
                        }
                        else if (pairState == PairState.ALREADY_IN_PROGRESS) {
                            message = getResources().getString(R.string.pair_already_in_progress);
                        }
                        else if (pairState == PairState.PAIRED) {
                            // Just navigate to the app view without displaying a toast
                            message = null;
                            success = true;

                            // Pin this certificate for later HTTPS use
                            managerBinder.getComputer(computer.uuid).serverCert = pm.getPairedCert();

                            // Invalidate reachability information after pairing to force
                            // a refresh before reading pair state again
                            managerBinder.invalidateStateForComputer(computer.uuid);
                        }
                        else {
                            // Should be no other values
                            message = null;
                        }
                    }
                } catch (UnknownHostException e) {
                    message = getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    message = getResources().getString(R.string.error_404);
                } catch (XmlPullParserException | IOException e) {
                    e.printStackTrace();
                    message = e.getMessage();
                }

                Dialog.closeDialogs();

                final String toastMessage = message;
                final boolean toastSuccess = success;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (toastMessage != null) {
                            Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show();
                        }

                        if (toastSuccess) {
                            // Open the app list after a successful pairing attempt
                            doAppList(computer, true, false);
                        }
                        else {
                            // Start polling again if we're still in the foreground
                            startComputerUpdates();
                        }
                    }
                });
            }
        }).start();
    }

    private void doOTPPair(final ComputerDetails computer) {
        Context context = PcView.this;

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 40);

        final EditText otpInput = new EditText(context);
        otpInput.setHint("PIN");
        otpInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        otpInput.setFilters(new InputFilter[] { new InputFilter.LengthFilter(4) });

        final EditText passphraseInput = new EditText(context);
        passphraseInput.setHint(getString(R.string.pair_passphrase_hint));
        passphraseInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        layout.addView(otpInput);
        layout.addView(passphraseInput);

        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(context);
        dialogBuilder.setTitle(R.string.pcview_menu_pair_pc_otp);
        dialogBuilder.setView(layout);

        dialogBuilder.setPositiveButton(getString(R.string.proceed), null);

        dialogBuilder.setNegativeButton(getString(R.string.cancel), (dialog, which) -> dialog.dismiss());
        AlertDialog dialog = dialogBuilder.create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String pin = otpInput.getText().toString();
            String passphrase = passphraseInput.getText().toString();
            if (pin.length() != 4) {
                Toast.makeText(context, getString(R.string.pair_pin_length_msg), Toast.LENGTH_SHORT).show();
                return;
            }
            if (passphrase.length() < 4 ) {
                Toast.makeText(context, getString(R.string.pair_passphrase_length_msg), Toast.LENGTH_SHORT).show();
                return;
            }
            doPair(computer, pin, passphrase);
            dialog.dismiss(); // Manually dismiss the dialog if the input is valid
        });
    }

    private void doWakeOnLan(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.ONLINE) {
            Toast.makeText(PcView.this, getResources().getString(R.string.wol_pc_online), Toast.LENGTH_SHORT).show();
            return;
        }

        if (computer.macAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.wol_no_mac), Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                String message;
                try {
                    WakeOnLanSender.sendWolPacket(computer);
                    message = getResources().getString(R.string.wol_waking_msg);
                } catch (IOException e) {
                    message = getResources().getString(R.string.wol_fail);
                }

                final String toastMessage = message;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private void doUnpair(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(PcView.this, getResources().getString(R.string.unpairing), Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn;
                String message;
                try {
                    httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                            computer.httpsPort, managerBinder.getUniqueId(), computer.serverCert,
                            PlatformBinding.getCryptoProvider(PcView.this));
                    if (httpConn.getPairState() == PairState.PAIRED) {
                        httpConn.unpair();
                        if (httpConn.getPairState() == PairState.NOT_PAIRED) {
                            message = getResources().getString(R.string.unpair_success);
                        }
                        else {
                            message = getResources().getString(R.string.unpair_fail);
                        }
                    }
                    else {
                        message = getResources().getString(R.string.unpair_error);
                    }
                } catch (UnknownHostException e) {
                    message = getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    message = getResources().getString(R.string.error_404);
                } catch (XmlPullParserException | IOException e) {
                    message = e.getMessage();
                    e.printStackTrace();
                }

                final String toastMessage = message;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private void doAppList(ComputerDetails computer, boolean newlyPaired, boolean showHiddenGames) {
        if (computer.state == ComputerDetails.State.OFFLINE) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        // The PC we go back to on the next launch, unless the user says otherwise.
        PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putString(LAST_PC_PREF_STRING, computer.uuid)
                .apply();

        Intent i = new Intent(this, AppView.class);
        i.putExtra(AppView.NAME_EXTRA, computer.name);
        i.putExtra(AppView.UUID_EXTRA, computer.uuid);
        i.putExtra(AppView.NEW_PAIR_EXTRA, newlyPaired);
        i.putExtra(AppView.SHOW_HIDDEN_APPS_EXTRA, showHiddenGames);
        startActivity(i);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
        final ComputerObject computer = (ComputerObject) pcCardAdapter.getItem(info.position);
        switch (item.getItemId()) {
            case PAIR_ID:
                doPair(computer.details, null, null);
                return true;

            case PAIR_ID_OTP:
                doOTPPair(computer.details);
                return true;

            case UNPAIR_ID:
                doUnpair(computer.details);
                return true;

            case WOL_ID:
                doWakeOnLan(computer.details);
                return true;

            case DELETE_ID:
                if (ActivityManager.isUserAMonkey()) {
                    LimeLog.info("Ignoring delete PC request from monkey");
                    return true;
                }
                UiHelper.displayDeletePcConfirmationDialog(this, computer.details, new Runnable() {
                    @Override
                    public void run() {
                        if (managerBinder == null) {
                            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                            return;
                        }
                        removeComputer(computer.details);
                    }
                }, null);
                return true;

            case FULL_APP_LIST_ID:
                doAppList(computer.details, false, true);
                return true;

            case RESUME_ID:
                if (managerBinder == null) {
                    Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                    return true;
                }

                ServerHelper.doStart(this, new NvApp("app", null, computer.details.runningGameId, false), computer.details, managerBinder, false);
                return true;

            case QUIT_ID:
                if (managerBinder == null) {
                    Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                    return true;
                }

                // Display a confirmation dialog first
                UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                    @Override
                    public void run() {
                        ServerHelper.doQuit(PcView.this, computer.details,
                                new NvApp("app", null, 0, false), managerBinder, null);
                    }
                }, null);
                return true;

            case VIEW_DETAILS_ID:
                Dialog.displayDialog(PcView.this, getResources().getString(R.string.title_details), computer.details.toString(), false);
                return true;

            case TEST_NETWORK_ID:
                ServerHelper.doNetworkTest(PcView.this);
                return true;

            case GAMESTREAM_EOL_ID:
                HelpLauncher.launchGameStreamEolFaq(PcView.this);
                return true;

            case OPEN_MANAGEMENT_PAGE_ID:
                String managementUrl = computer.guessManagementUrl();
                if (managementUrl == null) {
                    Toast.makeText(PcView.this, getResources().getString(R.string.pcview_error_no_management_url), Toast.LENGTH_LONG).show();
                } else {
                    HelpLauncher.launchUrl(PcView.this, managementUrl);
                }

            default:
                return super.onContextItemSelected(item);
        }
    }

    private void removeComputer(ComputerDetails details) {
        managerBinder.removeComputer(details);

        new DiskAssetLoader(this).deleteAssetsForComputer(details.uuid);

        // Delete hidden games preference value
        getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .edit()
                .remove(details.uuid)
                .apply();

        for (int i = 0; i < pcCardAdapter.getCount(); i++) {
            ComputerObject computer = (ComputerObject) pcCardAdapter.getItem(i);

            if (details.equals(computer.details)) {
                // Disable or delete shortcuts referencing this PC
                shortcutHelper.disableComputerShortcut(details,
                        getResources().getString(R.string.scut_deleted_pc));

                pcCardAdapter.removeComputer(computer);
                pcCardAdapter.notifyDataSetChanged();

                if (pcCardAdapter.getCount() == 0) {
                    // Show the "Discovery in progress" view
                    noPcFoundLayout.setVisibility(View.VISIBLE);
                }

                break;
            }
        }
    }

    /**
     * The name of the game the host is in, or null: for a host that is idle, for one whose app
     * list we have never seen, and for one whose running game is not in the list we have.
     *
     * A newer Sunshine host names the game in its serverinfo and there is nothing to work out.
     * For any other host, this screen has to find the name itself, and it does not poll for app
     * lists — AppView does — so the list comes from the cache AppView left behind, as it does for
     * a launcher shortcut. A host whose library has never been opened, running a game, leaves the
     * card saying only that a game is up.
     */
    private String resolveRunningAppName(ComputerDetails details) {
        if (details.runningGameId == 0) {
            return null;
        }

        if (details.runningGameName != null) {
            return details.runningGameName;
        }

        final String key = details.uuid + ":" + details.runningGameId;
        synchronized (runningAppNames) {
            String known = runningAppNames.get(key);
            if (known != null) {
                return known;
            }
        }

        String rawAppList = details.rawAppList;
        if (rawAppList == null) {
            try (InputStream in = CacheHelper.openCacheFileForInput(getCacheDir(), "applist", details.uuid)) {
                rawAppList = CacheHelper.readInputStreamToString(in);
            } catch (IOException e) {
                // No list for this host yet.
                return null;
            }
        }

        if (rawAppList.isEmpty()) {
            return null;
        }

        try {
            List<NvApp> apps = NvHTTP.getAppListByReader(new StringReader(rawAppList));
            for (NvApp app : apps) {
                if (app.getAppId() == details.runningGameId) {
                    synchronized (runningAppNames) {
                        runningAppNames.put(key, app.getAppName());
                    }
                    return app.getAppName();
                }
            }
        } catch (XmlPullParserException | IOException e) {
            // The card falls back to saying only that a game is running.
            LimeLog.warning("Unable to name the running game: " + e.getMessage());
        }

        return null;
    }

    private void updateComputer(ComputerDetails details, String runningAppName) {
        ComputerObject existingEntry = null;

        for (int i = 0; i < pcCardAdapter.getCount(); i++) {
            ComputerObject computer = (ComputerObject) pcCardAdapter.getItem(i);

            // Check if this is the same computer
            if (details.uuid.equals(computer.details.uuid)) {
                existingEntry = computer;
                break;
            }
        }

        if (existingEntry != null) {
            // The hero image belongs to the game that was running, not to the host.
            if (existingEntry.details.runningGameId != details.runningGameId) {
                existingEntry.runningBackground = null;
            }

            // Replace the information in the existing entry
            existingEntry.details = details;
            existingEntry.runningAppName = runningAppName;
        }
        else {
            // Add a new entry
            existingEntry = new ComputerObject(details);
            existingEntry.runningAppName = runningAppName;
            pcCardAdapter.addComputer(existingEntry);

            // Remove the "Discovery in progress" view
            noPcFoundLayout.setVisibility(View.INVISIBLE);
        }

        // Notify the view that the data has changed
        pcCardAdapter.notifyDataSetChanged();

        fetchRunningBackground(existingEntry);

        landFocusOnFirstCard();

        maybeEnterLastPc();
    }

    /**
     * Fetch the hero image of the game a host is running, which the card bleeds across itself.
     *
     * Only newer Sunshine hosts serve one, and only for a game that has one, so a failure is
     * ordinary and silent: the card falls back to the box art. Fetched once per game — the poll
     * comes round every few seconds, and this is a picture, not a status.
     */
    private void fetchRunningBackground(final ComputerObject computer) {
        final ComputerDetails details = computer.details;

        if (details.runningGameId == 0 || details.state != ComputerDetails.State.ONLINE ||
                details.pairState != PairState.PAIRED || managerBinder == null) {
            return;
        }

        final String key = details.uuid + ":" + details.runningGameId;
        if (computer.runningBackground != null || !backgroundsRequested.add(key)) {
            return;
        }

        backgroundExecutor.execute(() -> {
            Bitmap loaded = null;
            try {
                NvHTTP httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(details),
                        details.httpsPort, managerBinder.getUniqueId(), details.serverCert,
                        PlatformBinding.getCryptoProvider(PcView.this));

                try (InputStream in = httpConn.getBackgroundArt(details.runningGameId)) {
                    // A hero image is made to fill a screen; this one fills a strip of a card.
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = 2;
                    loaded = BitmapFactory.decodeStream(in, null, options);
                }
            } catch (Exception e) {
                LimeLog.info("No hero image for the running game: " + e.getMessage());
            }

            if (loaded == null) {
                return;
            }

            final Bitmap background = loaded;
            runOnUiThread(() -> {
                // The host may have moved on to another game while we were fetching.
                if (key.equals(computer.details.uuid + ":" + computer.details.runningGameId)) {
                    computer.runningBackground = background;
                    pcCardAdapter.notifyDataSetChanged();
                }
            });
        });
    }

    @Override
    public int getAdapterFragmentLayoutId() {
        return R.layout.pc_card_list;
    }

    @Override
    public void receiveRecyclerView(RecyclerView recyclerView) {
        pcRecyclerView = recyclerView;

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(pcCardAdapter);
        pcCardAdapter.setItemsFocusableInTouchMode(UiHelper.isGamepadConnected());

        // One centred column of cards, which stop widening at pc_card_max_width: the side
        // padding is what centres them, and what keeps a card on a TV from becoming a banner.
        final int maxWidth = getResources().getDimensionPixelSize(R.dimen.pc_card_max_width);
        final int minInset = getResources().getDimensionPixelSize(R.dimen.pc_card_spacing);
        recyclerView.addOnLayoutChangeListener((v, left, top, right, bottom,
                                                oldLeft, oldTop, oldRight, oldBottom) -> {
            int inset = Math.max(minInset, ((right - left) - maxWidth) / 2);
            if (v.getPaddingLeft() != inset) {
                v.setPadding(inset, v.getPaddingTop(), inset, v.getPaddingBottom());
            }
        });

        pcCardAdapter.setOnItemClickListener((view, pos) -> {
            ComputerObject computer = (ComputerObject) pcCardAdapter.getItem(pos);
            if (computer.details.state == ComputerDetails.State.UNKNOWN ||
                computer.details.state == ComputerDetails.State.OFFLINE) {
                // Open the context menu if a PC is offline or refreshing
                openContextMenu(view);
            } else if (computer.details.pairState != PairState.PAIRED) {
                // Pair an unpaired machine by default
                doPair(computer.details, null, null);
            } else {
                doAppList(computer.details, false, false);
            }
        });

        // The card's own action: the one thing that PC is waiting for.
        pcCardAdapter.setOnActionClickListener((computer, action) -> {
            switch (action) {
                case PcCardAdapter.ACTION_WAKE:
                    doWakeOnLan(computer.details);
                    break;

                case PcCardAdapter.ACTION_PAIR:
                    doPair(computer.details, null, null);
                    break;

                case PcCardAdapter.ACTION_RESUME:
                    if (managerBinder == null) {
                        Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                        break;
                    }

                    ServerHelper.doStart(this, new NvApp("app", null, computer.details.runningGameId, false),
                            computer.details, managerBinder, false);
                    break;
            }
        });

        UiHelper.applyStatusBarPadding(recyclerView);
        registerForContextMenu(recyclerView);
        // Otherwise the first press of the d-pad lands on the actions in the top bar
        // rather than on the PCs, which are what this screen is for. The list is empty at this
        // point, so this focuses the RecyclerView itself; landFocusOnFirstCard hands the focus
        // down to a card as soon as there is one.
        recyclerView.requestFocus();
    }

    /**
     * Move the focus from the empty list onto the first PC, once a first PC exists.
     *
     * The RecyclerView takes focus while it has no children, and does not pass it on when they
     * arrive: without this, the first press of the d-pad is spent reclaiming focus rather than
     * moving, exactly as it was on the carousel. Only ever taken from the RecyclerView itself,
     * so a user who has already moved to a card or to the top bar keeps where they are.
     */
    private void landFocusOnFirstCard() {
        if (pcRecyclerView == null || pcCardAdapter.getCount() == 0) {
            return;
        }

        if (pcRecyclerView.findFocus() != pcRecyclerView) {
            return;
        }

        // One more pass, so the holder for the card exists to be focused.
        pcRecyclerView.post(() -> {
            if (pcRecyclerView.findFocus() != pcRecyclerView) {
                return;
            }

            RecyclerView.ViewHolder holder = pcRecyclerView.findViewHolderForAdapterPosition(0);
            if (holder != null) {
                holder.itemView.requestFocus();
            }
        });
    }

    public static class ComputerObject {
        public ComputerDetails details;

        /** The game this PC is in, named from its app list; null when it is idle. */
        public String runningAppName;

        /** That game's hero image, fetched from the host; null until it arrives, or if it has none. */
        public Bitmap runningBackground;

        public ComputerObject(ComputerDetails details) {
            if (details == null) {
                throw new IllegalArgumentException("details must not be null");
            }
            this.details = details;
        }

        @Override
        public String toString() {
            return details.name;
        }
        public String guessManagementUrl() {
            if (details.activeAddress == null) return null;
            return "https://" + details.activeAddress.address + ":" + (details.guessExternalPort() + 1);
        }
    }
}
