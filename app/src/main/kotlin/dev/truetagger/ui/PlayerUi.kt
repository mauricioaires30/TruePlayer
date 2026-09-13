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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
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
import java.text.Normalizer


private val artworkCache = object : LruCache<String, Bitmap>(64 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
}

private enum class HomeTab(val label: String) {
    LIBRARY("Biblioteca"), PLAYLISTS("Playlists"), ARTISTS("Artistas"), ALBUMS("Álbuns"), GENRES("Gêneros"), SETTINGS("Config.")
}
private enum class PlayerScreen { NONE, FULL, QUEUE }
private enum class Detail { NONE, ARTIST, ALBUM, GENRE, PLAYLIST }
private enum class PlayerSortMode(val label: String) {
    NAME("Nome"), TITLE("Título"), ARTIST("Artista"), ALBUM("Álbum"), DATE("Data"), DURATION("Duração")
}
private enum class AlbumSortMode(val label: String) {
    NAME("Nome"), ARTIST("Artista"), YEAR("Ano"), DURATION("Duração"), SONGS("Quantidade de músicas")
}
private data class NavEntry(
    val tab: HomeTab,
    val playerScreen: PlayerScreen,
    val detail: Detail,
    val detailKey: String,
    val editorOpen: Boolean,
)

private data class PlaylistData(val name: String, val trackIds: List<String>)

private class PlaylistStore(context: Context) {
    private val prefs = context.getSharedPreferences("truetagger_player", Context.MODE_PRIVATE)
    private val key = "playlists_json"

