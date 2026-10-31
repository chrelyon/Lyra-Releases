package app.lyra.music.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTransitionPolicyTest {
    private fun allowed(
        enabled: Boolean = true,
        queueHasNext: Boolean = true,
        repeatOne: Boolean = false,
        remainingDurationMs: Long = 5_000,
        fadeMs: Int = 4_000,
        firstAlbum: String? = "Album A",
        secondAlbum: String? = "Album B",
        firstAlbumId: Long? = 1,
        secondAlbumId: Long? = 2,
        firstDurationMs: Long = 180_000,
    ) = AudioTransitionPolicy.canCrossfade(
        enabled, queueHasNext, repeatOne, remainingDurationMs, fadeMs,
        firstAlbum, secondAlbum, firstAlbumId, secondAlbumId, firstDurationMs,
    )

    @Test fun `separate albums may crossfade`() = assertTrue(allowed())
    @Test fun `disabled crossfade is gapless`() = assertFalse(allowed(enabled = false))
    @Test fun `same album id keeps gapless`() = assertFalse(allowed(secondAlbumId = 1))
    @Test fun `same album title keeps gapless`() = assertFalse(allowed(secondAlbum = "album a", secondAlbumId = null))
    @Test fun `repeat one is not crossfaded`() = assertFalse(allowed(repeatOne = true))
    @Test fun `short tracks keep gapless`() = assertFalse(allowed(firstDurationMs = 8_000))
    @Test fun `no next track keeps gapless`() = assertFalse(allowed(queueHasNext = false))
}
