package dev.truetagger.ui

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.media.MediaMetadataRetriever
import android.util.Log
import android.util.Size
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.truetagger.android.MusicPlaybackService
import dev.truetagger.android.R
import dev.truetagger.android.PlayerSnapshot
import dev.truetagger.android.PlayerState
import dev.truetagger.android.RepeatMode
import dev.truetagger.android.VolumeNormalizer
import dev.truetagger.model.AppSettings
import dev.truetagger.model.AudioTrack
import dev.truetagger.android.AppEvents
import dev.truetagger.android.PlayStatsState
import dev.truetagger.model.EditableTags
import dev.truetagger.model.TagWriteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import java.text.Normalizer



private val artworkCache = object : LruCache<String, Bitmap>(64 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
}

private val artistPhotoCache = object : LruCache<String, Bitmap>(12 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
}

private val artistPhotoUrlCache = mutableMapOf<String, String?>()
private val artistPhotoLock = Any()

private suspend fun fetchArtistPhoto(artist: String): Bitmap? = withContext(Dispatchers.IO) {
    val normalized = Normalizer.normalize(artist, Normalizer.Form.NFKC).trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
    artistPhotoCache.get(normalized)?.let { return@withContext it }
    synchronized(artistPhotoLock) {
        if (artistPhotoUrlCache.containsKey(normalized) && artistPhotoUrlCache[normalized].isNullOrBlank()) return@withContext null
    }

    runCatching {
        val query = URLEncoder.encode(artist.trim(), Charsets.UTF_8.name())
        val searchUrl = URL("https://api.deezer.com/search/artist?q=$query&limit=1")
        val connection = (searchUrl.openConnection() as HttpURLConnection).apply {
            connectTimeout = 3500
            readTimeout = 5000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "TruePlayer/1.0")
        }
        val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        connection.disconnect()
        val data = JSONObject(json).optJSONArray("data")
        val artistJson = data?.optJSONObject(0)
        val picture = artistJson?.optString("picture_xl").orEmpty().ifBlank { artistJson?.optString("picture_big").orEmpty() }.ifBlank { artistJson?.optString("picture_medium").orEmpty() }.ifBlank { artistJson?.optString("picture").orEmpty() }
        synchronized(artistPhotoLock) { artistPhotoUrlCache[normalized] = picture.ifBlank { null } }
        if (picture.isBlank()) return@runCatching null
        val imageConnection = (URL(picture).openConnection() as HttpURLConnection).apply {
            connectTimeout = 3500
            readTimeout = 6000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "TruePlayer/1.0")
        }
        val bitmap = imageConnection.inputStream.use { input ->
            val bytes = input.readBytes()
            if (bytes.isEmpty()) return@use null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val maxDimension = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            var sample = 1
            while (maxDimension / sample > 1024) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample.coerceAtLeast(1)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }
        imageConnection.disconnect()
        if (bitmap != null) artistPhotoCache.put(normalized, bitmap)
        bitmap
    }.getOrNull()
}

private enum class HomeTab(val label: String) {
    LIBRARY("Biblioteca"), MOST_PLAYED("Playlists"), PLAYLISTS("Playlists"), ARTISTS("Artistas"), ALBUMS("Álbuns"), GENRES("Gêneros"), SETTINGS("Config.")
}
private enum class PlayerScreen { NONE, FULL, QUEUE }
private enum class Detail { NONE, ARTIST, ALBUM, GENRE, PLAYLIST }
private enum class PlayerSortMode(val label: String) {
    NAME("Nome"), TITLE("Título"), ARTIST("Artista"), ALBUM("Álbum"), DATE("Data"), DURATION("Duração")
}
private enum class PlaylistSortMode(val label: String) {
    MANUAL("Manual"), TITLE("Título"), ARTIST("Artista"), ALBUM("Álbum"), DATE("Data"), DURATION("Duração")
}
private enum class AlbumSortMode(val label: String) {
    NAME("Nome"), ARTIST("Artista"), YEAR("Ano"), DURATION("Duração"), SONGS("Quantidade de músicas")
}
private enum class MostPlayedMode(val label: String) {
    OVERVIEW("Playlists"), TRACKS("Músicas"), ALBUMS("Álbuns"), ARTISTS("Artistas"), PLAYLISTS("Playlists")
}

private class PlayStatsStore(context: Context) {
    private val appContext = context.applicationContext

    init {
        PlayStatsState.initialize(appContext)
    }

    fun count(trackId: String): Long = PlayStatsState.count(appContext, trackId)

    private fun countsFor(tracks: List<AudioTrack>): Map<String, Long> {
        val allCounts = PlayStatsState.snapshot(appContext)
        return tracks.asSequence()
            .map { it.id }
            .distinct()
            .associateWith { allCounts[it] ?: 0L }
    }

    fun topTracks(tracks: List<AudioTrack>, limit: Int = 10): List<Pair<AudioTrack, Long>> {
        val counts = countsFor(tracks)
        return tracks.asSequence()
            .map { it to (counts[it.id] ?: 0L) }
            .filter { it.second > 0L }
            .sortedWith(compareByDescending<Pair<AudioTrack, Long>> { it.second }.thenBy { it.first.displayTitle().lowercase(Locale.ROOT) })
            .take(limit)
            .toList()
    }

    fun topAlbums(tracks: List<AudioTrack>, limit: Int = 10): List<Triple<String, String, Long>> {
        val counts = countsFor(tracks)
        return tracks.asSequence()
            .filter { it.album.isNotBlank() }
            .groupBy { Normalizer.normalize(it.album, Normalizer.Form.NFKC).trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT) }
            .mapNotNull { (_, group) ->
                val total = group.sumOf { counts[it.id] ?: 0L }
                if (total <= 0L) return@mapNotNull null
                val first = group.first()
                val artist = first.albumArtist.ifBlank { first.artist }
                Triple(first.album, artist, total)
            }
            .sortedWith(compareByDescending<Triple<String, String, Long>> { it.third }.thenBy { it.first.lowercase(Locale.ROOT) })
            .take(limit)
    }

    fun topArtists(tracks: List<AudioTrack>, limit: Int = 10): List<Pair<String, Long>> {
        val counts = countsFor(tracks)
        return tracks.asSequence()
            .filter { it.artist.isNotBlank() && it.artist != "<unknown>" }
            .groupBy { Normalizer.normalize(it.artist, Normalizer.Form.NFKC).trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT) }
            .mapNotNull { (_, group) ->
                val total = group.sumOf { counts[it.id] ?: 0L }
                if (total <= 0L) null else (group.first().artist to total)
            }
            .sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first.lowercase(Locale.ROOT) })
            .take(limit)
    }
}

private data class NavEntry(
    val tab: HomeTab,
    val playerScreen: PlayerScreen,
    val detail: Detail,
    val detailKey: String,
    val editorOpen: Boolean,
)

private data class PlaylistData(val name: String, val trackIds: List<String>)

private class PlaylistStore(private val context: Context) {
    companion object {
        private val _revision = MutableStateFlow(0L)
        val revision: StateFlow<Long> = _revision.asStateFlow()

        private fun bumpRevision() {
            _revision.value = _revision.value + 1L
        }
    }

    private val prefs = context.getSharedPreferences("truetagger_player", Context.MODE_PRIVATE)
    private val key = "playlists_json"

    fun load(): List<PlaylistData> = runCatching {
        val raw = prefs.getString(key, "[]") ?: "[]"
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val name = obj.optString("name").trim()
                if (name.isBlank()) continue
                add(PlaylistData(name, buildList {
                    val ids = obj.optJSONArray("trackIds") ?: JSONArray()
                    for (j in 0 until ids.length()) {
                        val id = ids.optString(j)
                        if (id.isNotBlank()) add(id)
                    }
                }))
            }
        }
    }.getOrElse { emptyList() }

    private fun save(items: List<PlaylistData>) {
        val array = JSONArray()
        items.forEach { playlist ->
            array.put(JSONObject().apply {
                put("name", playlist.name)
                put("trackIds", JSONArray(playlist.trackIds))
            })
        }
        prefs.edit().putString(key, array.toString()).apply()
        bumpRevision()
        AppEvents.bump()
    }

    fun create(name: String, trackIds: List<String> = emptyList()) {
        val clean = name.trim()
        if (clean.isBlank() || load().any { it.name.equals(clean, true) }) return
        save(load() + PlaylistData(clean, trackIds.distinct()))
    }

    fun delete(name: String) = save(load().filterNot { it.name == name })

    fun rename(oldName: String, newName: String): Boolean {
        val clean = newName.trim()
        val items = load()
        if (clean.isBlank() || oldName == clean || items.any { it.name != oldName && it.name.equals(clean, true) } || items.none { it.name == oldName }) return false
        save(items.map { if (it.name == oldName) it.copy(name = clean) else it })
        return true
    }

    fun add(name: String, trackId: String) {
        save(load().map {
            if (it.name == name && trackId !in it.trackIds) it.copy(trackIds = listOf(trackId) + it.trackIds) else it
        })
    }

    fun addAll(name: String, trackIds: List<String>) {
        val incoming = trackIds.distinct()
        if (incoming.isEmpty()) return
        save(load().map {
            if (it.name == name) {
                val existing = it.trackIds.toHashSet()
                val fresh = incoming.filterNot(existing::contains)
                it.copy(trackIds = fresh + it.trackIds)
            } else it
        })
    }

    fun remove(name: String, trackId: String) {
        save(load().map { if (it.name == name) it.copy(trackIds = it.trackIds - trackId) else it })
    }

    fun removeAll(name: String, trackIds: List<String>) {
        if (trackIds.isEmpty()) return
        val removeSet = trackIds.toHashSet()
        save(load().map { if (it.name == name) it.copy(trackIds = it.trackIds.filterNot(removeSet::contains)) else it })
    }

    fun removeTracksEverywhere(trackIds: Collection<String>): Boolean {
        val removeSet = trackIds.filter { it.isNotBlank() }.toHashSet()
        if (removeSet.isEmpty()) return false
        val current = load()
        val cleaned = current.map { playlist ->
            val filtered = playlist.trackIds.filterNot(removeSet::contains)
            if (filtered == playlist.trackIds) playlist else playlist.copy(trackIds = filtered)
        }
        if (cleaned == current) return false
        save(cleaned)
        return true
    }

    fun replaceOrderInMemory(current: List<PlaylistData>, name: String, orderedIds: List<String>): List<PlaylistData> {
        val cleanIds = orderedIds.distinct()
        return current.map { if (it.name == name) it.copy(trackIds = cleanIds) else it }
    }

    fun persist(items: List<PlaylistData>) { save(items) }

    fun exportM3u(uri: Uri, playlist: PlaylistData, tracks: List<AudioTrack>) {
        val trackById = tracks.associateBy { it.id }
        val text = buildString {
            appendLine("#EXTM3U")
            playlist.trackIds.forEach { id ->
                val track = trackById[id] ?: return@forEach
                val seconds = (track.durationMs / 1000L).coerceAtLeast(0L)
                val label = listOf(track.artist, track.displayTitle()).filter { it.isNotBlank() }.joinToString(" - ")
                appendLine("#EXTINF:$seconds,$label")
                appendLine(exportLocation(track))
            }
        }
        runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } }
    }

    fun importM3u(uri: Uri, tracks: List<AudioTrack>): String? {
        val text = runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } }.getOrNull() ?: return null
        val entries = text.lineSequence().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }.toList()
        if (entries.isEmpty()) return null
        val imported = entries.mapNotNull { findTrack(it, tracks) }.distinctBy { it.id }
        if (imported.isEmpty()) return null
        val fileName = queryDisplayName(uri)
        val base = fileName.substringBeforeLast('.', fileName).trim().ifBlank { "Playlist importada" }
        val name = uniqueName(base)
        create(name, imported.map { it.id })
        return name
    }

    private fun queryDisplayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0).orEmpty() else ""
        }.orEmpty()
    }.getOrDefault("")

    private fun uniqueName(base: String): String {
        val existing = load().map { it.name.lowercase(Locale.ROOT) }.toSet()
        var candidate = base
        var n = 2
        while (candidate.lowercase(Locale.ROOT) in existing) candidate = "$base ($n)".also { n++ }
        return candidate
    }

    private fun findTrack(line: String, tracks: List<AudioTrack>): AudioTrack? {
        val normalized = Uri.decode(line).trim().trim('\"')
        tracks.firstOrNull { it.id == line || it.id == normalized }?.let { return it }
        val fileName = normalized.substringAfterLast('/').substringAfterLast('\\')
        return tracks.firstOrNull { it.displayName.equals(fileName, true) }
            ?: tracks.firstOrNull { it.displayTitle().equals(fileName.substringBeforeLast('.', fileName), true) }
    }

    private fun exportLocation(track: AudioTrack): String = runCatching {
        context.contentResolver.query(Uri.parse(track.id), arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0).orEmpty() else ""
        }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: track.id
}


@Composable
private fun TruePlayerIntro() {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF050307)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(UiTokens.sectionSpacing),
        ) {
            Surface(
                modifier = Modifier.size(120.dp),
                shape = RoundedCornerShape(26.dp),
                color = Color.Black,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Image(
                        painter = painterResource(R.drawable.trueplayer_icon),
                        contentDescription = "TruePlayer",
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            Text(
                text = "TruePlayer",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFF7F0FF),
            )
            Text(
                text = "Carregando app...",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFBDB1C8),
            )
        }
    }
}