    fun load(): List<PlaylistData> = runCatching {
        val raw = prefs.getString(key, "[]") ?: "[]"
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                add(PlaylistData(obj.optString("name"), buildList {
                    val ids = obj.optJSONArray("trackIds") ?: JSONArray()
                    for (j in 0 until ids.length()) add(ids.optString(j))
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

    fun create(name: String) {
        val clean = name.trim()
        if (clean.isBlank()) return
        if (load().any { it.name.equals(clean, true) }) return
        save(load() + PlaylistData(clean, emptyList()))
    }

    fun delete(name: String) = save(load().filterNot { it.name == name })

    fun add(name: String, trackId: String) {
        save(load().map { if (it.name == name && trackId !in it.trackIds) it.copy(trackIds = it.trackIds + trackId) else it })
    }

    fun remove(name: String, trackId: String) {
        save(load().map { if (it.name == name) it.copy(trackIds = it.trackIds - trackId) else it })
    }


    fun reorder(name: String, orderedTrackIds: List<String>) {
        save(load().map { if (it.name == name) it.copy(trackIds = orderedTrackIds) else it })
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
    var tab by rememberSaveable { mutableStateOf(HomeTab.LIBRARY) }
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
    LaunchedEffect(Unit) {
        librarySearch = filterPrefs.getString("library_search", "").orEmpty()
        librarySortMode = runCatching { PlayerSortMode.valueOf(filterPrefs.getString("library_sort", PlayerSortMode.NAME.name)!!) }.getOrDefault(PlayerSortMode.NAME)
        libraryAscending = filterPrefs.getBoolean("library_ascending", true)
        albumSearch = filterPrefs.getString("album_search", "").orEmpty()
        albumSortMode = runCatching { AlbumSortMode.valueOf(filterPrefs.getString("album_sort", AlbumSortMode.NAME.name)!!) }.getOrDefault(AlbumSortMode.NAME)
        albumAscending = filterPrefs.getBoolean("album_ascending", true)
    }
    val store = remember { PlaylistStore(context) }
    var playlists by remember { mutableStateOf(store.load()) }
    val drafts = remember { mutableStateMapOf<String, EditableTags>() }
    val libraryListState = rememberLazyListState()

    fun reloadPlaylists() { playlists = store.load() }
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
        while (true) { snapshot = PlayerState.snapshot; delay(500) }
    }

    BackHandler(enabled = history.isNotEmpty() || editorOpen || playerScreen != PlayerScreen.NONE || detail != Detail.NONE) { goBack() }

    MaterialTheme(colorScheme = TrueTaggerDarkScheme) {
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
                    NavigationBar {
                        HomeTab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t && !editorOpen && detail == Detail.NONE && playerScreen == PlayerScreen.NONE,
                                onClick = { history.clear(); editorOpen = false; detail = Detail.NONE; playerScreen = PlayerScreen.NONE; tab = t; reloadPlaylists() },
                            icon = { Icon(when (t) {
                                HomeTab.LIBRARY -> Icons.Rounded.LibraryMusic
                                HomeTab.PLAYLISTS -> Icons.Rounded.QueueMusic
                                HomeTab.ARTISTS -> Icons.Rounded.Person
                                HomeTab.ALBUMS -> Icons.Rounded.Album
                                HomeTab.GENRES -> Icons.Rounded.Category
                                HomeTab.SETTINGS -> Icons.Rounded.Settings
                            }, t.label) },
                                label = { Text(t.label, maxLines = 1) }
                            )
                        }
                    }
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
                        onReorderPlaylist = { orderedIds -> store.reorder(detailKey, orderedIds); reloadPlaylists(); MusicPlaybackService.reorderQueue(context, orderedIds) },
                        onRemoveFromPlaylist = { id -> store.remove(detailKey, id); reloadPlaylists() },
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
                        HomeTab.PLAYLISTS -> PlaylistScreen(playlists, onCreate = { name -> store.create(name); reloadPlaylists() }, onOpen = { navigateDetail(Detail.PLAYLIST, it) }, onDelete = { store.delete(it); reloadPlaylists() })
                        HomeTab.ARTISTS -> ArtistsScreen(tracks) { navigateDetail(Detail.ARTIST, it) }
                        HomeTab.ALBUMS -> AlbumsScreen(tracks, albumSearch, { albumSearch = it }, albumSortMode, { albumSortMode = it }, albumAscending, { albumAscending = !albumAscending }) { navigateDetail(Detail.ALBUM, it) }
                        HomeTab.GENRES -> GenresScreen(tracks) { navigateDetail(Detail.GENRE, it) }
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
            LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(s.queue, key = { _, t -> t.id }) { i, t ->
                    TrackMenuRow(
                        track = t,
                        onPlay = { onSelect(i) },
                        onEdit = {}, onShare = {}, onAddToPlaylist = {}, onAlbum = {}, onArtist = {}, onGenre = {},
                        onRemoveFromPlaylist = null,
                        isCurrent = s.current?.id == t.id,
                    )
                }
            }
            TouchScrollbar(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 8.dp))
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selectionMode) {
                IconButton(onClick = { exitSelection() }) { Icon(Icons.Rounded.Close, "Cancelar seleção") }
                Text("${selectedIds.size} selecionada(s)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    if (selectedIds.size == shown.size) selectedIds.clear()
                    else { selectedIds.clear(); selectedIds.addAll(shown.map { it.id }) }
                }) { Text(if (shown.isNotEmpty() && selectedIds.size == shown.size) "Nenhuma" else "Todas") }
            } else {
                Text("Biblioteca", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = refresh) { Icon(Icons.Rounded.Refresh, "Atualizar") }
                IconButton(onClick = { selectionMode = true }) { Icon(Icons.Rounded.Checklist, "Selecionar músicas") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(librarySearch, changeSearch, Modifier.weight(1f).height(56.dp), singleLine = true, placeholder = { Text("Pesquisar músicas") })
            Box {
                IconButton(onClick = { sortOpen = true }) { Icon(Icons.Rounded.Tune, "Filtros") }
                DropdownMenu(sortOpen, { sortOpen = false }) {
                    Text("Ordenar por", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontWeight = FontWeight.Bold)
                    PlayerSortMode.entries.forEach { mode -> DropdownMenuItem({ Text(mode.label) }, onClick = { changeSort(mode); sortOpen = false }) }
                    DropdownMenuItem({ Text(if (ascending) "Crescente" else "Decrescente") }, onClick = { changeAscending(); sortOpen = false })
                }
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (selectionMode) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    enabled = selectedIds.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Icon(Icons.Rounded.Delete, null); Spacer(Modifier.width(6.dp)); Text("Remover") }
                OutlinedButton(onClick = { showPlaylistPicker = true }, enabled = selectedIds.isNotEmpty(), modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.PlaylistAdd, null); Spacer(Modifier.width(6.dp)); Text("Playlist")
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { play(shown, 0) }, enabled = shown.isNotEmpty(), modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Tocar") }
                OutlinedButton(onClick = { val start = shown.indices.randomOrNull(); if (start != null) play(shown, start) }, enabled = shown.isNotEmpty(), modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("Aleatório") }
            }
        }
        Box(Modifier.fillMaxSize().weight(1f)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(shown, key = { it.id }) { track ->
                    TrackMenuRow(
                        track,
                        onPlay = { play(shown, shown.indexOf(track)) },
                        onEdit = { edit(track) },
                        onShare = { share(track) },
                        onAddToPlaylist = { activePlaylistTrack = track },
                        onAlbum = { if (track.album.isNotBlank()) onAlbum(track.album) },
                        onArtist = { if (track.artist.isNotBlank()) onArtist(track.artist) },
                        onGenre = { if (track.genre.isNotBlank()) onGenre(track.genre) },
                        isCurrent = currentTrackId == track.id,
                        selectionMode = selectionMode,
                        selected = track.id in selectedIds,
                        onToggleSelect = {
                            if (track.id in selectedIds) selectedIds.remove(track.id) else selectedIds.add(track.id)
                        },
                    )
                }
            }
            TouchScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 8.dp))
            ListJumpButtons(listState, Modifier.align(Alignment.BottomEnd).padding(end = 38.dp, bottom = 12.dp))
        }
    }
    activePlaylistTrack?.let { track -> AddToPlaylistDialog(track, onDismiss = { activePlaylistTrack = null }) }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Remover músicas") },
            text = { Text("Remover ${selectedIds.size} música(s) do dispositivo? Essa ação não pode ser desfeita.") },
            confirmButton = { TextButton(onClick = { performDelete() }) { Text("Remover") } },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancelar") } },
        )
    }
    if (showPlaylistPicker) {
        AddSelectedToPlaylistDialog(
            trackIds = shown.filter { it.id in selectedIds }.map { it.id },
            onDismiss = { showPlaylistPicker = false },
            onDone = { showPlaylistPicker = false; exitSelection() },
        )
    }
}

