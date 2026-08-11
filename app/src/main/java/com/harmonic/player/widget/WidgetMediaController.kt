package com.harmonic.player.widget

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.harmonic.player.playback.PlaybackService
import kotlinx.coroutines.CompletableDeferred

/**
 * Conexão com a sessão de mídia do [PlaybackService], usada só pelos botões
 * do widget (play/pause, próxima, anterior).
 *
 * Por que não usar [com.harmonic.player.playback.PlaybackServiceHolder]
 * direto, já que ele já existe? Porque ele só tem uma referência ao player
 * ENQUANTO o serviço já estiver vivo no processo — se o Android encerrar o
 * serviço em segundo plano (comum em vários Android por aí, principalmente
 * fora de fabricantes "puros"), essa referência vira null e os botões do
 * widget silenciosamente não fazem nada, mesmo continuando visíveis e
 * "normais" na tela. Foi exatamente esse o bug relatado: primeira música
 * aparecia, nunca mais atualizava, nenhum botão respondia.
 *
 * Conectar via [SessionToken] + [MediaController] resolve isso na raiz:
 * é o mesmo mecanismo que notificação, Bluetooth e Android Auto usam pra
 * controlar a reprodução mesmo com o app "fechado" — se o serviço não
 * estiver rodando, essa conexão o inicia sozinha (o serviço já está
 * declarado no Manifest com o intent-filter certo pra isso). Assim que ele
 * sobe, o próprio `PlaybackService.onCreate()` já reconecta o
 * `PlaybackServiceHolder` e reenvia o estado real pro widget.
 */
object WidgetMediaController {
    @Volatile private var controller: MediaController? = null

    /**
     * Devolve um controller conectado, reaproveitando o existente se ainda
     * estiver válido. Nunca bloqueia nenhuma thread — a conexão (que pode
     * envolver iniciar o serviço do zero) roda via callback, e essa função
     * só retoma quando o resultado (controller pronto, ou null se falhou)
     * estiver disponível.
     */
    suspend fun connect(context: Context): MediaController? {
        controller?.takeIf { it.isConnected }?.let { return it }

        val appContext = context.applicationContext
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()

        val result = CompletableDeferred<MediaController?>()
        future.addListener(
            {
                val connected = try {
                    future.get()
                } catch (e: Exception) {
                    null
                }
                controller = connected
                result.complete(connected)
            },
            // MediaController exige que a conexão (e qualquer chamada nele
            // depois) aconteça na main thread — mesma exigência do
            // ExoPlayer normal.
            ContextCompat.getMainExecutor(appContext)
        )
        return result.await()
    }
}
