package app.lyra.music.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioScanRetryPolicyTest {
    @Test fun `retry while tracks make progress`() = assertTrue(
        AudioScanRetryPolicy.shouldRetry(remaining = 2, successThisRun = 1, runAttemptCount = 9, priorityScan = false, stopped = false),
    )
    @Test fun `stop after repeated no progress`() = assertFalse(
        AudioScanRetryPolicy.shouldRetry(remaining = 2, successThisRun = 0, runAttemptCount = 3, priorityScan = false, stopped = false),
    )
    @Test fun `allow limited recovery attempts`() = assertTrue(
        AudioScanRetryPolicy.shouldRetry(remaining = 2, successThisRun = 0, runAttemptCount = 2, priorityScan = false, stopped = false),
    )
    @Test fun `priority track never schedules retry`() = assertFalse(
        AudioScanRetryPolicy.shouldRetry(remaining = 2, successThisRun = 0, runAttemptCount = 0, priorityScan = true, stopped = false),
    )
    @Test fun `no pending means complete`() = assertFalse(
        AudioScanRetryPolicy.shouldRetry(remaining = 0, successThisRun = 0, runAttemptCount = 0, priorityScan = false, stopped = false),
    )
    @Test fun `stopped scan does not retry`() = assertFalse(
        AudioScanRetryPolicy.shouldRetry(remaining = 2, successThisRun = 0, runAttemptCount = 0, priorityScan = false, stopped = true),
    )
    @Test fun `rotation reaches later candidates`() {
        assertEquals(0, AudioScanRetryPolicy.startOffset(40, 0, 12))
        assertEquals(12, AudioScanRetryPolicy.startOffset(40, 1, 12))
        assertEquals(24, AudioScanRetryPolicy.startOffset(40, 2, 12))
        assertEquals(36, AudioScanRetryPolicy.startOffset(40, 3, 12))
    }
}
