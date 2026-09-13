package dev.truetagger.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.util.LruCache
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
import dev.truetagger.model.AppSettings
import dev.truetagger.model.AudioTrack
import dev.truetagger.model.EditableTags
import dev.truetagger.model.TagWriteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import java.text.Normalizer



private val artworkCache = object : LruCache<String, Bitmap>(64 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
}

private enum class HomeTab(val label: String) {
    LIBRARY("Biblioteca"), PLAYLISTS("Playlists"), ARTISTS("Artistas"), ALBUMS("Álbuns"), GENRES("Gêneros"), MOST_PLAYED("Ouvidas"), SETTINGS("Config.")
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
    TRACKS("Músicas"), ALBUMS("Álbuns"), ARTISTS("Artistas")
}

private class PlayStatsStore(context: Context) {
    private val prefs = context.getSharedPreferences("truetagger_play_stats", Context.MODE_PRIVATE)
    private val prefix = "track|"

    fun count(trackId: String): Long = prefs.getLong(prefix + trackId, 0L)

    fun topTracks(tracks: List<AudioTrack>, limit: Int = 10): List<Pair<AudioTrack, Long>> =
        tracks.map { it to count(it.id) }
            .filter { it.second > 0L }
            .sortedWith(compareByDescending<Pair<AudioTrack, Long>> { it.second }.thenBy { it.first.displayTitle().lowercase(Locale.ROOT) })
            .take(limit)

