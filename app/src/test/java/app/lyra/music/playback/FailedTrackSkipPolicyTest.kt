package app.lyra.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FailedTrackSkipPolicyTest {
    @Test fun skipsOnlyWhenEnabledAndDoesNotWrap() {
        val policy = FailedTrackSkipPolicy()
        assertNull(policy.nextIndex(false, 0, 3))
        assertEquals(1, policy.nextIndex(true, 0, 3))
        assertNull(policy.nextIndex(true, 2, 3))
        assertNull(policy.nextIndex(true, -1, 3))
    }

    @Test fun stopsAfterConsecutiveFailuresAndResetsAfterPlayback() {
        val policy = FailedTrackSkipPolicy()
        repeat(5) { assertEquals(it + 1, policy.nextIndex(true, it, 8)) }
        assertNull(policy.nextIndex(true, 5, 8))
        policy.reset()
        assertEquals(7, policy.nextIndex(true, 6, 8))
    }
}