@Composable
private fun TrackMenuRow(
    track: AudioTrack,
    onPlay: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onAlbum: () -> Unit,
    onArtist: () -> Unit,
    onGenre: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    isCurrent: Boolean = false,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: () -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = if (selectionMode) onToggleSelect else onPlay)
            .padding(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
        }
        PlayerArtworkBox(track.artworkBytes, Modifier.size(54.dp), track.id, maxDimension = 384)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text(track.displayTitle(), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Row(verticalAlignment = Alignment.CenterVertically) { Text(listOf(track.album, track.artist).filter { it.isNotBlank() }.joinToString(" - "), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)); Text(formatDuration(track.durationMs), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp)) } }
        if (!selectionMode) {
            Box {
                IconButton(onClick = { expanded = true }) { Icon(Icons.Rounded.MoreVert, "Opções") }
                DropdownMenu(expanded, { expanded = false }) {
                    DropdownMenuItem({ Text("Ver infos") }, onClick = { expanded = false; info = true })
                    DropdownMenuItem({ Text("Editar tags") }, onClick = { expanded = false; onEdit() })
                    DropdownMenuItem({ Text("Adicionar à playlist") }, onClick = { expanded = false; onAddToPlaylist() })
                    DropdownMenuItem({ Text("Compartilhar") }, onClick = { expanded = false; onShare() })
                    onRemoveFromPlaylist?.let { remove -> DropdownMenuItem({ Text("Remover da playlist") }, onClick = { expanded = false; remove() }) }
                    DropdownMenuItem({ Text("Excluir") }, onClick = { expanded = false; Unit })
                }
            }
        }
    }
    if (info) InfoDialog(track) { info = false }
}

@Composable
private fun InfoDialog(track: AudioTrack, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(track.displayTitle()) }, text = { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text("Artista: ${track.artist.ifBlank { "—" }}"); Text("Álbum: ${track.album.ifBlank { "—" }}"); Text("Ano: ${track.year.ifBlank { "—" }}"); Text("Gênero: ${track.genre.ifBlank { "—" }}"); Text("Faixa: ${track.trackNumber.ifBlank { "—" }}"); Text("Duração: ${formatDuration(track.durationMs)}") } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } })
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
                        // Adiciona na mesma ordem em que aparecem na tela
                        trackIds.forEach { id -> store.add(p.name, id) }
                        onDone()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(p.name, Modifier.fillMaxWidth()) }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable
