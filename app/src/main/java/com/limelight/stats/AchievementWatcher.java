package com.limelight.stats;

import android.os.Handler;
import android.os.Looper;

import com.limelight.LimeLog;
import com.limelight.nvstream.http.Achievement;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Watches the host for achievements unlocked while a game is streamed, for the "just unlocked"
 * toast.
 *
 * <p>During a stream it asks {@code /appachievements/recent?since=<stream start>} every thirty
 * seconds, off the main thread. SuccessStory usually refreshes only when the game closes, so the
 * library asks once more when the stream is over ({@link #takePendingCheck}); whatever was
 * already announced is remembered for the whole run, so nothing is announced twice whichever of
 * the two sees it first. What counts as news is CouchPilot's rule, in
 * {@link AchievementFreshness}.
 *
 * <p>A host without the endpoint answers the first request with a 404, and the watcher stops
 * there: one request per stream is all it costs.
 */
public class AchievementWatcher {
    public static final long POLL_SECONDS = 30;
    public static final int LIMIT = 40;

    /** Told, on the main thread, about unlocks to announce, newest first. */
    public interface Listener {
        void onFreshAchievements(List<Achievement> fresh);
    }

    // What has been announced (or judged stale) this run, across streams and the library.
    private static final Set<String> seen = Collections.synchronizedSet(new HashSet<>());

    // The stream that just ended, for the library's one last look.
    private static String pendingPcUuid;
    private static long pendingSince;

    private final HostSession session;
    private final long sinceSeconds;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ScheduledExecutorService executor;

    public AchievementWatcher(HostSession session, long sinceSeconds, Listener listener) {
        this.session = session;
        this.sinceSeconds = sinceSeconds;
        this.listener = listener;
    }

    /**
     * Start polling, and remember this stream so the library can look once more after it.
     * {@code pcUuid} may be null for a stream launched without one, which skips that last look.
     */
    public synchronized void start(String pcUuid) {
        if (executor != null) {
            return;
        }
        synchronized (AchievementWatcher.class) {
            pendingPcUuid = pcUuid;
            pendingSince = sinceSeconds;
        }
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(this::poll, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void poll() {
        if (!check(session, sinceSeconds, listener, main)) {
            LimeLog.info("Achievements: host has no recent unlocks to follow; not asking again");
            stop();
        }
    }

    /**
     * One look, blocking; call off the main thread. Returns false when the host doesn't serve
     * recent achievements at all, true otherwise (including when the request merely failed).
     */
    private static boolean check(HostSession session, long sinceSeconds, Listener listener, Handler main) {
        List<Achievement> recent;
        try {
            recent = session.http().getRecentAchievements(sinceSeconds, LIMIT);
        } catch (Exception e) {
            LimeLog.warning("Achievements: " + e.getMessage());
            return true;
        }
        if (recent == null) {
            return false;
        }
        final List<Achievement> fresh = AchievementFreshness.select(recent, seen, System.currentTimeMillis());
        if (!fresh.isEmpty()) {
            main.post(() -> listener.onFreshAchievements(fresh));
        }
        return true;
    }

    /**
     * The start of the stream that last ran against {@code pcUuid}, in unix seconds, if the
     * library has not looked after it yet; -1 otherwise. Taking it clears it.
     */
    public static synchronized long takePendingCheck(String pcUuid) {
        if (pcUuid == null || !pcUuid.equalsIgnoreCase(pendingPcUuid)) {
            return -1;
        }
        pendingPcUuid = null;
        return pendingSince;
    }

    /** Look once, in the background, and tell {@code listener} about anything not yet announced. */
    public static void checkOnce(HostSession session, long sinceSeconds, Listener listener) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> check(session, sinceSeconds, listener, main), "AchievementCheck").start();
    }
}
