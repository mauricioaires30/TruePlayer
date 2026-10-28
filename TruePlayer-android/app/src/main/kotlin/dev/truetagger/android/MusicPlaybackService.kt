
package dev.truetagger.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.Settings
import dev.truetagger.model.AudioTrack

class MusicPlaybackService : Service() {
    companion object {
        const val ACTION_PLAY = "dev.truetagger.player.PLAY"
        const val ACTION_TOGGLE = "dev.truetagger.player.TOGGLE"
        const val ACTION_NEXT = "dev.truetagger.player.NEXT"
        const val ACTION_PREVIOUS = "dev.truetagger.player.PREVIOUS"
        const val ACTION_SEEK = "dev.truetagger.player.SEEK"
        const val ACTION_SHUFFLE = "dev.truetagger.player.SHUFFLE"
        const val ACTION_REPEAT = "dev.truetagger.player.REPEAT"
        const val ACTION_STOP = "dev.truetagger.player.STOP"
        const val ACTION_HIDE_NOTIFICATION = "dev.truetagger.player.HIDE_NOTIFICATION"
        const val ACTION_PLAY_QUEUE_INDEX = "dev.truetagger.player.PLAY_QUEUE_INDEX"
        const val ACTION_REPLACE_QUEUE = "dev.truetagger.player.REPLACE_QUEUE"
        const val ACTION_RESTORE_LAST = "dev.truetagger.player.RESTORE_LAST"
        const val ACTION_RECONCILE_DELETED_TRACKS = "dev.truetagger.player.RECONCILE_DELETED_TRACKS"
        const val ACTION_RELEASE_FILE_HANDLES = "dev.truetagger.player.RELEASE_FILE_HANDLES"
        const val EXTRA_TRACK_ID = "track_id"
        const val EXTRA_TRACK_TITLE = "track_title"
        const val EXTRA_TRACK_ARTIST = "track_artist"
        const val EXTRA_TRACK_ALBUM = "track_album"
        const val EXTRA_TRACK_DURATION = "track_duration"
        const val EXTRA_TRACK_ARTWORK = "track_artwork"
        const val EXTRA_INDEX = "index"
        const val EXTRA_START_SHUFFLE = "start_shuffle"
        const val EXTRA_POSITION = "position"
        const val EXTRA_CURRENT_ID = "current_id"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_SOURCE_TYPE = "source_type"
        const val EXTRA_SOURCE_NAME = "source_name"
        const val EXTRA_DELETED_TRACK_IDS = "deleted_track_ids"
        const val SOURCE_LIBRARY = "LIBRARY"
        const val SOURCE_PLAYLIST = "PLAYLIST"
        const val SOURCE_ALBUM = "ALBUM"
        const val CHANNEL = "truetagger_player_v4"
        const val NOTIFICATION_ID = 301
        const val PREFS_LAST = "truetagger_last_playback"

        /** Tempo pausado antes de soltar o arquivo aberto. */
        private const val IDLE_RELEASE_DELAY_MS = 45_000L

        /** Lado da capa usada no ícone grande da notificação. */
        private const val NOTIFICATION_ARTWORK_SIZE = 1024

        fun hideNotification(context: Context) {
            runCatching {
                context.applicationContext.startService(
                    Intent(context.applicationContext, MusicPlaybackService::class.java)
                        .setAction(ACTION_HIDE_NOTIFICATION)
                )
            }
        }

        fun play(context: Context, track: AudioTrack, queue: List<AudioTrack>, index: Int, startShuffle: Boolean = false, sourceType: String = SOURCE_LIBRARY, sourceName: String = "") {
            val safeIndex = index.coerceIn(queue.indices)
            PlaybackRequests.set(
                PlaybackRequest(
                    track = queue[safeIndex],
                    queue = queue.toList(),
                    index = safeIndex,
                    shuffle = startShuffle,
                    sourceType = sourceType,
                    sourceName = sourceName,
                )
            )
            context.startForegroundService(Intent(context, MusicPlaybackService::class.java).setAction(ACTION_PLAY))
        }

        fun restoreLastPlayback(context: Context, track: AudioTrack, sourceQueue: List<AudioTrack>, index: Int, sourceType: String, sourceName: String, positionMs: Long) {
            if (sourceQueue.isEmpty()) return
            val safeIndex = index.coerceIn(sourceQueue.indices)
            PlaybackRequests.set(
                PlaybackRequest(
                    track = track,
                    queue = sourceQueue.toList(),
                    index = safeIndex,
                    sourceType = sourceType,
                    sourceName = sourceName,
                    restorePositionMs = positionMs.coerceAtLeast(0L),
                )
            )
            context.startService(Intent(context, MusicPlaybackService::class.java).setAction(ACTION_RESTORE_LAST))
        }

        fun command(context: Context, action: String, position: Long? = null) {
            val i = Intent(context, MusicPlaybackService::class.java).apply {
                this.action = action
                if (position != null) putExtra(EXTRA_POSITION, position)
            }
            context.startForegroundService(i)
        }

        fun replaceQueue(context: Context, tracks: List<AudioTrack>) {
            PlaybackRequests.set(PlaybackRequest(track = tracks.firstOrNull() ?: return, queue = tracks.toList(), index = 0))
            context.startService(Intent(context, MusicPlaybackService::class.java).setAction(ACTION_REPLACE_QUEUE))
        }

        fun reconcileDeletedTracks(context: Context, trackIds: Collection<String>) {
            val ids = trackIds.filter { it.isNotBlank() }.distinct()
            if (ids.isEmpty()) return
            context.startService(
                Intent(context, MusicPlaybackService::class.java).apply {
                    action = ACTION_RECONCILE_DELETED_TRACKS
                    putStringArrayListExtra(EXTRA_DELETED_TRACK_IDS, ArrayList(ids))
                }
            )
        }

        /**
         * Pede ao serviço que solte o arquivo de áudio aberto no momento.
         *
         * O MediaPlayer mantém um descritor aberto enquanto existir, e como o
         * serviço é foreground ele sobrevive pausado — por isso o Android
         * considera a pasta "em uso" e o gerenciador de arquivos se recusa a
         * excluí-la. A fila, o índice e a posição são preservados: ao dar play
         * de novo, a faixa recomeça exatamente de onde parou.
         */
        fun releaseFileHandles(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, MusicPlaybackService::class.java).setAction(ACTION_RELEASE_FILE_HANDLES)
                )
            }
        }


    }

    private var player: MediaPlayer? = null
    private lateinit var session: MediaSession
    private lateinit var handler: android.os.Handler
    private lateinit var audioManager: AudioManager
    private var resumeAfterAudioRouteReturn = false

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                    if (isCurrentlyPlaying()) {
                        resumeAfterAudioRouteReturn = true
                        pause()
                    }
                }
                Intent.ACTION_HEADSET_PLUG -> {
                    // Wired headset connected again. The Android broadcast exposes the
                    // state as an integer extra: 1 = connected, 0 = disconnected.
                    if (intent.getIntExtra("state", 0) == 1) {
                        resumeFromReturnedAudioRoute()
                    }
                }
                android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(
                        android.bluetooth.BluetoothProfile.EXTRA_STATE,
                        android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
                    )
                    if (state == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                        resumeFromReturnedAudioRoute()
                    }
                }
                android.bluetooth.BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(
                        android.bluetooth.BluetoothProfile.EXTRA_STATE,
                        android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
                    )
                    if (state == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                        resumeFromReturnedAudioRoute()
                    }
                }
            }
        }
    }

    private fun resumeFromReturnedAudioRoute() {
        if (!resumeAfterAudioRouteReturn) return
        resumeAfterAudioRouteReturn = false
        resume()
    }

    private fun isCurrentlyPlaying(): Boolean =
        runCatching { player?.isPlaying == true }.getOrDefault(false)

    private var queue = mutableListOf<AudioTrack>()
    private var baseQueue = mutableListOf<AudioTrack>()
    private var index = -1
    private var shuffle = false
    private var repeat = RepeatMode.OFF
    private var playbackSourceType = SOURCE_LIBRARY
    private var playbackSourceName = ""
    private var pendingPlay = false
    private var mediaPrepared = false
    private var mediaSourcePfd: ParcelFileDescriptor? = null
    private var playerGeneration = 0L
    private var navigationLockUntilMs = 0L
    private var notificationArtwork: android.graphics.Bitmap? = null
    private var notificationArtworkTrackId: String? = null
    private var notificationLastUpdateMs = 0L
    private var notificationSuppressed = false
    private var sessionMetadataTrackId: String? = null
    private var sessionMetadataDurationMs = -1L
    private var restoringLastPlayback = false
    private var playCountRecordedGeneration = -1L

    /**
     * Posição guardada quando o player é liberado por ociosidade (ou a pedido).
     * Enquanto for >= 0, a fila continua válida mas nenhum arquivo está aberto.
     */
    private var detachedPositionMs = -1L

    private val progressRunnable = object : Runnable {
        override fun run() {
            trackListening()
            updateStateAndNotification()
            if (player != null || queue.isNotEmpty()) {
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        handler = android.os.Handler(mainLooper)
        audioManager = getSystemService(AudioManager::class.java)
        createChannel()
        runCatching {
            registerReceiver(noisyReceiver, IntentFilter().apply {
                addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                addAction(Intent.ACTION_HEADSET_PLUG)
                addAction(android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(android.bluetooth.BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            })
        }
        session = MediaSession(this, "TruePlayer").apply {
            setPlaybackToLocal(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setUsage(AudioAttributes.USAGE_MEDIA).build())
            setSessionActivity(PendingIntent.getActivity(
                this@MusicPlaybackService, 1, Intent(this@MusicPlaybackService, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { resume() }
                override fun onPause() { pause() }
                override fun onSkipToNext() { next() }
                override fun onSkipToPrevious() { previous() }
                override fun onSeekTo(pos: Long) { seek(pos) }
                override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                    when (action) {
                        ACTION_SHUFFLE -> setShuffle(!shuffle)
                        ACTION_REPEAT -> cycleRepeat()
                    }
                }
                override fun onStop() { stopPlayback() }
            })
            isActive = true
        }
        handler.post(progressRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action != ACTION_HIDE_NOTIFICATION) {
            notificationSuppressed = false
        }
        when (action) {
            ACTION_PLAY -> {
                val request = PlaybackRequests.take()
                if (request == null || request.queue.isEmpty()) {
                    android.util.Log.e("TruePlayer", "Não foi possível reproduzir: comando sem fila em memória")
                } else {
                    playbackSourceType = request.sourceType.ifBlank { SOURCE_LIBRARY }
                    playbackSourceName = request.sourceName
                    baseQueue = request.queue.distinctBy { it.id }.toMutableList()
                    if (baseQueue.isEmpty()) return START_NOT_STICKY
                    shuffle = request.shuffle
                    val requestedIndex = request.index.coerceIn(baseQueue.indices)
                    val currentId = request.track.id.ifBlank { baseQueue[requestedIndex].id }
                    val rebuilt = rebuildQueueFromSavedModes(baseQueue, currentId, shuffle)
                    queue = rebuilt.first
                    index = rebuilt.second
                    loadCurrent(shouldPlay = true)
                }
            }
            ACTION_RESTORE_LAST -> {
                val request = PlaybackRequests.take()
                if (request != null && request.queue.isNotEmpty()) {
                    playbackSourceType = request.sourceType.ifBlank { SOURCE_LIBRARY }
                    playbackSourceName = request.sourceName
                    val prefs = getSharedPreferences(PREFS_LAST, MODE_PRIVATE)
                    shuffle = prefs.getBoolean("shuffle", false)
                    repeat = if (prefs.getBoolean("repeat_one", false)) RepeatMode.ONE else RepeatMode.OFF
                    baseQueue = request.queue.toMutableList()
                    val currentId = request.track.id.ifBlank { baseQueue.getOrNull(request.index)?.id }
                    val rebuilt = rebuildQueueFromSavedModes(baseQueue, currentId, shuffle)
                    queue = rebuilt.first
                    index = rebuilt.second
                    loadCurrent(shouldPlay = false, startPositionMs = request.restorePositionMs ?: 0L)
                } else {
                    restoreSavedPlaybackState()
                }
            }
            ACTION_TOGGLE -> {
                if (restoringLastPlayback) return START_STICKY
                val p = player
                if (p == null || !mediaPrepared) {
                    if (queue.isEmpty()) {
                        restoreSavedPlaybackState(shouldPlay = true)
                    } else {
                        // O player pode ter sido solto por ociosidade: recarrega
                        // a faixa na posição guardada em vez de só marcar pendência.
                        resume()
                    }
                } else if (runCatching { p.isPlaying }.getOrDefault(false)) {
                    pause()
                } else {
                    resume()
                }
            }
            ACTION_RELEASE_FILE_HANDLES -> {
                cancelIdleRelease()
                detachPlayerKeepingPosition()
            }
            ACTION_NEXT -> next()
            ACTION_PREVIOUS -> previous()
            ACTION_PLAY_QUEUE_INDEX -> playQueueIndex(intent.getIntExtra(EXTRA_INDEX, -1))
            ACTION_REPLACE_QUEUE -> {
                val request = PlaybackRequests.take()
                if (request != null) replaceCurrentQueue(request.queue)
            }
            ACTION_RECONCILE_DELETED_TRACKS -> {
                reconcileDeletedTracks(intent.getStringArrayListExtra(EXTRA_DELETED_TRACK_IDS).orEmpty().toSet())
            }
            ACTION_SEEK -> seek(intent.getLongExtra(EXTRA_POSITION, 0L))
            ACTION_SHUFFLE -> setShuffle(!shuffle)
            ACTION_REPEAT -> cycleRepeat()
            ACTION_STOP -> stopPlayback()
            ACTION_HIDE_NOTIFICATION -> hideNotificationNow()
        }
        updateStateAndNotification()
        return START_NOT_STICKY
    }

    private fun replaceCurrentQueue(newBase: List<AudioTrack>) {
        val currentId = queue.getOrNull(index)?.id
        val normalizedBase = newBase.distinctBy { it.id }
        if (normalizedBase.isEmpty()) {
            releaseCurrentPlayer()
            queue.clear()
            baseQueue.clear()
            index = -1
            pendingPlay = false
            updateStateAndNotification()
            return
        }

        val currentStillExists = currentId != null && normalizedBase.any { it.id == currentId }
        baseQueue = normalizedBase.toMutableList()
        queue = if (shuffle) {
            val current = currentId?.let { id -> normalizedBase.firstOrNull { it.id == id } }
            val rest = normalizedBase.filter { it.id != currentId }.shuffled()
            if (current != null) mutableListOf(current).apply { addAll(rest) } else normalizedBase.toMutableList()
        } else {
            normalizedBase.toMutableList()
        }

        index = if (currentStillExists) queue.indexOfFirst { it.id == currentId }.coerceAtLeast(0) else 0
        if (shuffle && currentStillExists) index = 0

        if (!currentStillExists && player != null && queue.isNotEmpty()) {
            val wasPlaying = pendingPlay || runCatching { player?.isPlaying == true }.getOrDefault(false)
            loadCurrent(wasPlaying)
        } else {
            updateStateAndNotification()
        }
    }

    private fun reconcileDeletedTracks(deletedIds: Set<String>) {
        if (deletedIds.isEmpty()) return

        val oldQueue = queue.toList()
        val oldIndex = index
        val currentId = oldQueue.getOrNull(oldIndex)?.id
        val currentWasDeleted = currentId != null && currentId in deletedIds
        val wasPlaying = pendingPlay || runCatching { player?.isPlaying == true }.getOrDefault(false)

        val cleanedBase = baseQueue.filterNot { it.id in deletedIds }
        val cleanedQueue = oldQueue.filterNot { it.id in deletedIds }

        if (cleanedQueue.isEmpty() || cleanedBase.isEmpty()) {
            stopPlayback()
            return
        }

        baseQueue = cleanedBase.toMutableList()
        queue = cleanedQueue.toMutableList()

        if (currentWasDeleted) {
            // Quando a faixa atual some do aparelho, continua na próxima faixa
            // da ordem de reprodução. Se não houver uma, volta para a primeira.
            index = if (cleanedQueue.isNotEmpty()) {
                oldIndex.coerceAtMost(cleanedQueue.lastIndex)
            } else {
                0
            }
            loadCurrent(shouldPlay = wasPlaying)
            return
        }

        val removedBeforeCurrent = if (oldIndex >= 0) {
            oldQueue.take(oldIndex).count { it.id in deletedIds }
        } else {
            0
        }
        index = (oldIndex - removedBeforeCurrent).coerceIn(0, cleanedQueue.lastIndex)
        updateStateAndNotification()
    }

    private fun playQueueIndex(targetIndex: Int) {
        if (targetIndex !in queue.indices) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now < navigationLockUntilMs) return
        navigationLockUntilMs = now + 150L
        val wasPlaying = runCatching { pendingPlay || player?.isPlaying == true }.getOrDefault(pendingPlay)
        if (repeat == RepeatMode.ONE && queue.getOrNull(index)?.id != queue[targetIndex].id) {
            updateStateAndNotification()
            return
        }
        index = targetIndex
        loadCurrent(wasPlaying)
    }

    private fun setShuffle(enabled: Boolean) {
        if (queue.isEmpty()) return
        val currentId = queue.getOrNull(index)?.id
        if (enabled == shuffle) {
            updateStateAndNotification()
            return
        }
        shuffle = enabled
        if (enabled) {
            val current = queue.getOrNull(index)
            val rest = baseQueue.filter { it.id != currentId }.shuffled()
            if (current != null) {
                queue = mutableListOf(current).apply { addAll(rest) }
                index = 0
            }
        } else {
            queue = baseQueue.toMutableList()
            index = queue.indexOfFirst { it.id == currentId }.let { if (it >= 0) it else 0 }
        }
        updateStateAndNotification()
    }

    private fun cycleRepeat() {
        repeat = if (repeat == RepeatMode.OFF) RepeatMode.ONE else RepeatMode.OFF
        updateStateAndNotification()
    }

    /**
     * Depois de um tempo pausado não há motivo para segurar o arquivo aberto.
     * Liberá-lo devolve o descritor ao sistema e permite renomear/excluir a
     * pasta normalmente. A notificação e a fila continuam de pé.
     */
    private val idleReleaseRunnable = Runnable {
        val active = runCatching { player?.isPlaying == true }.getOrDefault(false)
        if (!active && !pendingPlay && player != null) detachPlayerKeepingPosition()
    }

    private fun scheduleIdleRelease() {
        handler.removeCallbacks(idleReleaseRunnable)
        handler.postDelayed(idleReleaseRunnable, IDLE_RELEASE_DELAY_MS)
    }

    private fun cancelIdleRelease() {
        handler.removeCallbacks(idleReleaseRunnable)
    }

    /** Solta o MediaPlayer (e o descritor de arquivo) preservando a posição. */
    private fun detachPlayerKeepingPosition() {
        val current = player ?: return
        detachedPositionMs = runCatching {
            if (mediaPrepared) current.currentPosition.toLong().coerceAtLeast(0L) else 0L
        }.getOrDefault(0L)
        releaseCurrentPlayer()
        pendingPlay = false
        updateStateAndNotification()
    }

    /** Uma nova reprodução da faixa começou; o play só é contado depois de 30 s ouvidos. */
    private fun recordPlayCount(track: AudioTrack) {
        PlayStatsState.beginPlay(PlayStatsState.keyFor(track))
    }

    private var lastListenTickMs = 0L

    /** Chamada a cada segundo: soma o tempo realmente ouvido (só enquanto está tocando). */
    private fun trackListening() {
        val now = android.os.SystemClock.elapsedRealtime()
        val playing = runCatching { player?.isPlaying == true }.getOrDefault(false)
        val track = queue.getOrNull(index)
        if (playing && track != null && lastListenTickMs > 0L) {
            val delta = (now - lastListenTickMs).coerceIn(0L, 2_500L)
            PlayStatsState.addListening(this, PlayStatsState.keyFor(track), track.durationMs, delta)
        } else if (!playing && lastListenTickMs > 0L) {
            PlayStatsState.flush(this)
        }
        lastListenTickMs = if (playing) now else 0L
    }

    private fun releaseCurrentPlayer() {
        mediaPrepared = false
        player?.setOnCompletionListener(null)
        player?.setOnErrorListener(null)
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { mediaSourcePfd?.close() }
        mediaSourcePfd = null
    }

    private fun loadCurrent(shouldPlay: Boolean = true, startPositionMs: Long = 0L) {
        val track = queue.getOrNull(index)
        if (track == null) {
            android.util.Log.e("TruePlayer", "Não foi possível reproduzir: índice $index inválido para fila de ${queue.size} músicas")
            return
        }

        val generation = ++playerGeneration
        pendingPlay = shouldPlay
        mediaPrepared = false
        detachedPositionMs = -1L
        cancelIdleRelease()
        releaseCurrentPlayer()

        notificationArtwork = loadNotificationArtwork(track)
        notificationArtworkTrackId = track.id
        sessionMetadataTrackId = null
        sessionMetadataDurationMs = -1L
        playCountRecordedGeneration = -1L


        try {
            // MediaPlayer.create() já devolve o player PREPARADO. Usamos a variante
            // que recebe AudioAttributes para que os atributos sejam definidos antes
            // da preparação, evitando INVALID_OPERATION (-38).
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()

            val mediaPlayer = MediaPlayer.create(
                this,
                Uri.parse(track.id),
                null,
                audioAttributes,
                0,
            ) ?: throw IllegalStateException("MediaPlayer.create() retornou null para ${track.id}")

            if (generation != playerGeneration) {
                runCatching { mediaPlayer.release() }
                return
            }

            player = mediaPlayer
            mediaPrepared = true

            mediaPlayer.setOnCompletionListener {
                if (generation != playerGeneration || player !== mediaPlayer) return@setOnCompletionListener
                if (repeat == RepeatMode.ONE) {
                    runCatching {
                        mediaPlayer.seekTo(0)
                        if (pendingPlay) {
                            mediaPlayer.start()
                            if (playCountRecordedGeneration != generation) {
                                recordPlayCount(track)
                                playCountRecordedGeneration = generation
                            }
                        }
                    }.onFailure {
                        android.util.Log.e("TruePlayer", "Falha ao repetir música: ${track.id}", it)
                    }
                } else {
                    next(auto = true)
                }
            }

            mediaPlayer.setOnErrorListener { _, what, extra ->
                if (generation != playerGeneration || player !== mediaPlayer) return@setOnErrorListener true
                android.util.Log.e("TruePlayer", "Erro do MediaPlayer ao reproduzir ${track.id}: what=$what extra=$extra")
                pendingPlay = false
                mediaPrepared = false
                runCatching { mediaPlayer.reset() }
                runCatching { mediaPlayer.release() }
                player = null
                updateStateAndNotification()
                true
            }

            val safePosition = startPositionMs.coerceIn(
                0L,
                (mediaPlayer.duration.toLong() - 500L).coerceAtLeast(0L)
            )
            if (safePosition > 0L) {
                mediaPlayer.seekTo(safePosition.toInt())
            }

            var playbackStarted = false
            fun startWhenReady() {
                if (generation != playerGeneration || player !== mediaPlayer || playbackStarted) return
                if (!shouldPlay) {
                    updateStateAndNotification()
                    return
                }
                runCatching { mediaPlayer.start() }.onSuccess {
                    playbackStarted = true
                    if (playCountRecordedGeneration != generation) {
                        recordPlayCount(track)
                        playCountRecordedGeneration = generation
                    }
                    updateStateAndNotification()
                }.onFailure { error ->
                    android.util.Log.e("TruePlayer", "Falha ao iniciar música ${track.id}", error)
                    pendingPlay = false
                    updateStateAndNotification()
                }
            }

            startWhenReady()

            if (!shouldPlay) {
                updateStateAndNotification()
            }
        } catch (error: Throwable) {
            if (generation != playerGeneration) return
            pendingPlay = false
            mediaPrepared = false
            player = null
            android.util.Log.e("TruePlayer", "Falha ao preparar/iniciar música ${track.id}", error)
            updateStateAndNotification()
        }
    }

    private fun resume() {
        cancelIdleRelease()
        val p = player
        if (p == null) {
            // Player solto por ociosidade (ou nunca carregado): recarrega a faixa
            // atual exatamente na posição em que parou.
            if (queue.isEmpty()) {
                pendingPlay = true
                updateStateAndNotification()
                return
            }
            val startAt = detachedPositionMs.coerceAtLeast(0L)
            detachedPositionMs = -1L
            loadCurrent(shouldPlay = true, startPositionMs = startAt)
            return
        }
        if (!mediaPrepared) {
            pendingPlay = true
            updateStateAndNotification()
            return
        }
        runCatching {
            if (!p.isPlaying) {
                p.start()
                val track = queue.getOrNull(index)
                if (track != null && playCountRecordedGeneration != playerGeneration) {
                    recordPlayCount(track)
                    playCountRecordedGeneration = playerGeneration
                }
            }
        }.onFailure {
            pendingPlay = true
            android.util.Log.e("TruePlayer", "Falha ao iniciar reprodução: ${queue.getOrNull(index)?.id}", it)
        }
        updateStateAndNotification()
    }
    private fun pause() {
        if (!mediaPrepared) {
            pendingPlay = false
            updateStateAndNotification()
            return
        }
        runCatching { player?.pause() }
        pendingPlay = false
        scheduleIdleRelease()
        updateStateAndNotification()
    }

    private fun seek(pos: Long) {
        val p = player
        if (p == null || !mediaPrepared) return
        runCatching {
            p.seekTo(pos.coerceIn(0L, p.duration.toLong()).toInt())
        }.onFailure {
            android.util.Log.e("TruePlayer", "Falha ao buscar posição da música", it)
        }
        updateStateAndNotification()
    }

    private fun next(auto: Boolean = false) {
        if (queue.isEmpty()) return
        if (!auto) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now < navigationLockUntilMs) return
            navigationLockUntilMs = now + 150L
        }
        val wasPlaying = runCatching { player?.isPlaying == true }.getOrDefault(false)
        if (repeat == RepeatMode.ONE) {
            loadCurrent(wasPlaying || auto)
            return
        }
        index = if (index + 1 < queue.size) index + 1 else 0
        loadCurrent(wasPlaying || auto)
    }

    private fun previous() {
        if (queue.isEmpty()) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now < navigationLockUntilMs) return
        navigationLockUntilMs = now + 150L

        val currentPosition = runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)
        val wasPlaying = runCatching { pendingPlay || player?.isPlaying == true }.getOrDefault(pendingPlay)

        // Comportamento padrão de players de música: se já passou de ~3 s,
        // o botão anterior apenas reinicia a música atual; só depois volta
        // para a faixa anterior.
        if (currentPosition > 3_000L) {
            runCatching { player?.seekTo(0) }
            updateStateAndNotification()
            return
        }

        if (repeat == RepeatMode.ONE) {
            loadCurrent(wasPlaying)
            return
        }
        index = if (index - 1 >= 0) index - 1 else queue.lastIndex
        loadCurrent(wasPlaying)
    }

    private fun hideNotificationNow() {
        notificationSuppressed = true
        notificationLastUpdateMs = android.os.SystemClock.uptimeMillis()
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        runCatching {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(NOTIFICATION_ID)
        }
    }

    private fun stopPlayback() {
        ++playerGeneration
        handler.removeCallbacks(progressRunnable)
        cancelIdleRelease()
        // releaseCurrentPlayer() também fecha o ParcelFileDescriptor, que antes
        // ficava aberto aqui e segurava o arquivo mesmo após parar.
        releaseCurrentPlayer()
        queue.clear()
        baseQueue.clear()
        index = -1
        pendingPlay = false
        detachedPositionMs = -1L
        notificationArtwork = null
        notificationArtworkTrackId = null
        sessionMetadataTrackId = null
        sessionMetadataDurationMs = -1L
        runCatching { session.setMetadata(null) }
        session.isActive = false
        PlayerState.snapshot = PlayerSnapshot()
        AppEvents.bump()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun rebuildQueueFromSavedModes(sourceQueue: List<AudioTrack>, currentId: String?, savedShuffle: Boolean): Pair<MutableList<AudioTrack>, Int> {
        // A fila-base é sempre preservada na ordem recebida. Ela nunca é
        // embaralhada nem sobrescrita pelo modo shuffle.
        val orderedBase = sourceQueue.distinctBy { it.id }.toList()
        if (orderedBase.isEmpty()) return mutableListOf<AudioTrack>() to -1

        val currentIndex = currentId?.let { id ->
            orderedBase.indexOfFirst { it.id == id }
        }?.takeIf { it >= 0 } ?: 0

        // Sem shuffle: restaura exatamente a ordem original da fonte.
        if (!savedShuffle || orderedBase.size <= 1) {
            return orderedBase.toMutableList() to currentIndex
        }

        // Com shuffle: mantém a música atual em primeiro e só embaralha o
        // restante. Isso permite reconstruir uma fila válida sem persisti-la.
        val current = orderedBase[currentIndex]
        val rest = orderedBase.filterIndexed { i, _ -> i != currentIndex }.shuffled()
        return mutableListOf<AudioTrack>().apply {
            add(current)
            addAll(rest)
        } to 0
    }

    private fun restoreSavedPlaybackState(shouldPlay: Boolean = false) {
        val prefs = getSharedPreferences(PREFS_LAST, MODE_PRIVATE)
        val trackId = prefs.getString("track_id", "").orEmpty()
        if (trackId.isBlank()) return
        val track = AudioTrack(
            id = trackId,
            displayName = prefs.getString("track_title", "").orEmpty(),
            title = prefs.getString("track_title", "").orEmpty(),
            artist = prefs.getString("track_artist", "").orEmpty(),
            album = prefs.getString("track_album", "").orEmpty(),
            durationMs = prefs.getLong("track_duration", 0L),
        )
        shuffle = prefs.getBoolean("shuffle", false)
        repeat = if (prefs.getBoolean("repeat_one", false)) RepeatMode.ONE else RepeatMode.OFF
        playbackSourceType = prefs.getString("source_type", SOURCE_LIBRARY).orEmpty().ifBlank { SOURCE_LIBRARY }
        playbackSourceName = prefs.getString("source_name", "").orEmpty()
        baseQueue = mutableListOf(track)
        val rebuilt = rebuildQueueFromSavedModes(baseQueue, track.id, shuffle)
        queue = rebuilt.first
        index = rebuilt.second
        loadCurrent(shouldPlay = shouldPlay, startPositionMs = prefs.getLong("position_ms", 0L))
    }

    private fun persistPlaybackState() {
        val track = queue.getOrNull(index) ?: return
        val position = if (detachedPositionMs >= 0L) {
            detachedPositionMs
        } else {
            runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)
        }
        getSharedPreferences(PREFS_LAST, MODE_PRIVATE).edit()
            .putString("track_id", track.id)
            .putString("track_title", track.displayTitle())
            .putString("track_artist", track.artist)
            .putString("track_album", track.album)
            .putLong("track_duration", track.durationMs)
            .putBoolean("shuffle", shuffle)
            .putBoolean("repeat_one", repeat == RepeatMode.ONE)
            .putString("source_type", playbackSourceType)
            .putString("source_name", playbackSourceName)
            .putLong("position_ms", position.coerceAtLeast(0L))
            .apply()
    }

    private fun updateStateAndNotification() {
        val track = queue.getOrNull(index)
        val p = player
        val duration = track?.durationMs ?: 0L
        val position = if (detachedPositionMs >= 0L) {
            detachedPositionMs
        } else {
            runCatching { p?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)
        }
        val playing = runCatching { p?.isPlaying == true }.getOrDefault(false)
        val safeDuration = runCatching {
            p?.duration?.toLong()?.takeIf { it > 0 } ?: duration
        }.getOrDefault(duration)
        PlayerState.snapshot = PlayerSnapshot(track, queue.toList(), index, playing, position, safeDuration, shuffle, repeat)
        AppEvents.bump()
        if (track != null) persistPlaybackState()

        val state = if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP
        val playbackState = PlaybackState.Builder()
            .setActions(actions)
            .setState(state, position, 1f)
            .addCustomAction(PlaybackState.CustomAction.Builder(ACTION_SHUFFLE, if (shuffle) "Aleatório: ligado" else "Aleatório: desligado", if (shuffle) R.drawable.truetagger_shuffle_on else R.drawable.truetagger_shuffle_off).build())
            .addCustomAction(PlaybackState.CustomAction.Builder(ACTION_REPEAT, if (repeat == RepeatMode.ONE) "Repetir música: ligado" else "Repetição: desligada", if (repeat == RepeatMode.ONE) R.drawable.truetagger_repeat_on else R.drawable.truetagger_repeat_off).build())
            .build()
        session.setPlaybackState(playbackState)
        if (track != null) {
            // A arte grande da notificação de mídia (e da tela de bloqueio) vem
            // dos metadados da MediaSession, não do setLargeIcon — que o Android
            // reduz para ~64dp. Sem METADATA_KEY_ALBUM_ART o sistema só tinha o
            // ícone pequeno para esticar, daí a aparência borrada.
            if (sessionMetadataTrackId != track.id || sessionMetadataDurationMs != safeDuration) {
                sessionMetadataTrackId = track.id
                sessionMetadataDurationMs = safeDuration
                updateSessionMetadata(track, safeDuration)
            }
            val now = android.os.SystemClock.uptimeMillis()
            if (!notificationSuppressed && (now - notificationLastUpdateMs >= 1000L || !playing)) {
                notificationLastUpdateMs = now
                val notification = buildNotification(track, position, safeDuration, playing)
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun buildNotification(track: AudioTrack, position: Long, duration: Long, playing: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).apply { action = ShareContract.ACTION_OPEN_APP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        fun action(code: String, icon: Int, title: String): Notification.Action = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, icon), title,
            PendingIntent.getService(
                this, code.hashCode(), Intent(this, MusicPlaybackService::class.java).setAction(code),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        ).build()

        val shuffleTitle = if (shuffle) "Aleatório: ligado" else "Aleatório: desligado"
        val repeatTitle = if (repeat == RepeatMode.ONE) "Repetir música: ligado" else "Repetição: desligada"
        val style = Notification.MediaStyle().setMediaSession(session.sessionToken)
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.truetagger_status)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setPriority(Notification.PRIORITY_LOW)
            .setContentTitle(track.displayTitle())
            .setContentText(listOf(track.album, track.artist).filter { it.isNotBlank() }.joinToString(" - "))
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setStyle(style)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            style.setShowActionsInCompactView(0, 2, 4)
            builder.addAction(action(ACTION_PREVIOUS, android.R.drawable.ic_media_previous, "Anterior"))
                .addAction(action(ACTION_SHUFFLE, if (shuffle) R.drawable.truetagger_shuffle_on else R.drawable.truetagger_shuffle_off, shuffleTitle))
                .addAction(action(ACTION_TOGGLE, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (playing) "Pausar" else "Tocar"))
                .addAction(action(ACTION_NEXT, android.R.drawable.ic_media_next, "Próxima"))
                .addAction(action(ACTION_REPEAT, if (repeat == RepeatMode.ONE) R.drawable.truetagger_repeat_on else R.drawable.truetagger_repeat_off, repeatTitle))
        }
        if (Build.VERSION.SDK_INT >= 26) {
            builder.setProgress(duration.toInt().coerceAtLeast(1), position.toInt().coerceIn(0, duration.toInt().coerceAtLeast(1)), false)
        }
        if (notificationArtworkTrackId != track.id) {
            notificationArtwork = loadNotificationArtwork(track)
            notificationArtworkTrackId = track.id
        }
        notificationArtwork?.let { builder.setLargeIcon(it) }
        return builder.build()
    }

    /**
     * Entrega título, artista, álbum, duração e capa à MediaSession.
     *
     * É isso que alimenta o player de mídia do sistema, a tela de bloqueio e o
     * Android Auto. A capa aqui vai em resolução alta de propósito: quem decide
     * o tamanho final é o sistema.
     */
    private fun updateSessionMetadata(track: AudioTrack, durationMs: Long) {
        runCatching {
            val artwork = if (notificationArtworkTrackId == track.id) {
                notificationArtwork
            } else {
                loadNotificationArtwork(track).also {
                    notificationArtwork = it
                    notificationArtworkTrackId = track.id
                }
            }
            val builder = android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, track.displayTitle())
                .putString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE, track.displayTitle())
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, track.artist)
                .putString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, track.artist)
                .putString(android.media.MediaMetadata.METADATA_KEY_ALBUM, track.album)
                .putString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST, track.albumArtist.ifBlank { track.artist })
                .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, durationMs.coerceAtLeast(0L))
            artwork?.let {
                builder.putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, it)
                builder.putBitmap(android.media.MediaMetadata.METADATA_KEY_ART, it)
            }
            session.setMetadata(builder.build())
        }
    }

    private fun loadNotificationArtwork(track: AudioTrack): android.graphics.Bitmap? {
        // A capa vinha quase sempre do loadThumbnail(512) do MediaStore, que
        // devolve uma miniatura já comprimida — daí a baixa qualidade. A ordem
        // agora é: bytes embutidos na faixa, depois a arte embutida no arquivo
        // (resolução original) e só então a miniatura, num tamanho maior.
        val target = NOTIFICATION_ARTWORK_SIZE

        fun decode(raw: ByteArray): android.graphics.Bitmap? = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
            val max = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            var sample = 1
            // Só reduz enquanto continuar acima do alvo: nunca abaixo dele.
            while (max / (sample * 2) >= target) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
        }.getOrNull()

        val embedded = track.artworkBytes?.takeIf { it.isNotEmpty() }?.let(::decode)

        val fromFile = embedded ?: runCatching {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(this, Uri.parse(track.id))
                retriever.embeddedPicture?.let(::decode)
            }
        }.getOrNull()

        val fallback = fromFile ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                contentResolver.loadThumbnail(Uri.parse(track.id), android.util.Size(target, target), null)
            }.getOrNull()
        } else null

        // O ícone grande da notificação fica muito melhor com a capa quadrada.
        return fallback?.let { bitmap ->
            val side = minOf(bitmap.width, bitmap.height)
            if (side <= 0) return@let bitmap
            val left = (bitmap.width - side) / 2
            val top = (bitmap.height - side) / 2
            runCatching {
                android.graphics.Bitmap.createBitmap(bitmap, left, top, side, side)
            }.getOrElse { bitmap }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Música", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Player de música do TruePlayer"
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
            )
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Ao fechar o app pela lista de recentes, encerra também a reprodução.
        stopPlayback()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        persistPlaybackState()
        runCatching { unregisterReceiver(noisyReceiver) }
        handler.removeCallbacks(progressRunnable)
        cancelIdleRelease()
        ++playerGeneration
        releaseCurrentPlayer()
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

private fun AudioTrack.displayTitle(): String = title.ifBlank { displayName.ifBlank { location.substringAfterLast("/") } }