@Composable
fun TrueTaggerPlayerApp(
    tracks: List<AudioTrack>,
    selectedTrack: AudioTrack?,
    libraryLoading: Boolean,
    onSelectTrack: (AudioTrack) -> Unit,
    onBackToLibrary: () -> Unit,
    onRefreshLibrary: () -> Unit,
    onSaveEdits: suspend (AudioTrack, EditableTags) -> TagWriteResult,
    settings: AppSettings,
    downloadFolderLabel: String,
    onChooseDownloadFolder: (() -> Unit)?,
    onSettingsChange: (AppSettings) -> Unit,
    appVersion: String,
    importStatus: dev.truetagger.model.ImportStatus?,
    onDismissImportStatus: () -> Unit,
) {
    var showIntro by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(850)
        showIntro = false
    }

    if (showIntro) {
        MaterialTheme(colorScheme = TruePlayerDarkScheme) {
            TruePlayerIntro()
        }
        return
    }

    var tab by rememberSaveable { mutableStateOf(HomeTab.LIBRARY) }
    var playerScreen by rememberSaveable { mutableStateOf(PlayerScreen.NONE) }
    var detail by rememberSaveable { mutableStateOf(Detail.NONE) }
    var detailKey by rememberSaveable { mutableStateOf("") }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf(PlayerState.snapshot) }
    var lastObservedTrackId by remember { mutableStateOf(PlayerState.snapshot.current?.id) }
    var librarySearch by rememberSaveable { mutableStateOf("") }
    var librarySortMode by rememberSaveable { mutableStateOf(PlayerSortMode.NAME) }
    var libraryAscending by rememberSaveable { mutableStateOf(true) }
    var albumSearch by rememberSaveable { mutableStateOf("") }
    var albumSortMode by rememberSaveable { mutableStateOf(AlbumSortMode.NAME) }
    var albumAscending by rememberSaveable { mutableStateOf(true) }
    val history = remember { mutableStateListOf<NavEntry>() }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        (context as? Activity)?.title = "TruePlayer"
    }
    val filterPrefs = remember { context.getSharedPreferences("truetagger_filters", Context.MODE_PRIVATE) }
    val uiPrefs = remember { context.getSharedPreferences("truetagger_ui", Context.MODE_PRIVATE) }
    var pendingExportPlaylist by remember { mutableStateOf<PlaylistData?>(null) }
    var uiStateLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        librarySearch = filterPrefs.getString("library_search", "").orEmpty()
        librarySortMode = runCatching { PlayerSortMode.valueOf(filterPrefs.getString("library_sort", PlayerSortMode.NAME.name)!!) }.getOrDefault(PlayerSortMode.NAME)
        libraryAscending = filterPrefs.getBoolean("library_ascending", true)
        albumSearch = filterPrefs.getString("album_search", "").orEmpty()
        albumSortMode = runCatching { AlbumSortMode.valueOf(filterPrefs.getString("album_sort", AlbumSortMode.NAME.name)!!) }.getOrDefault(AlbumSortMode.NAME)
        albumAscending = filterPrefs.getBoolean("album_ascending", true)
        tab = when (uiPrefs.getString("home_tab", HomeTab.LIBRARY.name)) {
            HomeTab.PLAYLISTS.name, HomeTab.MOST_PLAYED.name -> HomeTab.MOST_PLAYED
            HomeTab.ALBUMS.name -> HomeTab.ALBUMS
            else -> HomeTab.LIBRARY
        }
        val savedDetail = uiPrefs.getString("detail", Detail.NONE.name)
        val savedDetailKey = uiPrefs.getString("detail_key", "").orEmpty()
        val lastPlaylistKey = uiPrefs.getString("last_playlist_key", "").orEmpty()
        if ((savedDetail == Detail.PLAYLIST.name || savedDetail == Detail.ALBUM.name) && savedDetailKey.isNotBlank()) {
            detail = runCatching { Detail.valueOf(savedDetail) }.getOrDefault(Detail.NONE)
            detailKey = savedDetailKey
            tab = if (detail == Detail.PLAYLIST) HomeTab.MOST_PLAYED else HomeTab.ALBUMS
        } else if (lastPlaylistKey.isNotBlank()) {
            // Mantém a última playlist aberta para que a aba Playlists retorne ao mesmo lugar.
            detailKey = lastPlaylistKey
        }
        uiStateLoaded = true
    }
    LaunchedEffect(tab, detail, detailKey, editorOpen, playerScreen, uiStateLoaded) {
        if (uiStateLoaded && !editorOpen && playerScreen == PlayerScreen.NONE) {
            val savedTab = when {
                detail == Detail.PLAYLIST -> HomeTab.MOST_PLAYED
                detail == Detail.ALBUM -> HomeTab.ALBUMS
                tab == HomeTab.PLAYLISTS || tab == HomeTab.ALBUMS || tab == HomeTab.MOST_PLAYED -> tab
                else -> HomeTab.LIBRARY
            }
            val savedDetail = if (detail == Detail.PLAYLIST || detail == Detail.ALBUM) detail else Detail.NONE
            uiPrefs.edit()
                .putString("home_tab", savedTab.name)
                .putString("detail", savedDetail.name)
                .putString("detail_key", if (savedDetail == Detail.NONE) "" else detailKey)
                .apply()
        }
    }
    val store = remember { PlaylistStore(context) }
    val persistenceScope = rememberCoroutineScope()
    var playlistPersistJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var playlists by remember { mutableStateOf(store.load()) }
    var restoreRequested by remember { mutableStateOf(false) }

    LaunchedEffect(tracks, playlists) {
        if (restoreRequested || tracks.isEmpty()) return@LaunchedEffect
        val prefs = context.getSharedPreferences("truetagger_last_playback", Context.MODE_PRIVATE)
        val trackId = prefs.getString("track_id", "").orEmpty()
        if (trackId.isBlank()) return@LaunchedEffect
        val track = tracks.firstOrNull { it.id == trackId } ?: return@LaunchedEffect
        val sourceType = prefs.getString("source_type", MusicPlaybackService.SOURCE_LIBRARY).orEmpty().ifBlank { MusicPlaybackService.SOURCE_LIBRARY }
        val sourceName = prefs.getString("source_name", "").orEmpty()
        val sourceQueue = when (sourceType) {
            MusicPlaybackService.SOURCE_PLAYLIST -> {
                val ids = playlists.firstOrNull { it.name == sourceName }?.trackIds.orEmpty()
                ids.mapNotNull { id -> tracks.firstOrNull { it.id == id } }
            }
            MusicPlaybackService.SOURCE_ALBUM -> tracks.filter { it.album.equals(sourceName, ignoreCase = true) }
            else -> tracks
        }.let { list -> if (list.any { it.id == track.id }) list else listOf(track) }
        val restoredIndex = sourceQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        restoreRequested = true
        MusicPlaybackService.restoreLastPlayback(
            context = context,
            track = track,
            sourceQueue = sourceQueue,
            index = restoredIndex,
            sourceType = sourceType,
            sourceName = sourceName,
            positionMs = prefs.getLong("position_ms", 0L),
        )
    }
    val trackById by remember(tracks) { mutableStateOf(tracks.associateBy { it.id }) }
    val drafts = remember { mutableStateMapOf<String, EditableTags>() }
    val libraryListState = rememberLazyListState()

    fun reloadPlaylists() { playlists = store.load() }

    var previousLibraryTrackIds by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(tracks) {
        val currentIds = tracks.asSequence().map { it.id }.toSet()
        val previousIds = previousLibraryTrackIds
        if (previousIds != null) {
            val removedIds = previousIds.minus(currentIds)
            if (removedIds.isNotEmpty() && store.removeTracksEverywhere(removedIds)) {
                playlists = store.load()
            }
        }
        previousLibraryTrackIds = currentIds
    }
    val exportPlaylistLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
        val playlist = pendingExportPlaylist
        if (uri != null && playlist != null) store.exportM3u(uri, playlist, tracks)
        pendingExportPlaylist = null
    }
    val importPlaylistLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && store.importM3u(uri, tracks) != null) reloadPlaylists()
    }
    fun queueMatchesPlaylist(ids: List<String>): Boolean =
        snapshot.queue.isNotEmpty() && snapshot.queue.map { it.id }.toSet() == ids.toSet()
    fun updatePlayingQueueIfNeeded(oldIds: List<String>, newIds: List<String>) {
        // Playlists are identified by the track IDs, never by their numeric positions.
        // This also handles an insertion: before adding, the queue matches oldIds;
        // after removing/reordering, it may match oldIds or newIds.
        if (!queueMatchesPlaylist(oldIds) && !queueMatchesPlaylist(newIds)) return
        val ordered = newIds.mapNotNull(trackById::get)
        MusicPlaybackService.replaceQueue(context, ordered)
    }
    fun play(list: List<AudioTrack>, index: Int, startShuffle: Boolean = false, sourceType: String = MusicPlaybackService.SOURCE_LIBRARY, sourceName: String = "") {
        if (list.isEmpty()) return
        val safeIndex = index.coerceIn(list.indices)
        MusicPlaybackService.play(context, list[safeIndex], list, safeIndex, startShuffle, sourceType, sourceName)
    }
    LaunchedEffect(librarySearch, librarySortMode, libraryAscending) {
        filterPrefs.edit().putString("library_search", librarySearch).putString("library_sort", librarySortMode.name).putBoolean("library_ascending", libraryAscending).apply()
    }
    LaunchedEffect(albumSearch, albumSortMode, albumAscending) {
        filterPrefs.edit().putString("album_search", albumSearch).putString("album_sort", albumSortMode.name).putBoolean("album_ascending", albumAscending).apply()
    }
    fun share(track: AudioTrack) {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, Uri.parse(track.id))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Compartilhar música"))
    }
    fun openEditor(track: AudioTrack) { onSelectTrack(track); editorOpen = true }
    fun currentEntry() = NavEntry(tab, playerScreen, detail, detailKey, editorOpen)
    fun pushCurrent() { history += currentEntry() }
    fun navigateDetail(type: Detail, key: String) {
        pushCurrent()
        editorOpen = false
        playerScreen = PlayerScreen.NONE
        detail = type
        detailKey = key
        if (type == Detail.PLAYLIST && key.isNotBlank()) {
            uiPrefs.edit().putString("last_playlist_key", key).apply()
        }
    }
    fun navigatePlayer(screen: PlayerScreen) { pushCurrent(); editorOpen = false; detail = Detail.NONE; playerScreen = screen }
    fun goBack() {
        if (history.isNotEmpty()) {
            val previous = history.removeAt(history.lastIndex)
            tab = previous.tab; playerScreen = previous.playerScreen; detail = previous.detail; detailKey = previous.detailKey; editorOpen = previous.editorOpen
        } else {
            editorOpen = false; playerScreen = PlayerScreen.NONE; detail = Detail.NONE
        }
    }

    val appRevision by AppEvents.revision.collectAsState()
    val playlistRevision by PlaylistStore.revision.collectAsState()
    LaunchedEffect(appRevision) {
        snapshot = PlayerState.snapshot
        lastObservedTrackId = snapshot.current?.id
    }
    LaunchedEffect(playlistRevision) {
        playlists = store.load()
    }

    BackHandler { goBack() }

    MaterialTheme(colorScheme = TruePlayerDarkScheme) {
        Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                Column {
                    if (snapshot.current != null && !editorOpen && playerScreen == PlayerScreen.NONE) {
                        MiniPlayer(
                            snapshot,
                            onOpen = { navigatePlayer(PlayerScreen.FULL) },
                            onToggle = { MusicPlaybackService.command(context, MusicPlaybackService.ACTION_TOGGLE) },
                            onPrevious = { MusicPlaybackService.command(context, MusicPlaybackService.ACTION_PREVIOUS) },
                            onNext = { MusicPlaybackService.command(context, MusicPlaybackService.ACTION_NEXT) },
                            onShuffle = { MusicPlaybackService.command(context, MusicPlaybackService.ACTION_SHUFFLE) },
                            onRepeat = { MusicPlaybackService.command(context, MusicPlaybackService.ACTION_REPEAT) },
                        )
                    }
                    CompactBottomBar(
                        items = HomeTab.entries.filter { it != HomeTab.PLAYLISTS }.map { t ->
                            BottomNavSpec(
                                label = t.label,
                                icon = when (t) {
                                    HomeTab.LIBRARY -> Icons.Rounded.LibraryMusic
                                    HomeTab.PLAYLISTS -> Icons.Rounded.QueueMusic
                                    HomeTab.ARTISTS -> Icons.Rounded.Person
                                    HomeTab.ALBUMS -> Icons.Rounded.Album
                                    HomeTab.GENRES -> Icons.Rounded.Category
                                    HomeTab.MOST_PLAYED -> Icons.Rounded.QueueMusic
                                    HomeTab.SETTINGS -> Icons.Rounded.Settings
                                },
                            )
                        },
                        selectedIndex = HomeTab.entries.filter { it != HomeTab.PLAYLISTS }.indexOf(tab).takeIf { it >= 0 && !editorOpen && detail == Detail.NONE && playerScreen == PlayerScreen.NONE } ?: -1,
                        onSelected = { index ->
                            val t = HomeTab.entries.filter { it != HomeTab.PLAYLISTS }[index]
                            val alreadyHere = tab == t && detail == Detail.NONE && playerScreen == PlayerScreen.NONE && !editorOpen
                            if (!alreadyHere) {
                                val current = currentEntry()
                                if (current.tab != t || current.detail != Detail.NONE || current.playerScreen != PlayerScreen.NONE || current.editorOpen) {
                                    history += current
                                }
                            }
                            editorOpen = false
                            playerScreen = PlayerScreen.NONE
                            tab = t
                            if (t == HomeTab.PLAYLISTS) {
                                reloadPlaylists()
                                val remembered = uiPrefs.getString("last_playlist_key", "").orEmpty()
                                val exists = remembered.isNotBlank() && playlists.any { it.name == remembered }
                                if (exists) {
                                    detail = Detail.PLAYLIST
                                    detailKey = remembered
                                } else {
                                    detail = Detail.NONE
                                    detailKey = ""
                                }
                            } else {
                                detail = Detail.NONE
                                detailKey = ""
                            }
                        },
                    )
                }
            }
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                importStatus?.let { ImportBanner(it, onDismissImportStatus) }
                when {
                    editorOpen -> CurrentTrackScreen(
                        track = selectedTrack,
                        draft = selectedTrack?.let { drafts[it.id] },
                        settings = settings,
                        onDraftChange = { id, draft -> drafts[id] = draft },
                        onSaveEdits = { track, edits ->
                            val result = onSaveEdits(track, edits)
                            if (result.success) { drafts[track.id] = result.updatedTrack?.toEditableTags() ?: edits; editorOpen = false }
                            result
                        }
                    )
                    detail != Detail.NONE -> DetailScreen(
                        type = detail,
                        key = detailKey,
                        tracks = tracks,
                        playlist = playlists.firstOrNull { it.name == detailKey },
                        onPlay = { list, index ->
                            val sourceType = when (detail) {
                                Detail.PLAYLIST -> MusicPlaybackService.SOURCE_PLAYLIST
                                Detail.ALBUM -> MusicPlaybackService.SOURCE_ALBUM
                                else -> MusicPlaybackService.SOURCE_LIBRARY
                            }
                            play(list, index, false, sourceType, if (sourceType == MusicPlaybackService.SOURCE_LIBRARY) "" else detailKey)
                        },
                        onPlayRandom = { list ->
                            if (list.isNotEmpty()) {
                                val sourceType = when (detail) {
                                    Detail.PLAYLIST -> MusicPlaybackService.SOURCE_PLAYLIST
                                    Detail.ALBUM -> MusicPlaybackService.SOURCE_ALBUM
                                    else -> MusicPlaybackService.SOURCE_LIBRARY
                                }
                                play(list, list.indices.random(), true, sourceType, if (sourceType == MusicPlaybackService.SOURCE_LIBRARY) "" else detailKey)
                            }
                        },
                        onEdit = ::openEditor,
                        onShare = ::share,
                        onRemoveFromPlaylist = { id ->
                            val oldIds = playlists.firstOrNull { it.name == detailKey }?.trackIds.orEmpty()
                            store.remove(detailKey, id)
                            val newIds = oldIds.filterNot { it == id }
                            updatePlayingQueueIfNeeded(oldIds, newIds)
                            reloadPlaylists()
                        },
                        onRemoveSelectedFromPlaylist = { name, idsToRemove ->
                            val oldIds = playlists.firstOrNull { it.name == name }?.trackIds.orEmpty()
                            val newIds = oldIds.filterNot { it in idsToRemove }
                            store.removeAll(name, idsToRemove)
                            playlists = store.replaceOrderInMemory(playlists, name, newIds)
                            updatePlayingQueueIfNeeded(oldIds, newIds)
                        },
                        onPlaylistOrderChange = { name, ids ->
                            val oldIds = playlists.firstOrNull { it.name == name }?.trackIds.orEmpty()
                            val updated = store.replaceOrderInMemory(playlists, name, ids)
                            playlists = updated
                            playlistPersistJob?.cancel()
                            playlistPersistJob = persistenceScope.launch(Dispatchers.Default) { store.persist(updated) }
                            updatePlayingQueueIfNeeded(oldIds, ids)
                        },
                        currentTrackId = snapshot.current?.id,
                        onBack = { goBack() },
                        onOpenArtist = { name -> if (name.isNotBlank()) navigateDetail(Detail.ARTIST, name) },
                        onOpenAlbum = { name -> if (name.isNotBlank()) navigateDetail(Detail.ALBUM, name) }
                    )
                    else -> when (tab) {
                        HomeTab.LIBRARY -> LibraryPlayerScreen(
                            tracks, libraryLoading, onRefreshLibrary, ::play, ::openEditor, ::share, libraryListState, librarySearch, { librarySearch = it }, librarySortMode, libraryAscending, { librarySortMode = it }, { libraryAscending = !libraryAscending }, snapshot.current?.id,
                            onAddToPlaylist = { track -> playlistMenu(context, store, track.id) { reloadPlaylists() } },
                            onAlbum = { navigateDetail(Detail.ALBUM, it) },
                            onArtist = { navigateDetail(Detail.ARTIST, it) },
                            onGenre = { navigateDetail(Detail.GENRE, it) },
                            onPlaylistChanged = ::updatePlayingQueueIfNeeded
                        )
                        HomeTab.PLAYLISTS -> CombinedMostPlayedPlaylistHost(
                            tracks = tracks, playlists = playlists, play = ::play,
                            onAlbum = { navigateDetail(Detail.ALBUM, it) }, onArtist = { navigateDetail(Detail.ARTIST, it) },
                            onCreate = { name -> store.create(name); reloadPlaylists() },
                            onImport = { importPlaylistLauncher.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "text/plain")) },
                            onOpen = { navigateDetail(Detail.PLAYLIST, it) },
                            onDelete = { name -> store.delete(name); if (uiPrefs.getString("last_playlist_key", "").orEmpty() == name) uiPrefs.edit().remove("last_playlist_key").apply(); reloadPlaylists() },
                            onExport = { playlist -> pendingExportPlaylist = playlist; exportPlaylistLauncher.launch("${playlist.name}.m3u") },
                            onRename = { oldName, newName -> if (store.rename(oldName, newName)) { reloadPlaylists(); if (uiPrefs.getString("last_playlist_key", "").orEmpty() == oldName) uiPrefs.edit().putString("last_playlist_key", newName).apply() } },
                        )
                        HomeTab.ARTISTS -> ArtistsScreen(tracks) { navigateDetail(Detail.ARTIST, it) }
                        HomeTab.ALBUMS -> AlbumsScreen(tracks, albumSearch, { albumSearch = it }, albumSortMode, { albumSortMode = it }, albumAscending, { albumAscending = !albumAscending }) { navigateDetail(Detail.ALBUM, it) }
                        HomeTab.GENRES -> GenresScreen(tracks) { navigateDetail(Detail.GENRE, it) }
                        HomeTab.MOST_PLAYED -> CombinedMostPlayedPlaylistHost(
                            tracks = tracks, playlists = playlists, play = ::play,
                            onAlbum = { navigateDetail(Detail.ALBUM, it) }, onArtist = { navigateDetail(Detail.ARTIST, it) },
                            onCreate = { name -> store.create(name); reloadPlaylists() },
                            onImport = { importPlaylistLauncher.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "text/plain")) },
                            onOpen = { navigateDetail(Detail.PLAYLIST, it) },
                            onDelete = { name -> store.delete(name); if (uiPrefs.getString("last_playlist_key", "").orEmpty() == name) uiPrefs.edit().remove("last_playlist_key").apply(); reloadPlaylists() },
                            onExport = { playlist -> pendingExportPlaylist = playlist; exportPlaylistLauncher.launch("${playlist.name}.m3u") },
                            onRename = { oldName, newName -> if (store.rename(oldName, newName)) { reloadPlaylists(); if (uiPrefs.getString("last_playlist_key", "").orEmpty() == oldName) uiPrefs.edit().putString("last_playlist_key", newName).apply() } },
                        )
                        HomeTab.SETTINGS -> SettingsTab(settings, onSettingsChange, downloadFolderLabel, onChooseDownloadFolder, appVersion)
                    }
                }
            }
        }
        if (playerScreen == PlayerScreen.FULL) Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            FullPlayer(snapshot, { goBack() }, { navigatePlayer(PlayerScreen.QUEUE) }, {
                snapshot.current?.artist?.takeIf { it.isNotBlank() }?.let { navigateDetail(Detail.ARTIST, it) }
            }, {
                snapshot.current?.album?.takeIf { it.isNotBlank() }?.let { navigateDetail(Detail.ALBUM, it) }
            })
        }
        if (playerScreen == PlayerScreen.QUEUE) Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            QueueScreen(
                s = snapshot,
                onBack = { goBack() },
                onSelect = { idx ->
                    context.startForegroundService(Intent(context, MusicPlaybackService::class.java).apply {
                        action = MusicPlaybackService.ACTION_PLAY_QUEUE_INDEX
                        putExtra(MusicPlaybackService.EXTRA_INDEX, idx)
                    })
                },
                onToggle = { MusicPlaybackService.command(context, MusicPlaybackService.ACTION_TOGGLE) },
                onPrevious = { MusicPlaybackService.command(context, MusicPlaybackService.ACTION_PREVIOUS) },
                onNext = { MusicPlaybackService.command(context, MusicPlaybackService.ACTION_NEXT) },
                onShuffle = { MusicPlaybackService.command(context, MusicPlaybackService.ACTION_SHUFFLE) },
                onRepeat = { MusicPlaybackService.command(context, MusicPlaybackService.ACTION_REPEAT) },
            )
        }
        }
    }
}