    fun topAlbums(tracks: List<AudioTrack>, limit: Int = 10): List<Triple<String, String, Long>> =
        tracks.filter { it.album.isNotBlank() }
            .groupBy { Normalizer.normalize(it.album, Normalizer.Form.NFKC).trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT) }
            .mapNotNull { (_, group) ->
                val count = group.sumOf { count(it.id) }
                if (count <= 0L) return@mapNotNull null
                val album = group.firstOrNull()?.album.orEmpty()
                val artist = group.firstOrNull()?.albumArtist?.ifBlank { group.firstOrNull()?.artist.orEmpty() }.orEmpty()
                Triple(album, artist, count)
            }
            .sortedWith(compareByDescending<Triple<String, String, Long>> { it.third }.thenBy { it.first.lowercase(Locale.ROOT) })
            .take(limit)

    fun topArtists(tracks: List<AudioTrack>, limit: Int = 10): List<Pair<String, Long>> =
        tracks.filter { it.artist.isNotBlank() && it.artist != "<unknown>" }
            .groupBy { Normalizer.normalize(it.artist, Normalizer.Form.NFKC).trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT) }
            .mapNotNull { (_, group) ->
                val count = group.sumOf { count(it.id) }
                if (count <= 0L) null else (group.firstOrNull()?.artist.orEmpty() to count)
            }
            .sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first.lowercase(Locale.ROOT) })
            .take(limit)
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
    var tab by rememberSaveable { mutableStateOf(HomeTab.LIBRARY) }
    var showIntro by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(900)
        showIntro = false
    }
    var playerScreen by rememberSaveable { mutableStateOf(PlayerScreen.NONE) }
    var detail by rememberSaveable { mutableStateOf(Detail.NONE) }
    var detailKey by rememberSaveable { mutableStateOf("") }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf(PlayerState.snapshot) }
    var librarySearch by rememberSaveable { mutableStateOf("") }
    var librarySortMode by rememberSaveable { mutableStateOf(PlayerSortMode.NAME) }
    var libraryAscending by rememberSaveable { mutableStateOf(true) }
    var albumSearch by rememberSaveable { mutableStateOf("") }
    var albumSortMode by rememberSaveable { mutableStateOf(AlbumSortMode.NAME) }
    var albumAscending by rememberSaveable { mutableStateOf(true) }
    val history = remember { mutableStateListOf<NavEntry>() }
    val context = LocalContext.current
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
            HomeTab.PLAYLISTS.name -> HomeTab.PLAYLISTS
            HomeTab.ALBUMS.name -> HomeTab.ALBUMS
            HomeTab.MOST_PLAYED.name -> HomeTab.MOST_PLAYED
            else -> HomeTab.LIBRARY
        }
        val savedDetail = uiPrefs.getString("detail", Detail.NONE.name)
        val savedDetailKey = uiPrefs.getString("detail_key", "").orEmpty()
        if ((savedDetail == Detail.PLAYLIST.name || savedDetail == Detail.ALBUM.name) && savedDetailKey.isNotBlank()) {
            detail = runCatching { Detail.valueOf(savedDetail) }.getOrDefault(Detail.NONE)
            detailKey = savedDetailKey
            tab = if (detail == Detail.PLAYLIST) HomeTab.PLAYLISTS else HomeTab.ALBUMS
        }
        uiStateLoaded = true
    }
    LaunchedEffect(tab, detail, detailKey, editorOpen, playerScreen, uiStateLoaded) {
        if (uiStateLoaded && !editorOpen && playerScreen == PlayerScreen.NONE) {
            val savedTab = when {
                detail == Detail.PLAYLIST -> HomeTab.PLAYLISTS
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
    val trackById = remember(tracks) { tracks.associateBy { it.id } }
    val drafts = remember { mutableStateMapOf<String, EditableTags>() }
    val libraryListState = rememberLazyListState()

    fun reloadPlaylists() { playlists = store.load() }
    val exportPlaylistLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
        val playlist = pendingExportPlaylist
        if (uri != null && playlist != null) store.exportM3u(uri, playlist, tracks)
        pendingExportPlaylist = null
    }
    val importPlaylistLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && store.importM3u(uri, tracks) != null) reloadPlaylists()
    }
    fun queueMatchesPlaylist(ids: List<String>): Boolean = snapshot.queue.isNotEmpty() && snapshot.queue.map { it.id }.toSet() == ids.toSet()
    fun updatePlayingQueueIfNeeded(oldIds: List<String>, newIds: List<String>) {
        if (!queueMatchesPlaylist(oldIds)) return
        val ordered = newIds.mapNotNull(trackById::get)
        if (ordered.isNotEmpty()) MusicPlaybackService.replaceQueue(context, ordered)
    }
    fun play(list: List<AudioTrack>, index: Int, startShuffle: Boolean = false) { if (list.isNotEmpty()) MusicPlaybackService.play(context, list[index], list, index, startShuffle) }
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
    fun navigateDetail(type: Detail, key: String) { pushCurrent(); editorOpen = false; playerScreen = PlayerScreen.NONE; detail = type; detailKey = key }
    fun navigatePlayer(screen: PlayerScreen) { pushCurrent(); editorOpen = false; detail = Detail.NONE; playerScreen = screen }
    fun goBack() {
        if (history.isNotEmpty()) {
            val previous = history.removeAt(history.lastIndex)
            tab = previous.tab; playerScreen = previous.playerScreen; detail = previous.detail; detailKey = previous.detailKey; editorOpen = previous.editorOpen
        } else {
            editorOpen = false; playerScreen = PlayerScreen.NONE; detail = Detail.NONE
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val next = PlayerState.snapshot
            if (next != snapshot) snapshot = next
            delay(750)
        }
    }

    BackHandler(enabled = history.isNotEmpty() || editorOpen || playerScreen != PlayerScreen.NONE || detail != Detail.NONE) { goBack() }

    MaterialTheme(colorScheme = TrueTaggerDarkScheme) {
        if (showIntro) {
            IntroSplash()
        } else Box(Modifier.fillMaxSize()) {
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
                        items = HomeTab.entries.map { t ->
                            BottomNavSpec(
                                label = t.label,
                                icon = when (t) {
                                    HomeTab.LIBRARY -> Icons.Rounded.LibraryMusic
                                    HomeTab.PLAYLISTS -> Icons.Rounded.QueueMusic
                                    HomeTab.ARTISTS -> Icons.Rounded.Person
                                    HomeTab.ALBUMS -> Icons.Rounded.Album
                                    HomeTab.GENRES -> Icons.Rounded.Category
                                    HomeTab.MOST_PLAYED -> Icons.Rounded.BarChart
                                    HomeTab.SETTINGS -> Icons.Rounded.Settings
                                },
                            )
                        },
                        selectedIndex = HomeTab.entries.indexOf(tab).takeIf { !editorOpen && detail == Detail.NONE && playerScreen == PlayerScreen.NONE } ?: -1,
                        onSelected = { index ->
                            val t = HomeTab.entries[index]
                            history.clear()
                            editorOpen = false
                            detail = Detail.NONE
                            playerScreen = PlayerScreen.NONE
                            tab = t
                            if (t == HomeTab.PLAYLISTS) reloadPlaylists()
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
                        onPlay = ::play,
                        onPlayRandom = { list -> if (list.isNotEmpty()) play(list, list.indices.random(), true) },
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
                            onGenre = { navigateDetail(Detail.GENRE, it) }
                        )
                        HomeTab.PLAYLISTS -> PlaylistScreen(
                            playlists,
                            onCreate = { name -> store.create(name); reloadPlaylists() },
                            onImport = { importPlaylistLauncher.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "text/plain")) },
                            onOpen = { navigateDetail(Detail.PLAYLIST, it) },
                            onDelete = { store.delete(it); reloadPlaylists() },
                            onExport = { playlist -> pendingExportPlaylist = playlist; exportPlaylistLauncher.launch("${playlist.name}.m3u") },
                            onRename = { oldName, newName ->
                                if (store.rename(oldName, newName)) {
                                    if (detail == Detail.PLAYLIST && detailKey == oldName) detailKey = newName
                                    reloadPlaylists()
                                }
                            },
                        )
                        HomeTab.ARTISTS -> ArtistsScreen(tracks) { navigateDetail(Detail.ARTIST, it) }
                        HomeTab.ALBUMS -> AlbumsScreen(tracks, albumSearch, { albumSearch = it }, albumSortMode, { albumSortMode = it }, albumAscending, { albumAscending = !albumAscending }) { navigateDetail(Detail.ALBUM, it) }
                        HomeTab.GENRES -> GenresScreen(tracks) { navigateDetail(Detail.GENRE, it) }
                        HomeTab.MOST_PLAYED -> MostPlayedScreen(tracks, play = ::play, onAlbum = { navigateDetail(Detail.ALBUM, it) }, onArtist = { navigateDetail(Detail.ARTIST, it) })
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

