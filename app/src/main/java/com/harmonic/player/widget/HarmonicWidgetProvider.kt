package com.harmonic.player.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.harmonic.player.MainActivity
import com.harmonic.player.R
import com.harmonic.player.playback.PlaybackServiceHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Widget clássico (RemoteViews + AppWidgetProvider), substituindo a versão
 * anterior em Jetpack Glance.
 *
 * Motivo da troca: o Glance tem um bug conhecido e sem solução definitiva
 * até hoje (issuetracker.google.com/issues/299093375, e vários relatos da
 * comunidade) onde `updateAll()`/recomposição às vezes simplesmente não
 * aplica o novo estado na tela do widget — foi exatamente o sintoma visto
 * aqui: o comando de play/pause chegava e tocava a música de verdade, mas o
 * ícone não trocava; próxima/anterior sem efeito visível nenhum. Trocar a
 * lógica de quem mandava o comando (nossa tentativa anterior) não resolvia
 * porque o problema não estava aí — estava na camada de recomposição do
 * Glance em si.
 *
 * RemoteViews é o mecanismo mais antigo e mais manual — sem essa camada de
 * recomposição no meio — e é também o que praticamente todo widget de
 * player de música que "só funciona" usa (é o padrão usado pela grande
 * maioria dos apps de música open source no Android).
 */

private const val ACTION_PLAY_PAUSE = "com.harmonic.player.widget.ACTION_PLAY_PAUSE"
private const val ACTION_NEXT = "com.harmonic.player.widget.ACTION_NEXT"
private const val ACTION_PREVIOUS = "com.harmonic.player.widget.ACTION_PREVIOUS"
private const val TAG = "HarmonicWidgetProvider"

/** Dourado do tema padrão do widget — usado quando ainda não há capa (ou nenhuma cor extraída dela). */
private const val DEFAULT_THEME_ACCENT = 0xFFE3A63E.toInt()

// Um AppWidgetProvider por tamanho — mesmo motivo de antes: aparecerem como
// opções separadas no seletor de widgets do sistema, em vez de um só genérico.

class HarmonicWidgetSmallProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, buildSmallRemoteViews(context)) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action in HANDLED_ACTIONS) {
            handleActionBroadcast(context, intent, goAsync())
        }
    }
}

class HarmonicWidgetMediumProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, buildMediumRemoteViews(context)) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action in HANDLED_ACTIONS) {
            handleActionBroadcast(context, intent, goAsync())
        }
    }
}

class HarmonicWidgetLargeProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, buildLargeRemoteViews(context)) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action in HANDLED_ACTIONS) {
            handleActionBroadcast(context, intent, goAsync())
        }
    }
}

private val HANDLED_ACTIONS = setOf(ACTION_PLAY_PAUSE, ACTION_NEXT, ACTION_PREVIOUS)

/**
 * Atualiza as três variantes do widget (chamado tanto pelas ações dos
 * botões quanto pelo PlaybackService sempre que o estado de reprodução
 * muda por qualquer outro motivo — troca de faixa pela notificação, pelo
 * app, pelo Bluetooth do carro, etc).
 */
fun updateAllHarmonicWidgets(context: Context) {
    val manager = AppWidgetManager.getInstance(context)
    manager.updateAppWidget(ComponentName(context, HarmonicWidgetSmallProvider::class.java), buildSmallRemoteViews(context))
    manager.updateAppWidget(ComponentName(context, HarmonicWidgetMediumProvider::class.java), buildMediumRemoteViews(context))
    manager.updateAppWidget(ComponentName(context, HarmonicWidgetLargeProvider::class.java), buildLargeRemoteViews(context))
}

/**
 * Trata o toque nos botões (play/pause/próxima/anterior).
 *
 * `goAsync()` (chamado pelo receiver antes de entrar aqui) segura o
 * BroadcastReceiver "vivo" tempo suficiente pra corrotina terminar — sem
 * isso, o Android pode encerrar o processo assim que `onReceive` retorna,
 * antes do comando (que passa por IPC até o MediaController da sessão de
 * mídia) ser processado de verdade. É um erro clássico e uma causa comum
 * de "o botão às vezes funciona, às vezes não".
 */