private fun playlistMenu(context: Context, store: PlaylistStore, trackId: String, onChanged: () -> Unit) {
    // The actual Compose dialog is triggered through a shared event below.
    PlaylistEventBus.open(trackId, store, onChanged)
}

private object PlaylistEventBus {
    var request by mutableStateOf<Triple<String, PlaylistStore, () -> Unit>?>(null)
    fun open(trackId: String, store: PlaylistStore, onChanged: () -> Unit) { request = Triple(trackId, store, onChanged) }
}

@Composable
private fun MiniPlayer(
    s: PlayerSnapshot,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(UiTokens.miniPlayerHeight)
                .clickable(onClick = onOpen)
                .padding(horizontal = UiTokens.compactPadding, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(UiTokens.itemSpacing),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                key(s.current?.id) {
                    PlayerArtworkBox(
                        s.current?.artworkBytes,
                        Modifier.size(UiTokens.miniPlayerArtworkSize),
                        s.current?.id,
                        maxDimension = 256,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f).padding(horizontal = UiTokens.cardItemHorizontalPadding),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        s.current?.displayTitle().orEmpty(),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOf(s.current?.artist, s.current?.album).filter { !it.isNullOrBlank() }.joinToString(" • "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onShuffle) {
                    Icon(painterResource(if (s.shuffle) R.drawable.truetagger_shuffle_on else R.drawable.truetagger_shuffle_off), "Aleatório", tint = if (s.shuffle) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                }
                IconButton(onClick = onPrevious) { Icon(Icons.Rounded.SkipPrevious, "Anterior") }
                IconButton(onClick = onToggle) { Icon(if (s.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (s.playing) "Pausar" else "Reproduzir") }
                IconButton(onClick = onNext) { Icon(Icons.Rounded.SkipNext, "Próxima") }
                IconButton(onClick = onRepeat) { Icon(painterResource(if (s.repeat == RepeatMode.ONE) R.drawable.truetagger_repeat_on else R.drawable.truetagger_repeat_off), "Repetição", tint = if (s.repeat != RepeatMode.OFF) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
            }
        }
    }
}

@Composable
private fun FullPlayer(s: PlayerSnapshot, onClose: () -> Unit, onQueue: () -> Unit, onArtist: () -> Unit, onAlbum: () -> Unit) {
    val context = LocalContext.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(UiTokens.screenPadding), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.ArrowBack, "Voltar") }
                Text("Reproduzindo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(UiTokens.sectionSpacing))
            PlayerArtworkBox(s.current?.artworkBytes, Modifier.fillMaxWidth().aspectRatio(1f), s.current?.id, maxDimension = 1024)
            Spacer(Modifier.height(UiTokens.sectionSpacing))
            Text(s.current?.displayTitle().orEmpty(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onAlbum, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Text(s.current?.album.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onArtist, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Text(s.current?.artist.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Slider(value = if (s.durationMs > 0) s.positionMs.toFloat() / s.durationMs else 0f, onValueChange = { f -> MusicPlaybackService.command(context, MusicPlaybackService.ACTION_SEEK, (f * s.durationMs).toLong()) }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(formatDuration(s.positionMs)); Text(formatDuration(s.durationMs)) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { MusicPlaybackService.command(context, MusicPlaybackService.ACTION_SHUFFLE) }) { Icon(painterResource(if (s.shuffle) dev.truetagger.android.R.drawable.truetagger_shuffle_on else dev.truetagger.android.R.drawable.truetagger_shuffle_off), "Aleatório", tint = if (s.shuffle) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
                IconButton(onClick = { MusicPlaybackService.command(context, MusicPlaybackService.ACTION_PREVIOUS) }) { Icon(Icons.Rounded.SkipPrevious, null, Modifier.size(42.dp)) }
                FilledIconButton(onClick = { MusicPlaybackService.command(context, MusicPlaybackService.ACTION_TOGGLE) }, modifier = Modifier.size(64.dp)) { Icon(if (s.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(34.dp)) }
                IconButton(onClick = { MusicPlaybackService.command(context, MusicPlaybackService.ACTION_NEXT) }) { Icon(Icons.Rounded.SkipNext, null, Modifier.size(42.dp)) }
                IconButton(onClick = { MusicPlaybackService.command(context, MusicPlaybackService.ACTION_REPEAT) }) { Icon(painterResource(if (s.repeat == RepeatMode.ONE) dev.truetagger.android.R.drawable.truetagger_repeat_on else dev.truetagger.android.R.drawable.truetagger_repeat_off), "Repetição", tint = if (s.repeat != RepeatMode.OFF) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
            }
            OutlinedButton(onClick = onQueue, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.QueueMusic, null); Spacer(Modifier.width(8.dp)); Text("Abrir fila") }
        }
    }
}

@Composable
private fun QueueScreen(
    s: PlayerSnapshot,
    onBack: () -> Unit,
    onSelect: (Int) -> Unit,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
) {
    val state = rememberLazyListState()
    LaunchedEffect(s.queue, s.current?.id) {
        val currentIndex = s.queue.indexOfFirst { it.id == s.current?.id }
        if (currentIndex >= 0) state.scrollToItem(currentIndex)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = UiTokens.screenHorizontalPadding, vertical = UiTokens.screenVerticalPadding), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Voltar") }
            Text("Fila atual", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = state, modifier = Modifier.fillMaxSize().songListSpace(), contentPadding = PaddingValues(bottom = UiTokens.songListBottomPadding), verticalArrangement = Arrangement.spacedBy(UiTokens.songListSpacing)) {
                itemsIndexed(s.queue, key = { _, t -> t.id }) { i, t ->
                    SongItem(
                        track = t,
                        onPlay = { onSelect(i) },
                        onEdit = {}, onShare = {}, onAddToPlaylist = {}, onAlbum = {}, onArtist = {}, onGenre = {},
                        onRemoveFromPlaylist = null,
                        isCurrent = s.current?.id == t.id,
                    )
                }
            }
            TouchScrollbar(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = UiTokens.scrollbarInset))
            ListJumpButtons(state, Modifier.align(Alignment.BottomEnd).padding(end = UiTokens.jumpEndPadding, bottom = UiTokens.jumpBottomPadding))
        }
        if (s.current != null) {
            MiniPlayer(
                s = s,
                onOpen = {}, onToggle = onToggle, onPrevious = onPrevious, onNext = onNext, onShuffle = onShuffle, onRepeat = onRepeat,
            )
        }
    }
}

@Composable
private fun LibraryPlayerScreen(
    tracks: List<AudioTrack>, loading: Boolean, refresh: () -> Unit,
    play: (List<AudioTrack>, Int) -> Unit, edit: (AudioTrack) -> Unit, share: (AudioTrack) -> Unit, listState: LazyListState,
    librarySearch: String, onLibrarySearchChange: (String) -> Unit, sortMode: PlayerSortMode, ascending: Boolean, onSortModeChange: (PlayerSortMode) -> Unit, onAscendingChange: () -> Unit, currentTrackId: String?,
    onAddToPlaylist: (AudioTrack) -> Unit, onAlbum: (String) -> Unit, onArtist: (String) -> Unit, onGenre: (String) -> Unit,
    onPlaylistChanged: (List<String>, List<String>) -> Unit,
) {
    var sortOpen by remember { mutableStateOf(false) }
    var selectionMode by rememberSaveable { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    val context = LocalContext.current

    fun exitSelection() {
        selectionMode = false
        selectedIds.clear()
    }

    val shown = remember(tracks, librarySearch, sortMode, ascending) {
        tracks.filter { librarySearch.isBlank() || listOf(it.displayTitle(), it.artist, it.album, it.genre).any { x -> x.contains(librarySearch, true) } }
            .sortedWith { a, b ->
                val v = when (sortMode) {
                    PlayerSortMode.NAME -> a.displayTitle().compareTo(b.displayTitle(), true)
                    PlayerSortMode.TITLE -> a.title.compareTo(b.title, true)
                    PlayerSortMode.ARTIST -> a.artist.compareTo(b.artist, true)
                    PlayerSortMode.ALBUM -> a.album.compareTo(b.album, true)
                    PlayerSortMode.DATE -> a.modifiedAtSeconds.compareTo(b.modifiedAtSeconds)
                    PlayerSortMode.DURATION -> a.durationMs.compareTo(b.durationMs)
                }
                if (ascending) v else -v
            }
    }

    var pendingDeleteIds by remember { mutableStateOf<List<String>>(emptyList()) }

    fun finishDelete(requested: List<String>) {
        if (requested.isEmpty()) {
            refresh()
            return
        }
        // O provedor pode levar um instante para refletir a exclusão. Não dependemos
        // da consulta para decidir se o usuário confirmou: o MediaStore já recebeu
        // a ordem de exclusão. Removemos da fila/playlist e deixamos a ContentObserver
        // atualizar a biblioteca.
        MusicPlaybackService.reconcileDeletedTracks(context, requested)
        pendingDeleteIds = emptyList()
        exitSelection()
        refresh()
    }

    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val requested = pendingDeleteIds
        pendingDeleteIds = emptyList()
        if (result.resultCode == Activity.RESULT_OK) {
            finishDelete(requested)
        } else {
            refresh()
        }
    }

    fun performDelete() {
        val idsToDelete = shown.asSequence()
            .filter { it.id in selectedIds }
            .map { it.id }
            .filter { runCatching { Uri.parse(it).scheme == "content" && Uri.parse(it).authority == MediaStore.AUTHORITY }.getOrDefault(false) }
            .distinct()
            .toList()
        showDeleteConfirm = false
        if (idsToDelete.isEmpty()) return

        // Primeiro tentamos remover diretamente. Isso resolve imediatamente os
        // arquivos que o próprio app criou e, para os demais, caímos no diálogo
        // oficial de autorização do MediaStore.
        val deleted = ArrayList<String>()
        var recoverableAction: android.content.IntentSender? = null
        for (id in idsToDelete) {
            try {
                if (context.contentResolver.delete(Uri.parse(id), null, null) > 0) {
                    deleted += id
                }
            } catch (error: RecoverableSecurityException) {
                recoverableAction = error.userAction.actionIntent.intentSender
                break
            } catch (error: SecurityException) {
                Log.w("TruePlayer", "Exclusão direta negada para $id; usando autorização do MediaStore", error)
            }
        }

        val remaining = idsToDelete.filterNot(deleted::contains)
        if (remaining.isEmpty()) {
            finishDelete(deleted)
            return
        }

        // Android 10 pode devolver uma ação recuperável diretamente.
        if (recoverableAction != null && Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            pendingDeleteIds = remaining
            deleteLauncher.launch(IntentSenderRequest.Builder(recoverableAction!!).build())
            return
        }

        // Android 11+ usa createDeleteRequest(), que abre a confirmação oficial do
        // sistema para os itens que o app não pode excluir silenciosamente.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pending = runCatching {
                MediaStore.createDeleteRequest(context.contentResolver, remaining.map(Uri::parse))
            }.getOrElse { error ->
                Log.e("TruePlayer", "Falha ao criar pedido de exclusão no MediaStore", error)
                null
            }
            if (pending != null) {
                if (deleted.isNotEmpty()) {
                    MusicPlaybackService.reconcileDeletedTracks(context, deleted)
                }
                // Mantemos remaining em pendingDeleteIds até o resultado do diálogo.
                pendingDeleteIds = remaining
                deleteLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                if (deleted.isNotEmpty()) {
                    refresh()
                }
                return
            }
        }

        // Se nenhuma forma de autorização estiver disponível, pelo menos mantém
        // a biblioteca consistente com o que realmente foi removido.
        if (deleted.isNotEmpty()) finishDelete(deleted) else refresh()
    }

    var pendingRestoreKey by remember { mutableStateOf<String?>(null) }
    var pendingRestoreOffset by remember { mutableIntStateOf(0) }
    LaunchedEffect(librarySearch, sortMode, ascending, shown.size) {
        val key = pendingRestoreKey
        if (key != null && shown.isNotEmpty()) {
            val target = shown.indexOfFirst { it.id == key }
            if (target >= 0) listState.scrollToItem(target, pendingRestoreOffset.coerceAtLeast(0))
            pendingRestoreKey = null
        }
    }
    var activePlaylistTrack by remember { mutableStateOf<AudioTrack?>(null) }
    val changeSearch: (String) -> Unit = { value -> pendingRestoreKey = shown.getOrNull(listState.firstVisibleItemIndex)?.id; pendingRestoreOffset = listState.firstVisibleItemScrollOffset; onLibrarySearchChange(value) }
    val changeSort: (PlayerSortMode) -> Unit = { mode -> pendingRestoreKey = shown.getOrNull(listState.firstVisibleItemIndex)?.id; pendingRestoreOffset = listState.firstVisibleItemScrollOffset; onSortModeChange(mode) }
    val changeAscending: () -> Unit = { pendingRestoreKey = shown.getOrNull(listState.firstVisibleItemIndex)?.id; pendingRestoreOffset = listState.firstVisibleItemScrollOffset; onAscendingChange() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = UiTokens.screenHorizontalPadding, vertical = UiTokens.screenVerticalPadding), verticalAlignment = Alignment.CenterVertically) {
            Text("Biblioteca", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = refresh) { Icon(Icons.Rounded.Refresh, "Atualizar") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val wideLayout = maxWidth >= 700.dp
            if (wideLayout) {
                Row(Modifier.fillMaxSize().padding(horizontal = UiTokens.pageHorizontal), horizontalArrangement = Arrangement.spacedBy(UiTokens.toolbarGap)) {
                    LibraryListContent(
                        shown = shown, listState = listState, selectionMode = selectionMode, selectedIds = selectedIds,
                        sortOpen = sortOpen, onSortOpenChange = { sortOpen = it }, librarySearch = librarySearch, onLibrarySearchChange = changeSearch,
                        sortMode = sortMode, ascending = ascending, onSortModeChange = changeSort, onAscendingChange = changeAscending,
                        play = play, edit = edit, share = share, onAddToPlaylist = { activePlaylistTrack = it }, onAlbum = onAlbum, onArtist = onArtist, onGenre = onGenre,
                        currentTrackId = currentTrackId, onSelectionModeExit = { exitSelection() }, onSelectAll = {
                            if (selectedIds.size == shown.size) selectedIds.clear() else { selectedIds.clear(); selectedIds.addAll(shown.map { it.id }) }
                        }, onToggleSelect = { id -> if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id) },
                        onDeleteClick = { showDeleteConfirm = true }, onPlaylistClick = { showPlaylistPicker = true },
                        onSelectionModeToggle = { if (selectionMode) exitSelection() else selectionMode = true },
                        onDeleteTrack = { track -> selectedIds.clear(); selectedIds.add(track.id); showDeleteConfirm = true },
                        onPlayAll = { if (shown.isNotEmpty()) play(shown, 0) }, onPlayRandom = { val start = shown.indices.randomOrNull(); if (start != null) play(shown, start) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    LibraryListContent(
                        shown = shown, listState = listState, selectionMode = selectionMode, selectedIds = selectedIds,
                        sortOpen = sortOpen, onSortOpenChange = { sortOpen = it }, librarySearch = librarySearch, onLibrarySearchChange = changeSearch,
                        sortMode = sortMode, ascending = ascending, onSortModeChange = changeSort, onAscendingChange = changeAscending,
                        play = play, edit = edit, share = share, onAddToPlaylist = { activePlaylistTrack = it }, onAlbum = onAlbum, onArtist = onArtist, onGenre = onGenre,
                        currentTrackId = currentTrackId, onSelectionModeExit = { exitSelection() }, onSelectAll = {
                            if (selectedIds.size == shown.size) selectedIds.clear() else { selectedIds.clear(); selectedIds.addAll(shown.map { it.id }) }
                        }, onToggleSelect = { id -> if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id) },
                        onDeleteClick = { showDeleteConfirm = true }, onPlaylistClick = { showPlaylistPicker = true },
                        onSelectionModeToggle = { if (selectionMode) exitSelection() else selectionMode = true },
                        onDeleteTrack = { track -> selectedIds.clear(); selectedIds.add(track.id); showDeleteConfirm = true },
                        onPlayAll = { if (shown.isNotEmpty()) play(shown, 0) }, onPlayRandom = { val start = shown.indices.randomOrNull(); if (start != null) play(shown, start) },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
        }
    }

    activePlaylistTrack?.let { track ->
        AddToPlaylistDialog(
            track,
            onDismiss = { activePlaylistTrack = null },
            onPlaylistChanged = { oldIds, newIds -> onPlaylistChanged(oldIds, newIds) },
        )
    }
    if (showDeleteConfirm) {
        AlertDialog(onDismissRequest = { showDeleteConfirm = false }, title = { Text("Remover músicas") }, text = { Text("Remover ${selectedIds.size} música(s) do dispositivo? Essa ação não pode ser desfeita.") }, confirmButton = { TextButton(onClick = { performDelete() }) { Text("Remover") } }, dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancelar") } })
    }
    if (showPlaylistPicker) {
        AddSelectedToPlaylistDialog(
            trackIds = shown.filter { it.id in selectedIds }.map { it.id },
            onDismiss = { showPlaylistPicker = false },
            onDone = { oldIds, newIds ->
                onPlaylistChanged(oldIds, newIds)
                showPlaylistPicker = false
                exitSelection()
            },
        )
    }
}

@Composable
private fun LibraryListContent(
    shown: List<AudioTrack>, listState: LazyListState, selectionMode: Boolean, selectedIds: List<String>, sortOpen: Boolean, onSortOpenChange: (Boolean) -> Unit,
    librarySearch: String, onLibrarySearchChange: (String) -> Unit, sortMode: PlayerSortMode, ascending: Boolean, onSortModeChange: (PlayerSortMode) -> Unit, onAscendingChange: () -> Unit,
    play: (List<AudioTrack>, Int) -> Unit, edit: (AudioTrack) -> Unit, share: (AudioTrack) -> Unit, onAddToPlaylist: (AudioTrack) -> Unit,
    onAlbum: (String) -> Unit, onArtist: (String) -> Unit, onGenre: (String) -> Unit, currentTrackId: String?, onSelectionModeExit: () -> Unit, onSelectAll: () -> Unit,
    onToggleSelect: (String) -> Unit, onDeleteClick: () -> Unit, onPlaylistClick: () -> Unit, onPlayAll: () -> Unit, onPlayRandom: () -> Unit, onSelectionModeToggle: () -> Unit, onDeleteTrack: (AudioTrack) -> Unit, modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = UiTokens.cardItemHorizontalPadding), verticalAlignment = Alignment.CenterVertically) {
            SearchBar(value = librarySearch, onValueChange = onLibrarySearchChange, placeholder = "Pesquisar músicas", modifier = Modifier.weight(1f))
            SortMenuButton(
                open = sortOpen,
                onOpenChange = onSortOpenChange,
                title = "Ordenar por",
                options = PlayerSortMode.entries,
                selected = sortMode,
                label = { it.label },
                onSelected = onSortModeChange,
                ascending = ascending,
                onToggleAscending = onAscendingChange,
            )
            IconButton(onClick = onSelectionModeToggle) {
                Icon(
                    imageVector = if (selectionMode) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                    contentDescription = if (selectionMode) "Encerrar seleção" else "Selecionar",
                    tint = if (selectionMode) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                )
            }
        }
        if (selectionMode) {
            SelectionToolbar(
                selectedCount = selectedIds.size,
                totalCount = shown.size,
                onCancel = onSelectionModeExit,
                onToggleAll = onSelectAll,
            )
            Row(Modifier.fillMaxWidth().padding(vertical = UiTokens.sectionSpacing), horizontalArrangement = Arrangement.spacedBy(UiTokens.toolbarGap)) {
                OutlinedButton(onClick = onDeleteClick, enabled = selectedIds.isNotEmpty(), modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Icon(Icons.Rounded.Delete, null); Spacer(Modifier.width(6.dp)); Text("Remover") }
                OutlinedButton(onClick = onPlaylistClick, enabled = selectedIds.isNotEmpty(), modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.PlaylistAdd, null); Spacer(Modifier.width(6.dp)); Text("Playlist") }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(vertical = UiTokens.sectionSpacing), horizontalArrangement = Arrangement.spacedBy(UiTokens.toolbarGap)) {
                OutlinedButton(onClick = onPlayAll, enabled = shown.isNotEmpty(), modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(5.dp)); Text("Tocar", maxLines = 1, softWrap = false) }
                OutlinedButton(onClick = onPlayRandom, enabled = shown.isNotEmpty(), modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(5.dp)); Text("Aleatório", maxLines = 1, softWrap = false) }
            }
        }
        SongListContainer(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(shown, key = { it.id }) { track ->
                    SongItem(track,
                        onPlay = { play(shown, shown.indexOf(track)) }, onEdit = { edit(track) }, onShare = { share(track) }, onAddToPlaylist = { onAddToPlaylist(track) },
                        onAlbum = { if (track.album.isNotBlank()) onAlbum(track.album) }, onArtist = { if (track.artist.isNotBlank()) onArtist(track.artist) }, onGenre = { if (track.genre.isNotBlank()) onGenre(track.genre) },
                        isCurrent = currentTrackId == track.id, selectionMode = selectionMode, selected = track.id in selectedIds, onToggleSelect = { onToggleSelect(track.id) },
                        onDelete = { onDeleteTrack(track) },
                    )
                }
            }
    }
}