private fun PlaylistScreen(playlists: List<PlaylistData>, onCreate: (String) -> Unit, onOpen: (String) -> Unit, onDelete: (String) -> Unit) {
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Text("Playlists", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { creating = true; name = "" }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Criar playlist") }
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        if (playlists.isEmpty()) Text("Nenhuma playlist criada.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else playlists.forEach { p ->
            var expanded by remember(p.name) { mutableStateOf(false) }
            ListItem(headlineContent = { Text(p.name) }, supportingContent = { Text("${p.trackIds.size} músicas") }, modifier = Modifier.clickable { onOpen(p.name) }, trailingContent = {
                Box { IconButton(onClick = { expanded = true }) { Icon(Icons.Rounded.MoreVert, "Opções") }; DropdownMenu(expanded, { expanded = false }) { DropdownMenuItem({ Text("Entrar na playlist") }, onClick = { expanded = false; onOpen(p.name) }); DropdownMenuItem({ Text("Excluir") }, onClick = { expanded = false; onDelete(p.name) }) } }
            })
        }
    }
    if (creating) AlertDialog(onDismissRequest = { creating = false }, title = { Text("Nova playlist") }, text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Nome") }) }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name); creating = false }) { Text("Criar") } }, dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancelar") } })
}

@Composable
private fun ArtistsScreen(tracks: List<AudioTrack>, open: (String) -> Unit) {
    val artists = tracks.map { it.artist.trim() }.filter { it.isNotBlank() && it != "<unknown>" }.distinct().sortedBy { it.lowercase() }
    AlphabeticalGroupList("Artistas", artists, open)
}

@Composable
private fun GenresScreen(tracks: List<AudioTrack>, open: (String) -> Unit) {
    val genres = tracks.map { it.genre.trim() }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }
    val values = if (genres.isEmpty()) listOf("Sem gênero") else genres
    AlphabeticalGroupList("Gêneros", values, open)
}

