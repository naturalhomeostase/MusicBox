package com.harmonic.player.playback

import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class WidgetPlaybackState(
    val title: String? = null,
    val artist: String? = null,
    val isPlaying: Boolean = false,
    val hasQueue: Boolean = false,
    val currentMediaId: Long? = null,
    val coverBitmap: android.graphics.Bitmap? = null,
    val coverAccentColor: Int? = null
)

/**
 * Estado do timer de dormir, espelhado aqui porque o timer em si roda
 * dentro do PlaybackService (ver [PlaybackSessionCallback] em
 * PlaybackService.kt) — não mais no PlayerController/Activity — justamente
 * pra continuar contando mesmo se o app sair de primeiro plano e o
 * processo da Activity for encerrado pelo sistema. O [PlayerController]
 * só espelha esse valor pra UI ler (ver `attachListener`/`init`).
 */
data class SleepTimerState(
    val endAt: Long? = null, // timestamp (ms); -1L = "pausar ao fim da música atual" (sem hora fixa)
    val remainingMs: Long = 0
)

/**
 * Ponte entre o widget de tela inicial (RemoteViews) e o player real, que só
 * existe dentro do `PlaybackService`. Como o serviço roda no mesmo processo
 * do app, guardar a referência do `Player` aqui (populada pelo próprio
 * serviço) é o caminho mais direto pro widget ler/controlar a reprodução,
 * sem precisar montar um MediaController próprio só pra isso.
 */
object PlaybackServiceHolder {
    private var player: Player? = null

    private val _state = MutableStateFlow(WidgetPlaybackState())
    val state: StateFlow<WidgetPlaybackState> = _state.asStateFlow()

    private val _sleepTimer = MutableStateFlow(SleepTimerState())
    val sleepTimer: StateFlow<SleepTimerState> = _sleepTimer.asStateFlow()

    fun updateSleepTimer(endAt: Long?, remainingMs: Long) {
        _sleepTimer.value = SleepTimerState(endAt, remainingMs)
    }

    // Evento avulso (não é estado — não faz sentido "reler" um erro
    // antigo ao reabrir uma tela) pra avisar a UI quando uma música falha
    // ao tocar. `extraBufferCapacity = 1` garante que o evento não se
    // perde se for emitido um instante antes de alguém começar a coletar
    // (ex: serviço dispara o erro assim que inicia, antes da Activity
    // terminar de montar a UI).
    private val _errorEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errorEvents: SharedFlow<String> = _errorEvents.asSharedFlow()

    fun emitPlaybackError(message: String) {
        _errorEvents.tryEmit(message)
    }

    // Ponte pro botão "Favoritar" da notificação: quando o coração é tocado
    // NO APP (tela Tocando Agora, listas), o app avisa aqui — e o serviço
    // (que "ouve" isso) atualiza o ícone da notificação na hora, sem
    // precisar trocar de música pra sincronizar.
    private var onFavoriteChangedExternally: ((songId: Long, isFavorite: Boolean) -> Unit)? = null

    fun setFavoriteChangeListener(listener: ((Long, Boolean) -> Unit)?) {
        onFavoriteChangedExternally = listener
    }

    fun notifyFavoriteChanged(songId: Long, isFavorite: Boolean) {
        onFavoriteChangedExternally?.invoke(songId, isFavorite)
    }

    fun attach(player: Player) {
        this.player = player
    }

    fun detach() {
        player = null
        _state.value = WidgetPlaybackState()
    }

    fun refreshState() {
        val p = player ?: run { _state.value = WidgetPlaybackState(); return }
        val metadata = p.mediaMetadata
        val mediaId = p.currentMediaItem?.mediaId?.toLongOrNull()
        // Só zera a capa quando a música REALMENTE mudou — sem isso, cada
        // play/pause (que também chama refreshState) fazia a capa sumir e
        // recarregar do zero, piscando à toa no widget.
        val songChanged = mediaId != _state.value.currentMediaId
        _state.value = _state.value.copy(
            title = metadata.title?.toString(),
            artist = metadata.artist?.toString(),
            isPlaying = p.isPlaying,
            hasQueue = p.mediaItemCount > 0,
            currentMediaId = mediaId,
            coverBitmap = if (songChanged) null else _state.value.coverBitmap,
            // Junto com a capa: sem isso, o botão de play ficava com a cor
            // da música ANTERIOR por um instante, até a nova capa (e sua
            // cor) terminar de carregar.
            coverAccentColor = if (songChanged) null else _state.value.coverAccentColor
        )
    }

    /**
     * Aplica direto o que o [androidx.media3.session.MediaController] do
     * widget já sabe logo após mandar um comando (play/pause/próxima/
     * anterior) — ver comentário em widget/HarmonicWidgetProvider.kt
     * (handleActionBroadcast) pra entender por que ler daqui é mais
     * rápido/confiável do que esperar o PlaybackService notificar de
     * volta pelo Player.Listener.
     *
     * Só mexe na capa quando a música muda de verdade (mesma lógica de
     * [refreshState]) — a capa nova continua vindo só do PlaybackService,
     * que tem acesso ao banco de dados local pra carregar/decodificar ela.
     */
    fun applyControllerSnapshot(
        title: String?,
        artist: String?,
        isPlaying: Boolean,
        hasQueue: Boolean,
        mediaId: Long?
    ) {
        val songChanged = mediaId != _state.value.currentMediaId
        _state.value = _state.value.copy(
            title = title,
            artist = artist,
            isPlaying = isPlaying,
            hasQueue = hasQueue,
            currentMediaId = mediaId,
            coverBitmap = if (songChanged) null else _state.value.coverBitmap,
            coverAccentColor = if (songChanged) null else _state.value.coverAccentColor
        )
    }

    /** Chamado pelo PlaybackService depois de carregar a capa em segundo plano — só aplica se a música ainda for a mesma. */
    fun updateCover(mediaId: Long?, bitmap: android.graphics.Bitmap?) {
        if (mediaId == _state.value.currentMediaId) {
            _state.value = _state.value.copy(
                coverBitmap = bitmap,
                coverAccentColor = bitmap?.let { extractAccentColor(it) }
            )
        }
    }

    /**
     * Cor de destaque extraída da capa, pro botão de play do widget seguir a
     * mesma cor da música tocando — mesma ordem de prioridade de swatch já
     * usada na tela Tocando Agora (vibrante > vibrante-claro > dominante >
     * neutro), pra ficar visualmente consistente com o resto do app.
     */
    private fun extractAccentColor(bitmap: android.graphics.Bitmap): Int? = try {
        val palette = androidx.palette.graphics.Palette.from(bitmap).generate()
        val swatch = palette.vibrantSwatch ?: palette.lightVibrantSwatch
            ?: palette.dominantSwatch ?: palette.mutedSwatch
        swatch?.rgb
    } catch (e: Exception) {
        null
    }

    fun togglePlayPause() {
        player?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun skipNext() {
        player?.seekToNextMediaItem()
    }

    fun skipPrevious() {
        player?.seekToPreviousMediaItem()
    }
}