@Composable
private fun SongListContainer(
    state: LazyListState,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
    endPadding: Dp = UiTokens.songListEndPadding,
    jumpButtonEndPadding: Dp = UiTokens.jumpEndPadding,
    jumpButtonBottomPadding: Dp = UiTokens.jumpBottomPadding,
    content: LazyListScope.() -> Unit,
) {
    Box(modifier) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize().padding(end = endPadding),
            verticalArrangement = Arrangement.spacedBy(UiTokens.songListSpacing),
            contentPadding = PaddingValues(bottom = UiTokens.songListBottomPadding),
            content = content,
        )
        TouchScrollbar(
            state = state,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = UiTokens.scrollbarInset),
            topInset = topInset,
            bottomInset = bottomInset,
        )
        ListJumpButtons(
            state = state,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = jumpButtonEndPadding, bottom = jumpButtonBottomPadding),
        )
    }
}

@Composable
private fun <T> SortMenuButton(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit,
    ascending: Boolean? = null,
    onToggleAscending: (() -> Unit)? = null,
) {
    Box {
        IconButton(onClick = { onOpenChange(true) }) { Icon(Icons.Rounded.FilterAlt, "Filtros") }
        DropdownMenu(expanded = open, onDismissRequest = { onOpenChange(false) }) {
            Text(title, modifier = Modifier.padding(horizontal = UiTokens.cardItemHorizontalPadding, vertical = 6.dp), fontWeight = FontWeight.Bold)
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = { onSelected(option); onOpenChange(false) },
                )
            }
            if (ascending != null && onToggleAscending != null) {
                DropdownMenuItem(
                    text = { Text(if (ascending) "Crescente" else "Decrescente") },
                    onClick = { onToggleAscending(); onOpenChange(false) },
                )
            }
        }
    }
}

