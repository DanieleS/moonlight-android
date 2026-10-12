package com.limelight.nvstream;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReconnectPolicyTest {
    @Test
    public void hostEndsAreNeverReconnected() {
        // The game quit, early termination, protected content, frame conversion: even if the
        // network happened to be bad at the time, the host meant it
        for (int code : new int[] {0, -102, -103, -104}) {
            assertFalse(ReconnectPolicy.isNetworkDrop(code, true));
            assertFalse(ReconnectPolicy.isNetworkDrop(code, false));
        }
    }

    @Test
    public void aDeadPeerIsADropOnlyAfterNetworkTrouble() {
        // ENet timing out after a silence is the drop this is for; the same -1 on a healthy link
        // is the host disconnecting on purpose
        assertTrue(ReconnectPolicy.isNetworkDrop(-1, true));
        assertFalse(ReconnectPolicy.isNetworkDrop(-1, false));
    }

    @Test
    public void socketErrorsNeedNetworkTroubleToo() {
        // 101 is ENETUNREACH, but a positive code may also be a host termination reason
        assertTrue(ReconnectPolicy.isNetworkDrop(101, true));
        assertFalse(ReconnectPolicy.isNetworkDrop(101, false));
    }

    @Test
    public void noCompleteFrameIsADropOnlyAfterNetworkTrouble() {
        // -101 comes right after a connection or resume, while a link that just came back may
        // still be unsteady; without a silence or a network loss it is something else
        assertTrue(ReconnectPolicy.isNetworkDrop(-101, true));
        assertFalse(ReconnectPolicy.isNetworkDrop(-101, false));
    }

    @Test
    public void otherCodesAreNotDrops() {
        // No video at the start of a stream (a blocked port) and unknown host codes
        assertFalse(ReconnectPolicy.isNetworkDrop(-100, true));
        assertFalse(ReconnectPolicy.isNetworkDrop(0x800e9403, true));
    }

    @Test
    public void networkTroubleIsSilenceOrARecentLoss() {
        long now = 100_000;
        assertTrue(ReconnectPolicy.hadNetworkTrouble(now - 2_000, 0, now));
        assertTrue(ReconnectPolicy.hadNetworkTrouble(0, now - 1_000, now));
        assertFalse(ReconnectPolicy.hadNetworkTrouble(0, now - ReconnectPolicy.NETWORK_LOSS_RECENT_MS, now));
        assertFalse(ReconnectPolicy.hadNetworkTrouble(0, 0, now));
    }

    @Test
    public void aDropSoonAfterAReconnectContinuesTheSameOutage() {
        long firstOutage = 50_000;
        long reconnectedAt = 70_000;
        // Dropped again 10 s after coming back: the window keeps counting from the first drop
        assertEquals(firstOutage, ReconnectPolicy.outageStartMs(80_000, reconnectedAt, firstOutage));
        // Long after: a new outage
        long later = reconnectedAt + ReconnectPolicy.SAME_OUTAGE_MS;
        assertEquals(later, ReconnectPolicy.outageStartMs(later, reconnectedAt, firstOutage));
        // Never reconnected before
        assertEquals(80_000, ReconnectPolicy.outageStartMs(80_000, 0, 0));
    }

    @Test
    public void theWindowIsStrictlyBelowItsLength() {
        assertTrue(ReconnectPolicy.withinWindow(0, 60));
        assertTrue(ReconnectPolicy.withinWindow(59_999, 60));
        assertFalse(ReconnectPolicy.withinWindow(60_000, 60));
    }

    @Test
    public void retryDelayGrowsUntilTheCap() {
        // random01 = 0.5 means no jitter: 1 s, 1.6 s, 2.56 s, 4.096 s, then the 5 s cap
        assertEquals(1000, ReconnectPolicy.retryDelayMs(1, 0.5));
        assertEquals(1600, ReconnectPolicy.retryDelayMs(2, 0.5));
        assertEquals(2560, ReconnectPolicy.retryDelayMs(3, 0.5));
        assertEquals(4096, ReconnectPolicy.retryDelayMs(4, 0.5));
        assertEquals(ReconnectPolicy.RETRY_MAX_MS, ReconnectPolicy.retryDelayMs(5, 0.5));
        assertEquals(ReconnectPolicy.RETRY_MAX_MS, ReconnectPolicy.retryDelayMs(1000, 0.5));
    }

    @Test
    public void retryDelayJitterIsTwentyPercentEachWay() {
        assertEquals(800, ReconnectPolicy.retryDelayMs(1, 0.0));
        assertEquals(1200, ReconnectPolicy.retryDelayMs(1, 0.9999999));
        assertEquals(4915, ReconnectPolicy.retryDelayMs(4, 0.9999999));
        // Past the cap, even the low end of the jitter stays at the cap
        assertEquals(ReconnectPolicy.RETRY_MAX_MS, ReconnectPolicy.retryDelayMs(5, 0.0));
    }

    @Test
    public void onlyAnswersThatRuleOutAResumeStopTheAttempts() {
        assertTrue(ReconnectPolicy.isAttemptFailureFinal(NvConnection.RESUME_ERROR_NO_SESSION));
        assertTrue(ReconnectPolicy.isAttemptFailureFinal(470));
        assertTrue(ReconnectPolicy.isAttemptFailureFinal(525));
        assertTrue(ReconnectPolicy.isAttemptFailureFinal(403));
        // No answer, a timeout, a host hiccup: try again
        assertFalse(ReconnectPolicy.isAttemptFailureFinal(0));
        assertFalse(ReconnectPolicy.isAttemptFailureFinal(-408));
        assertFalse(ReconnectPolicy.isAttemptFailureFinal(503));
        assertFalse(ReconnectPolicy.isAttemptFailureFinal(110));
    }
}