@Composable
private fun AlphabeticalGroupList(title: String, values: List<String>, open: (String) -> Unit) {
    val state = rememberLazyListState()
    val sections = values.groupBy { value -> value.firstOrNull()?.uppercaseChar()?.toString() ?: "#" }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item { Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp)) }
            sections.toSortedMap().forEach { (letter, names) ->
                item(key = "section-$letter") {
                    Text(letter, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                }
                items(names, key = { "$letter-$it" }) { value ->
                    ListItem(headlineContent = { Text(value) }, modifier = Modifier.clickable { open(value) })
                }
            }
        }
        TouchScrollbar(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 10.dp))
        ListJumpButtons(state, Modifier.align(Alignment.BottomEnd).padding(end = 38.dp, bottom = 14.dp))
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
    var pendingGridKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(search, sortMode, ascending, albums.size) {
        val key = pendingGridKey
        if (key != null && albums.isNotEmpty()) {
            val target = albums.indexOfFirst { it.key == key }
            if (target >= 0) gridState.scrollToItem(target)
            pendingGridKey = null
        }
    }
    val changeAlbumSearch: (String) -> Unit = { value -> pendingGridKey = albums.getOrNull(gridState.firstVisibleItemIndex)?.key; onSearchChange(value) }
    val changeAlbumSort: (AlbumSortMode) -> Unit = { mode -> pendingGridKey = albums.getOrNull(gridState.firstVisibleItemIndex)?.key; onSortModeChange(mode) }
    val changeAlbumAscending: () -> Unit = { pendingGridKey = albums.getOrNull(gridState.firstVisibleItemIndex)?.key; onAscendingChange() }
    val albumTrackIds = remember(tracks) { tracks.groupBy { it.album.ifBlank { "Sem álbum" } }.mapValues { (_, list) -> list.firstOrNull()?.id } }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            Text("Álbuns", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(search, changeAlbumSearch, Modifier.weight(1f).height(56.dp), singleLine = true, placeholder = { Text("Pesquisar álbuns") })
                Box {
                    IconButton(onClick = { filterOpen = true }) { Icon(Icons.Rounded.Tune, "Filtros") }
                    DropdownMenu(filterOpen, { filterOpen = false }) {
                        Text("Ordenar por", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontWeight = FontWeight.Bold)
                        AlbumSortMode.entries.forEach { mode -> DropdownMenuItem({ Text(mode.label) }, onClick = { changeAlbumSort(mode); filterOpen = false }) }
                        DropdownMenuItem({ Text(if (ascending) "Crescente" else "Decrescente") }, onClick = { changeAlbumAscending(); filterOpen = false })
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            LazyVerticalGrid(state = gridState, columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        }
        AlbumGridJumpButtons(gridState, Modifier.align(Alignment.BottomEnd).padding(end = 38.dp, bottom = 14.dp))
    }
}

@Composable
private fun DetailScreen(type: Detail, key: String, tracks: List<AudioTrack>, playlist: PlaylistData?, onPlay: (List<AudioTrack>, Int) -> Unit, onPlayRandom: (List<AudioTrack>) -> Unit, onEdit: (AudioTrack) -> Unit, onShare: (AudioTrack) -> Unit, onRemoveFromPlaylist: (String) -> Unit, onReorderPlaylist: (List<String>) -> Unit, currentTrackId: String?, onBack: () -> Unit, onOpenArtist: (String) -> Unit, onOpenAlbum: (String) -> Unit) {
    val base = when (type) {
        Detail.ARTIST -> tracks.filter { it.artist.equals(key, true) }
        Detail.ALBUM -> tracks.filter { it.album.equals(key, true) }
        Detail.GENRE -> if (key.equals("Sem gênero", true)) tracks.filter { it.genre.isBlank() } else tracks.filter { it.genre.equals(key, true) }
        Detail.PLAYLIST -> playlist?.trackIds?.mapNotNull { id -> tracks.firstOrNull { it.id == id } } ?: emptyList()
        else -> emptyList()
    }
    var playlistSearch by rememberSaveable(key) { mutableStateOf("") }
    var playlistOrder by remember(key, playlist?.trackIds) { mutableStateOf(playlist?.trackIds.orEmpty()) }
    LaunchedEffect(playlist?.trackIds) { playlistOrder = playlist?.trackIds.orEmpty() }
    val orderedBase = if (type == Detail.PLAYLIST) playlistOrder.mapNotNull { id -> base.firstOrNull { it.id == id } } else base
    val shown = if (type == Detail.PLAYLIST) {
        orderedBase.filter { playlistSearch.isBlank() || listOf(it.displayTitle(), it.artist, it.album).any { value -> value.contains(playlistSearch, true) } }
    } else {
        orderedBase.sortedWith(compareBy({ it.discNumber.toIntOrNull() ?: 0 }, { it.trackNumber.toIntOrNull() ?: Int.MAX_VALUE }, { it.displayTitle().lowercase() }))
    }
    val duration = shown.sumOf { it.durationMs }
    val state = rememberLazyListState()
    val artwork = shown.firstOrNull { it.artworkBytes?.isNotEmpty() == true }?.artworkBytes
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Voltar") }
                Text(key, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (type == Detail.ALBUM) {
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
                LazyColumn(state = state, modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(shown, key = { it.id }) { t ->
                        TrackMenuRow(t, { onPlay(shown, shown.indexOf(t)) }, { onEdit(t) }, { onShare(t) }, {}, {}, {}, {}, null, t.id == currentTrackId)
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
                    LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        albums.forEach { (album, albumTracks) ->
                            item(key = "album-$album") {
                                Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp)) {
                                    TextButton(onClick = { onOpenAlbum(album) }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) { Text(album, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    Text("${albumTracks.size} músicas • ${formatDuration(albumTracks.sumOf { it.durationMs })}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            items(albumTracks, key = { it.id }) { t ->
                                TrackMenuRow(t, { onPlay(shown, shown.indexOf(t)) }, { onEdit(t) }, { onShare(t) }, {}, {}, {}, {}, null, t.id == currentTrackId)
                            }
                        }
                    }
                } else {
                    if (type == Detail.PLAYLIST) {
                        OutlinedTextField(
                            value = playlistSearch,
                            onValueChange = { playlistSearch = it },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            singleLine = true,
                            placeholder = { Text("Pesquisar na playlist") },
                            leadingIcon = { Icon(Icons.Rounded.Search, "Pesquisar") },
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    if (shown.isNotEmpty()) Text("${shown.size} músicas • ${formatDuration(duration)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onPlay(shown, 0) }, enabled = shown.isNotEmpty(), modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(5.dp)); Text("Tocar") }
                        OutlinedButton(onClick = { onPlayRandom(shown) }, enabled = shown.isNotEmpty(), modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(5.dp)); Text("Aleatório") }
                    }
                    if (type == Detail.PLAYLIST && playlistSearch.isBlank()) {
                        Text("Arraste as músicas para alterar a ordem.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                    if (type == Detail.PLAYLIST) {
                        var draggingId by remember { mutableStateOf<String?>(null) }
                        var dragOffset by remember { mutableFloatStateOf(0f) }
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        val rowStepPx = with(density) { 68.dp.toPx() }
                        LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            itemsIndexed(shown, key = { _, it -> it.id }) { visibleIndex, t ->
                                val isDragging = draggingId == t.id
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .then(if (isDragging) Modifier.offset { IntOffset(0, dragOffset.roundToInt()) }.zIndex(1f) else Modifier)
                                        .pointerInput(t.id, playlistSearch.isBlank(), shown) {
                                            if (playlistSearch.isBlank()) {
                                                detectDragGesturesAfterLongPress(
                                                    onDragStart = { draggingId = t.id; dragOffset = 0f },
                                                    onDragCancel = { draggingId = null; dragOffset = 0f },
                                                    onDragEnd = { draggingId = null; dragOffset = 0f },
                                                    onDrag = { change, amount ->
                                                        change.consume()
                                                        dragOffset += amount.y
                                                        val direction = when {
                                                            dragOffset > rowStepPx -> 1
                                                            dragOffset < -rowStepPx -> -1
                                                            else -> 0
                                                        }
                                                        if (direction != 0) {
                                                            val current = playlistOrder.toMutableList()
                                                            val from = current.indexOf(t.id)
                                                            val targetId = shown.getOrNull(visibleIndex + direction)?.id
                                                            val target = targetId?.let { current.indexOf(it) } ?: -1
                                                            if (from >= 0 && target >= 0 && from != target) {
                                                                current.removeAt(from)
                                                                current.add(target.coerceIn(0, current.size), t.id)
                                                                playlistOrder = current
                                                                onReorderPlaylist(current)
                                                                dragOffset -= direction * rowStepPx
                                                            }
                                                        }
                                                    },
                                                )
                                            }
                                        },
                                ) {
                                    TrackMenuRow(t, { onPlay(shown, shown.indexOf(t)) }, { onEdit(t) }, { onShare(t) }, {}, {}, {}, {}, { onRemoveFromPlaylist(t.id) }, t.id == currentTrackId)
                                }
                            }
                        }
                    } else {
                        LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(shown, key = { it.id }) { t -> TrackMenuRow(t, { onPlay(shown, shown.indexOf(t)) }, { onEdit(t) }, { onShare(t) }, {}, {}, {}, {}, null, t.id == currentTrackId) }
                        }
                    }
                }
            }
        }
        TouchScrollbar(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 8.dp))
        ListJumpButtons(state, Modifier.align(Alignment.BottomEnd).padding(end = 38.dp, bottom = 14.dp))
    }
}

