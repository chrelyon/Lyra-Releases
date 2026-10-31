package app.lyra.music.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSeekRecoveryPolicyTest {
    private fun recover(
        direction: Int = 1,
        beforeIndex: Int = 0,
        afterIndex: Int = 0,
        beforeMediaId: String? = "trackA",
        afterMediaId: String? = "trackA",
        beforePositionMs: Long = 4_000,
        afterPositionMs: Long = 4_050,
        navigationInFlight: Boolean = false,
        persistedCurrentMediaId: String? = "trackA",
        hasNext: Boolean = true,
        hasPrevious: Boolean = true,
    ) = AutoSeekRecoveryPolicy.shouldRecover(
        direction, beforeIndex, afterIndex, beforeMediaId, afterMediaId,
        beforePositionMs, afterPositionMs, navigationInFlight,
        persistedCurrentMediaId, hasNext, hasPrevious,
    )

    @Test fun `recovers unchanged next when queue permits`() = assertTrue(recover())
    @Test fun `does not double skip after index change`() = assertFalse(recover(afterIndex = 1))
    @Test fun `does not double skip after id change`() = assertFalse(recover(afterMediaId = "trackB"))
    @Test fun `does not race loading navigation`() = assertFalse(recover(navigationInFlight = true))
    @Test fun `requires persisted queue alignment`() = assertFalse(recover(persistedCurrentMediaId = "trackB"))
    @Test fun `respects end of queue`() = assertFalse(recover(hasNext = false))
    @Test fun `recognizes valid previous restart`() = assertFalse(recover(direction = -1, beforePositionMs = 14_000, afterPositionMs = 0))
    @Test fun `recovers previous only with preceding item`() = assertFalse(recover(direction = -1, hasPrevious = false))
    @Test fun `ignores seek operations other than next previous`() = assertFalse(recover(direction = 0))
}
