package app.lyra.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VerifiedQueueRecoveryTest {
    @Test fun reusesOldestAtVerifiedTail() {
        assertEquals(0, VerifiedQueueRecovery.targetIndex(true, 19, 20))
    }

    @Test fun neverRecyclesNonVerifiedOrSingleTrackQueues() {
        assertNull(VerifiedQueueRecovery.targetIndex(false, 19, 20))
        assertNull(VerifiedQueueRecovery.targetIndex(true, 0, 1))
    }

    @Test fun neverInterruptsRemainingSongs() {
        assertNull(VerifiedQueueRecovery.targetIndex(true, 18, 20))
    }
}
