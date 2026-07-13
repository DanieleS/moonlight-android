package com.limelight.nvstream.http;

import com.limelight.LimeLog;

public class NvApp {
    public static final String REMOTE_INPUT_UUID = "8CB5C136-DA67-4F99-B4A1-F9CD35005CF4";
    // Vibepollo assigns this fixed UUID to its auto-generated "Virtual Display" entry
    // (VIRTUAL_DISPLAY_UUID in the host's process.h). We recognise it to pull the entry out
    // of the library and offer it as a dedicated app-bar action instead of a cover.
    public static final String VIRTUAL_DISPLAY_UUID = "8902CB19-674A-403D-A587-41B092E900BA";
    private String appName = "";
    private String appUUID = "";
    private int appId;
    private int appIndex;
    private boolean initialized;
    private boolean hdrSupported;
    
    public NvApp() {}
    
    public NvApp(String appName) {
        this.appName = appName;
    }
    
    public NvApp(String appName, String appUUID, int appId, boolean hdrSupported) {
        this.appName = appName;
        this.appUUID = appUUID;
        this.appId = appId;
        this.hdrSupported = hdrSupported;
        this.initialized = true;
    }
    
    public void setAppName(String appName) {
        this.appName = appName;
    }

    public void setAppUUID(String appUUID) {
        this.appUUID = appUUID;
    }
    
    public void setAppId(String appId) {
        try {
            this.appId = Integer.parseInt(appId);
            this.initialized = true;
        } catch (NumberFormatException e) {
            LimeLog.warning("Malformed app ID: "+appId);
        }
    }

    public void setAppIndex(String appIndex) {
        try {
            this.appIndex = Integer.parseInt(appIndex);
            this.initialized = true;
        } catch (NumberFormatException e) {
            LimeLog.warning("Malformed app index: "+appIndex);
        }
    }

    public void setAppId(int appId) {
        this.appId = appId;
        this.initialized = true;
    }

    public void setAppIndex(int appIndex) {
        this.appIndex = appIndex;
    }

    public void setHdrSupported(boolean hdrSupported) {
        this.hdrSupported = hdrSupported;
    }
    
    public String getAppName() {
        return this.appName;
    }

    public String getAppUUID() {
        return this.appUUID;
    }
    
    public int getAppId() {
        return this.appId;
    }

    public int getAppIndex() {
        return this.appIndex;
    }

    public boolean isHdrSupported() {
        return this.hdrSupported;
    }
    
    public boolean isInitialized() {
        return this.initialized;
    }

    public boolean isVirtualDisplay() {
        return VIRTUAL_DISPLAY_UUID.equalsIgnoreCase(this.appUUID);
    }

    @Override
    public String toString() {
        StringBuilder str = new StringBuilder();
        str.append("Name: ").append(appName).append("\n");
        str.append("UUID: ").append(appUUID).append("\n");
        str.append("ID: ").append(appId).append("\n");
        str.append("HDR Supported: ").append(hdrSupported ? "Yes" : "Unknown").append("\n");
        return str.toString();
    }
}
