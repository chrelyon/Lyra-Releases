package app.lyra.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Player esposto alla MediaSession.
 *
 * La timeline contiene normalmente la coda Media3 completa (gapless), ma
 * puo' essere temporaneamente composta da un solo elemento in resume o
 * durante una riconfigurazione. In questi casi Media3 puo' risolvere NEXT
 * come no-op (INDEX_UNSET) pur avendo una coda Lyra persistita.
 *
 * Il wrapper gestisce direttamente il fallback INDEX_UNSET e osserva i seek
 * nativi: il servizio verifica che abbiano davvero cambiato traccia prima
 * di richiedere un eventuale recupero. Nessun doppio salto preventivo.
 */
@OptIn(UnstableApi::class)
class LyraSessionPlayer(
    private val delegatePlayer: Player,
    private val canNavigate: (direction: Int) -> Boolean,
    private val onNavigate: (direction: Int) -> Unit,
    private val onNativeSeek: (
        direction: Int,
        previousIndex: Int,
        previousMediaId: String?,
        previousPositionMs: Long,
    ) -> Unit,
) : ForwardingSimpleBasePlayer(delegatePlayer) {

    override fun getState(): State {
        val state = super.getState()
        val commands = state.availableCommands.buildUpon()

        if (canNavigate(1)) {
            commands
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        }

        if (canNavigate(-1)) {
            commands
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        }

        return state.buildUpon()
            .setAvailableCommands(commands.build())
            .build()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        if (mediaItemIndex == C.INDEX_UNSET) {
            when (seekCommand) {
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                    if (canNavigate(1)) {
                        onNavigate(1)
                        return Futures.immediateVoidFuture()
                    }
                }

                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                    if (canNavigate(-1)) {
                        onNavigate(-1)
                        return Futures.immediateVoidFuture()
                    }
                }
            }
        }

        // Solo NEXT/PREV sono candidati al recupero. Salviamo posizione e
        // mediaId PRIMA del seek, per non duplicare una navigazione gia' riuscita.
        val direction = when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> 1
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> -1
            else -> 0
        }
        val beforeIndex = delegatePlayer.currentMediaItemIndex
        val beforeId = delegatePlayer.currentMediaItem?.mediaId
        val beforePosition = delegatePlayer.currentPosition
        val result = super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        if (direction != 0 && mediaItemIndex != C.INDEX_UNSET) {
            onNativeSeek(direction, beforeIndex, beforeId, beforePosition)
        }
        return result
    }
}