@Composable
private fun IntroSplash() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF07050E),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = Color(0xFF22133B),
                    tonalElevation = 6.dp,
                ) {
                    Icon(
                        Icons.Rounded.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.padding(26.dp).size(56.dp),
                        tint = Color(0xFFB18CFF),
                    )
                }
                Text("TrueTagger", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineMedium, color = Color(0xFFF4EEFF))
                Text("Carregando app...", color = Color(0xFFBDB2CF), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.width(150.dp), color = Color(0xFF9B6CFF), trackColor = Color(0xFF251A35))
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
        Row(
            Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            key(s.current?.id) { PlayerArtworkBox(s.current?.artworkBytes, Modifier.size(60.dp).clickable(onClick = onOpen), s.current?.id, maxDimension = 512) }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp).clickable(onClick = onOpen)) {
                Text(s.current?.displayTitle().orEmpty(), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(s.current?.album, s.current?.artist).filter { !it.isNullOrBlank() }.joinToString(" - "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onPrevious) { Icon(Icons.Rounded.SkipPrevious, "Anterior") }
            IconButton(onClick = onToggle) { Icon(if (s.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Tocar/Pausar") }
            IconButton(onClick = onNext) { Icon(Icons.Rounded.SkipNext, "Próxima") }
            IconButton(onClick = onShuffle) { Icon(painterResource(if (s.shuffle) dev.truetagger.android.R.drawable.truetagger_shuffle_on else dev.truetagger.android.R.drawable.truetagger_shuffle_off), "Aleatório", tint = if (s.shuffle) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
            IconButton(onClick = onRepeat) { Icon(painterResource(if (s.repeat == RepeatMode.ONE) dev.truetagger.android.R.drawable.truetagger_repeat_on else dev.truetagger.android.R.drawable.truetagger_repeat_off), "Repetição", tint = if (s.repeat != RepeatMode.OFF) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
        }
    }
}

@Composable
private fun FullPlayer(s: PlayerSnapshot, onClose: () -> Unit, onQueue: () -> Unit, onArtist: () -> Unit, onAlbum: () -> Unit) {
    val context = LocalContext.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.ArrowBack, "Voltar") }
                Text("Reproduzindo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(18.dp))
            PlayerArtworkBox(s.current?.artworkBytes, Modifier.fillMaxWidth().aspectRatio(1f), s.current?.id, maxDimension = 1024)
            Spacer(Modifier.height(18.dp))
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
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
            ListJumpButtons(state, Modifier.align(Alignment.BottomEnd).padding(end = 38.dp, bottom = 14.dp))
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

    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            exitSelection()
            refresh()
        }
    }

    fun performDelete() {
        val idsToDelete = shown.filter { it.id in selectedIds }.map { it.id }
        showDeleteConfirm = false
        if (idsToDelete.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val uris = idsToDelete.map(Uri::parse)
            val pending = runCatching { MediaStore.createDeleteRequest(context.contentResolver, uris) }.getOrNull()
            if (pending != null) {
                deleteLauncher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
            }
        } else {
            idsToDelete.forEach { id -> runCatching { context.contentResolver.delete(Uri.parse(id), null, null) } }
            exitSelection()
            refresh()
        }
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
        Row(Modifier.fillMaxWidth().padding(horizontal = UiTokens.pageHorizontal, vertical = UiTokens.pageVertical), verticalAlignment = Alignment.CenterVertically) {
            Text("Biblioteca", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = refresh) { Icon(Icons.Rounded.Refresh, "Atualizar") }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val wideLayout = maxWidth >= 700.dp
            if (wideLayout) {
                Row(Modifier.fillMaxSize().padding(horizontal = UiTokens.pageHorizontal), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                        onPlayAll = { if (shown.isNotEmpty()) play(shown, 0) }, onPlayRandom = { val start = shown.indices.randomOrNull(); if (start != null) play(shown, start) },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
        }
    }

    activePlaylistTrack?.let { track -> AddToPlaylistDialog(track, onDismiss = { activePlaylistTrack = null }) }
    if (showDeleteConfirm) {
        AlertDialog(onDismissRequest = { showDeleteConfirm = false }, title = { Text("Remover músicas") }, text = { Text("Remover ${selectedIds.size} música(s) do dispositivo? Essa ação não pode ser desfeita.") }, confirmButton = { TextButton(onClick = { performDelete() }) { Text("Remover") } }, dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancelar") } })
    }
    if (showPlaylistPicker) {
        AddSelectedToPlaylistDialog(trackIds = shown.filter { it.id in selectedIds }.map { it.id }, onDismiss = { showPlaylistPicker = false }, onDone = { showPlaylistPicker = false; exitSelection() })
    }
}

@Composable
private fun LibraryListContent(
    shown: List<AudioTrack>, listState: LazyListState, selectionMode: Boolean, selectedIds: List<String>, sortOpen: Boolean, onSortOpenChange: (Boolean) -> Unit,
    librarySearch: String, onLibrarySearchChange: (String) -> Unit, sortMode: PlayerSortMode, ascending: Boolean, onSortModeChange: (PlayerSortMode) -> Unit, onAscendingChange: () -> Unit,
    play: (List<AudioTrack>, Int) -> Unit, edit: (AudioTrack) -> Unit, share: (AudioTrack) -> Unit, onAddToPlaylist: (AudioTrack) -> Unit,
    onAlbum: (String) -> Unit, onArtist: (String) -> Unit, onGenre: (String) -> Unit, currentTrackId: String?, onSelectionModeExit: () -> Unit, onSelectAll: () -> Unit,
    onToggleSelect: (String) -> Unit, onDeleteClick: () -> Unit, onPlaylistClick: () -> Unit, onPlayAll: () -> Unit, onPlayRandom: () -> Unit, onSelectionModeToggle: () -> Unit, modifier: Modifier = Modifier,
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
        }
        SelectionToggle(active = selectionMode, onToggle = onSelectionModeToggle)
        if (selectionMode) {
            SelectionToolbar(
                selectedCount = selectedIds.size,
                totalCount = shown.size,
                onCancel = onSelectionModeExit,
                onToggleAll = onSelectAll,
            )
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDeleteClick, enabled = selectedIds.isNotEmpty(), modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Icon(Icons.Rounded.Delete, null); Spacer(Modifier.width(6.dp)); Text("Remover") }
                OutlinedButton(onClick = onPlaylistClick, enabled = selectedIds.isNotEmpty(), modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.PlaylistAdd, null); Spacer(Modifier.width(6.dp)); Text("Playlist") }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        IconButton(onClick = { onOpenChange(true) }) { Icon(Icons.Rounded.Tune, "Filtros") }
        DropdownMenu(expanded = open, onDismissRequest = { onOpenChange(false) }) {
            Text(title, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontWeight = FontWeight.Bold)
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
private fun MostPlayedScreen(
    tracks: List<AudioTrack>,
    play: (List<AudioTrack>, Int) -> Unit,
    onAlbum: (String) -> Unit,
    onArtist: (String) -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf(MostPlayedMode.TRACKS) }
    val context = LocalContext.current
    val store = remember { PlayStatsStore(context) }
    StandardPage {
        MostPlayedPanel(
            tracks = tracks, mode = mode, onModeChange = { mode = it }, play = play,
            onAlbum = onAlbum, onArtist = onArtist, store = store, modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun MostPlayedPanel(
    tracks: List<AudioTrack>, mode: MostPlayedMode, onModeChange: (MostPlayedMode) -> Unit, play: (List<AudioTrack>, Int) -> Unit,
    onAlbum: (String) -> Unit, onArtist: (String) -> Unit, store: PlayStatsStore, modifier: Modifier = Modifier,
) {
    val state = rememberLazyListState()
    Card(modifier = modifier, shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxSize().padding(horizontal = UiTokens.screenHorizontalPadding, vertical = UiTokens.screenVerticalPadding)) {
            Text("Mais tocadas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                MostPlayedMode.entries.forEachIndexed { index, item ->
                    SegmentedButton(selected = mode == item, onClick = { onModeChange(item) }, shape = SegmentedButtonDefaults.itemShape(index, MostPlayedMode.entries.size)) { Text(item.label, maxLines = 1, softWrap = false) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (mode) {
                    MostPlayedMode.TRACKS -> {
                        val values = store.topTracks(tracks)
                        if (values.isEmpty()) EmptyMostPlayed() else SongListContainer(state = state, modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(values, key = { _, pair -> pair.first.id }) { index, (track, count) ->
                                MostPlayedRow(index + 1, track.displayTitle(), track.artist, track.album, count, track.artworkBytes) { play(listOf(track), 0) }
                            }
                        }
                    }
                    MostPlayedMode.ALBUMS -> {
                        val values = store.topAlbums(tracks)
                        if (values.isEmpty()) EmptyMostPlayed() else SongListContainer(state = state, modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(values, key = { _, item -> item.first + "\u0000" + item.second }) { index, (album, artist, count) ->
                                MostPlayedRow(index + 1, album, artist, "", count, tracks.firstOrNull { it.album.equals(album, true) }?.artworkBytes) { onAlbum(album) }
                            }
                        }
                    }
                    MostPlayedMode.ARTISTS -> {
                        val values = store.topArtists(tracks)
                        if (values.isEmpty()) EmptyMostPlayed() else SongListContainer(state = state, modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(values, key = { _, item -> item.first }) { index, (artist, count) ->
                                MostPlayedRow(index + 1, artist, artist, "", count, tracks.firstOrNull { it.artist.equals(artist, true) }?.artworkBytes) { onArtist(artist) }
                            }
                        }
                    }
                }
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
private fun MostPlayedRow(rank: Int, title: String, artist: String, album: String, count: Long, artwork: ByteArray?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(UiTokens.songItemRadius)).border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(UiTokens.songItemRadius)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.34f)).clickable(onClick = onClick).padding(horizontal = UiTokens.songItemPadding + 2.dp, vertical = UiTokens.songItemPadding), verticalAlignment = Alignment.CenterVertically) {
        Text(rank.toString(), modifier = Modifier.width(22.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        PlayerArtworkBox(artwork, Modifier.size(UiTokens.songItemArtworkSize), maxDimension = 192)
        Column(Modifier.weight(1f).padding(horizontal = UiTokens.songItemSpacing + 6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artist.ifBlank { "Artista desconhecido" }, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(album.ifBlank { "Sem álbum" }, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(UiTokens.songItemRadius))
            .border(
                BorderStroke(1.dp, if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant),
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
                IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.KeyboardArrowUp, "Subir música") }
                IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.KeyboardArrowDown, "Abaixar música") }
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
                DropdownMenuItem({ Text("Excluir") }, onClick = { expanded = false; Unit })
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
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
private fun AddToPlaylistDialog(track: AudioTrack, onDismiss: () -> Unit) {
    var playlists by remember { mutableStateOf(emptyList<PlaylistData>()) }
    val context = LocalContext.current
    val store = remember { PlaylistStore(context) }
    LaunchedEffect(Unit) { playlists = store.load() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Adicionar à playlist") }, text = {
        if (playlists.isEmpty()) Text("Crie uma playlist primeiro na aba Playlists.") else Column { playlists.forEach { p -> TextButton(onClick = { store.add(p.name, track.id); playlists = store.load(); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text(p.name, Modifier.fillMaxWidth()) } } }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable
private fun AddSelectedToPlaylistDialog(trackIds: List<String>, onDismiss: () -> Unit, onDone: () -> Unit) {
    var playlists by remember { mutableStateOf(emptyList<PlaylistData>()) }
    val context = LocalContext.current
    val store = remember { PlaylistStore(context) }
    LaunchedEffect(Unit) { playlists = store.load() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Adicionar ${trackIds.size} música(s) à playlist") }, text = {
        if (playlists.isEmpty()) Text("Crie uma playlist primeiro na aba Playlists.") else Column {
            playlists.forEach { p ->
                TextButton(
                    onClick = {
                        // Adiciona no início mantendo a ordem vertical mostrada na seleção.
                        store.addAll(p.name, trackIds)
                        onDone()
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
                        Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Criar playlist")
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
                ListItem(
                    headlineContent = { Text(playlist.name) },
                    supportingContent = { Text("${playlist.trackIds.size} músicas") },
                    modifier = Modifier.clickable { onOpen(playlist.name) },
                    trailingContent = {
                        Box {
                            IconButton(onClick = { expanded = true }) { Icon(Icons.Rounded.MoreVert, "Opções") }
                            DropdownMenu(expanded, { expanded = false }) {
                                DropdownMenuItem({ Text("Exportar M3U") }, onClick = { expanded = false; onExport(playlist) })
                                DropdownMenuItem({ Text("Renomear") }, onClick = { expanded = false; renameText = playlist.name; renaming = playlist })
                                DropdownMenuItem({ Text("Excluir") }, onClick = { expanded = false; onDelete(playlist.name) })
                            }
                        }
                    },
                )
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
    val artists = tracks
        .map { it.artist.trim() }
        .filter { it.isNotBlank() && it != "<unknown>" }
        .distinct()
        .sortedBy { it.lowercase(Locale.ROOT) }
        .filter { search.isBlank() || it.contains(search, ignoreCase = true) }
    StandardPage {
        SectionTitle("Artistas")
        Spacer(Modifier.height(UiTokens.listHeaderGap))
        SearchBar(value = search, onValueChange = { search = it }, placeholder = "Pesquisar artistas")
        Spacer(Modifier.height(UiTokens.standardListTopSpacing))
        AlphabeticalGroupList("", artists, open, Modifier.weight(1f))
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
                Text(letter, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = UiTokens.standardListTopSpacing, bottom = 2.dp))
            }
            items(names, key = { "$letter-$it" }) { value ->
                ListItem(headlineContent = { Text(value) }, modifier = Modifier.clickable { open(value) })
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
                LazyVerticalGrid(state = gridState, columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize().songListSpace(), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(albums, key = { "${it.key}\u0000${it.artist}" }) { item ->
                        Card(Modifier.clickable { open(item.key) }) {
                            PlayerArtworkBox(item.artwork, Modifier.fillMaxWidth().aspectRatio(1f), albumTrackIds[item.name], maxDimension = 256)
                            Column(Modifier.padding(8.dp)) {
                                Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${item.songs} músicas • ${formatDuration(item.duration)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (item.year.isNotBlank()) Text(item.year, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Row(Modifier.fillMaxWidth().padding(horizontal = UiTokens.cardItemHorizontalPadding), verticalAlignment = Alignment.CenterVertically) {
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
                }
                SelectionToggle(
                    active = playlistSelectionMode,
                    onToggle = {
                        playlistSelectionMode = !playlistSelectionMode
                        if (!playlistSelectionMode) selectedPlaylistIds.clear()
                    },
                )
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
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onPlay(shown, 0) }, enabled = shown.isNotEmpty(), modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(5.dp)); Text("Tocar") }
                        OutlinedButton(onClick = { onPlayRandom(shown) }, enabled = shown.isNotEmpty(), modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(5.dp)); Text("Aleatório") }
                    }
                }
                Spacer(Modifier.height(6.dp))
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
                            )
                        }
                    }
            } else if (type == Detail.ALBUM) {
                PlayerArtworkBox(artwork, Modifier.size(240.dp).align(Alignment.CenterHorizontally), shown.firstOrNull()?.id)
                Spacer(Modifier.height(12.dp))
                Text(key, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                val artist = shown.firstOrNull()?.albumArtist?.ifBlank { shown.firstOrNull()?.artist.orEmpty() }.orEmpty()
                TextButton(onClick = { onOpenArtist(artist) }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp), modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(artist, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Text("${shown.size} músicas • ${formatDuration(duration)}${shown.firstOrNull()?.year?.takeIf { it.isNotBlank() }?.let { " • $it" } ?: ""}", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { onPlay(shown, 0) }, enabled = shown.isNotEmpty(), modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(5.dp)); Text("Tocar") }
                    OutlinedButton(onClick = { onPlayRandom(shown) }, enabled = shown.isNotEmpty(), modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(5.dp)); Text("Aleatório") }
                }
                Spacer(Modifier.height(10.dp))
                SongListContainer(state = state, modifier = Modifier.fillMaxWidth().weight(1f)) {
                        items(shown, key = { it.id }) { t ->
                            SongItem(t, { onPlay(shown, shown.indexOf(t)) }, { onEdit(t) }, { onShare(t) }, {}, {}, {}, {}, onRemoveFromPlaylist = null, isCurrent = t.id == currentTrackId)
                        }
                    }
            } else {
                if (type == Detail.ARTIST) {
                    val albums = shown.groupBy { it.album.ifBlank { "Sem álbum" } }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                    Text("${albums.size} álbuns • ${shown.size} músicas • ${formatDuration(duration)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onPlay(shown, 0) }, enabled = shown.isNotEmpty()) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(5.dp)); Text("Tocar") }
                        OutlinedButton(onClick = { onPlayRandom(shown) }, enabled = shown.isNotEmpty()) { Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(5.dp)); Text("Aleatório") }
                    }
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        LazyColumn(state = state, modifier = Modifier.fillMaxSize().songListSpace(), verticalArrangement = Arrangement.spacedBy(UiTokens.songListSpacing), contentPadding = PaddingValues(bottom = UiTokens.songListBottomPadding)) {
                            albums.forEach { (album, albumTracks) ->
                                item(key = "album-$album") {
                                    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp)) {
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
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onPlay(shown, 0) }, enabled = shown.isNotEmpty()) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(5.dp)); Text("Tocar") }
                        OutlinedButton(onClick = { onPlayRandom(shown) }, enabled = shown.isNotEmpty()) { Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(5.dp)); Text("Aleatório") }
                    }
                    Spacer(Modifier.height(6.dp))
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
    }
}

@Composable
private fun SettingsTab(settings: AppSettings, onChange: (AppSettings) -> Unit, downloadFolderLabel: String, onChooseDownloadFolder: (() -> Unit)?, appVersion: String) {
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
                item { SettingSwitch("Atualizar downloader automaticamente", settings.autoUpdateDownloader) { onChange(settings.copy(autoUpdateDownloader = it)) } }
                item { Column { Text("Pasta de download", fontWeight = FontWeight.SemiBold); Text(downloadFolderLabel.ifBlank { "Nenhuma selecionada" }, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(6.dp)); OutlinedButton(onClick = { onChooseDownloadFolder?.invoke() }) { Icon(Icons.Rounded.Folder, null); Spacer(Modifier.width(6.dp)); Text("Escolher pasta") } } }
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
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
private fun PlayerArtworkBox(bytes: ByteArray?, modifier: Modifier, uri: String? = null, maxDimension: Int = 512) {
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
            } ?: if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { context.contentResolver.loadThumbnail(Uri.parse(uri), Size(maxDimension, maxDimension), null) }.getOrNull()
            } else null
            loaded?.let { artworkCache.put(cacheKey, it) }
            loaded
        }
    }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val current = bitmap
        if (current != null) Image(current.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Rounded.MusicNote, null, Modifier.size(32.dp))
    }
}

