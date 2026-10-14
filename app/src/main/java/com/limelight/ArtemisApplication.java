package com.limelight;

import android.app.Application;
import android.widget.Toast;

import com.limelight.binding.video.PyroWaveSupport;
import com.limelight.companion.CompanionDisplayManager;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.profiles.ProfilesManager;

public class ArtemisApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ProfilesManager profilesManager = ProfilesManager.getInstance();
        if (!profilesManager.load(this)) {
            Toast.makeText(this, R.string.profile_manager_failed_to_load, Toast.LENGTH_LONG).show();
        }

        CompanionDisplayManager.install(this);

        // The PyroWave probe creates a Vulkan device: run it now, off the UI thread, so that
        // starting a stream finds the result cached
        if (PreferenceConfiguration.isPyroWaveEnabled(this)) {
            PyroWaveSupport.probeInBackground(this);
        }
    }
}