private fun handleActionBroadcast(context: Context, intent: Intent, pendingResult: android.content.BroadcastReceiver.PendingResult) {
    val action = intent.action ?: run { pendingResult.finish(); return }
    CoroutineScope(Dispatchers.Main).launch {
        try {
            val controller = WidgetMediaController.connect(context)
            if (controller == null) {
                android.util.Log.e(TAG, "[$action] sessão não conectou (controller nulo)")
                return@launch
            }
            when (action) {
                ACTION_PLAY_PAUSE -> if (controller.isPlaying) controller.pause() else controller.play()
                ACTION_NEXT -> controller.seekToNextMediaItem()
                ACTION_PREVIOUS -> controller.seekToPreviousMediaItem()
            }
            // O controller já reflete o novo estado assim que a chamada
            // acima retorna — é a fonte mais rápida disponível, sem
            // esperar o PlaybackService avisar de volta.
            PlaybackServiceHolder.applyControllerSnapshot(
                title = controller.mediaMetadata.title?.toString(),
                artist = controller.mediaMetadata.artist?.toString(),
                isPlaying = controller.isPlaying,
                hasQueue = controller.mediaItemCount > 0,
                mediaId = controller.currentMediaItem?.mediaId?.toLongOrNull()
            )
            updateAllHarmonicWidgets(context)
            // Reforço único ~350ms depois: dá tempo do PlaybackService
            // (que também reage a esse mesmo comando, sem IPC) terminar de
            // carregar a capa nova e publicar o estado definitivo.
            delay(350)
            updateAllHarmonicWidgets(context)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "[$action] falhou ao executar comando", e)
        } finally {
            pendingResult.finish()
        }
    }
}

private fun buildSmallRemoteViews(context: Context): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.widget_small)
    applyCommonState(context, views)
    views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent(context))
    views.setOnClickPendingIntent(R.id.btn_play_pause, actionPendingIntent(context, HarmonicWidgetSmallProvider::class.java, ACTION_PLAY_PAUSE))
    return views
}

private fun buildMediumRemoteViews(context: Context): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.widget_medium)
    applyCommonState(context, views)
    applyTitleArtist(context, views)
    views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent(context))
    views.setOnClickPendingIntent(R.id.btn_play_pause, actionPendingIntent(context, HarmonicWidgetMediumProvider::class.java, ACTION_PLAY_PAUSE))
    views.setOnClickPendingIntent(R.id.btn_next, actionPendingIntent(context, HarmonicWidgetMediumProvider::class.java, ACTION_NEXT))
    views.setOnClickPendingIntent(R.id.btn_previous, actionPendingIntent(context, HarmonicWidgetMediumProvider::class.java, ACTION_PREVIOUS))
    return views
}

private fun buildLargeRemoteViews(context: Context): RemoteViews {
    val views = RemoteViews(context.packageName, R.layout.widget_large)
    applyCommonState(context, views)
    applyTitleArtist(context, views)
    views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent(context))
    views.setOnClickPendingIntent(R.id.btn_play_pause, actionPendingIntent(context, HarmonicWidgetLargeProvider::class.java, ACTION_PLAY_PAUSE))
    views.setOnClickPendingIntent(R.id.btn_next, actionPendingIntent(context, HarmonicWidgetLargeProvider::class.java, ACTION_NEXT))
    views.setOnClickPendingIntent(R.id.btn_previous, actionPendingIntent(context, HarmonicWidgetLargeProvider::class.java, ACTION_PREVIOUS))
    return views
}

/** Capa (ou cor de fundo, se não tiver capa) + ícone de play/pause — comum aos três tamanhos. */
private fun applyCommonState(context: Context, views: RemoteViews) {
    val state = PlaybackServiceHolder.state.value

    views.setInt(R.id.cover_fallback_bg, "setBackgroundColor", state.coverAccentColor ?: DEFAULT_THEME_ACCENT)
    if (state.coverBitmap != null) {
        views.setImageViewBitmap(R.id.iv_cover, state.coverBitmap)
        views.setViewVisibility(R.id.iv_cover, View.VISIBLE)
    } else {
        views.setViewVisibility(R.id.iv_cover, View.GONE)
    }

    views.setImageViewResource(
        R.id.btn_play_pause,
        if (state.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
    )
}

/** Título/artista — só existe nos layouts médio e grande (o pequeno não tem espaço pra texto). */
private fun applyTitleArtist(context: Context, views: RemoteViews) {
    val state = PlaybackServiceHolder.state.value
    views.setTextViewText(R.id.widget_title, state.title ?: context.getString(R.string.app_name))
    views.setTextViewText(
        R.id.widget_artist,
        state.artist ?: if (state.hasQueue) "" else "Nenhuma música tocando"
    )
}

private fun openAppPendingIntent(context: Context): PendingIntent {
    val intent = Intent(context, MainActivity::class.java)
    return PendingIntent.getActivity(
        context, 0, intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}

/**
 * Um PendingIntent explícito por ação, endereçado direto à classe do
 * provider (`providerClass`) — como a intent é explícita (componente já
 * definido), o sistema não precisa casar com nenhum <intent-filter> do
 * manifesto pra entregá-la; por isso as ações customizadas (play/pause,
 * próxima, anterior) não precisam estar declaradas lá, só a ação padrão
 * de atualização do próprio Android (APPWIDGET_UPDATE) precisa.
 */
private fun actionPendingIntent(context: Context, providerClass: Class<*>, action: String): PendingIntent {
    val intent = Intent(context, providerClass).setAction(action)
    return PendingIntent.getBroadcast(
        context, 0, intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}