@Composable
private fun SettingsTab(settings: AppSettings, onChange: (AppSettings) -> Unit, downloadFolderLabel: String, onChooseDownloadFolder: (() -> Unit)?, appVersion: String) {
    val state = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
            item { Text("Configurações", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
            item { SettingSwitch("Atualizar biblioteca automaticamente", settings.autoRefreshLibrary) { onChange(settings.copy(autoRefreshLibrary = it)) } }
            item { SettingSwitch("Incorporar thumbnail do YouTube", settings.embedYoutubeThumbnail) { onChange(settings.copy(embedYoutubeThumbnail = it)) } }
            item { SettingSwitch("Atualizar downloader automaticamente", settings.autoUpdateDownloader) { onChange(settings.copy(autoUpdateDownloader = it)) } }
            item { Column { Text("Pasta de download", fontWeight = FontWeight.SemiBold); Text(downloadFolderLabel.ifBlank { "Nenhuma selecionada" }, color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(6.dp)); OutlinedButton(onClick = { onChooseDownloadFolder?.invoke() }) { Icon(Icons.Rounded.Folder, null); Spacer(Modifier.width(6.dp)); Text("Escolher pasta") } } }
            item { Text("Versão $appVersion", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        TouchScrollbar(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 8.dp))
    }
}

@Composable
private fun SettingSwitch(label: String, value: Boolean, onChange: (Boolean) -> Unit) { ListItem(headlineContent = { Text(label) }, trailingContent = { Switch(value, onCheckedChange = onChange) }) }


@Composable
private fun AlbumGridJumpButtons(state: androidx.compose.foundation.lazy.grid.LazyGridState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        FilledTonalIconButton(onClick = { scope.launch { state.animateScrollToItem(0) } }) { Icon(Icons.Rounded.KeyboardArrowUp, "Ir para o topo") }
        FilledTonalIconButton(onClick = { scope.launch { val last = (state.layoutInfo.totalItemsCount - 1).coerceAtLeast(0); state.animateScrollToItem(last) } }) { Icon(Icons.Rounded.KeyboardArrowDown, "Ir para o final") }
    }
}

@Composable
private fun ListJumpButtons(state: LazyListState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        FilledTonalIconButton(onClick = { scope.launch { state.animateScrollToItem(0) } }) { Icon(Icons.Rounded.KeyboardArrowUp, "Ir para o topo") }
        FilledTonalIconButton(onClick = { scope.launch { val last = (state.layoutInfo.totalItemsCount - 1).coerceAtLeast(0); state.animateScrollToItem(last) } }) { Icon(Icons.Rounded.KeyboardArrowDown, "Ir para o final") }
    }
}

