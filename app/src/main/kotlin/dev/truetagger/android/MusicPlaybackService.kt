
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
import android.media.audiofx.LoudnessEnhancer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import kotlin.math.roundToInt
import kotlin.math.pow
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
        const val ACTION_SET_VOLUME_NORMALIZATION = "dev.truetagger.player.SET_VOLUME_NORMALIZATION"
        const val ACTION_RESTORE_LAST = "dev.truetagger.player.RESTORE_LAST"
        const val ACTION_RECONCILE_DELETED_TRACKS = "dev.truetagger.player.RECONCILE_DELETED_TRACKS"
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

        fun setVolumeNormalization(context: Context, enabled: Boolean) {
            val i = Intent(context, MusicPlaybackService::class.java).apply {
                action = ACTION_SET_VOLUME_NORMALIZATION
                putExtra(EXTRA_ENABLED, enabled)
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
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var normalizationRampGeneration = 0L
    private var restoringLastPlayback = false
    private var playCountRecordedGeneration = -1L

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
        when (intent?.action) {
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
                        pendingPlay = true
                        updateStateAndNotification()
                    }
                } else if (runCatching { p.isPlaying }.getOrDefault(false)) {
                    pause()
                } else {
                    resume()
                }
            }
            ACTION_NEXT -> next()
            ACTION_PREVIOUS -> previous()
            ACTION_PLAY_QUEUE_INDEX -> playQueueIndex(intent.getIntExtra(EXTRA_INDEX, -1))
            ACTION_SET_VOLUME_NORMALIZATION -> {
                val enabled = intent.getBooleanExtra(EXTRA_ENABLED, false)
                VolumeNormalizer.setEnabled(this, enabled)
                if (enabled) {
                    val current = player
                    val track = queue.getOrNull(index)
                    if (current != null && track != null && mediaPrepared) {
                        applyVolumeNormalization(current, track, playerGeneration)
                    }
                } else {
                    clearVolumeNormalization()
                }
                updateStateAndNotification()
            }
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

    private var mediaErrorRetryCount = 0
    private var lastMediaErrorKey: String? = null
    private var lastMediaErrorAtMs = 0L

    private fun clearVolumeNormalization() {
        runCatching { loudnessEnhancer?.enabled = false }
        runCatching { loudnessEnhancer?.release() }
        loudnessEnhancer = null
        runCatching { player?.setVolume(1f, 1f) }
    }

    private fun applyVolumeNormalization(mediaPlayer: MediaPlayer, track: AudioTrack, generation: Long) {
        clearVolumeNormalization()
        if (!VolumeNormalizer.isEnabled(this)) return

        val cacheKey = "gain_" + track.id.hashCode().toUInt().toString(16)
        val prefs = getSharedPreferences("truetagger_volume_normalization_cache_v2", MODE_PRIVATE)
        val cached = if (prefs.contains(cacheKey)) prefs.getFloat(cacheKey, 0f) else null

        // Start every new track at the neutral 80% baseline. The normalization
        // target is then reached gradually so the beginning of the song never
        // gets a sudden volume jump/drop while the gain is being applied.
        val normalizedOutputLevel = VolumeNormalizer.getOutputLevel(this).coerceIn(0.50f, 1.00f)
        runCatching { mediaPlayer.setVolume(normalizedOutputLevel, normalizedOutputLevel) }
        clearVolumeEnhancerOnly()

        fun rampToGain(gainDb: Float) {
            if (generation != playerGeneration || player !== mediaPlayer) return

            val rampGeneration = ++normalizationRampGeneration
            val clampedGain = gainDb.coerceIn(-10f, 10f)
            val steps = 20
            val stepDelayMs = 50L
            val baseline = normalizedOutputLevel

            val effect = if (clampedGain > 0f) {
                runCatching { LoudnessEnhancer(mediaPlayer.audioSessionId) }.getOrNull()?.also { created ->
                    loudnessEnhancer = created
                    runCatching {
                        created.setTargetGain(0)
                        created.enabled = true
                    }.onFailure {
                        runCatching { created.release() }
                        if (loudnessEnhancer === created) loudnessEnhancer = null
                    }
                }
            } else null

            var step = 0
            val runnable = object : Runnable {
                override fun run() {
                    if (generation != playerGeneration || player !== mediaPlayer || rampGeneration != normalizationRampGeneration) return
                    step++
                    val fraction = (step / steps.toFloat()).coerceIn(0f, 1f)
                    if (clampedGain < 0f) {
                        val targetLinear = (baseline * 10.0.pow((clampedGain / 20.0).toDouble()).toFloat())
                            .coerceIn(0.05f, baseline)
                        val currentLinear = baseline + (targetLinear - baseline) * fraction
                        runCatching { mediaPlayer.setVolume(currentLinear, currentLinear) }
                    } else if (effect != null) {
                        val targetMilliDb = (clampedGain * 100f).roundToInt()
                        val currentMilliDb = (targetMilliDb * fraction).roundToInt()
                        runCatching { effect.setTargetGain(currentMilliDb) }
                    }
                    if (step < steps) handler.postDelayed(this, stepDelayMs)
                }
            }
            handler.post(runnable)
        }

        if (cached != null) {
            rampToGain(cached)
            return
        }

        VolumeNormalizer.analyzeGainAsync(this, Uri.parse(track.id)) { gainDb ->
            prefs.edit().putFloat(cacheKey, gainDb).apply()
            rampToGain(gainDb)
        }
    }

    private fun prewarmVolumeNormalization(track: AudioTrack) {
        val cacheKey = "gain_" + track.id.hashCode().toUInt().toString(16)
        val prefs = getSharedPreferences("truetagger_volume_normalization_cache_v2", MODE_PRIVATE)
        if (prefs.contains(cacheKey)) return
        VolumeNormalizer.analyzeGainAsync(this, Uri.parse(track.id)) { gainDb ->
            prefs.edit().putFloat(cacheKey, gainDb).apply()
        }
    }

    private fun clearVolumeEnhancerOnly() {
        runCatching { loudnessEnhancer?.enabled = false }
        runCatching { loudnessEnhancer?.release() }
        loudnessEnhancer = null
    }

    private fun recordPlayCount(trackId: String) {
        if (trackId.isBlank()) return
        PlayStatsState.record(this, trackId)
        AppEvents.bump()
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
        releaseCurrentPlayer()
        clearVolumeNormalization()

        notificationArtwork = loadNotificationArtwork(track)
        notificationArtworkTrackId = track.id
        playCountRecordedGeneration = -1L

        if (VolumeNormalizer.isEnabled(this)) {
            queue.getOrNull(index + 1)?.let(::prewarmVolumeNormalization)
        }

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
                                recordPlayCount(track.id)
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

            applyVolumeNormalization(mediaPlayer, track, generation)

            if (shouldPlay) {
                mediaPlayer.start()
                if (playCountRecordedGeneration != generation) {
                    recordPlayCount(track.id)
                    playCountRecordedGeneration = generation
                }
            }

            updateStateAndNotification()
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
        val p = player ?: return run { pendingPlay = true; updateStateAndNotification() }
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
                    recordPlayCount(track.id)
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
        mediaPrepared = false
        session.isActive = false
        PlayerState.snapshot = PlayerSnapshot()
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
        val position = runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)
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
        val position = runCatching {
            p?.currentPosition?.toLong() ?: 0L
        }.getOrDefault(0L)
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
        // Always use the real embedded cover first. Then fall back to MediaStore.
        val raw = track.artworkBytes
        val source = if (raw != null && raw.isNotEmpty()) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
                val max = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
                val sample = generateSequence(1) { it * 2 }
                    .takeWhile { it < 16 }
                    .lastOrNull { max / it >= 512 } ?: 1
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
            }.getOrNull()
        } else null

        val fallback = source ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                contentResolver.loadThumbnail(Uri.parse(track.id), android.util.Size(512, 512), null)
            }.getOrNull()
        } else null

        // Notification large icons look much better when the artwork is a clean square.
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

    override fun onDestroy() {
        persistPlaybackState()
        handler.removeCallbacks(progressRunnable)
        ++playerGeneration
        clearVolumeNormalization()
        releaseCurrentPlayer()
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

private fun AudioTrack.displayTitle(): String = title.ifBlank { displayName.ifBlank { location.substringAfterLast("/") } }

