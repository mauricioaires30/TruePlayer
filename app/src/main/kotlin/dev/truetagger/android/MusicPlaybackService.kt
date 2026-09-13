
package dev.truetagger.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.AudioAttributes
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.IBinder
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
        const val ACTION_PLAY_QUEUE_INDEX = "dev.truetagger.player.PLAY_QUEUE_INDEX"
        const val ACTION_REPLACE_QUEUE = "dev.truetagger.player.REPLACE_QUEUE"
        const val EXTRA_TRACK_ID = "track_id"
        const val EXTRA_TRACK_TITLE = "track_title"
        const val EXTRA_TRACK_ARTIST = "track_artist"
        const val EXTRA_TRACK_ALBUM = "track_album"
        const val EXTRA_TRACK_DURATION = "track_duration"
        const val EXTRA_TRACK_ARTWORK = "track_artwork"
        const val EXTRA_QUEUE_IDS = "queue_ids"
        const val EXTRA_QUEUE_TITLES = "queue_titles"
        const val EXTRA_QUEUE_ARTISTS = "queue_artists"
        const val EXTRA_QUEUE_ALBUMS = "queue_albums"
        const val EXTRA_QUEUE_DURATIONS = "queue_durations"
        const val EXTRA_INDEX = "index"
        const val EXTRA_START_SHUFFLE = "start_shuffle"
        const val EXTRA_POSITION = "position"
        const val EXTRA_CURRENT_ID = "current_id"
        const val CHANNEL = "truetagger_player_v4"
        const val NOTIFICATION_ID = 301

        fun play(context: Context, track: AudioTrack, queue: List<AudioTrack>, index: Int, startShuffle: Boolean = false) {
            val i = Intent(context, MusicPlaybackService::class.java).apply {
                action = ACTION_PLAY
                putExtra(EXTRA_TRACK_ID, track.id)
                putExtra(EXTRA_TRACK_TITLE, track.displayTitle())
                putExtra(EXTRA_TRACK_ARTIST, track.artist)
                putExtra(EXTRA_TRACK_ALBUM, track.album)
                putExtra(EXTRA_TRACK_DURATION, track.durationMs)
                track.artworkBytes?.let { putExtra(EXTRA_TRACK_ARTWORK, it) }
                putStringArrayListExtra(EXTRA_QUEUE_IDS, ArrayList(queue.map { it.id }))
                putStringArrayListExtra(EXTRA_QUEUE_TITLES, ArrayList(queue.map { it.displayTitle() }))
                putStringArrayListExtra(EXTRA_QUEUE_ARTISTS, ArrayList(queue.map { it.artist }))
                putStringArrayListExtra(EXTRA_QUEUE_ALBUMS, ArrayList(queue.map { it.album }))
                putStringArrayListExtra(EXTRA_QUEUE_DURATIONS, ArrayList(queue.map { it.durationMs.toString() }))
                putExtra(EXTRA_INDEX, index)
                putExtra(EXTRA_START_SHUFFLE, startShuffle)
            }
            context.startForegroundService(i)
        }

        fun command(context: Context, action: String, position: Long? = null) {
            val i = Intent(context, MusicPlaybackService::class.java).apply {
                this.action = action
                if (position != null) putExtra(EXTRA_POSITION, position)
            }
            context.startForegroundService(i)
        }

        fun replaceQueue(context: Context, tracks: List<AudioTrack>) {
            if (tracks.isEmpty()) return
            val i = Intent(context, MusicPlaybackService::class.java).apply {
                action = ACTION_REPLACE_QUEUE
                putStringArrayListExtra(EXTRA_QUEUE_IDS, ArrayList(tracks.map { it.id }))
                putStringArrayListExtra(EXTRA_QUEUE_TITLES, ArrayList(tracks.map { it.displayTitle() }))
                putStringArrayListExtra(EXTRA_QUEUE_ARTISTS, ArrayList(tracks.map { it.artist }))
                putStringArrayListExtra(EXTRA_QUEUE_ALBUMS, ArrayList(tracks.map { it.album }))
                putStringArrayListExtra(EXTRA_QUEUE_DURATIONS, ArrayList(tracks.map { it.durationMs.toString() }))
            }
            context.startService(i)
        }
    }

    private var player: MediaPlayer? = null
    private lateinit var session: MediaSession
    private lateinit var handler: android.os.Handler
    private var queue = mutableListOf<AudioTrack>()
    private var baseQueue = mutableListOf<AudioTrack>()
    private var index = -1
    private var shuffle = false
    private var repeat = RepeatMode.OFF
    private var pendingPlay = false
    private var mediaPrepared = false
    private var playerGeneration = 0L
    private var navigationLockUntilMs = 0L
    private var notificationArtwork: android.graphics.Bitmap? = null
    private var notificationArtworkTrackId: String? = null
    private var notificationLastUpdateMs = 0L

    private val progressRunnable = object : Runnable {
        override fun run() {
            updateStateAndNotification()
            if (player != null || queue.isNotEmpty()) {
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        handler = android.os.Handler(mainLooper)
        createChannel()
        session = MediaSession(this, "TrueTagger").apply {
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
        when (intent?.action) {
            ACTION_PLAY -> {
                val ids = intent.getStringArrayListExtra(EXTRA_QUEUE_IDS).orEmpty()
                if (ids.isEmpty()) {
                    android.util.Log.e("TrueTagger", "Não foi possível reproduzir: fila de músicas vazia")
                }
                val titles = intent.getStringArrayListExtra(EXTRA_QUEUE_TITLES).orEmpty()
                val artists = intent.getStringArrayListExtra(EXTRA_QUEUE_ARTISTS).orEmpty()
                val albums = intent.getStringArrayListExtra(EXTRA_QUEUE_ALBUMS).orEmpty()
                val durations = intent.getStringArrayListExtra(EXTRA_QUEUE_DURATIONS)?.mapNotNull { it.toLongOrNull() }.orEmpty()
                val hadPreviousQueue = queue.isNotEmpty()
                queue = ids.mapIndexed { i, id ->
                    AudioTrack(
                        id = id,
                        displayName = titles.getOrNull(i).orEmpty(),
                        title = titles.getOrNull(i).orEmpty(),
                        artist = artists.getOrNull(i).orEmpty(),
                        album = albums.getOrNull(i).orEmpty(),
                        durationMs = durations.getOrNull(i) ?: 0L,
                    )
                }.toMutableList()
                index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (queue.size - 1).coerceAtLeast(0))
                intent.getByteArrayExtra(EXTRA_TRACK_ARTWORK)?.let { bytes ->
                    queue.getOrNull(index)?.let { current -> queue[index] = current.copy(artworkBytes = bytes) }
                }
                baseQueue = queue.toMutableList()
                // Ao escolher uma nova música, preserve o estado atual de aleatório/repetição.
                // O estado inicial só é definido quando o serviço ainda não tinha uma fila.
                val requestedShuffle = intent.getBooleanExtra(EXTRA_START_SHUFFLE, false)
                if (!hadPreviousQueue) shuffle = requestedShuffle
                if (shuffle && queue.size > 1) {
                    val current = queue.getOrNull(index)
                    val rest = baseQueue.filterIndexed { i, _ -> i != index }.shuffled()
                    if (current != null) {
                        queue = mutableListOf(current).apply { addAll(rest) }
                        index = 0
                    }
                }
                loadCurrent()
            }
            ACTION_TOGGLE -> {
                val p = player
                if (p == null || !mediaPrepared) {
                    pendingPlay = !pendingPlay
                    updateStateAndNotification()
                } else if (runCatching { p.isPlaying }.getOrDefault(false)) {
                    pause()
                } else {
                    resume()
                }
            }
            ACTION_NEXT -> next()
            ACTION_PREVIOUS -> previous()
            ACTION_PLAY_QUEUE_INDEX -> playQueueIndex(intent.getIntExtra(EXTRA_INDEX, -1))
            ACTION_REPLACE_QUEUE -> replaceCurrentQueue(
                intent.getStringArrayListExtra(EXTRA_QUEUE_IDS).orEmpty(),
                intent.getStringArrayListExtra(EXTRA_QUEUE_TITLES).orEmpty(),
                intent.getStringArrayListExtra(EXTRA_QUEUE_ARTISTS).orEmpty(),
                intent.getStringArrayListExtra(EXTRA_QUEUE_ALBUMS).orEmpty(),
                intent.getStringArrayListExtra(EXTRA_QUEUE_DURATIONS).orEmpty()
            )
            ACTION_SEEK -> seek(intent.getLongExtra(EXTRA_POSITION, 0L))
            ACTION_SHUFFLE -> setShuffle(!shuffle)
            ACTION_REPEAT -> cycleRepeat()
            ACTION_STOP -> stopPlayback()
        }
        updateStateAndNotification()
        return START_NOT_STICKY
    }

    private fun replaceCurrentQueue(
        ids: List<String>,
        titles: List<String>,
        artists: List<String>,
        albums: List<String>,
        durations: List<String>
    ) {
        if (ids.isEmpty()) return
        val currentId = queue.getOrNull(index)?.id
        val newBase = ids.mapIndexed { i, id ->
            AudioTrack(
                id = id,
                displayName = titles.getOrNull(i).orEmpty(),
                title = titles.getOrNull(i).orEmpty(),
                artist = artists.getOrNull(i).orEmpty(),
                album = albums.getOrNull(i).orEmpty(),
                durationMs = durations.getOrNull(i)?.toLongOrNull() ?: 0L,
            )
        }.toMutableList()
        baseQueue = newBase.toMutableList()
        queue = if (shuffle) {
            val current = newBase.firstOrNull { it.id == currentId }
            val rest = newBase.filter { it.id != currentId }
            if (current != null) mutableListOf(current).apply { addAll(rest) } else newBase.toMutableList()
        } else newBase.toMutableList()
        index = if (currentId != null) queue.indexOfFirst { it.id == currentId }.let { if (it >= 0) it else 0 } else 0
        if (shuffle && queue.getOrNull(index)?.id == currentId) index = 0
        updateStateAndNotification()
    }

    private fun playQueueIndex(targetIndex: Int) {
        if (targetIndex !in queue.indices) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now < navigationLockUntilMs) return
        navigationLockUntilMs = now + 250L
        val wasPlaying = runCatching { player?.isPlaying == true }.getOrDefault(false)
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

    private var mediaErrorRetryCount = 0
    private var lastMediaErrorKey: String? = null
    private var lastMediaErrorAtMs = 0L

    private fun loadCurrent(shouldPlay: Boolean = true) {
        val track = queue.getOrNull(index)
        if (track == null) {
            android.util.Log.e("TrueTagger", "Não foi possível reproduzir: índice $index inválido para fila de ${queue.size} músicas")
            return
        }

        val generation = ++playerGeneration
        mediaErrorRetryCount = 0
        player?.setOnCompletionListener(null)
        player?.setOnErrorListener(null)
        runCatching { player?.stop() }
        player?.release()
        player = null
        pendingPlay = shouldPlay
        mediaPrepared = false

        // Não bloqueie a thread principal preparando a mídia. prepareAsync reduz o
        // atraso perceptível ao trocar de faixa, especialmente com arquivos maiores.
        try {
            val mediaPlayer = MediaPlayer()
            mediaPlayer.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            val sourceUri = Uri.parse(track.id)
            runCatching {
                mediaPlayer.setDataSource(this, sourceUri)
            }.getOrElse { directError ->
                // Alguns dispositivos/MediaStore providers não lidam bem com a sobrecarga
                // Context+Uri. Tenta uma vez via FileDescriptor antes de abandonar a faixa.
                val pfd = contentResolver.openFileDescriptor(sourceUri, "r")
                    ?: throw directError
                pfd.use { mediaPlayer.setDataSource(it.fileDescriptor) }
            }
            var prepared = false

            mediaPlayer.setOnPreparedListener {
                if (generation != playerGeneration) {
                    runCatching { mediaPlayer.release() }
                    return@setOnPreparedListener
                }
                prepared = true
                mediaPrepared = true
                val shouldStart = pendingPlay
                if (shouldStart) {
                    runCatching { mediaPlayer.start() }
                        .onFailure {
                            android.util.Log.e("TrueTagger", "Falha ao iniciar reprodução: ${track.id}", it)
                            pendingPlay = false
                        }
                    if (shouldStart) pendingPlay = false
                }
                updateStateAndNotification()
            }

            mediaPlayer.setOnCompletionListener {
                if (generation != playerGeneration || !prepared) return@setOnCompletionListener
                if (repeat == RepeatMode.ONE) {
                    runCatching {
                        mediaPlayer.seekTo(0)
                        if (shouldPlay || pendingPlay) mediaPlayer.start()
                    }.onFailure {
                        android.util.Log.e("TrueTagger", "Falha ao repetir música: ${track.id}", it)
                    }
                } else {
                    next(auto = true)
                }
            }

            mediaPlayer.setOnErrorListener { _, what, extra ->
                val now = android.os.SystemClock.uptimeMillis()
                val duplicate = lastMediaErrorKey == track.id && now - lastMediaErrorAtMs < 1000L
                if (!duplicate) {
                    if (what == -38) {
                        android.util.Log.w("TrueTagger", "MediaPlayer entrou em estado inválido ao reproduzir ${track.id}; encerrando a tentativa")
                    } else {
                        android.util.Log.e("TrueTagger", "Erro do MediaPlayer ao reproduzir ${track.id}: what=$what extra=$extra")
                    }
                    lastMediaErrorKey = track.id
                    lastMediaErrorAtMs = now
                }
                if (generation == playerGeneration) {
                    mediaPrepared = false
                    // -38 é INVALID_OPERATION/estado inválido; repetir imediatamente
                    // tende a recriar o mesmo erro e deixa a troca de faixa mais pesada.
                    if (what == -38 || mediaErrorRetryCount > 0) {
                        mediaErrorRetryCount = 1
                        pendingPlay = false
                        if (player === mediaPlayer) {
                            player = null
                        }
                        runCatching { mediaPlayer.reset() }
                        runCatching { mediaPlayer.release() }
                        updateStateAndNotification()
                    } else {
                        mediaErrorRetryCount = 1
                        handler.postDelayed({
                            if (generation == playerGeneration && queue.getOrNull(index)?.id == track.id) {
                                loadCurrent(pendingPlay)
                            }
                        }, 180L)
                    }
                }
                true
            }

            player = mediaPlayer
            notificationArtwork = track.artworkBytes?.takeIf { it.isNotEmpty() }?.let { raw ->
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
                    val max = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
                    val sample = generateSequence(1) { it * 2 }.takeWhile { it < 8 }.lastOrNull { max / it >= 1024 } ?: 1
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
                    BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
                }.getOrNull()
            }
            notificationArtworkTrackId = track.id

            updateStateAndNotification()
            try {
                mediaPlayer.prepareAsync()
            } catch (error: IllegalStateException) {
                android.util.Log.w("TrueTagger", "prepareAsync falhou; tentando preparar novamente ${track.id}", error)
                runCatching {
                    mediaPlayer.reset()
                    mediaPlayer.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    runCatching {
                        mediaPlayer.setDataSource(this, Uri.parse(track.id))
                    }.getOrElse {
                        val pfd = contentResolver.openFileDescriptor(Uri.parse(track.id), "r")
                            ?: throw it
                        pfd.use { mediaPlayer.setDataSource(it.fileDescriptor) }
                    }
                    mediaPlayer.prepareAsync()
                }.onFailure { retryError ->
                    android.util.Log.e("TrueTagger", "Falha ao preparar música após retry ${track.id}", retryError)
                    if (generation == playerGeneration) {
                        runCatching { mediaPlayer.release() }
                        if (player === mediaPlayer) player = null
                        pendingPlay = false
                        updateStateAndNotification()
                    }
                }
            }
        } catch (error: Throwable) {
            android.util.Log.e("TrueTagger", "Falha ao abrir música ${track.id}", error)
            player?.release()
            player = null
            pendingPlay = false
            if (generation == playerGeneration) updateStateAndNotification()
        }

        session.setMetadata(
            android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, track.displayTitle())
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, track.artist)
                .putString(android.media.MediaMetadata.METADATA_KEY_ALBUM, track.album)
                .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, track.durationMs)
                .apply { notificationArtwork?.let { putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                .build()
        )
    }

    private fun resume() {
        val p = player ?: return run { pendingPlay = true; updateStateAndNotification() }
        if (!mediaPrepared) {
            pendingPlay = true
            updateStateAndNotification()
            return
        }
        runCatching {
            if (!p.isPlaying) p.start()
        }.onFailure {
            pendingPlay = true
            android.util.Log.e("TrueTagger", "Falha ao iniciar reprodução: ${queue.getOrNull(index)?.id}", it)
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
        updateStateAndNotification()
    }

    private fun seek(pos: Long) {
        player?.seekTo(pos.coerceIn(0L, (player?.duration ?: 0).toLong()).toInt())
        updateStateAndNotification()
    }

    private fun next(auto: Boolean = false) {
        if (queue.isEmpty()) return
        if (!auto) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now < navigationLockUntilMs) return
            navigationLockUntilMs = now + 450L
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
        navigationLockUntilMs = now + 450L
        val wasPlaying = runCatching { player?.isPlaying == true }.getOrDefault(false)
        if (repeat == RepeatMode.ONE) {
            loadCurrent(wasPlaying)
            return
        }
        index = if (index - 1 >= 0) index - 1 else queue.lastIndex
        loadCurrent(wasPlaying)
    }

    private fun stopPlayback() {
        ++playerGeneration
        handler.removeCallbacks(progressRunnable)
        player?.setOnCompletionListener(null)
        player?.setOnErrorListener(null)
        runCatching { player?.stop() }
        player?.release()
        player = null
        mediaPrepared = false
        queue.clear()
        baseQueue.clear()
        index = -1
        pendingPlay = false
        session.isActive = false
        PlayerState.snapshot = PlayerSnapshot()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateStateAndNotification() {
        val track = queue.getOrNull(index)
        val p = player
        val duration = track?.durationMs ?: 0L
        val position = runCatching {
            p?.currentPosition?.toLong() ?: 0L
        }.getOrDefault(0L)
        val playing = runCatching { p?.isPlaying == true }.getOrDefault(false)
        val safeDuration = runCatching {
            p?.duration?.toLong()?.takeIf { it > 0 } ?: duration
        }.getOrDefault(duration)
        PlayerState.snapshot = PlayerSnapshot(track, queue.toList(), index, playing, position, safeDuration, shuffle, repeat)

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
            val now = android.os.SystemClock.uptimeMillis()
            if (now - notificationLastUpdateMs >= 1000L || !playing) {
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

    private fun loadNotificationArtwork(track: AudioTrack): android.graphics.Bitmap? {
        val raw = track.artworkBytes
        if (raw != null && raw.isNotEmpty()) {
            return runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
                val max = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
                val sample = generateSequence(1) { it * 2 }.takeWhile { it < 8 }.lastOrNull { max / it >= 1024 } ?: 1
                val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
                BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
            }.getOrNull()
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { contentResolver.loadThumbnail(Uri.parse(track.id), android.util.Size(1024, 1024), null) }.getOrNull()
        } else null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Música", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Player de música do TrueTagger"
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
            )
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(progressRunnable)
        ++playerGeneration
        player?.setOnCompletionListener(null)
        player?.setOnErrorListener(null)
        runCatching { player?.stop() }
        player?.release()
        player = null
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

private fun AudioTrack.displayTitle(): String = title.ifBlank { displayName.ifBlank { location.substringAfterLast("/") } }