@Composable
private fun CombinedMostPlayedPlaylistHost(
    tracks: List<AudioTrack>,
    playlists: List<PlaylistData>,
    play: (List<AudioTrack>, Int) -> Unit,
    onAlbum: (String) -> Unit,
    onArtist: (String) -> Unit,
    onCreate: (String) -> Unit,
    onImport: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onExport: (PlaylistData) -> Unit,
    onRename: (String, String) -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(MostPlayedMode.OVERVIEW) }
    val context = LocalContext.current
    val store = remember { PlayStatsStore(context) }
    var creating by remember { mutableStateOf(false) }
    var createName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<PlaylistData?>(null) }
    var renameText by remember { mutableStateOf("") }

    // A revisão muda sempre que uma reprodução é registrada. Isso força
    // as três categorias a recalcularem juntas, sem depender da identidade
    // da lista da biblioteca.
    val statsRevision by PlayStatsState.revision.collectAsState()
    // A revisão é coletada deliberadamente antes dos cálculos para garantir
    // que os três cards sejam invalidados pelo mesmo evento de reprodução.
    @Suppress("UNUSED_VARIABLE")
    val _statsRevision = statsRevision
    val liveTopTrack = store.topTracks(tracks, 1).firstOrNull()
    val liveTopAlbum = store.topAlbums(tracks, 1).firstOrNull()
    val liveTopArtist = store.topArtists(tracks, 1).firstOrNull()

    BackHandler(enabled = mode != MostPlayedMode.OVERVIEW) {
        mode = MostPlayedMode.OVERVIEW
    }

    StandardPage {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = UiTokens.screenHorizontalPadding, vertical = UiTokens.screenVerticalPadding)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (mode != MostPlayedMode.OVERVIEW) {
                    IconButton(onClick = { mode = MostPlayedMode.OVERVIEW }) {
                        Icon(Icons.Rounded.ArrowBack, "Voltar")
                    }
                }
                Text(
                    text = if (mode == MostPlayedMode.OVERVIEW) "Playlists" else "Mais ouvidas",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (mode != MostPlayedMode.OVERVIEW) {
                    TextButton(onClick = { mode = MostPlayedMode.OVERVIEW }) { Text("Voltar") }
                }
            }

            if (mode == MostPlayedMode.OVERVIEW) {
                val state = rememberLazyListState()
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(UiTokens.toolbarGap),
                    ) {
                        OutlinedButton(onClick = { creating = true; createName = "" }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.Add, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Criar")
                        }
                        OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.FileOpen, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Importar")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = state,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(UiTokens.songListSpacing),
                            contentPadding = PaddingValues(top = 4.dp, bottom = UiTokens.songListBottomPadding),
                        ) {
                            if (playlists.isNotEmpty()) {
                                items(playlists, key = { it.name }) { playlist ->
                                    var expanded by remember(playlist.name) { mutableStateOf(false) }
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(UiTokens.songItemRadius))
                                            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(UiTokens.songItemRadius))
                                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.34f))
                                            .clickable { onOpen(playlist.name) }
                                            .padding(horizontal = UiTokens.cardItemHorizontalPadding, vertical = UiTokens.songItemPadding),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(UiTokens.songItemSpacing)) {
                                            Text(playlist.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("${playlist.trackIds.size} músicas", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Box {
                                            IconButton(onClick = { expanded = true }) { Icon(Icons.Rounded.MoreVert, "Opções") }
                                            DropdownMenu(expanded, { expanded = false }) {
                                                DropdownMenuItem({ Text("Exportar M3U") }, onClick = { expanded = false; onExport(playlist) })
                                                DropdownMenuItem({ Text("Renomear") }, onClick = { expanded = false; renameText = playlist.name; renaming = playlist })
                                                DropdownMenuItem({ Text("Excluir") }, onClick = { expanded = false; onDelete(playlist.name) })
                                            }
                                        }
                                    }
                                }
                            } else {
                                item { Text("Nenhuma playlist criada.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }

                            item {
                                HorizontalDivider(Modifier.padding(vertical = UiTokens.sectionSpacing))
                            }
                            item {
                                Text("MAIS OUVIDAS", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
                            }
                            item {
                                // A reprodução iniciada é o único gatilho necessário para
                                // atualizar os cards de Mais Ouvidas. O estado de progresso
                                // do player não precisa invalidar essa parte da tela.
                                key(statsRevision) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(UiTokens.toolbarGap)) {
                                        val topTrack = liveTopTrack?.first
                                        val topAlbum = liveTopAlbum?.first
                                        val topAlbumTrack = topAlbum?.let { album ->
                                            tracks.firstOrNull { it.album.equals(album, true) && (it.artworkBytes?.isNotEmpty() == true) }
                                                ?: tracks.firstOrNull { it.album.equals(album, true) }
                                        }
                                        MostPlayedCategoryCard(
                                        title = "MÚSICAS",
                                        subtitle = topTrack?.let { "${it.displayTitle()} • ${liveTopTrack?.second ?: 0L}x" },
                                        artwork = topTrack?.artworkBytes,
                                        artworkUri = topTrack?.id,
                                        modifier = Modifier.weight(1f),
                                        onClick = { mode = MostPlayedMode.TRACKS },
                                    )
                                    MostPlayedCategoryCard(
                                        title = "ÁLBUNS",
                                        subtitle = topAlbum?.let { "$it • ${liveTopAlbum?.third ?: 0L}x" },
                                        artwork = topAlbumTrack?.artworkBytes,
                                        artworkUri = topAlbumTrack?.id,
                                        modifier = Modifier.weight(1f),
                                        onClick = { mode = MostPlayedMode.ALBUMS },
                                    )
                                        MostPlayedCategoryCard(
                                            title = "ARTISTAS",
                                            subtitle = liveTopArtist?.let { "${it.first} • ${it.second}x" },
                                            artistName = liveTopArtist?.first,
                                            modifier = Modifier.weight(1f),
                                            onClick = { mode = MostPlayedMode.ARTISTS },
                                        )
                                    }
                                }
                            }
                        }
                        TouchScrollbar(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = UiTokens.scrollbarInset))
                    }
                }
            } else {
                val state = rememberLazyListState()
                when (mode) {
                    MostPlayedMode.TRACKS -> {
                        val values = remember(statsRevision, tracks) { store.topTracks(tracks) }
                        if (values.isEmpty()) EmptyMostPlayed() else SongListContainer(state = state, modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(values, key = { _, pair -> pair.first.id }) { index, (track, count) ->
                                MostPlayedRow(
                                    index + 1,
                                    track.displayTitle(),
                                    track.artist,
                                    track.album,
                                    count,
                                    track.artworkBytes,
                                    track.id,
                                ) { play(listOf(track), 0) }
                            }
                        }
                    }
                    MostPlayedMode.ALBUMS -> {
                        val values = remember(statsRevision, tracks) { store.topAlbums(tracks) }
                        if (values.isEmpty()) EmptyMostPlayed() else SongListContainer(state = state, modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(values, key = { _, item -> item.first + "\u0000" + item.second }) { index, (album, artist, count) ->
                                val artworkTrack = tracks.firstOrNull { it.album.equals(album, true) && it.artworkBytes?.isNotEmpty() == true }
                                    ?: tracks.firstOrNull { it.album.equals(album, true) }
                                MostPlayedRow(
                                    index + 1,
                                    album,
                                    artist,
                                    "",
                                    count,
                                    artworkTrack?.artworkBytes,
                                    artworkTrack?.id,
                                ) { onAlbum(album) }
                            }
                        }
                    }
                    MostPlayedMode.ARTISTS -> {
                        val values = remember(statsRevision, tracks) { store.topArtists(tracks) }
                        if (values.isEmpty()) EmptyMostPlayed() else SongListContainer(state = state, modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(values, key = { _, item -> item.first }) { index, (artist, count) ->
                                MostPlayedRow(
                                    index + 1,
                                    artist,
                                    "",
                                    "",
                                    count,
                                    null,
                                    null,
                                    artist,
                                ) { onArtist(artist) }
                            }
                        }
                    }
                    MostPlayedMode.OVERVIEW, MostPlayedMode.PLAYLISTS -> Unit
                }
            }
        }
    }

    if (creating) AlertDialog(
        onDismissRequest = { creating = false },
        title = { Text("Nova playlist") },
        text = { OutlinedTextField(value = createName, onValueChange = { createName = it }, label = { Text("Nome") }, singleLine = true) },
        confirmButton = { TextButton(enabled = createName.isNotBlank(), onClick = { onCreate(createName.trim()); creating = false }) { Text("Criar") } },
        dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancelar") } },
    )
    renaming?.let { playlist ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Renomear playlist") },
            text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true) },
            confirmButton = { TextButton(enabled = renameText.isNotBlank(), onClick = { onRename(playlist.name, renameText.trim()); renaming = null }) { Text("Salvar") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun MostPlayedCategoryCard(
    title: String,
    subtitle: String?,
    artwork: ByteArray? = null,
    artworkUri: String? = null,
    artistName: String? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier.aspectRatio(0.92f).clickable(onClick = onClick),
        shape = RoundedCornerShape(UiTokens.songItemRadius),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.fillMaxSize().padding(UiTokens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(UiTokens.itemSpacing + 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            when {
                artistName != null -> ArtistAvatar(artistName, Modifier.fillMaxWidth().aspectRatio(1f))
                artwork != null || artworkUri != null -> PlayerArtworkBox(artwork, Modifier.fillMaxWidth().aspectRatio(1f), artworkUri ?: "most_$title", maxDimension = 768)
                else -> Box(
                    Modifier.fillMaxWidth().aspectRatio(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                }
            }
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EmptyMostPlayed() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Ainda não há músicas tocadas.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun MostPlayedRow(rank: Int, title: String, artist: String, album: String, count: Long, artwork: ByteArray?, artworkUri: String? = null, artistImageName: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = UiTokens.mostPlayedRowHeight)
            .clip(RoundedCornerShape(UiTokens.songItemRadius))
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(UiTokens.songItemRadius))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.34f))
            .clickable(onClick = onClick)
            .padding(horizontal = UiTokens.songItemPadding, vertical = UiTokens.songItemPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(rank.toString(), modifier = Modifier.width(UiTokens.rankWidth), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (artistImageName != null) {
            ArtistAvatar(artistImageName, Modifier.size(UiTokens.songItemArtworkSize))
        } else if (artwork != null || artworkUri != null) {
            PlayerArtworkBox(artwork, Modifier.size(UiTokens.songItemArtworkSize), artworkUri, maxDimension = 192)
        } else {
            Box(Modifier.size(UiTokens.songItemArtworkSize), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(UiTokens.primaryIconSize))
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = UiTokens.cardItemHorizontalPadding), verticalArrangement = Arrangement.spacedBy(UiTokens.songItemSpacing)) {
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (artist.isNotBlank()) Text(artist, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (album.isNotBlank()) Text(album, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("$count×", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SelectionIndicator(
    selected: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(36.dp),
    ) {
        Icon(
            imageVector = if (selected) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
            contentDescription = if (selected) "Desselecionar" else "Selecionar",
        )
    }
}

@Composable
private fun SongItem(
    track: AudioTrack,
    onPlay: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onAlbum: () -> Unit,
    onArtist: () -> Unit,
    onGenre: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    showAddToPlaylist: Boolean = true,
    isCurrent: Boolean = false,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    reorderControls: Boolean = false,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    emphasizedBorder: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = UiTokens.songItemHeight)
            .clip(RoundedCornerShape(UiTokens.songItemRadius))
            .border(
                BorderStroke(1.dp, when {
                    isCurrent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                    emphasizedBorder -> MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
                    else -> MaterialTheme.colorScheme.outlineVariant
                }),
                RoundedCornerShape(UiTokens.songItemRadius),
            )
            .background(if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.34f))
            .clickable(onClick = if (selectionMode) onToggleSelect else onPlay)
            .padding(UiTokens.songItemPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            selectionMode -> SelectionIndicator(selected = selected, onClick = onToggleSelect)
            reorderControls && onMoveUp != null && onMoveDown != null -> Column(
                modifier = Modifier.width(UiTokens.reorderControlsWidth),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(UiTokens.toolbarIconButtonSize)) { Icon(Icons.Rounded.KeyboardArrowUp, "Subir música") }
                IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(UiTokens.toolbarIconButtonSize)) { Icon(Icons.Rounded.KeyboardArrowDown, "Abaixar música") }
            }
        }
        PlayerArtworkBox(track.artworkBytes, Modifier.size(UiTokens.songItemArtworkSize), track.id, maxDimension = 384)
        Column(Modifier.weight(1f).padding(horizontal = UiTokens.cardItemHorizontalPadding), verticalArrangement = Arrangement.spacedBy(UiTokens.songItemSpacing)) {
            Text(track.displayTitle(), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist.ifBlank { "—" }, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.album.ifBlank { "—" }, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatDuration(track.durationMs), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Box {
            IconButton(onClick = { expanded = true }) { Icon(Icons.Rounded.MoreVert, "Opções") }
            DropdownMenu(expanded, { expanded = false }) {
                DropdownMenuItem({ Text("Ver infos") }, onClick = { expanded = false; info = true })
                DropdownMenuItem({ Text("Editar tags") }, onClick = { expanded = false; onEdit() })
                if (showAddToPlaylist) DropdownMenuItem({ Text("Adicionar à playlist") }, onClick = { expanded = false; onAddToPlaylist() })
                DropdownMenuItem({ Text("Compartilhar") }, onClick = { expanded = false; onShare() })
                onRemoveFromPlaylist?.let { remove -> DropdownMenuItem({ Text("Remover da playlist") }, onClick = { expanded = false; remove() }) }
                if (onRemoveFromPlaylist == null && onDelete != null) {
                    DropdownMenuItem({ Text("Excluir") }, onClick = { expanded = false; onDelete() })
                }
            }
        }
    }
    if (info) InfoDialog(track, onArtist = onArtist, onAlbum = onAlbum) { info = false }
}

@Composable
private fun InfoDialog(track: AudioTrack, onArtist: () -> Unit, onAlbum: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(track.displayTitle()) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(UiTokens.itemSpacing)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Artista: ")
                    TextButton(onClick = onArtist, contentPadding = PaddingValues(0.dp), enabled = track.artist.isNotBlank()) { Text(track.artist.ifBlank { "—" }, color = MaterialTheme.colorScheme.primary) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Álbum: ")
                    TextButton(onClick = onAlbum, contentPadding = PaddingValues(0.dp), enabled = track.album.isNotBlank()) { Text(track.album.ifBlank { "—" }, color = MaterialTheme.colorScheme.primary) }
                }
                Text("Ano: ${track.year.ifBlank { "—" }}")
                Text("Gênero: ${track.genre.ifBlank { "—" }}")
                Text("Faixa: ${track.trackNumber.ifBlank { "—" }}")
                Text("Duração: ${formatDuration(track.durationMs)}")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}

@Composable
private fun AddToPlaylistDialog(track: AudioTrack, onDismiss: () -> Unit, onPlaylistChanged: (List<String>, List<String>) -> Unit) {
    var playlists by remember { mutableStateOf(emptyList<PlaylistData>()) }
    val context = LocalContext.current
    val store = remember { PlaylistStore(context) }
    LaunchedEffect(Unit) { playlists = store.load() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Adicionar à playlist") }, text = {
        if (playlists.isEmpty()) Text("Crie uma playlist primeiro na aba Playlists.") else Column {
            playlists.forEach { p ->
                TextButton(
                    onClick = {
                        val oldIds = p.trackIds
                        store.add(p.name, track.id)
                        val newIds = store.load().firstOrNull { it.name == p.name }?.trackIds.orEmpty()
                        onPlaylistChanged(oldIds, newIds)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(p.name, Modifier.fillMaxWidth()) }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable
private fun AddSelectedToPlaylistDialog(trackIds: List<String>, onDismiss: () -> Unit, onDone: (List<String>, List<String>) -> Unit) {
    var playlists by remember { mutableStateOf(emptyList<PlaylistData>()) }
    val context = LocalContext.current
    val store = remember { PlaylistStore(context) }
    LaunchedEffect(Unit) { playlists = store.load() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Adicionar ${trackIds.size} música(s) à playlist") }, text = {
        if (playlists.isEmpty()) Text("Crie uma playlist primeiro na aba Playlists.") else Column {
            playlists.forEach { p ->
                TextButton(
                    onClick = {
                        val oldIds = p.trackIds
                        store.addAll(p.name, trackIds)
                        val newIds = store.load().firstOrNull { it.name == p.name }?.trackIds.orEmpty()
                        onDone(oldIds, newIds)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(p.name, Modifier.fillMaxWidth()) }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable
private fun PlaylistScreen(
    playlists: List<PlaylistData>,
    onCreate: (String) -> Unit,
    onImport: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onExport: (PlaylistData) -> Unit,
    onRename: (String, String) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<PlaylistData?>(null) }
    var renameText by remember { mutableStateOf("") }
    val state = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize().padding(horizontal = UiTokens.pageHorizontal, vertical = UiTokens.pageVertical).songListSpace(),
            verticalArrangement = Arrangement.spacedBy(UiTokens.songListSpacing),
            contentPadding = PaddingValues(bottom = UiTokens.songListBottomPadding),
        ) {
            item {
                Text("Playlists", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(UiTokens.standardListTopSpacing))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(UiTokens.toolbarGap)) {
                    OutlinedButton(onClick = { creating = true; name = "" }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Criar")
                    }
                    OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.FileOpen, null); Spacer(Modifier.width(6.dp)); Text("Importar")
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = UiTokens.sectionSpacing))
            }
            if (playlists.isEmpty()) item { Text("Nenhuma playlist criada.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else items(playlists, key = { it.name }) { playlist ->
                var expanded by remember(playlist.name) { mutableStateOf(false) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(UiTokens.songItemRadius))
                        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(UiTokens.songItemRadius))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.34f))
                        .clickable { onOpen(playlist.name) }
                        .padding(horizontal = UiTokens.cardItemHorizontalPadding, vertical = UiTokens.songItemPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(vertical = 1.dp), verticalArrangement = Arrangement.spacedBy(UiTokens.songItemSpacing)) {
                        Text(playlist.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${playlist.trackIds.size} músicas", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box {
                        IconButton(onClick = { expanded = true }) { Icon(Icons.Rounded.MoreVert, "Opções") }
                        DropdownMenu(expanded, { expanded = false }) {
                            DropdownMenuItem({ Text("Exportar M3U") }, onClick = { expanded = false; onExport(playlist) })
                            DropdownMenuItem({ Text("Renomear") }, onClick = { expanded = false; renameText = playlist.name; renaming = playlist })
                            DropdownMenuItem({ Text("Excluir") }, onClick = { expanded = false; onDelete(playlist.name) })
                        }
                    }
                }
            }
        }
        TouchScrollbar(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = UiTokens.scrollbarInset))
        ListJumpButtons(state, Modifier.align(Alignment.BottomEnd).padding(end = UiTokens.jumpEndPadding, bottom = UiTokens.jumpBottomPadding))
    }
    if (creating) AlertDialog(
        onDismissRequest = { creating = false },
        title = { Text("Nova playlist") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Nome") }) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name); creating = false }) { Text("Criar") } },
        dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancelar") } },
    )
    renaming?.let { playlist ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Renomear playlist") },
            text = { OutlinedTextField(renameText, { renameText = it }, singleLine = true, label = { Text("Nome") }) },
            confirmButton = { TextButton(enabled = renameText.isNotBlank(), onClick = { onRename(playlist.name, renameText); renaming = null }) { Text("Salvar") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun ArtistsScreen(tracks: List<AudioTrack>, open: (String) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    data class ArtistItem(val name: String, val songs: Int)
    val artists = remember(tracks, search) {
        tracks.asSequence()
            .map { it.artist.trim() }
            .filter { it.isNotBlank() && it != "<unknown>" }
            .groupBy { Normalizer.normalize(it, Normalizer.Form.NFKC).replace(Regex("\\s+"), " ").trim().lowercase(Locale.ROOT) }
            .values
            .map { group -> ArtistItem(group.first(), group.size) }
            .filter { search.isBlank() || it.name.contains(search, ignoreCase = true) }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .toList()
    }
    StandardPage {
        SectionTitle("Artistas")
        Spacer(Modifier.height(UiTokens.listHeaderGap))
        SearchBar(value = search, onValueChange = { search = it }, placeholder = "Pesquisar artistas")
        Spacer(Modifier.height(UiTokens.standardListTopSpacing))
        val state = rememberLazyListState()
        SongListContainer(state = state, modifier = Modifier.weight(1f)) {
            val grouped = artists.groupBy { it.name.firstOrNull()?.uppercaseChar()?.toString() ?: "#" }.toSortedMap()
            grouped.forEach { (letter, items) ->
                item(key = "artist-section-$letter") {
                    Text(letter, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = UiTokens.standardListTopSpacing, bottom = 1.dp))
                }
                items(items, key = { "$letter-${it.name}" }) { item ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(UiTokens.songItemRadius))
                            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(UiTokens.songItemRadius))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.34f))
                            .clickable { open(item.name) }
                            .padding(horizontal = UiTokens.cardItemHorizontalPadding, vertical = UiTokens.songItemPadding),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ArtistAvatar(item.name, Modifier.size(UiTokens.songItemArtworkSize))
                        Column(Modifier.weight(1f).padding(start = UiTokens.cardItemHorizontalPadding)) {
                            Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (item.songs == 1) "1 música" else "${item.songs} músicas",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberArtistPhotoBitmap(artist: String): Bitmap? {
    val normalized = remember(artist) { Normalizer.normalize(artist, Normalizer.Form.NFKC).trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT) }
    var bitmap by remember(artist) { mutableStateOf<Bitmap?>(artistPhotoCache.get(normalized)) }
    LaunchedEffect(artist) {
        if (bitmap == null) bitmap = fetchArtistPhoto(artist)
    }
    return bitmap
}

@Composable
private fun ArtistAvatar(artist: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val bitmap = rememberArtistPhotoBitmap(artist)
    Box(
        modifier
            .clip(RoundedCornerShape(UiTokens.songItemRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = artist,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } ?: Icon(Icons.Rounded.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(UiTokens.primaryIconSize))
    }
}

@Composable
private fun GenresScreen(tracks: List<AudioTrack>, open: (String) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    val genres = tracks
        .map { it.genre.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .sortedBy { it.lowercase(Locale.ROOT) }
        .filter { search.isBlank() || it.contains(search, ignoreCase = true) }
    StandardPage {
        SectionTitle("Gêneros")
        Spacer(Modifier.height(UiTokens.listHeaderGap))
        SearchBar(value = search, onValueChange = { search = it }, placeholder = "Pesquisar gêneros")
        Spacer(Modifier.height(UiTokens.standardListTopSpacing))
        AlphabeticalGroupList("", if (genres.isEmpty()) listOf("Sem gênero") else genres, open, Modifier.weight(1f))
    }
}

@Composable
private fun AlphabeticalGroupList(title: String, values: List<String>, open: (String) -> Unit, modifier: Modifier = Modifier) {
    val state = rememberLazyListState()
    val sections = values.groupBy { value -> value.firstOrNull()?.uppercaseChar()?.toString() ?: "#" }
    SongListContainer(
        state = state,
        modifier = modifier.fillMaxSize(),
    ) {
        sections.toSortedMap().forEach { (letter, names) ->
            item(key = "section-$letter") {
                Text(letter, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = UiTokens.standardListTopSpacing, bottom = 1.dp))
            }
            items(names, key = { "$letter-$it" }) { value ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(UiTokens.songItemRadius))
                        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(UiTokens.songItemRadius))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.34f))
                        .clickable { open(value) }
                        .padding(horizontal = UiTokens.cardItemHorizontalPadding, vertical = UiTokens.songItemPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(value, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun AlbumsScreen(tracks: List<AudioTrack>, search: String, onSearchChange: (String) -> Unit, sortMode: AlbumSortMode, onSortModeChange: (AlbumSortMode) -> Unit, ascending: Boolean, onAscendingChange: () -> Unit, open: (String) -> Unit) {
    var filterOpen by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    data class AlbumItem(val key: String, val name: String, val artist: String, val year: String, val duration: Long, val songs: Int, val artwork: ByteArray?)
    fun albumKey(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")
        .trim()
        .replace(Regex("\\s+"), " ")
        .lowercase(Locale.ROOT)

    val albums = remember(tracks, search, sortMode, ascending) {
        tracks.filter { it.album.isNotBlank() }
            .groupBy { albumKey(it.album) }
            .values.mapNotNull { group ->
                val first = group.firstOrNull() ?: return@mapNotNull null
                val artist = first.albumArtist.ifBlank { first.artist }
                AlbumItem(first.album, first.album, artist, group.mapNotNull { it.year.toIntOrNull() }.maxOrNull()?.toString().orEmpty(), group.sumOf { it.durationMs }, group.size, group.firstOrNull { it.artworkBytes?.isNotEmpty() == true }?.artworkBytes ?: first.artworkBytes)
            }
            .filter { item -> search.isBlank() || listOf(item.name, item.artist, item.year).any { it.contains(search, true) } }
            .sortedWith { a, b ->
                val v = when (sortMode) {
                    AlbumSortMode.NAME -> a.name.compareTo(b.name, true)
                    AlbumSortMode.ARTIST -> a.artist.compareTo(b.artist, true)
                    AlbumSortMode.YEAR -> a.year.toIntOrNull()?.compareTo(b.year.toIntOrNull() ?: 0) ?: 0
                    AlbumSortMode.DURATION -> a.duration.compareTo(b.duration)
                    AlbumSortMode.SONGS -> a.songs.compareTo(b.songs)
                }
                if (ascending) v else -v
            }
    }
    val changeAlbumSearch: (String) -> Unit = onSearchChange
    val changeAlbumSort: (AlbumSortMode) -> Unit = { mode -> onSortModeChange(mode) }
    val changeAlbumAscending: () -> Unit = { onAscendingChange() }
    val albumTrackIds = remember(tracks) { tracks.groupBy { it.album.ifBlank { "Sem álbum" } }.mapValues { (_, list) -> list.firstOrNull()?.id } }
    Box(Modifier.fillMaxSize()) {
        StandardScreenColumn {
            SectionTitle("Álbuns", modifier = Modifier.padding(bottom = UiTokens.listHeaderGap))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SearchBar(value = search, onValueChange = changeAlbumSearch, placeholder = "Pesquisar álbuns", modifier = Modifier.weight(1f))
                SortMenuButton(
                    open = filterOpen,
                    onOpenChange = { filterOpen = it },
                    title = "Ordenar por",
                    options = AlbumSortMode.entries,
                    selected = sortMode,
                    label = { it.label },
                    onSelected = { mode -> changeAlbumSort(mode) },
                    ascending = ascending,
                    onToggleAscending = changeAlbumAscending,
                )
            }
            Spacer(Modifier.height(UiTokens.standardListTopSpacing))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyVerticalGrid(state = gridState, columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize().songListSpace(), contentPadding = PaddingValues(bottom = UiTokens.songListBottomPadding), verticalArrangement = Arrangement.spacedBy(UiTokens.itemSpacing), horizontalArrangement = Arrangement.spacedBy(UiTokens.itemSpacing)) {
                    items(albums, key = { "${it.key}\u0000${it.artist}" }) { item ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(UiTokens.albumCardHeight)
                                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(UiTokens.cardRadius))
                                .clickable { open(item.key) },
                        ) {
                            PlayerArtworkBox(
                                item.artwork,
                                Modifier.fillMaxWidth().height(UiTokens.albumArtworkHeight),
                                albumTrackIds[item.name],
                                maxDimension = 256,
                            )
                            Column(
                                Modifier.fillMaxWidth().padding(UiTokens.cardPadding),
                                verticalArrangement = Arrangement.spacedBy(UiTokens.itemSpacing),
                            ) {
                                Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.artist.ifBlank { "—" }, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${item.songs} músicas", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                Text(formatDuration(item.duration), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                if (item.year.isNotBlank()) Text(item.year, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                }
                TouchGridScrollbar(gridState, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = UiTokens.scrollbarInset))
                AlbumGridJumpButtons(gridState, Modifier.align(Alignment.BottomEnd).padding(end = UiTokens.jumpEndPadding, bottom = UiTokens.jumpBottomPadding))
            }
        }
    }
}

@Composable
private fun DetailScreen(type: Detail, key: String, tracks: List<AudioTrack>, playlist: PlaylistData?, onPlay: (List<AudioTrack>, Int) -> Unit, onPlayRandom: (List<AudioTrack>) -> Unit, onEdit: (AudioTrack) -> Unit, onShare: (AudioTrack) -> Unit, onRemoveFromPlaylist: (String) -> Unit, onRemoveSelectedFromPlaylist: (String, List<String>) -> Unit, onPlaylistOrderChange: (String, List<String>) -> Unit, currentTrackId: String?, onBack: () -> Unit, onOpenArtist: (String) -> Unit, onOpenAlbum: (String) -> Unit) {
    var playlistOrderIds by remember(key, type) { mutableStateOf(playlist?.trackIds.orEmpty()) }
    var playlistSearch by rememberSaveable(key, type) { mutableStateOf("") }
    var playlistSortMode by rememberSaveable(key, type) { mutableStateOf(PlaylistSortMode.MANUAL) }
    var playlistAscending by rememberSaveable(key, type) { mutableStateOf(true) }
    var playlistSortOpen by remember { mutableStateOf(false) }
    var playlistSelectionMode by rememberSaveable(key, type) { mutableStateOf(false) }
    var showExpandedArtwork by remember(key, type) { mutableStateOf(false) }
    val selectedPlaylistIds = remember { mutableStateListOf<String>() }
    LaunchedEffect(playlist?.trackIds) { if (type == Detail.PLAYLIST && playlist != null) playlistOrderIds = playlist.trackIds }
    val detailTrackById = remember(tracks) { tracks.associateBy { it.id } }
    val base = when (type) {
        Detail.ARTIST -> tracks.filter { it.artist.equals(key, true) }
        Detail.ALBUM -> tracks.filter { it.album.equals(key, true) }
        Detail.GENRE -> if (key.equals("Sem gênero", true)) tracks.filter { it.genre.isBlank() } else tracks.filter { it.genre.equals(key, true) }
        Detail.PLAYLIST -> playlistOrderIds.mapNotNull(detailTrackById::get)
        else -> emptyList()
    }
    val playlistFiltered = if (type == Detail.PLAYLIST && playlistSearch.isNotBlank()) {
        val query = playlistSearch.trim()
        base.filter {
            it.displayTitle().contains(query, true) ||
                it.artist.contains(query, true) ||
                it.album.contains(query, true)
        }
    } else base
    val playlistShown = if (type == Detail.PLAYLIST) {
        val sorted = when (playlistSortMode) {
            PlaylistSortMode.MANUAL -> playlistFiltered
            PlaylistSortMode.TITLE -> playlistFiltered.sortedBy { it.displayTitle().lowercase(Locale.ROOT) }
            PlaylistSortMode.ARTIST -> playlistFiltered.sortedWith(compareBy({ it.artist.lowercase(Locale.ROOT) }, { it.displayTitle().lowercase(Locale.ROOT) }))
            PlaylistSortMode.ALBUM -> playlistFiltered.sortedWith(compareBy({ it.album.lowercase(Locale.ROOT) }, { it.displayTitle().lowercase(Locale.ROOT) }))
            PlaylistSortMode.DATE -> playlistFiltered.sortedBy { it.modifiedAtSeconds }
            PlaylistSortMode.DURATION -> playlistFiltered.sortedBy { it.durationMs }
        }
        if (playlistAscending || playlistSortMode == PlaylistSortMode.MANUAL) sorted else sorted.asReversed()
    } else base
    val shown = if (type == Detail.PLAYLIST) playlistShown else base.sortedWith(compareBy({ it.discNumber.toIntOrNull() ?: 0 }, { it.trackNumber.toIntOrNull() ?: Int.MAX_VALUE }, { it.displayTitle().lowercase() }))
    val duration = shown.sumOf { it.durationMs }
    val state = rememberLazyListState()
    val reorderScope = rememberCoroutineScope()

    fun scrollItemToCenter(index: Int) {
        if (index !in shown.indices) return
        reorderScope.launch {
            val viewport = (state.layoutInfo.viewportEndOffset - state.layoutInfo.viewportStartOffset).coerceAtLeast(0)
            val centeredOffset = -((viewport - 104) / 2).coerceAtLeast(0)
            state.scrollToItem(index, centeredOffset)
        }
    }

    fun movePlaylistItem(id: String, direction: Int) {
        if (type != Detail.PLAYLIST || playlistSortMode != PlaylistSortMode.MANUAL || playlistSearch.isNotBlank()) return
        val from = playlistOrderIds.indexOf(id)
        if (from < 0) return
        val to = (from + direction).coerceIn(0, playlistOrderIds.lastIndex)
        if (from == to) {
            val currentIndex = playlistOrderIds.indexOf(id)
            if (currentIndex >= 0) scrollItemToCenter(currentIndex)
            return
        }
        val order = playlistOrderIds.toMutableList().apply { add(to, removeAt(from)) }
        playlistOrderIds = order
        onPlaylistOrderChange(key, order)
        scrollItemToCenter(to)
    }

    fun exitPlaylistSelection() { playlistSelectionMode = false; selectedPlaylistIds.clear() }

    val artwork = shown.firstOrNull { it.artworkBytes?.isNotEmpty() == true }?.artworkBytes
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = UiTokens.screenHorizontalPadding, vertical = UiTokens.screenVerticalPadding)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (playlistSelectionMode) exitPlaylistSelection() else onBack() }) {
                    Icon(Icons.Rounded.ArrowBack, "Voltar")
                }
                Text(
                    key,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (type == Detail.PLAYLIST) {
                Row(Modifier.fillMaxWidth().padding(horizontal = UiTokens.cardItemHorizontalPadding), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SearchBar(value = playlistSearch, onValueChange = { playlistSearch = it }, placeholder = "Pesquisar músicas", modifier = Modifier.weight(1f))
                    SortMenuButton(
                        open = playlistSortOpen,
                        onOpenChange = { playlistSortOpen = it },
                        title = "Ordenar por",
                        options = PlaylistSortMode.entries,
                        selected = playlistSortMode,
                        label = { it.label },
                        onSelected = { playlistSortMode = it },
                        ascending = playlistAscending.takeIf { playlistSortMode != PlaylistSortMode.MANUAL },
                        onToggleAscending = { playlistAscending = !playlistAscending }.takeIf { playlistSortMode != PlaylistSortMode.MANUAL },
                    )
                    IconButton(onClick = {
                        playlistSelectionMode = !playlistSelectionMode
                        if (!playlistSelectionMode) selectedPlaylistIds.clear()
                    }) {
                        Icon(
                            imageVector = if (playlistSelectionMode) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                            contentDescription = if (playlistSelectionMode) "Encerrar seleção" else "Selecionar",
                            tint = if (playlistSelectionMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (playlistSelectionMode) {
                    SelectionToolbar(
                        selectedCount = selectedPlaylistIds.size,
                        totalCount = playlistShown.size,
                        onCancel = { exitPlaylistSelection() },
                        onToggleAll = {
                            if (selectedPlaylistIds.size == playlistShown.size) selectedPlaylistIds.clear()
                            else { selectedPlaylistIds.clear(); selectedPlaylistIds.addAll(playlistShown.map { it.id }) }
                        },
                    )
                    Row(Modifier.fillMaxWidth().padding(vertical = UiTokens.sectionSpacing), horizontalArrangement = Arrangement.spacedBy(UiTokens.toolbarGap)) {
                        OutlinedButton(
                            enabled = selectedPlaylistIds.isNotEmpty(),
                            onClick = { onRemoveSelectedFromPlaylist(key, selectedPlaylistIds.toList()); exitPlaylistSelection() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            Icon(Icons.Rounded.Delete, null); Spacer(Modifier.width(6.dp)); Text("Remover da playlist")
                        }
                    }
                } else {
                    Spacer(Modifier.height(UiTokens.sectionSpacing))
                    Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.toolbarGap)) {
                        OutlinedButton(onClick = { onPlay(shown, 0) }, enabled = shown.isNotEmpty(), modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(5.dp)); Text("Tocar") }
                        OutlinedButton(onClick = { onPlayRandom(shown) }, enabled = shown.isNotEmpty(), modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(5.dp)); Text("Aleatório") }
                    }
                }
                Spacer(Modifier.height(UiTokens.sectionSpacing))
                Text("${shown.size} músicas • ${formatDuration(duration)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (playlistSortMode != PlaylistSortMode.MANUAL || playlistSearch.isNotBlank()) Text("Para reordenar manualmente, use Manual e deixe a busca vazia.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                SongListContainer(state = state, modifier = Modifier.fillMaxWidth().weight(1f)) {
                        items(shown, key = { it.id }) { t ->
                            val orderIndex = playlistOrderIds.indexOf(t.id)
                            SongItem(
                                t,
                                { onPlay(shown, shown.indexOfFirst { it.id == t.id }) },
                                { onEdit(t) },
                                { onShare(t) },
                                {}, {}, {}, {},
                                { onRemoveFromPlaylist(t.id) },
                                showAddToPlaylist = false,
                                isCurrent = t.id == currentTrackId,
                                selectionMode = playlistSelectionMode,
                                selected = t.id in selectedPlaylistIds,
                                onToggleSelect = { if (t.id in selectedPlaylistIds) selectedPlaylistIds.remove(t.id) else selectedPlaylistIds.add(t.id) },
                                reorderControls = !playlistSelectionMode && playlistSearch.isBlank() && playlistSortMode == PlaylistSortMode.MANUAL,
                                onMoveUp = { movePlaylistItem(t.id, -1) },
                                onMoveDown = { movePlaylistItem(t.id, 1) },
                                canMoveUp = orderIndex > 0,
                                canMoveDown = orderIndex in 0 until playlistOrderIds.lastIndex,
                                emphasizedBorder = true,
                            )
                        }
                    }
            } else if (type == Detail.ALBUM) {
                PlayerArtworkBox(
                    artwork,
                    Modifier.size(UiTokens.detailArtworkSize).align(Alignment.CenterHorizontally),
                    shown.firstOrNull()?.id,
                    onClick = { showExpandedArtwork = true },
                )
                Spacer(Modifier.height(UiTokens.sectionSpacing))
                Text(key, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                val artist = shown.firstOrNull()?.albumArtist?.ifBlank { shown.firstOrNull()?.artist.orEmpty() }.orEmpty()
                TextButton(onClick = { onOpenArtist(artist) }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp), modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(artist, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Text("${shown.size} músicas • ${formatDuration(duration)}${shown.firstOrNull()?.year?.takeIf { it.isNotBlank() }?.let { " • $it" } ?: ""}", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { onPlay(shown, 0) }, enabled = shown.isNotEmpty(), modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(5.dp)); Text("Tocar") }
                    OutlinedButton(onClick = { onPlayRandom(shown) }, enabled = shown.isNotEmpty(), modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(5.dp)); Text("Aleatório") }
                }
                Spacer(Modifier.height(UiTokens.sectionSpacing))
                SongListContainer(state = state, modifier = Modifier.fillMaxWidth().weight(1f)) {
                        items(shown, key = { it.id }) { t ->
                            SongItem(t, { onPlay(shown, shown.indexOf(t)) }, { onEdit(t) }, { onShare(t) }, {}, {}, {}, {}, onRemoveFromPlaylist = null, isCurrent = t.id == currentTrackId)
                        }
                    }
            } else {
                if (type == Detail.ARTIST) {
                    ArtistAvatar(
                        key,
                        Modifier
                            .size(UiTokens.detailArtworkSize)
                            .align(Alignment.CenterHorizontally),
                        onClick = { showExpandedArtwork = true },
                    )
                    Spacer(Modifier.height(UiTokens.sectionSpacing))
                    Text(
                        key,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(UiTokens.itemSpacing))
                    val albums = shown.groupBy { it.album.ifBlank { "Sem álbum" } }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                    Text("${shown.size} músicas • ${albums.size} álbuns • ${formatDuration(duration)}", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(UiTokens.sectionSpacing))
                    Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.toolbarGap)) {
                        OutlinedButton(onClick = { onPlay(shown, 0) }, enabled = shown.isNotEmpty()) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(5.dp)); Text("Tocar") }
                        OutlinedButton(onClick = { onPlayRandom(shown) }, enabled = shown.isNotEmpty()) { Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(5.dp)); Text("Aleatório") }
                    }
                    Spacer(Modifier.height(UiTokens.sectionSpacing))
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        LazyColumn(state = state, modifier = Modifier.fillMaxSize().songListSpace(), verticalArrangement = Arrangement.spacedBy(UiTokens.songListSpacing), contentPadding = PaddingValues(bottom = UiTokens.songListBottomPadding)) {
                            albums.forEach { (album, albumTracks) ->
                                item(key = "album-$album") {
                                    Column(Modifier.fillMaxWidth().padding(top = UiTokens.standardListTopSpacing, bottom = 1.dp)) {
                                        TextButton(onClick = { onOpenAlbum(album) }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) { Text(album, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                        Text("${albumTracks.size} músicas • ${formatDuration(albumTracks.sumOf { it.durationMs })}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                items(albumTracks, key = { it.id }) { t ->
                                    SongItem(t, { onPlay(shown, shown.indexOf(t)) }, { onEdit(t) }, { onShare(t) }, {}, {}, {}, {}, onRemoveFromPlaylist = null, isCurrent = t.id == currentTrackId)
                                }
                            }
                        }
                        TouchScrollbar(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = UiTokens.scrollbarInset))
                        ListJumpButtons(state, Modifier.align(Alignment.BottomEnd).padding(end = UiTokens.jumpEndPadding, bottom = UiTokens.jumpBottomPadding))
                    }
                } else {
                    if (shown.isNotEmpty()) Text("${shown.size} músicas • ${formatDuration(duration)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(UiTokens.sectionSpacing))
                    Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.toolbarGap)) {
                        OutlinedButton(onClick = { onPlay(shown, 0) }, enabled = shown.isNotEmpty()) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(5.dp)); Text("Tocar") }
                        OutlinedButton(onClick = { onPlayRandom(shown) }, enabled = shown.isNotEmpty()) { Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(5.dp)); Text("Aleatório") }
                    }
                    Spacer(Modifier.height(UiTokens.sectionSpacing))
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        LazyColumn(state = state, modifier = Modifier.fillMaxSize().songListSpace(), verticalArrangement = Arrangement.spacedBy(UiTokens.songListSpacing), contentPadding = PaddingValues(bottom = UiTokens.songListBottomPadding)) {
                            items(shown, key = { it.id }) { t -> SongItem(t, { onPlay(shown, shown.indexOf(t)) }, { onEdit(t) }, { onShare(t) }, {}, {}, {}, {}, onRemoveFromPlaylist = null, isCurrent = t.id == currentTrackId) }
                        }
                        TouchScrollbar(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = UiTokens.scrollbarInset))
                        ListJumpButtons(state, Modifier.align(Alignment.BottomEnd).padding(end = UiTokens.jumpEndPadding, bottom = UiTokens.jumpBottomPadding))
                    }
                }
            }
        }
        if (showExpandedArtwork && type in setOf(Detail.ARTIST, Detail.ALBUM)) {
            val expandedBitmap = if (type == Detail.ARTIST) {
                rememberArtistPhotoBitmap(key)
            } else {
                rememberArtworkBitmap(artwork, shown.firstOrNull()?.id, 1280)
            }
            ArtworkFullscreenOverlay(
                bitmap = expandedBitmap,
                contentDescription = key,
                onDismiss = { showExpandedArtwork = false },
            )
        }
    }
}

@Composable
private fun SettingsTab(settings: AppSettings, onChange: (AppSettings) -> Unit, downloadFolderLabel: String, onChooseDownloadFolder: (() -> Unit)?, appVersion: String) {
    val context = LocalContext.current
    val state = rememberLazyListState()
    StandardPage {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = state,
                modifier = Modifier.fillMaxSize().songListSpace(),
                verticalArrangement = Arrangement.spacedBy(UiTokens.songListSpacing),
                contentPadding = PaddingValues(bottom = UiTokens.songListBottomPadding),
            ) {
                item { SectionTitle("Configurações") }
                item { SettingSwitch("Atualizar biblioteca automaticamente", settings.autoRefreshLibrary) { onChange(settings.copy(autoRefreshLibrary = it)) } }
                item { SettingSwitch("Incorporar thumbnail do YouTube", settings.embedYoutubeThumbnail) { onChange(settings.copy(embedYoutubeThumbnail = it)) } }
                item {
                    var volumeNormalization by remember { mutableStateOf(VolumeNormalizer.isEnabled(context)) }
                    var normalizationLevel by remember { mutableFloatStateOf(VolumeNormalizer.getOutputLevel(context)) }
                    Column(Modifier.fillMaxWidth()) {
                        SettingSwitch("Normalização de volume", volumeNormalization) { enabled ->
                            volumeNormalization = enabled
                            VolumeNormalizer.setEnabled(context, enabled)
                            MusicPlaybackService.setVolumeNormalization(context, enabled)
                        }
                        if (volumeNormalization) {
                            Spacer(Modifier.height(UiTokens.itemSpacing))
                            Text("Nível da normalização: ${kotlin.math.round(normalizationLevel * 100).toInt()}%", fontWeight = FontWeight.SemiBold)
                            Slider(
                                value = normalizationLevel,
                                onValueChange = { normalizationLevel = it },
                                onValueChangeFinished = {
                                    VolumeNormalizer.setOutputLevel(context, normalizationLevel)
                                },
                                valueRange = 0.50f..1.00f,
                                steps = 9,
                            )
                            Text("Controla o nível máximo usado pela normalização suave.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item { SettingSwitch("Atualizar downloader automaticamente", settings.autoUpdateDownloader) { onChange(settings.copy(autoUpdateDownloader = it)) } }
                item { Column { Text("Pasta de download", fontWeight = FontWeight.SemiBold); Text(downloadFolderLabel.ifBlank { "Nenhuma selecionada" }, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(UiTokens.sectionSpacing)); OutlinedButton(onClick = { onChooseDownloadFolder?.invoke() }) { Icon(Icons.Rounded.Folder, null); Spacer(Modifier.width(6.dp)); Text("Escolher pasta") } } }
                item { Text("Versão $appVersion", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            TouchScrollbar(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = UiTokens.scrollbarInset))
        }
    }
}

@Composable
private fun SettingSwitch(label: String, value: Boolean, onChange: (Boolean) -> Unit) { ListItem(headlineContent = { Text(label) }, trailingContent = { Switch(value, onCheckedChange = onChange) }) }


@Composable
private fun AlbumGridJumpButtons(state: androidx.compose.foundation.lazy.grid.LazyGridState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilledTonalIconButton(onClick = { scope.launch { state.scrollToItem(0) } }) { Icon(Icons.Rounded.KeyboardArrowUp, "Ir para o topo") }
        FilledTonalIconButton(onClick = { scope.launch { val last = (state.layoutInfo.totalItemsCount - 1).coerceAtLeast(0); state.scrollToItem(last) } }) { Icon(Icons.Rounded.KeyboardArrowDown, "Ir para o final") }
    }
}

@Composable
private fun TouchGridScrollbar(state: LazyGridState, modifier: Modifier, topInset: Dp = 0.dp, bottomInset: Dp = 0.dp) {
    val layout by remember { derivedStateOf { state.layoutInfo } }
    val total = layout.totalItemsCount
    val visible = layout.visibleItemsInfo.size
    if (total <= visible || total <= 0) return
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val top = with(density) { topInset.toPx() }
    val bottom = with(density) { bottomInset.toPx() }
    var dragY by remember { mutableFloatStateOf(0f) }
    Canvas(modifier.width(UiTokens.scrollbarTouchWidth).pointerInput(total, visible, top, bottom) {
        detectDragGestures(
            onDragStart = {
                val availableHeight = (size.height - top - bottom).coerceAtLeast(1f)
                val thumb = with(density) { UiTokens.scrollbarThumbHeight.toPx() }.coerceAtMost(availableHeight)
                val maxY = (availableHeight - thumb).coerceAtLeast(1f)
                val first = layout.visibleItemsInfo.firstOrNull()?.index ?: 0
                val maxIndex = (total - visible).coerceAtLeast(0)
                dragY = if (maxIndex > 0) first.toFloat() / maxIndex * maxY else 0f
            },
            onDrag = { change, dragAmount ->
                change.consume()
                val availableHeight = (size.height - top - bottom).coerceAtLeast(1f)
                val thumb = with(density) { UiTokens.scrollbarThumbHeight.toPx() }.coerceAtMost(availableHeight)
                val maxY = (availableHeight - thumb).coerceAtLeast(1f)
                dragY = (dragY + dragAmount.y).coerceIn(0f, maxY)
                val maxIndex = (total - visible).coerceAtLeast(0)
                val target = if (maxY > 0f) (dragY / maxY * maxIndex).toInt().coerceIn(0, maxIndex) else 0
                scope.launch { state.scrollToItem(target) }
            },
        )
    }) {
        val availableHeight = (size.height - top - bottom).coerceAtLeast(1f)
        val thumbH = with(density) { UiTokens.scrollbarThumbHeight.toPx() }.coerceAtMost(availableHeight)
        val maxY = (availableHeight - thumbH).coerceAtLeast(0f)
        val first = layout.visibleItemsInfo.firstOrNull()?.index ?: 0
        val maxIndex = (total - visible).coerceAtLeast(0)
        val fraction = if (maxIndex > 0) first.toFloat() / maxIndex else 0f
        val y = (fraction * maxY).coerceIn(0f, maxY)
        val thumbW = with(density) { UiTokens.scrollbarWidth.toPx() }
        val thumbX = ((size.width - thumbW) / 2f).coerceAtLeast(0f)
        drawRoundRect(Color.White.copy(alpha = 0.38f), Offset(thumbX, top + y), androidx.compose.ui.geometry.Size(thumbW, thumbH), CornerRadius(thumbW / 2f, thumbW / 2f))
    }
}

@Composable
private fun ListJumpButtons(state: LazyListState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilledTonalIconButton(onClick = { scope.launch { state.scrollToItem(0) } }) { Icon(Icons.Rounded.KeyboardArrowUp, "Ir para o topo") }
        FilledTonalIconButton(onClick = { scope.launch { val last = (state.layoutInfo.totalItemsCount - 1).coerceAtLeast(0); state.scrollToItem(last) } }) { Icon(Icons.Rounded.KeyboardArrowDown, "Ir para o final") }
    }
}

@Composable
private fun TouchScrollbar(state: LazyListState, modifier: Modifier, topInset: Dp = 0.dp, bottomInset: Dp = 0.dp) {
    val layout by remember { derivedStateOf { state.layoutInfo } }
    val total = layout.totalItemsCount
    val visible = layout.visibleItemsInfo.size
    if (total <= visible || total <= 0) return
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val top = with(density) { topInset.toPx() }
    val bottom = with(density) { bottomInset.toPx() }
    var dragY by remember { mutableFloatStateOf(0f) }
    var scrollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    Canvas(modifier.width(UiTokens.scrollbarTouchWidth).pointerInput(total, visible, top, bottom) {
        detectDragGestures(
            onDragStart = {
                val availableHeight = (size.height - top - bottom).coerceAtLeast(1f)
                val thumb = with(density) { UiTokens.scrollbarThumbHeight.toPx() }.coerceAtMost(availableHeight)
                val maxY = (availableHeight - thumb).coerceAtLeast(1f)
                val fraction = if (total > visible) state.firstVisibleItemIndex.toFloat() / (total - visible).toFloat() else 0f
                dragY = fraction * maxY
            },
            onDrag = { change, dragAmount ->
                change.consume()
                val availableHeight = (size.height - top - bottom).coerceAtLeast(1f)
                val thumb = with(density) { UiTokens.scrollbarThumbHeight.toPx() }.coerceAtMost(availableHeight)
                val maxY = (availableHeight - thumb).coerceAtLeast(1f)
                dragY = (dragY + dragAmount.y).coerceIn(0f, maxY)
                val maxIndex = (total - visible).coerceAtLeast(0)
                val target = if (maxY > 0f) (dragY / maxY * maxIndex).toInt().coerceIn(0, maxIndex) else 0
                scrollJob?.cancel()
                scrollJob = scope.launch { state.scrollToItem(target) }
            },
        )
    }) {
        val availableHeight = (size.height - top - bottom).coerceAtLeast(1f)
        val thumbH = with(density) { UiTokens.scrollbarThumbHeight.toPx() }.coerceAtMost(availableHeight)
        val maxY = (availableHeight - thumbH).coerceAtLeast(0f)
        val fraction = if (total > visible) state.firstVisibleItemIndex.toFloat() / (total - visible).toFloat() else 0f
        val y = (fraction * maxY).coerceIn(0f, maxY)
        val thumbW = with(density) { UiTokens.scrollbarWidth.toPx() }
        val thumbX = ((size.width - thumbW) / 2f).coerceAtLeast(0f)
        drawRoundRect(Color.White.copy(alpha = 0.38f), Offset(thumbX, top + y), androidx.compose.ui.geometry.Size(thumbW, thumbH), CornerRadius(thumbW / 2f, thumbW / 2f))
    }
}

private fun formatDuration(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0L)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val rest = (seconds % 60).toString().padStart(2, '0')
    return if (hours > 0) String.format(Locale.getDefault(), "%02d:%02d:%s", hours, minutes, rest) else "$minutes:$rest"
}
private fun AudioTrack.displayTitle(): String = title.ifBlank { displayName.substringBeforeLast('.').ifBlank { "Sem título" } }
private fun AudioTrack.toEditableTags() = EditableTags(title = title.ifBlank { displayName.substringBeforeLast('.') }, artist = artist, album = album, albumArtist = albumArtist, trackNumber = trackNumber, trackTotal = trackTotal, discNumber = discNumber, discTotal = discTotal, year = year, genre = genre, isrc = isrc, artworkBytes = artworkBytes)

@Composable
private fun rememberArtworkBitmap(bytes: ByteArray?, uri: String?, maxDimension: Int = 512): Bitmap? {
    val context = LocalContext.current
    val cacheKey = remember(uri, bytes, maxDimension) { uri ?: "bytes:${bytes?.let { System.identityHashCode(it) } ?: 0}:$maxDimension" }
    val bitmap by produceState<Bitmap?>(initialValue = artworkCache.get(cacheKey), cacheKey) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            val loaded = bytes?.let { raw ->
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
                    val max = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
                    val sample = generateSequence(1) { it * 2 }
                        .takeWhile { it < 8 }
                        .lastOrNull { max / it >= maxDimension } ?: 1
                    BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }.let { opts -> BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) }
                }.getOrNull()
            } ?: if (uri != null) {
                runCatching {
                    val parsed = Uri.parse(uri)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        context.contentResolver.loadThumbnail(parsed, Size(maxDimension, maxDimension), null)
                    } else null
                }.getOrNull() ?: runCatching {
                    MediaMetadataRetriever().use { retriever ->
                        retriever.setDataSource(context, Uri.parse(uri))
                        retriever.embeddedPicture?.let { raw ->
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
                            val max = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
                            val sample = generateSequence(1) { it * 2 }.takeWhile { it < 8 }.lastOrNull { max / it >= maxDimension } ?: 1
                            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }.let { opts -> BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) }
                        }
                    }
                }.getOrNull()
            } else null
            loaded?.let { artworkCache.put(cacheKey, it) }
            loaded
        }
    }
    return bitmap
}

@Composable
private fun PlayerArtworkBox(bytes: ByteArray?, modifier: Modifier, uri: String? = null, maxDimension: Int = 512, onClick: (() -> Unit)? = null) {
    val bitmap = rememberArtworkBitmap(bytes, uri, maxDimension)
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Rounded.MusicNote, null, Modifier.size(UiTokens.toolbarIconButtonSize))
    }
}

@Composable
private fun ArtworkFullscreenOverlay(bitmap: Bitmap?, contentDescription: String, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.90f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = contentDescription,
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .padding(horizontal = 4.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Fit,
                )
            }
        } else {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(72.dp),
            )
        }
    }
}