@Composable
private fun TouchScrollbar(state: LazyListState, modifier: Modifier) {
    val layout by remember { derivedStateOf { state.layoutInfo } }
    val total = layout.totalItemsCount
    val visible = layout.visibleItemsInfo.size
    if (total <= visible || total <= 0) return
    val scope = rememberCoroutineScope()
    var dragY by remember { mutableFloatStateOf(0f) }
    var scrollJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    Canvas(modifier.width(32.dp).pointerInput(total, visible) {
        detectDragGestures(
            onDragStart = {
                val thumb = (size.height * visible.toFloat() / total).coerceAtLeast(64f)
                val maxY = (size.height - thumb).coerceAtLeast(1f)
                val fraction = if (total > visible) state.firstVisibleItemIndex.toFloat() / (total - visible).toFloat() else 0f
                dragY = fraction * maxY
            },
            onDrag = { change, dragAmount ->
                change.consume()
                val thumb = (size.height * visible.toFloat() / total).coerceAtLeast(64f)
                val maxY = (size.height - thumb).coerceAtLeast(1f)
                dragY = (dragY + dragAmount.y).coerceIn(0f, maxY)
                val maxIndex = (total - visible).coerceAtLeast(0)
                val target = (dragY / maxY * maxIndex).toInt().coerceIn(0, maxIndex)
                scrollJob?.cancel(); scrollJob = scope.launch { state.scrollToItem(target) }
            }
        )
    }) {
        val thumbH = (size.height * visible.toFloat() / total).coerceIn(64f, size.height)
        val maxY = (size.height - thumbH).coerceAtLeast(0f)
        val fraction = if (total > visible) state.firstVisibleItemIndex.toFloat() / (total - visible).toFloat() else 0f
        val y = (fraction * maxY).coerceIn(0f, maxY)
        drawRoundRect(Color.White.copy(alpha = 0.38f), Offset(7f, y), androidx.compose.ui.geometry.Size(18f, thumbH), CornerRadius(9f, 9f))
    }
}

private fun formatDuration(ms: Long): String { val total = ms.coerceAtLeast(0) / 1000; val hours = total / 3600; val minutes = (total % 3600) / 60; val seconds = total % 60; return if (hours > 0) String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds) else String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds) }
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

