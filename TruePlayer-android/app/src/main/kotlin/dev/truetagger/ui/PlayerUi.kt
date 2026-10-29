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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import dev.truetagger.data.StatValue
import dev.truetagger.data.StatsRange
import java.text.NumberFormat
import dev.truetagger.android.ConnectivityObserver
import dev.truetagger.android.UpdateManager
import dev.truetagger.data.subtitleLines
import dev.truetagger.android.PlaylistData
import dev.truetagger.model.TrackKeys
import dev.truetagger.android.PlaylistStore
import dev.truetagger.android.LibraryIndex
import dev.truetagger.android.MusicFolder
import dev.truetagger.android.OpenPlaylistFiles
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
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import dev.truetagger.android.MusicPlaybackService
import dev.truetagger.android.R
import dev.truetagger.android.PlayerSnapshot
import dev.truetagger.android.PlayerState
import dev.truetagger.android.RepeatMode
import dev.truetagger.data.ThemeMode
import dev.truetagger.model.AppSettings
import dev.truetagger.model.AudioTrack
import dev.truetagger.android.AppEvents
import dev.truetagger.android.PlayStatsState
import dev.truetagger.model.EditableTags
import dev.truetagger.model.TagWriteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import java.text.Normalizer



/**
 * Os caches antes usavam limites fixos (64 MB de capas), o que é grande demais
 * para aparelhos modestos. Agora derivam da memória realmente disponível para
 * o processo, mantendo o mesmo comportamento em aparelhos folgados.
 */
private val cacheBudgetKb: Int = ((Runtime.getRuntime().maxMemory() / 1024L) / 8L)
    .coerceIn(8L * 1024L, 32L * 1024L)
    .toInt()

private val artworkCache = object : LruCache<String, Bitmap>(cacheBudgetKb) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
}

private val artistPhotoCache = object : LruCache<String, Bitmap>((cacheBudgetKb / 3).coerceAtLeast(4 * 1024)) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
}

private val artistPhotoUrlCache = mutableMapOf<String, String?>()
private val artistPhotoLock = Any()

/** Falhas de rede (não "sem resultado"): guarda quando foi e em qual "reconexão", para tentar de novo quando a internet voltar. */
private val artistPhotoFailures = mutableMapOf<String, Pair<Int, Long>>()

private suspend fun fetchArtistPhoto(artist: String): Bitmap? = withContext(Dispatchers.IO) {
    if (artist.isBlank()) return@withContext null
    val normalized = Normalizer.normalize(artist, Normalizer.Form.NFKC).trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
    artistPhotoCache.get(normalized)?.let { return@withContext it }
    val reconnects = ConnectivityObserver.reconnects.value
    synchronized(artistPhotoLock) {
        if (artistPhotoUrlCache.containsKey(normalized) && artistPhotoUrlCache[normalized].isNullOrBlank()) return@withContext null
        // Já falhou há pouco sem a rede ter voltado: não fica tentando a cada rolagem da lista.
        artistPhotoFailures[normalized]?.let { (failedAtReconnect, failedAt) ->
            if (failedAtReconnect == reconnects && System.currentTimeMillis() - failedAt < 30_000L) return@withContext null
        }
    }

    var networkFailure = false
    val result = runCatching {
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
    }.onFailure { networkFailure = true }.getOrNull()

    if (result == null) {
        synchronized(artistPhotoLock) {
            if (networkFailure) {
                // Sem internet ou erro de rede: não é "esse artista não tem foto". Tenta de novo quando a rede voltar.
                artistPhotoFailures[normalized] = reconnects to System.currentTimeMillis()
            } else {
                artistPhotoUrlCache[normalized] = null
            }
        }
    } else {
        synchronized(artistPhotoLock) { artistPhotoFailures.remove(normalized) }
    }
    result
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

    private fun valuesFor(tracks: List<AudioTrack>, range: StatsRange): Map<String, StatValue> {
        val totals = PlayStatsState.totals(appContext, range)
        return tracks.associate { it.id to (totals[PlayStatsState.keyFor(it)] ?: StatValue()) }
    }

    private fun StatValue.counts() = ms > 0L || plays > 0L

    private fun <T> byTime(selector: (T) -> StatValue, name: (T) -> String): Comparator<T> =
        compareByDescending<T> { selector(it).ms }.thenByDescending { selector(it).plays }.thenBy { name(it).lowercase(Locale.ROOT) }

    fun topTracks(tracks: List<AudioTrack>, range: StatsRange, limit: Int = 10): List<Pair<AudioTrack, StatValue>> {
        val values = valuesFor(tracks, range)
        return tracks.asSequence()
            .map { it to (values[it.id] ?: StatValue()) }
            .filter { it.second.counts() }
            .sortedWith(byTime({ it.second }, { it.first.displayTitle() }))
            .take(limit)
            .toList()
    }

    fun topAlbums(tracks: List<AudioTrack>, range: StatsRange, limit: Int = 10): List<Triple<String, String, StatValue>> {
        val values = valuesFor(tracks, range)
        return tracks.asSequence()
            .filter { it.album.isNotBlank() }
            .groupBy { Normalizer.normalize(it.album, Normalizer.Form.NFKC).trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT) }
            .mapNotNull { (_, group) ->
                val total = group.fold(StatValue()) { acc, t -> acc + (values[t.id] ?: StatValue()) }
                if (!total.counts()) return@mapNotNull null
                val first = group.first()
                Triple(first.album, first.albumArtist.ifBlank { first.artist }, total)
            }
            .sortedWith(byTime({ it.third }, { it.first }))
            .take(limit)
            .toList()
    }

    fun topArtists(tracks: List<AudioTrack>, range: StatsRange, limit: Int = 10): List<Pair<String, StatValue>> {
        val values = valuesFor(tracks, range)
        return tracks.asSequence()
            .filter { it.artist.isNotBlank() && it.artist != "<unknown>" }
            .groupBy { Normalizer.normalize(it.artist, Normalizer.Form.NFKC).trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT) }
            .mapNotNull { (_, group) ->
                val total = group.fold(StatValue()) { acc, t -> acc + (values[t.id] ?: StatValue()) }
                if (!total.counts()) null else (group.first().artist to total)
            }
            .sortedWith(byTime({ it.second }, { it.first }))
            .take(limit)
            .toList()
    }
}

private fun StatValue.minutesText(): String = when {
    ms <= 0L -> "—"
    ms < 60_000L -> "<1 min"
    else -> NumberFormat.getIntegerInstance(Locale("pt", "BR")).format(minutes) + " min"
}

@Composable
private fun StatsRangeChips(range: StatsRange, onChange: (StatsRange) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = UiTokens.sectionSpacing),
        horizontalArrangement = Arrangement.spacedBy(UiTokens.toolbarGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatsRange.entries.forEach { r ->
            FilterChip(selected = r == range, onClick = { onChange(r) }, label = { Text(r.label) })
        }
    }
}

@Composable
private fun StatsResetDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AppDialog(
        title = "Zerar estatísticas?",
        onDismiss = onDismiss,
        confirmText = "Zerar",
        onConfirm = onConfirm,
        destructive = true,
    ) {
        Text("Apaga o tempo ouvido e as vezes tocadas de todas as músicas.")
        DialogNote("Uma cópia fica guardada em .trueplayer/backup.")
    }
}

/**
 * Posição de rolagem das listas guardada na última vez que o app foi usado. Só vale logo depois de
 * abrir o app (para voltar ao mesmo ponto); depois disso cada lista se comporta como sempre.
 */
private object ScrollMemory {
    private const val PREFS = "truetagger_scroll"
    private const val WINDOW_MS = 20_000L
    private var pending: MutableMap<String, Pair<Int, Int>> = mutableMapOf()
    private var sessionStartMs = 0L

    @Synchronized
    fun beginSession(context: Context) {
        sessionStartMs = android.os.SystemClock.elapsedRealtime()
        pending = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
            .mapNotNull { (key, value) ->
                val parts = (value as? String)?.split(',') ?: return@mapNotNull null
                val index = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val offset = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                key to (index to offset)
            }.toMap().toMutableMap()
    }

    fun save(context: Context, key: String, index: Int, offset: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, "$index,$offset").apply()
    }

    /** Devolve a posição salva uma única vez, e só dentro da janela logo após a abertura. */
    @Synchronized
    fun takeForStart(key: String): Pair<Int, Int>? {
        if (android.os.SystemClock.elapsedRealtime() - sessionStartMs > WINDOW_MS) return null
        return pending.remove(key)
    }
}

@Composable
private fun PersistListScroll(state: LazyListState, key: String) {
    val context = LocalContext.current
    var restored by remember(key) { mutableStateOf(false) }
    LaunchedEffect(state, key) {
        val saved = ScrollMemory.takeForStart(key)
        if (saved != null && (saved.first > 0 || saved.second > 0)) {
            // A biblioteca pode ainda estar carregando: espera a lista ter itens suficientes.
            val ready = withTimeoutOrNull(10_000L) { snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > saved.first } }
            if (ready != null) state.scrollToItem(saved.first, saved.second)
        }
        restored = true
    }
    LaunchedEffect(state, key, restored) {
        if (!restored) return@LaunchedEffect
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collectLatest { (index, offset) ->
                delay(400)
                ScrollMemory.save(context, key, index, offset)
            }
    }
}

@Composable
private fun PersistGridScroll(state: LazyGridState, key: String) {
    val context = LocalContext.current
    var restored by remember(key) { mutableStateOf(false) }
    LaunchedEffect(state, key) {
        val saved = ScrollMemory.takeForStart(key)
        if (saved != null && (saved.first > 0 || saved.second > 0)) {
            val ready = withTimeoutOrNull(10_000L) { snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > saved.first } }
            if (ready != null) state.scrollToItem(saved.first, saved.second)
        }
        restored = true
    }
    LaunchedEffect(state, key, restored) {
        if (!restored) return@LaunchedEffect
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collectLatest { (index, offset) ->
                delay(400)
                ScrollMemory.save(context, key, index, offset)
            }
    }
}

private fun encodeNav(
    tab: HomeTab, detail: Detail, detailKey: String, editorTrackId: String?, playerScreen: PlayerScreen, history: List<NavEntry>,
): String = JSONObject().apply {
    put("tab", tab.name)
    put("detail", detail.name)
    put("detail_key", detailKey)
    put("editor_track", editorTrackId.orEmpty())
    put("player", playerScreen.name)
    put("history", JSONArray().apply {
        history.takeLast(20).forEach {
            put(JSONObject().apply {
                put("tab", it.tab.name)
                put("detail", it.detail.name)
                put("detail_key", it.detailKey)
            })
        }
    })
}.toString()

private fun decodeNavEntry(obj: JSONObject?): NavEntry? {
    obj ?: return null
    val tab = runCatching { HomeTab.valueOf(obj.optString("tab")) }.getOrNull() ?: return null
    val detail = runCatching { Detail.valueOf(obj.optString("detail")) }.getOrDefault(Detail.NONE)
    return NavEntry(tab, PlayerScreen.NONE, detail, obj.optString("detail_key"), editorOpen = false)
}

private data class NavEntry(
    val tab: HomeTab,
    val playerScreen: PlayerScreen,
    val detail: Detail,
    val detailKey: String,
    val editorOpen: Boolean,
)

@Composable
private fun TruePlayerIntro() {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).appInsetsPadding(),
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
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "Carregando app…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    onReleaseDownloadFolder: (() -> Unit)? = null,
    onSettingsChange: (AppSettings) -> Unit,
    appVersion: String,
    onOrganizeFolder: (() -> Unit)? = null,
    onCleanFolder: (() -> Unit)? = null,
    onCheckUpdate: (() -> Unit)? = null,
    permissions: List<dev.truetagger.model.PermissionItem> = emptyList(),
    importStatus: dev.truetagger.model.ImportStatus?,
    onDismissImportStatus: () -> Unit,
) {
    var showIntro by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(850)
        showIntro = false
    }

    if (showIntro) {
        TruePlayerTheme(settings.themeMode) {
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
    remember { ScrollMemory.beginSession(context) }
    LaunchedEffect(Unit) {
        (context as? Activity)?.title = "TruePlayer"
    }
    val filterPrefs = remember { context.getSharedPreferences("truetagger_filters", Context.MODE_PRIVATE) }
    val uiPrefs = remember { context.getSharedPreferences("truetagger_ui", Context.MODE_PRIVATE) }
    var pendingExportPlaylist by remember { mutableStateOf<PlaylistData?>(null) }
    var uiStateLoaded by remember { mutableStateOf(false) }
    // Tela de tag e tela do player só voltam depois que a biblioteca/o player estiverem prontos.
    var pendingEditorId by remember { mutableStateOf<String?>(null) }
    var pendingPlayerScreen by remember { mutableStateOf<PlayerScreen?>(null) }
    LaunchedEffect(Unit) {
        librarySearch = filterPrefs.getString("library_search", "").orEmpty()
        librarySortMode = runCatching { PlayerSortMode.valueOf(filterPrefs.getString("library_sort", PlayerSortMode.NAME.name)!!) }.getOrDefault(PlayerSortMode.NAME)
        libraryAscending = filterPrefs.getBoolean("library_ascending", true)
        albumSearch = filterPrefs.getString("album_search", "").orEmpty()
        albumSortMode = runCatching { AlbumSortMode.valueOf(filterPrefs.getString("album_sort", AlbumSortMode.NAME.name)!!) }.getOrDefault(AlbumSortMode.NAME)
        albumAscending = filterPrefs.getBoolean("album_ascending", true)
        val nav = uiPrefs.getString("nav_state", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (nav != null) {
            tab = runCatching { HomeTab.valueOf(nav.optString("tab")) }.getOrDefault(HomeTab.LIBRARY)
                .let { if (it == HomeTab.PLAYLISTS) HomeTab.MOST_PLAYED else it }
            detail = runCatching { Detail.valueOf(nav.optString("detail")) }.getOrDefault(Detail.NONE)
            detailKey = nav.optString("detail_key")
            if (detailKey.isBlank()) detail = Detail.NONE
            pendingPlayerScreen = runCatching { PlayerScreen.valueOf(nav.optString("player")) }.getOrNull()?.takeIf { it != PlayerScreen.NONE }
            pendingEditorId = nav.optString("editor_track").takeIf { it.isNotBlank() }
            history.clear()
            nav.optJSONArray("history")?.let { array ->
                for (i in 0 until array.length()) decodeNavEntry(array.optJSONObject(i))?.let(history::add)
            }
        } else {
            // Primeira abertura depois da atualização: usa a aba que o app guardava antes.
            tab = when (uiPrefs.getString("home_tab", HomeTab.LIBRARY.name)) {
                HomeTab.PLAYLISTS.name, HomeTab.MOST_PLAYED.name -> HomeTab.MOST_PLAYED
                HomeTab.ALBUMS.name -> HomeTab.ALBUMS
                else -> HomeTab.LIBRARY
            }
        }
        uiStateLoaded = true
    }
    // Se a tag/o player não puderem ser restaurados (faixa apagada, nada tocando), desiste depois de um tempo.
    LaunchedEffect(Unit) {
        delay(15_000)
        pendingEditorId = null
        pendingPlayerScreen = null
    }
    LaunchedEffect(tracks, pendingEditorId, uiStateLoaded) {
        val id = pendingEditorId ?: return@LaunchedEffect
        if (!uiStateLoaded) return@LaunchedEffect
        val track = tracks.firstOrNull { it.id == id } ?: return@LaunchedEffect
        pendingEditorId = null
        onSelectTrack(track)
        editorOpen = true
    }
    LaunchedEffect(snapshot.current?.id, pendingPlayerScreen) {
        val screen = pendingPlayerScreen ?: return@LaunchedEffect
        if (snapshot.current == null) return@LaunchedEffect
        pendingPlayerScreen = null
        playerScreen = screen
    }
    LaunchedEffect(tab, detail, detailKey, editorOpen, playerScreen, uiStateLoaded, selectedTrack?.id, history.size) {
        // Enquanto ainda restaura a tela de tag/player, não sobrescreve o que foi salvo.
        if (!uiStateLoaded || pendingEditorId != null || pendingPlayerScreen != null) return@LaunchedEffect
        uiPrefs.edit()
            .putString("nav_state", encodeNav(tab, detail, detailKey, if (editorOpen) selectedTrack?.id else null, playerScreen, history.toList()))
            .apply()
    }
    val store = remember { PlaylistStore(context) }
    val persistenceScope = rememberCoroutineScope()
    var playlistPersistJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var playlists by remember { mutableStateOf(store.load()) }
    var restoreRequested by remember { mutableStateOf(false) }

    LaunchedEffect(tracks, playlists) {
        if (restoreRequested || tracks.isEmpty()) return@LaunchedEffect
        // Se o serviço já está reproduzindo, abrir a Activity pela notificação
        // não pode reconstruir a faixa pausada por cima do player atual.
        if (PlayerState.snapshot.current != null) {
            restoreRequested = true
            snapshot = PlayerState.snapshot
            return@LaunchedEffect
        }
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
    val libraryListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    PersistListScroll(libraryListState, "library")

    fun reloadPlaylists() { playlists = store.load() }

    // A biblioteca mudou (ex.: arquivos movidos para songs/, varredura parcial, celular novo):
    // as playlists guardam caminhos relativos e nunca são podadas automaticamente. Entradas sem
    // arquivo ficam pendentes e voltam a aparecer quando o arquivo existir de novo.
    LaunchedEffect(tracks) { playlists = store.load() }
    val exportPlaylistLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
        val playlist = pendingExportPlaylist
        if (uri != null && playlist != null) store.exportM3u(uri, playlist, tracks)
        pendingExportPlaylist = null
    }
    val importContract = remember { OpenPlaylistFiles() }
    val importPlaylistLauncher = rememberLauncherForActivityResult(importContract) { uris ->
        if (uris.map { store.importM3u(it) }.any { it != null }) reloadPlaylists()
    }
    fun launchPlaylistImport() {
        importContract.initialUri = MusicFolder.playlistsFolderUri(context)
        importPlaylistLauncher.launch(arrayOf("audio/x-mpegurl", "audio/mpegurl", "application/octet-stream", "text/plain"))
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
        // Clicar em outra música não deve desligar o shuffle que já estava ativo.
        // O botão "Aleatório" continua podendo ligá-lo explicitamente.
        val keepShuffle = startShuffle || snapshot.shuffle
        MusicPlaybackService.play(context, list[safeIndex], list, safeIndex, keepShuffle, sourceType, sourceName)
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

    TruePlayerTheme(settings.themeMode) {
        Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            // Barras do sistema e recorte da câmera: o conteúdo nunca fica por baixo deles.
            contentWindowInsets = AppInsets,
            bottomBar = {
                Column {
                    if (snapshot.current != null && !editorOpen && playerScreen == PlayerScreen.NONE) {
                        MiniPlayer(
                            snapshot,
                            onQueue = { navigatePlayer(PlayerScreen.QUEUE) },
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
                        // Dentro de álbum/artista/gênero/playlist a aba de origem continua marcada
                        // (também ao voltar ao app, quando só o detalhe foi restaurado).
                        selectedIndex = when (detail) {
                            Detail.ALBUM -> HomeTab.ALBUMS
                            Detail.ARTIST -> HomeTab.ARTISTS
                            Detail.GENRE -> HomeTab.GENRES
                            Detail.PLAYLIST -> HomeTab.MOST_PLAYED
                            Detail.NONE -> tab
                        }.let { shown -> HomeTab.entries.filter { it != HomeTab.PLAYLISTS }.indexOf(shown) }
                            .takeIf { it >= 0 && !editorOpen && playerScreen == PlayerScreen.NONE } ?: -1,
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
            Column(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()) {
                importStatus?.let { ImportBanner(it, onDismissImportStatus) }
                when {
                    editorOpen -> CurrentTrackScreen(
                        track = selectedTrack,
                        albumTracks = remember(tracks, selectedTrack?.id, selectedTrack?.album, selectedTrack?.artist, selectedTrack?.albumArtist) {
                            selectedTrack?.let { sel -> sameAlbumTracks(tracks, sel) }.orEmpty()
                        },
                        draft = selectedTrack?.let { drafts[it.id] },
                        settings = settings,
                        onDraftChange = { id, draft -> drafts[id] = draft },
                        onBack = { goBack() },
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
                            onImport = { launchPlaylistImport() },
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
                            onImport = { launchPlaylistImport() },
                            onOpen = { navigateDetail(Detail.PLAYLIST, it) },
                            onDelete = { name -> store.delete(name); if (uiPrefs.getString("last_playlist_key", "").orEmpty() == name) uiPrefs.edit().remove("last_playlist_key").apply(); reloadPlaylists() },
                            onExport = { playlist -> pendingExportPlaylist = playlist; exportPlaylistLauncher.launch("${playlist.name}.m3u") },
                            onRename = { oldName, newName -> if (store.rename(oldName, newName)) { reloadPlaylists(); if (uiPrefs.getString("last_playlist_key", "").orEmpty() == oldName) uiPrefs.edit().putString("last_playlist_key", newName).apply() } },
                        )
                        HomeTab.SETTINGS -> SettingsTab(settings, onSettingsChange, downloadFolderLabel, onChooseDownloadFolder, onReleaseDownloadFolder, appVersion, onOrganizeFolder, onCleanFolder, permissions, onCheckUpdate)
                    }
                }
            }
        }
        // FullPlayer e Fila ficam fora do Scaffold: o fundo vai até a borda da tela, e o conteúdo respeita as
        // barras do sistema e o recorte da câmera (senão o horário sobrepõe o título e o mini player cobre os botões).
        if (playerScreen == PlayerScreen.FULL) Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().appInsetsPadding()) {
                FullPlayer(snapshot, { goBack() }, { navigatePlayer(PlayerScreen.QUEUE) }, {
                    snapshot.current?.artist?.takeIf { it.isNotBlank() }?.let { navigateDetail(Detail.ARTIST, it) }
                }, {
                    snapshot.current?.album?.takeIf { it.isNotBlank() }?.let { navigateDetail(Detail.ALBUM, it) }
                })
            }
        }
        if (playerScreen == PlayerScreen.QUEUE) Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          Box(Modifier.fillMaxSize().appInsetsPadding()) {
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
    /** Abre a fila. Só o mini player da tela principal mostra o botão; na própria tela da fila fica sem. */
    onQueue: (() -> Unit)? = null,
) {
    val accent = MaterialTheme.colorScheme.primary
    val context = LocalContext.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp,
        shape = RoundedCornerShape(topStart = UiTokens.space4, topEnd = UiTokens.space4),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(UiTokens.miniPlayerHeight)
                .clickable(onClick = onOpen),
        ) {
            // Linha ondulada de progresso no topo: anda com a música, acalma ao pausar e dá para tocar ou
            // arrastar nela para mudar o ponto da música sem abrir o player grande.
            SeekableWavyBar(
                s = s,
                onSeek = { f -> MusicPlaybackService.command(context, MusicPlaybackService.ACTION_SEEK, (f * s.durationMs).toLong()) },
                modifier = Modifier.fillMaxWidth().height(17.dp).padding(horizontal = UiTokens.space3),
            )
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(start = UiTokens.compactPadding, end = UiTokens.space2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Esquerda: capa e informações da música (nome grande rola em loop).
                key(s.current?.id) {
                    PlayerArtworkBox(
                        s.current?.artworkBytes,
                        Modifier.size(UiTokens.miniPlayerArtworkSize),
                        s.current?.id,
                        maxDimension = 256,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f).padding(horizontal = UiTokens.compactPadding),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    ScrollingText(
                        s.current?.displayTitle().orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // Álbum em cima, artista embaixo; cada linha rola sozinha se o nome for grande.
                    subtitleLines(s.current?.album, s.current?.artist).forEach { line ->
                        ScrollingText(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // Direita, em duas linhas: [anterior] [tocar/pausar] [próxima] em cima; embaixo, na mesma ordem,
                // [aleatório] [fila] [repetir].
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onPrevious, modifier = Modifier.size(MINI_SIDE_BUTTON, MINI_MAIN_BUTTON)) {
                            Icon(Icons.Rounded.SkipPrevious, "Anterior", Modifier.size(28.dp))
                        }
                        FilledIconButton(onClick = onToggle, modifier = Modifier.size(MINI_MAIN_BUTTON)) {
                            Icon(if (s.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (s.playing) "Pausar" else "Reproduzir", Modifier.size(30.dp))
                        }
                        IconButton(onClick = onNext, modifier = Modifier.size(MINI_SIDE_BUTTON, MINI_MAIN_BUTTON)) {
                            Icon(Icons.Rounded.SkipNext, "Próxima", Modifier.size(28.dp))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onShuffle, modifier = Modifier.size(MINI_SIDE_BUTTON, MINI_SMALL_BUTTON)) {
                            Icon(
                                painterResource(if (s.shuffle) R.drawable.truetagger_shuffle_on else R.drawable.truetagger_shuffle_off),
                                "Aleatório", modifier = Modifier.size(22.dp), tint = if (s.shuffle) accent else LocalContentColor.current,
                            )
                        }
                        // No meio, o botão da fila (debaixo do tocar/pausar); sem ele, o espaço fica vazio.
                        if (onQueue != null) {
                            IconButton(onClick = onQueue, modifier = Modifier.size(MINI_MAIN_BUTTON, MINI_SMALL_BUTTON)) {
                                Icon(Icons.Rounded.QueueMusic, "Ver fila", modifier = Modifier.size(22.dp))
                            }
                        } else {
                            Spacer(Modifier.width(MINI_MAIN_BUTTON))
                        }
                        IconButton(onClick = onRepeat, modifier = Modifier.size(MINI_SIDE_BUTTON, MINI_SMALL_BUTTON)) {
                            Icon(
                                painterResource(if (s.repeat == RepeatMode.ONE) R.drawable.truetagger_repeat_on else R.drawable.truetagger_repeat_off),
                                "Repetição", modifier = Modifier.size(22.dp), tint = if (s.repeat != RepeatMode.OFF) accent else LocalContentColor.current,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val MINI_MAIN_BUTTON = 48.dp
private val MINI_SIDE_BUTTON = 44.dp
private val MINI_SMALL_BUTTON = 34.dp

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun FullPlayer(s: PlayerSnapshot, onClose: () -> Unit, onQueue: () -> Unit, onArtist: () -> Unit, onAlbum: () -> Unit) {
    val context = LocalContext.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(horizontal = UiTokens.screenHorizontalPadding, vertical = UiTokens.screenVerticalPadding), horizontalAlignment = Alignment.CenterHorizontally) {
            ScreenHeaderRow(title = "Reproduzindo", onBack = onClose)
            Spacer(Modifier.height(UiTokens.sectionSpacing))
            key(s.current?.id) {
                PlayerArtworkBox(
                    s.current?.artworkBytes,
                    Modifier.fillMaxWidth().weight(1f, fill = false).aspectRatio(1f),
                    s.current?.id,
                    maxDimension = 1024,
                )
            }
            Spacer(Modifier.height(UiTokens.sectionSpacing))
            ScrollingText(
                s.current?.displayTitle().orEmpty(),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            // Álbum e artista em duas linhas, cada um tocável (abre a tela dele) e com rolagem se for grande.
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                val album = s.current?.album.orEmpty()
                val artist = s.current?.artist.orEmpty()
                if (album.isNotBlank()) {
                    TextButton(onClick = onAlbum, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp), modifier = Modifier.fillMaxWidth()) {
                        ScrollingText(album, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
                if (artist.isNotBlank()) {
                    TextButton(onClick = onArtist, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp), modifier = Modifier.fillMaxWidth()) {
                        ScrollingText(artist, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    }
                }
            }
            // O Slider continua cuidando do toque e da acessibilidade; o trilho e a barrinha são desenhados por nós.
            // O seek só é enviado ao soltar o dedo (antes ia um comando a cada pixel arrastado).
            val smooth = rememberSmoothFraction(s)
            var dragging by remember { mutableStateOf<Float?>(null) }
            var justSought by remember { mutableStateOf<Float?>(null) }
            // Depois de soltar, a barra fica no ponto escolhido até o player confirmar a nova posição.
            LaunchedEffect(justSought) {
                if (justSought != null) {
                    kotlinx.coroutines.delay(900)
                    justSought = null
                }
            }
            Slider(
                value = dragging ?: justSought ?: if (s.durationMs > 0) (s.positionMs.toFloat() / s.durationMs).coerceIn(0f, 1f) else 0f,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { f ->
                        justSought = f
                        MusicPlaybackService.command(context, MusicPlaybackService.ACTION_SEEK, (f * s.durationMs).toLong())
                    }
                    dragging = null
                },
                thumb = { Spacer(Modifier.size(2.dp, 32.dp)) },
                track = {
                    WavyProgressBar(
                        progress = { dragging ?: justSought ?: smooth.value },
                        playing = s.playing && dragging == null,
                        modifier = Modifier.fillMaxWidth().height(32.dp),
                        showThumb = true,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = UiTokens.space2), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration(s.positionMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatDuration(s.durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth().padding(vertical = UiTokens.space2)) {
                IconButton(onClick = { MusicPlaybackService.command(context, MusicPlaybackService.ACTION_SHUFFLE) }, modifier = Modifier.size(UiTokens.iconButtonSize)) { Icon(painterResource(if (s.shuffle) dev.truetagger.android.R.drawable.truetagger_shuffle_on else dev.truetagger.android.R.drawable.truetagger_shuffle_off), "Aleatório", modifier = Modifier.size(UiTokens.primaryIconSize), tint = if (s.shuffle) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
                IconButton(onClick = { MusicPlaybackService.command(context, MusicPlaybackService.ACTION_PREVIOUS) }, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipPrevious, "Anterior", Modifier.size(40.dp)) }
                FilledIconButton(onClick = { MusicPlaybackService.command(context, MusicPlaybackService.ACTION_TOGGLE) }, modifier = Modifier.size(68.dp)) { Icon(if (s.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (s.playing) "Pausar" else "Reproduzir", Modifier.size(36.dp)) }
                IconButton(onClick = { MusicPlaybackService.command(context, MusicPlaybackService.ACTION_NEXT) }, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipNext, "Próxima", Modifier.size(40.dp)) }
                IconButton(onClick = { MusicPlaybackService.command(context, MusicPlaybackService.ACTION_REPEAT) }, modifier = Modifier.size(UiTokens.iconButtonSize)) { Icon(painterResource(if (s.repeat == RepeatMode.ONE) dev.truetagger.android.R.drawable.truetagger_repeat_on else dev.truetagger.android.R.drawable.truetagger_repeat_off), "Repetição", modifier = Modifier.size(UiTokens.primaryIconSize), tint = if (s.repeat != RepeatMode.OFF) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
            }
            ActionButtonRow {
                ActionButton("Abrir fila", Icons.Rounded.QueueMusic, onQueue, Modifier.weight(1f))
            }
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
    val state = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    PersistListScroll(state, "queue")
    LaunchedEffect(s.queue, s.current?.id) {
        val currentIndex = s.queue.indexOfFirst { it.id == s.current?.id }
        if (currentIndex >= 0) state.scrollToItem(currentIndex)
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeaderRow(
            title = "Fila atual",
            modifier = Modifier.padding(
                horizontal = UiTokens.screenHorizontalPadding,
                vertical = UiTokens.screenVerticalPadding,
            ),
            onBack = onBack,
        )
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = UiTokens.screenHorizontalPadding)) {
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
        PlaylistStore(context).removeTracksEverywhere(requested)
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

        // O player mantém o arquivo atual aberto. Se ele estiver entre os
        // selecionados, o MediaStore recusa a exclusão (ou o arquivo "volta").
        // Soltamos os descritores antes de qualquer tentativa.
        MusicPlaybackService.releaseFileHandles(context)

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
        ScreenHeaderRow(
            title = "Biblioteca",
            modifier = Modifier.padding(
                horizontal = UiTokens.screenHorizontalPadding,
                vertical = UiTokens.screenVerticalPadding,
            ),
        ) {
            IconButton(onClick = refresh, modifier = Modifier.size(UiTokens.iconButtonSize)) {
                Icon(Icons.Rounded.Refresh, "Atualizar", modifier = Modifier.size(UiTokens.primaryIconSize))
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val wideLayout = maxWidth >= 700.dp
            if (wideLayout) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(UiTokens.toolbarGap)) {
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
        AppDialog(
            title = "Excluir ${plural(selectedIds.size, "música", "músicas")}?",
            onDismiss = { showDeleteConfirm = false },
            confirmText = "Excluir",
            onConfirm = { performDelete() },
            destructive = true,
        ) {
            Text("Apaga do celular. Não dá para desfazer.")
        }
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
    Column(modifier.padding(horizontal = UiTokens.screenHorizontalPadding)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(UiTokens.space1),
        ) {
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
            IconButton(onClick = onSelectionModeToggle, modifier = Modifier.size(UiTokens.iconButtonSize)) {
                Icon(
                    imageVector = if (selectionMode) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                    contentDescription = if (selectionMode) "Encerrar seleção" else "Selecionar",
                    tint = if (selectionMode) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    modifier = Modifier.size(UiTokens.primaryIconSize),
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
            ActionButtonRow(Modifier.padding(vertical = UiTokens.sectionSpacing)) {
                ActionButton("Remover", Icons.Rounded.Delete, onDeleteClick, Modifier.weight(1f), enabled = selectedIds.isNotEmpty(), contentColor = MaterialTheme.colorScheme.error)
                ActionButton("Playlist", Icons.Rounded.PlaylistAdd, onPlaylistClick, Modifier.weight(1f), enabled = selectedIds.isNotEmpty())
            }
        } else {
            ActionButtonRow(Modifier.padding(top = UiTokens.sectionSpacing, bottom = UiTokens.space1)) {
                ActionButton("Tocar", Icons.Rounded.PlayArrow, onPlayAll, Modifier.weight(1f), enabled = shown.isNotEmpty())
                ActionButton("Aleatório", Icons.Rounded.Shuffle, onPlayRandom, Modifier.weight(1f), enabled = shown.isNotEmpty())
            }
            if (shown.isNotEmpty()) {
                Text(
                    plural(shown.size, "música", "músicas"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = UiTokens.space1, top = UiTokens.space1, bottom = UiTokens.space2),
                )
            }
        }
        if (shown.isEmpty()) {
            EmptyState(
                if (librarySearch.isBlank()) Icons.Rounded.LibraryMusic else Icons.Rounded.Search,
                if (librarySearch.isBlank()) "Nenhuma música" else "Nada encontrado",
                if (librarySearch.isBlank()) "Escolha a pasta de músicas em Configurações ou baixe por link." else "Tente outro nome de música, artista ou álbum.",
            )
        }
        SongListContainer(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(shown, key = { it.id }) { track ->
                    SongItem(track,
                        onPlay = { play(shown, shown.indexOfFirst { it.id == track.id }.coerceAtLeast(0)) }, onEdit = { edit(track) }, onShare = { share(track) }, onAddToPlaylist = { onAddToPlaylist(track) },
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
        IconButton(onClick = { onOpenChange(true) }, modifier = Modifier.size(UiTokens.iconButtonSize)) {
            Icon(Icons.Rounded.FilterAlt, "Filtros", modifier = Modifier.size(UiTokens.primaryIconSize))
        }
        DropdownMenu(expanded = open, onDismissRequest = { onOpenChange(false) }) {
            Text(title, modifier = Modifier.padding(horizontal = UiTokens.cardItemHorizontalPadding, vertical = UiTokens.space2), fontWeight = FontWeight.Bold)
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
    var range by rememberSaveable { mutableStateOf(StatsRange.ALL) }
    var creating by remember { mutableStateOf(false) }
    var createName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<PlaylistData?>(null) }
    var renameText by remember { mutableStateOf("") }

    // A revisão muda sempre que uma reprodução é registrada. Isso força
    // as três categorias a recalcularem juntas, sem depender da identidade
    // da lista da biblioteca.
    val statsRevision by PlayStatsState.revision.collectAsState()
    // A revisão entra como chave do remember: os três cards são recalculados
    // pelo mesmo evento de reprodução, mas não a cada recomposição (o cálculo
    // percorre e ordena a biblioteca inteira).
    val liveTopTrack = remember(statsRevision, tracks, range) { store.topTracks(tracks, range, 1).firstOrNull() }
    val liveTopAlbum = remember(statsRevision, tracks, range) { store.topAlbums(tracks, range, 1).firstOrNull() }
    val liveTopArtist = remember(statsRevision, tracks, range) { store.topArtists(tracks, range, 1).firstOrNull() }

    BackHandler(enabled = mode != MostPlayedMode.OVERVIEW) {
        mode = MostPlayedMode.OVERVIEW
    }

    StandardPage {
        Column(Modifier.fillMaxSize()) {
            ScreenHeaderRow(
                title = if (mode == MostPlayedMode.OVERVIEW) "Playlists" else "Mais ouvidas",
                onBack = if (mode != MostPlayedMode.OVERVIEW) ({ mode = MostPlayedMode.OVERVIEW }) else null,
            )
            Spacer(Modifier.height(UiTokens.listHeaderGap))

            if (mode == MostPlayedMode.OVERVIEW) {
                val state = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
                PersistListScroll(state, "playlists_home")
                Column(Modifier.fillMaxSize()) {
                    ActionButtonRow {
                        ActionButton("Criar", Icons.Rounded.Add, { creating = true; createName = "" }, Modifier.weight(1f))
                        ActionButton("Importar", Icons.Rounded.FileOpen, onImport, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(UiTokens.sectionSpacing))
                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = state,
                            modifier = Modifier.fillMaxSize().songListSpace(),
                            verticalArrangement = Arrangement.spacedBy(UiTokens.songListSpacing),
                            contentPadding = PaddingValues(bottom = UiTokens.songListBottomPadding),
                        ) {
                            if (playlists.isNotEmpty()) {
                                items(playlists, key = { it.name }) { playlist ->
                                    var expanded by remember(playlist.name) { mutableStateOf(false) }
                                    ListCardRow(onClick = { onOpen(playlist.name) }) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(UiTokens.songItemSpacing)) {
                                            ScrollingText(playlist.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                            Text(plural(playlist.trackIds.size, "música", "músicas"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        Box {
                                            IconButton(onClick = { expanded = true }, modifier = Modifier.size(UiTokens.iconButtonSize)) {
                                                Icon(Icons.Rounded.MoreVert, "Opções", modifier = Modifier.size(UiTokens.primaryIconSize))
                                            }
                                            DropdownMenu(expanded, { expanded = false }) {
                                                DropdownMenuItem({ Text("Exportar M3U") }, onClick = { expanded = false; onExport(playlist) })
                                                DropdownMenuItem({ Text("Renomear") }, onClick = { expanded = false; renameText = playlist.name; renaming = playlist })
                                                DropdownMenuItem({ Text("Excluir") }, onClick = { expanded = false; onDelete(playlist.name) })
                                            }
                                        }
                                    }
                                }
                            } else {
                                item { EmptyState(Icons.Rounded.QueueMusic, "Nenhuma playlist", "Crie uma na aba Playlists para agrupar suas músicas.") }
                            }

                            item {
                                HorizontalDivider(Modifier.padding(vertical = UiTokens.sectionSpacing))
                            }
                            item {
                                Text("MAIS OUVIDAS", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = UiTokens.space2))
                            }
                            item { StatsRangeChips(range) { range = it } }
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
                                        subtitle = topTrack?.let { "${it.displayTitle()} • ${liveTopTrack?.second?.minutesText().orEmpty()}" },
                                        artwork = topTrack?.artworkBytes,
                                        artworkUri = topTrack?.id,
                                        modifier = Modifier.weight(1f),
                                        onClick = { mode = MostPlayedMode.TRACKS },
                                    )
                                    MostPlayedCategoryCard(
                                        title = "ÁLBUNS",
                                        subtitle = topAlbum?.let { "$it • ${liveTopAlbum?.third?.minutesText().orEmpty()}" },
                                        artwork = topAlbumTrack?.artworkBytes,
                                        artworkUri = topAlbumTrack?.id,
                                        modifier = Modifier.weight(1f),
                                        onClick = { mode = MostPlayedMode.ALBUMS },
                                    )
                                        MostPlayedCategoryCard(
                                            title = "ARTISTAS",
                                            subtitle = liveTopArtist?.let { "${it.first} • ${it.second.minutesText()}" },
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
                val state = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
                PersistListScroll(state, "most_played_${mode.name}")
                StatsRangeChips(range) { range = it }
                when (mode) {
                    MostPlayedMode.TRACKS -> {
                        val values = remember(statsRevision, tracks, range) { store.topTracks(tracks, range) }
                        if (values.isEmpty()) EmptyMostPlayed() else SongListContainer(state = state, modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(values, key = { _, pair -> pair.first.id }) { index, (track, value) ->
                                MostPlayedRow(
                                    index + 1,
                                    track.displayTitle(),
                                    track.artist,
                                    track.album,
                                    value,
                                    track.artworkBytes,
                                    track.id,
                                ) { play(listOf(track), 0) }
                            }
                        }
                    }
                    MostPlayedMode.ALBUMS -> {
                        val values = remember(statsRevision, tracks, range) { store.topAlbums(tracks, range) }
                        if (values.isEmpty()) EmptyMostPlayed() else SongListContainer(state = state, modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(values, key = { _, item -> item.first + "\u0000" + item.second }) { index, (album, artist, value) ->
                                val artworkTrack = tracks.firstOrNull { it.album.equals(album, true) && it.artworkBytes?.isNotEmpty() == true }
                                    ?: tracks.firstOrNull { it.album.equals(album, true) }
                                MostPlayedRow(
                                    index + 1,
                                    album,
                                    artist,
                                    "",
                                    value,
                                    artworkTrack?.artworkBytes,
                                    artworkTrack?.id,
                                ) { onAlbum(album) }
                            }
                        }
                    }
                    MostPlayedMode.ARTISTS -> {
                        val values = remember(statsRevision, tracks, range) { store.topArtists(tracks, range) }
                        if (values.isEmpty()) EmptyMostPlayed() else SongListContainer(state = state, modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(values, key = { _, item -> item.first }) { index, (artist, value) ->
                                MostPlayedRow(
                                    index + 1,
                                    artist,
                                    "",
                                    "",
                                    value,
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
    Box(modifier) {
        Card(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
            shape = RoundedCornerShape(UiTokens.songItemRadius),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(UiTokens.songItemPadding),
                verticalArrangement = Arrangement.spacedBy(UiTokens.songItemSpacing),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when {
                    artistName != null -> ArtistAvatar(
                        artistName,
                        Modifier.fillMaxWidth().aspectRatio(1f),
                    )
                    artwork != null || artworkUri != null -> PlayerArtworkBox(
                        artwork,
                        Modifier.fillMaxWidth().aspectRatio(1f),
                        artworkUri ?: "most_$title",
                        maxDimension = 768,
                    )
                    else -> Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(UiTokens.songItemRadius))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    }
                }
                ScrollingText(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    ScrollingText(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun EmptyMostPlayed() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(Icons.Rounded.MusicNote, "Nada tocado ainda", "As músicas que você mais ouve aparecem aqui.")
    }
}

@Composable
private fun MostPlayedRow(rank: Int, title: String, artist: String, album: String, value: StatValue, artwork: ByteArray?, artworkUri: String? = null, artistImageName: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = UiTokens.mostPlayedRowHeight)
            .clip(RoundedCornerShape(UiTokens.songItemRadius))
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(UiTokens.songItemRadius))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.34f))
            .clickable(onClick = onClick)
            .padding(horizontal = UiTokens.cardItemHorizontalPadding, vertical = UiTokens.songItemPadding),
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
            ScrollingText(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            subtitleLines(album, artist).forEach { line ->
                ScrollingText(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(value.minutesText(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text("${value.plays}×", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
    showTrackNumber: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    // Só a música atual lê o estado de "tocando": animar é só dela, e as outras linhas não recompõem.
    val nowPlaying = if (isCurrent) PlayerState.playing.collectAsState().value else false
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
            // Ondas passando atrás das informações da música que está tocando.
            .then(if (isCurrent && !selectionMode) Modifier.nowPlayingWaves(nowPlaying, MaterialTheme.colorScheme.primary) else Modifier)
            .clickable(onClick = if (selectionMode) onToggleSelect else onPlay)
            .padding(horizontal = UiTokens.cardItemHorizontalPadding, vertical = UiTokens.songItemPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            selectionMode -> SelectionIndicator(selected = selected, onClick = onToggleSelect)
            reorderControls && onMoveUp != null && onMoveDown != null -> Column(
                modifier = Modifier.width(UiTokens.reorderControlsWidth),
                horizontalAlignment = Alignment.CenterHorizontally,
                // Os botões tinham 38dp cada e o padding interno do IconButton
                // abria um vão entre as setas. Encostados, o par fica coeso.
                verticalArrangement = Arrangement.spacedBy((-6).dp),
            ) {
                IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(UiTokens.smallIconButtonSize)) {
                    Icon(Icons.Rounded.KeyboardArrowUp, "Subir música", modifier = Modifier.size(UiTokens.primaryIconSize))
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(UiTokens.smallIconButtonSize)) {
                    Icon(Icons.Rounded.KeyboardArrowDown, "Abaixar música", modifier = Modifier.size(UiTokens.primaryIconSize))
                }
            }
        }
        if (showTrackNumber && track.trackNumber.isNotBlank()) {
            Text(
                track.trackNumber,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(UiTokens.rankWidth),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 1,
            )
        }
        Box(Modifier.size(UiTokens.songItemArtworkSize), contentAlignment = Alignment.Center) {
            PlayerArtworkBox(track.artworkBytes, Modifier.fillMaxSize(), track.id, maxDimension = 384)
            // Música atual: véu escuro e ícone de ondas sobre a capa, além da borda colorida.
            if (isCurrent && !selectionMode) {
                Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    EqualizerBars(playing = nowPlaying, color = TruePlayerDarkScheme.primary)
                }
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = UiTokens.cardItemHorizontalPadding), verticalArrangement = Arrangement.spacedBy(UiTokens.songItemSpacing)) {
            ScrollingText(
                track.displayTitle(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            // Álbum em cima, artista embaixo (cada linha rola sozinha se o nome for grande).
            val lines = subtitleLines(track.album, track.artist).ifEmpty { listOf("—") }
            lines.forEach { line ->
                ScrollingText(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // A duração saiu de baixo do nome para o lado, para o item não ficar com quatro linhas.
        Text(
            formatDuration(track.durationMs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(start = UiTokens.space1),
        )
        Box {
            IconButton(onClick = { expanded = true }, modifier = Modifier.size(UiTokens.iconButtonSize)) {
                Icon(Icons.Rounded.MoreVert, "Opções", modifier = Modifier.size(UiTokens.primaryIconSize))
            }
            DropdownMenu(expanded, { expanded = false }) {
                DropdownMenuItem({ Text("Informações") }, leadingIcon = { Icon(Icons.Rounded.Info, null) }, onClick = { expanded = false; info = true })
                DropdownMenuItem({ Text("Editar tags") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { expanded = false; onEdit() })
                if (showAddToPlaylist) DropdownMenuItem({ Text("Adicionar à playlist") }, leadingIcon = { Icon(Icons.Rounded.PlaylistAdd, null) }, onClick = { expanded = false; onAddToPlaylist() })
                DropdownMenuItem({ Text("Compartilhar") }, leadingIcon = { Icon(Icons.Rounded.Share, null) }, onClick = { expanded = false; onShare() })
                onRemoveFromPlaylist?.let { remove ->
                    DropdownMenuItem({ Text("Tirar da playlist") }, leadingIcon = { Icon(Icons.Rounded.RemoveCircleOutline, null) }, onClick = { expanded = false; remove() })
                }
                if (onRemoveFromPlaylist == null && onDelete != null) {
                    DropdownMenuItem(
                        { Text("Excluir do celular", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { expanded = false; onDelete() },
                    )
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
            Column(Modifier.fillMaxWidth()) {
                InfoRow("Álbum", track.album, onAlbum)
                InfoRow("Artista", track.artist, onArtist)
                InfoRow("Ano", track.year)
                InfoRow("Gênero", track.genre)
                InfoRow("Faixa", track.trackNumber)
                InfoRow("Duração", formatDuration(track.durationMs))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}

/** Uma linha da ficha da música: rótulo apagado à esquerda, valor ao lado. Com [onClick], o valor abre a tela correspondente. */
@Composable
private fun InfoRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    val clickable = onClick != null && value.isNotBlank()
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.clickable { onClick?.invoke() } else Modifier)
            .padding(vertical = 5.dp),
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(76.dp))
        Text(
            value.ifBlank { "—" },
            color = if (clickable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
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
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Adicionar ${plural(trackIds.size, "música", "músicas")} à playlist") }, text = {
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
    val state = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    PersistListScroll(state, "playlist_screen")
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize().padding(horizontal = UiTokens.pageHorizontal, vertical = UiTokens.pageVertical).songListSpace(),
            verticalArrangement = Arrangement.spacedBy(UiTokens.songListSpacing),
            contentPadding = PaddingValues(bottom = UiTokens.songListBottomPadding),
        ) {
            item {
                ScreenHeaderRow(title = "Playlists")
                Spacer(Modifier.height(UiTokens.listHeaderGap))
                ActionButtonRow {
                    ActionButton("Criar", Icons.Rounded.Add, { creating = true; name = "" }, Modifier.weight(1f))
                    ActionButton("Importar", Icons.Rounded.FileOpen, onImport, Modifier.weight(1f))
                }
                HorizontalDivider(Modifier.padding(vertical = UiTokens.sectionSpacing))
            }
            if (playlists.isEmpty()) item { EmptyState(Icons.Rounded.QueueMusic, "Nenhuma playlist", "Toque em Criar ou em Importar para começar.") }
            else items(playlists, key = { it.name }) { playlist ->
                var expanded by remember(playlist.name) { mutableStateOf(false) }
                ListCardRow(onClick = { onOpen(playlist.name) }) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(UiTokens.songItemSpacing)) {
                        ScrollingText(playlist.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(plural(playlist.trackIds.size, "música", "músicas"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box {
                        IconButton(onClick = { expanded = true }, modifier = Modifier.size(UiTokens.iconButtonSize)) {
                            Icon(Icons.Rounded.MoreVert, "Opções", modifier = Modifier.size(UiTokens.primaryIconSize))
                        }
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
    Box(Modifier.fillMaxSize()) {
        StandardPage {
        ScreenHeaderRow(title = "Artistas")
        Spacer(Modifier.height(UiTokens.listHeaderGap))
        SearchBar(value = search, onValueChange = { search = it }, placeholder = "Pesquisar artistas")
        if (artists.isNotEmpty()) {
            Text(
                plural(artists.size, "artista", "artistas"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = UiTokens.space1, top = UiTokens.space2),
            )
        }
        Spacer(Modifier.height(UiTokens.space1))
        val state = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
        PersistListScroll(state, "artists")
        if (artists.isEmpty()) {
            EmptyState(
                if (search.isBlank()) Icons.Rounded.Person else Icons.Rounded.Search,
                if (search.isBlank()) "Nenhum artista" else "Nada encontrado",
                if (search.isBlank()) "Os artistas aparecem quando a biblioteca tem músicas com artista nas tags." else "Tente outro nome.",
            )
        }
        SongListContainer(state = state, modifier = Modifier.weight(1f)) {
            val grouped = artists.groupBy { it.name.firstOrNull()?.uppercaseChar()?.toString() ?: "#" }.toSortedMap()
            grouped.forEach { (letter, items) ->
                item(key = "artist-section-$letter") {
                    Text(letter, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = UiTokens.standardListTopSpacing, bottom = UiTokens.space1))
                }
                items(items, key = { "$letter-${it.name}" }) { item ->
                    ListCardRow(onClick = { open(item.name) }) {
                        ArtistAvatar(
                            item.name,
                            Modifier.size(UiTokens.songItemArtworkSize),
                        )
                        Column(
                            Modifier.weight(1f).padding(start = UiTokens.cardItemHorizontalPadding),
                            verticalArrangement = Arrangement.spacedBy(UiTokens.songItemSpacing),
                        ) {
                            ScrollingText(item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                plural(item.songs, "música", "músicas"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
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
    // Volta a tentar sozinho quando a internet reconecta.
    val reconnects by ConnectivityObserver.reconnects.collectAsState()
    LaunchedEffect(artist, reconnects) {
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
    // "Rock" e "rock" são o mesmo gênero; o nome mostrado é o do primeiro que aparece.
    val genres = remember(tracks, search) {
        val tagged = tracks.map { it.genre.trim() }
            .filter { it.isNotBlank() }
            .groupBy { it.lowercase(Locale.ROOT) }
            .values.map { it.first() to it.size }
            .filter { search.isBlank() || it.first.contains(search, ignoreCase = true) }
            .sortedBy { it.first.lowercase(Locale.ROOT) }
        val untagged = tracks.count { it.genre.isBlank() }
        // "Sem gênero" só aparece sem busca; com busca sem resultado a tela diz "Nada encontrado".
        if (search.isBlank() && untagged > 0) tagged + ("Sem gênero" to untagged) else tagged
    }
    StandardPage {
        ScreenHeaderRow(title = "Gêneros")
        Spacer(Modifier.height(UiTokens.listHeaderGap))
        SearchBar(value = search, onValueChange = { search = it }, placeholder = "Pesquisar gêneros")
        Spacer(Modifier.height(UiTokens.standardListTopSpacing))
        if (genres.isEmpty()) {
            EmptyState(
                if (search.isBlank()) Icons.Rounded.Category else Icons.Rounded.Search,
                if (search.isBlank()) "Nenhum gênero" else "Nada encontrado",
                if (search.isBlank()) "Os gêneros aparecem quando as músicas têm gênero nas tags." else "Tente outro nome.",
            )
        }
        GenreList(genres, open, Modifier.weight(1f))
    }
}

@Composable
private fun GenreList(values: List<Pair<String, Int>>, open: (String) -> Unit, modifier: Modifier = Modifier) {
    val state = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    PersistListScroll(state, "alpha_list")
    val sections = values.groupBy { (name, _) -> name.firstOrNull()?.uppercaseChar()?.toString() ?: "#" }
    SongListContainer(state = state, modifier = modifier.fillMaxSize()) {
        sections.toSortedMap().forEach { (letter, genres) ->
            item(key = "section-$letter") {
                Text(letter, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = UiTokens.standardListTopSpacing, bottom = UiTokens.space1))
            }
            items(genres, key = { (name, _) -> "$letter-$name" }) { (name, songs) ->
                ListCardRow(onClick = { open(name) }, minHeight = UiTokens.controlHeight) {
                    ScrollingText(name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(plural(songs, "música", "músicas"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(start = UiTokens.space2))
                }
            }
        }
    }
}

@Composable
private fun AlbumsScreen(tracks: List<AudioTrack>, search: String, onSearchChange: (String) -> Unit, sortMode: AlbumSortMode, onSortModeChange: (AlbumSortMode) -> Unit, ascending: Boolean, onAscendingChange: () -> Unit, open: (String) -> Unit) {
    var filterOpen by remember { mutableStateOf(false) }
    val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    PersistGridScroll(gridState, "albums")
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
                    AlbumSortMode.YEAR -> (a.year.toIntOrNull() ?: 0).compareTo(b.year.toIntOrNull() ?: 0)
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
            ScreenHeaderRow(title = "Álbuns")
            Spacer(Modifier.height(UiTokens.listHeaderGap))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(UiTokens.space1),
            ) {
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
                if (albums.isEmpty()) {
                    EmptyState(
                        if (search.isBlank()) Icons.Rounded.Album else Icons.Rounded.Search,
                        if (search.isBlank()) "Nenhum álbum" else "Nada encontrado",
                        if (search.isBlank()) "Os álbuns aparecem quando as músicas têm álbum nas tags." else "Tente outro nome de álbum ou artista.",
                    )
                }
                LazyVerticalGrid(state = gridState, columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize().songListSpace(), contentPadding = PaddingValues(bottom = UiTokens.songListBottomPadding), verticalArrangement = Arrangement.spacedBy(UiTokens.songListSpacing), horizontalArrangement = Arrangement.spacedBy(UiTokens.songListSpacing)) {
                    items(albums, key = { "${it.key}\u0000${it.artist}" }) { item ->
                        // A altura era fixa em 296dp com cinco linhas de texto,
                        // e o que não coubesse era simplesmente cortado. Agora o
                        // card cresce conforme o conteúdo e os dados secundários
                        // ficam numa linha só.
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(UiTokens.cardRadius))
                                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(UiTokens.cardRadius))
                                .clickable { open(item.key) },
                            shape = RoundedCornerShape(UiTokens.cardRadius),
                        ) {
                            PlayerArtworkBox(
                                item.artwork,
                                Modifier.fillMaxWidth().aspectRatio(1f),
                                albumTrackIds[item.name],
                                maxDimension = 256,
                            )
                            Column(
                                Modifier.fillMaxWidth().padding(UiTokens.songItemPadding),
                                verticalArrangement = Arrangement.spacedBy(UiTokens.songItemSpacing),
                            ) {
                                ScrollingText(item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                ScrollingText(item.artist.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    listOfNotNull(
                                        plural(item.songs, "música", "músicas"),
                                        formatDuration(item.duration),
                                        item.year.takeIf { it.isNotBlank() },
                                    ).joinToString(" • "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
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
    val state = rememberSaveable(type, key, saver = LazyListState.Saver) { LazyListState() }
    PersistListScroll(state, "detail_${type.name}_$key")
    val uiContext = LocalContext.current
    val uiPrefs = remember { uiContext.getSharedPreferences("truetagger_ui", Context.MODE_PRIVATE) }
    val scrollKey = remember(type, key) { "detail_scroll_${type.name}_${key.hashCode()}" }
    LaunchedEffect(type, key) {
        val savedIndex = uiPrefs.getInt("${scrollKey}_index", 0)
        val savedOffset = uiPrefs.getInt("${scrollKey}_offset", 0)
        if (savedIndex > 0 || savedOffset > 0) {
            runCatching { state.scrollToItem(savedIndex.coerceAtLeast(0), savedOffset.coerceAtLeast(0)) }
        }
    }
    LaunchedEffect(state, type, key) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                uiPrefs.edit()
                    .putInt("${scrollKey}_index", index)
                    .putInt("${scrollKey}_offset", offset)
                    .apply()
            }
    }
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
            ScreenHeaderRow(
                title = key,
                onBack = { if (playlistSelectionMode) exitPlaylistSelection() else onBack() },
            )
            Spacer(Modifier.height(UiTokens.listHeaderGap))
            if (type == Detail.PLAYLIST) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiTokens.space1)) {
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
                    IconButton(
                        onClick = {
                            playlistSelectionMode = !playlistSelectionMode
                            if (!playlistSelectionMode) selectedPlaylistIds.clear()
                        },
                        modifier = Modifier.size(UiTokens.iconButtonSize),
                    ) {
                        Icon(
                            imageVector = if (playlistSelectionMode) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                            contentDescription = if (playlistSelectionMode) "Encerrar seleção" else "Selecionar",
                            tint = if (playlistSelectionMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(UiTokens.primaryIconSize),
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
                    ActionButtonRow(Modifier.padding(vertical = UiTokens.sectionSpacing)) {
                        ActionButton(
                            "Remover da playlist",
                            Icons.Rounded.Delete,
                            { onRemoveSelectedFromPlaylist(key, selectedPlaylistIds.toList()); exitPlaylistSelection() },
                            Modifier.weight(1f),
                            enabled = selectedPlaylistIds.isNotEmpty(),
                            contentColor = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    Spacer(Modifier.height(UiTokens.sectionSpacing))
                    ActionButtonRow {
                        ActionButton("Tocar", Icons.Rounded.PlayArrow, { onPlay(shown, 0) }, Modifier.weight(1f), enabled = shown.isNotEmpty())
                        ActionButton("Aleatório", Icons.Rounded.Shuffle, { onPlayRandom(shown) }, Modifier.weight(1f), enabled = shown.isNotEmpty())
                    }
                }
                Spacer(Modifier.height(UiTokens.sectionSpacing))
                Text("${plural(shown.size, "música", "músicas")} • ${formatDuration(duration)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (playlistSortMode != PlaylistSortMode.MANUAL || playlistSearch.isNotBlank()) Text("Para reordenar manualmente, use Manual e deixe a busca vazia.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(UiTokens.standardListTopSpacing))
                if (shown.isEmpty()) {
                    EmptyState(
                        if (playlistSearch.isBlank()) Icons.Rounded.QueueMusic else Icons.Rounded.Search,
                        if (playlistSearch.isBlank()) "Playlist vazia" else "Nada encontrado",
                        if (playlistSearch.isBlank()) "Abra o menu de uma música e escolha Adicionar à playlist." else "Tente outro nome de música, artista ou álbum.",
                    )
                }
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
                val artist = shown.firstOrNull()?.albumArtist?.ifBlank { shown.firstOrNull()?.artist.orEmpty() }.orEmpty()
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(UiTokens.sectionSpacing),
                ) {
                    PlayerArtworkBox(
                        artwork,
                        Modifier.size(UiTokens.detailArtworkSize),
                        shown.firstOrNull()?.id,
                    )
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(UiTokens.itemSpacing),
                    ) {
                        ScrollingText(key, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        TextButton(
                            onClick = { onOpenArtist(artist) },
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                        ) {
                            ScrollingText(artist.ifBlank { "Artista desconhecido" }, color = MaterialTheme.colorScheme.primary)
                        }
                        ScrollingText(
                            listOfNotNull(
                                plural(shown.size, "música", "músicas"),
                                formatDuration(duration),
                                shown.firstOrNull()?.year?.takeIf { it.isNotBlank() },
                            ).joinToString(" • "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(UiTokens.sectionSpacing))
                ActionButtonRow {
                    ActionButton("Tocar", Icons.Rounded.PlayArrow, { onPlay(shown, 0) }, Modifier.weight(1f), enabled = shown.isNotEmpty())
                    ActionButton("Aleatório", Icons.Rounded.Shuffle, { onPlayRandom(shown) }, Modifier.weight(1f), enabled = shown.isNotEmpty())
                }
                Spacer(Modifier.height(UiTokens.sectionSpacing))
                SongListContainer(state = state, modifier = Modifier.fillMaxWidth().weight(1f)) {
                        items(shown, key = { it.id }) { t ->
                            SongItem(t, { onPlay(shown, shown.indexOfFirst { it.id == t.id }.coerceAtLeast(0)) }, { onEdit(t) }, { onShare(t) }, {}, {}, {}, {}, onRemoveFromPlaylist = null, isCurrent = t.id == currentTrackId, showTrackNumber = true)
                        }
                    }
             } else if (type == Detail.ARTIST) {
                    val albums = shown.groupBy { it.album.ifBlank { "Sem álbum" } }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                    // A fila segue exatamente a ordem da lista na tela (álbum por álbum).
                    val playOrder = albums.values.flatten()
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(UiTokens.sectionSpacing),
                    ) {
                        ArtistAvatar(
                            key,
                            Modifier.size(UiTokens.detailArtworkSize),
                        )
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(UiTokens.itemSpacing),
                        ) {
                            ScrollingText(key, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            ScrollingText(
                                listOf(plural(shown.size, "música", "músicas"), plural(albums.size, "álbum", "álbuns"), formatDuration(duration)).joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(UiTokens.sectionSpacing))
                    ActionButtonRow {
                        ActionButton("Tocar", Icons.Rounded.PlayArrow, { onPlay(playOrder, 0) }, Modifier.weight(1f), enabled = shown.isNotEmpty())
                        ActionButton("Aleatório", Icons.Rounded.Shuffle, { onPlayRandom(playOrder) }, Modifier.weight(1f), enabled = shown.isNotEmpty())
                    }
                    Spacer(Modifier.height(UiTokens.sectionSpacing))
                    SongListContainer(state = state, modifier = Modifier.fillMaxWidth().weight(1f)) {
                        albums.forEach { (album, albumTracks) ->
                            item(key = "album-$album") {
                                Column(Modifier.fillMaxWidth().padding(top = UiTokens.standardListTopSpacing, bottom = UiTokens.space1)) {
                                    TextButton(onClick = { onOpenAlbum(album) }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) { ScrollingText(album, fontWeight = FontWeight.Bold) }
                                    Text("${plural(albumTracks.size, "música", "músicas")} • ${formatDuration(albumTracks.sumOf { it.durationMs })}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            items(albumTracks, key = { it.id }) { t ->
                                SongItem(t, { onPlay(playOrder, playOrder.indexOfFirst { it.id == t.id }.coerceAtLeast(0)) }, { onEdit(t) }, { onShare(t) }, {}, {}, {}, {}, onRemoveFromPlaylist = null, isCurrent = t.id == currentTrackId)
                            }
                        }
                    }
                } else {
                    if (shown.isNotEmpty()) Text("${plural(shown.size, "música", "músicas")} • ${formatDuration(duration)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(UiTokens.sectionSpacing))
                    ActionButtonRow {
                        ActionButton("Tocar", Icons.Rounded.PlayArrow, { onPlay(shown, 0) }, Modifier.weight(1f), enabled = shown.isNotEmpty())
                        ActionButton("Aleatório", Icons.Rounded.Shuffle, { onPlayRandom(shown) }, Modifier.weight(1f), enabled = shown.isNotEmpty())
                    }
                    Spacer(Modifier.height(UiTokens.sectionSpacing))
                    SongListContainer(state = state, modifier = Modifier.fillMaxWidth().weight(1f)) {
                        items(shown, key = { it.id }) { t -> SongItem(t, { onPlay(shown, shown.indexOfFirst { it.id == t.id }.coerceAtLeast(0)) }, { onEdit(t) }, { onShare(t) }, {}, {}, {}, {}, onRemoveFromPlaylist = null, isCurrent = t.id == currentTrackId) }
                    }
                }
            }
        }
    }

private fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "Sistema"
    ThemeMode.LIGHT -> "Claro"
    ThemeMode.DARK -> "Escuro"
}

@Composable
private fun ThemeModeDialog(current: ThemeMode, onPick: (ThemeMode) -> Unit, onDismiss: () -> Unit) {
    AppDialog(title = "Tema", onDismiss = onDismiss) {
        ThemeMode.entries.forEach { mode ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(mode) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = mode == current, onClick = { onPick(mode) })
                Text(themeModeLabel(mode), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun SettingsTab(settings: AppSettings, onChange: (AppSettings) -> Unit, downloadFolderLabel: String, onChooseDownloadFolder: (() -> Unit)?, onReleaseDownloadFolder: (() -> Unit)?, appVersion: String, onOrganizeFolder: (() -> Unit)? = null, onCleanFolder: (() -> Unit)? = null, permissions: List<dev.truetagger.model.PermissionItem> = emptyList(), onCheckUpdate: (() -> Unit)? = null) {
    val context = LocalContext.current
    val state = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    PersistListScroll(state, "settings")
    var confirmReleaseFolder by remember { mutableStateOf(false) }
    var themeDialog by remember { mutableStateOf(false) }
    var confirmResetStats by remember { mutableStateOf(false) }
    val settingsScope = rememberCoroutineScope()

    if (confirmReleaseFolder) {
        AppDialog(
            title = "Liberar a pasta?",
            onDismiss = { confirmReleaseFolder = false },
            confirmText = "Liberar",
            onConfirm = { confirmReleaseFolder = false; onReleaseDownloadFolder?.invoke() },
        ) {
            Text("A biblioteca some da tela até você escolher a pasta de novo. Nenhuma música é apagada.")
        }
    }
    if (themeDialog) {
        ThemeModeDialog(
            current = settings.themeMode,
            onPick = { themeDialog = false; onChange(settings.copy(themeMode = it)) },
            onDismiss = { themeDialog = false },
        )
    }
    if (confirmResetStats) {
        StatsResetDialog(
            onConfirm = { confirmResetStats = false; settingsScope.launch { PlayStatsState.reset(context) } },
            onDismiss = { confirmResetStats = false },
        )
    }

    val hasFolder = downloadFolderLabel.isNotBlank()
    val missingPermissions = permissions.count { !it.granted }

    // Permissões que faltam aparecem no topo (é o que impede algo de funcionar); com tudo certo, vão para o fim.
    val permissionsGroup: @Composable () -> Unit = {
        SettingsGroup(if (missingPermissions > 0) "Permissões • faltam $missingPermissions" else "Permissões") {
            permissions.forEachIndexed { index, item ->
                if (index > 0) SettingsDivider()
                SettingsRow(
                    icon = if (item.granted) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                    title = item.label,
                    subtitle = item.description.ifBlank { null },
                    trailing = { if (item.granted) RowHint("OK") else RowHint("Conceder", highlighted = true) },
                    onClick = if (item.granted) null else item.onGrant,
                )
            }
        }
    }

    StandardPage {
        ScreenHeaderRow(title = "Configurações")
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = state,
                modifier = Modifier.fillMaxSize().songListSpace(),
                contentPadding = PaddingValues(bottom = UiTokens.songListBottomPadding),
            ) {
                if (missingPermissions > 0) item { permissionsGroup() }
                item {
                    SettingsGroup("Aparência") {
                        SettingsRow(
                            Icons.Rounded.Palette, "Tema", "Claro, escuro ou o mesmo do celular",
                            trailing = { RowHint(themeModeLabel(settings.themeMode)) },
                        ) { themeDialog = true }
                    }
                }
                item {
                    SettingsGroup("Biblioteca") {
                        SettingsRow(
                            Icons.Rounded.Folder, "Pasta de músicas", downloadFolderLabel.ifBlank { "Nenhuma escolhida" },
                            trailing = { RowHint(if (hasFolder) "Trocar" else "Escolher", highlighted = !hasFolder) },
                        ) { onChooseDownloadFolder?.invoke() }
                        SettingsDivider()
                        SettingsSwitchRow(
                            Icons.Rounded.Sync, "Atualizar sozinha", "Lê a pasta de novo quando algo muda",
                            settings.autoRefreshLibrary,
                        ) { onChange(settings.copy(autoRefreshLibrary = it)) }
                        SettingsDivider()
                        SettingsRow(
                            Icons.Rounded.DriveFileMove, "Organizar pasta", "Põe músicas em songs e playlists em playlists",
                            enabled = hasFolder,
                        ) { onOrganizeFolder?.invoke() }
                        SettingsDivider()
                        SettingsRow(
                            Icons.Rounded.CleaningServices, "Limpar pasta", "Move o que não é música para a _lixeira",
                            enabled = hasFolder,
                        ) { onCleanFolder?.invoke() }
                        SettingsDivider()
                        SettingsRow(
                            Icons.Rounded.FolderOff, "Liberar pasta", "Solta a pasta para renomear ou apagar",
                            enabled = hasFolder,
                        ) { confirmReleaseFolder = true }
                    }
                }
                item {
                    SettingsGroup("Downloads") {
                        SettingsSwitchRow(
                            Icons.Rounded.AutoFixHigh, "Corrigir tags", "Busca título, artista e capa depois de baixar",
                            settings.autoTag,
                        ) { onChange(settings.copy(autoTag = it)) }
                        SettingsDivider()
                        SettingsSwitchRow(
                            Icons.Rounded.Image, "Capa do vídeo", "Usa a imagem do vídeo quando falta capa",
                            settings.embedYoutubeThumbnail,
                        ) { onChange(settings.copy(embedYoutubeThumbnail = it)) }
                        SettingsDivider()
                        SettingsSwitchRow(
                            Icons.Rounded.Download, "Atualizar o baixador", "Mantém os downloads funcionando",
                            settings.autoUpdateDownloader,
                        ) { onChange(settings.copy(autoUpdateDownloader = it)) }
                    }
                }
                item { GitSection() }
                item {
                    SettingsGroup("Estatísticas") {
                        SettingsRow(
                            Icons.Rounded.Delete, "Zerar estatísticas", "Apaga tempo ouvido e vezes tocadas",
                            destructive = true,
                        ) { confirmResetStats = true }
                    }
                }
                if (missingPermissions == 0 && permissions.isNotEmpty()) item { permissionsGroup() }
                item {
                    SettingsGroup("Atualizações") {
                        SettingsSwitchRow(
                            Icons.Rounded.Notifications, "Avisar sobre versão nova", "Confere sozinho e avisa por notificação",
                            settings.autoCheckUpdates,
                        ) { onChange(settings.copy(autoCheckUpdates = it)) }
                        SettingsDivider()
                        val lastBackground = remember { UpdateManager.lastBackgroundCheckMs(context) }
                        SettingsRow(
                            Icons.Rounded.SystemUpdate, "Procurar atualização",
                            "Versão $appVersion • " + if (lastBackground > 0) {
                                "conferiu sozinho " + android.text.format.DateUtils.getRelativeTimeSpanString(
                                    lastBackground, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
                                )
                            } else {
                                "ainda não conferiu sozinho"
                            },
                        ) { onCheckUpdate?.invoke() }
                    }
                }
                item {
                    Text(
                        "TruePlayer $appVersion",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = UiTokens.space4),
                    )
                }
            }
            TouchScrollbar(state, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = UiTokens.scrollbarInset))
        }
    }
}


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
    val thumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
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
        drawRoundRect(thumbColor, Offset(thumbX, top + y), androidx.compose.ui.geometry.Size(thumbW, thumbH), CornerRadius(thumbW / 2f, thumbW / 2f))
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
    val thumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
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
        drawRoundRect(thumbColor, Offset(thumbX, top + y), androidx.compose.ui.geometry.Size(thumbW, thumbH), CornerRadius(thumbW / 2f, thumbW / 2f))
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
/** Artista principal, sem "feat.", vírgulas ou "&": serve para juntar as faixas de um mesmo álbum. */
private fun primaryArtist(name: String): String =
    name.lowercase().split(" feat", " ft.", ",", " & ", ";", " x ").first().trim()

/** Músicas do mesmo álbum que [sel]: mesmo nome e mesmo artista principal (ou artista do álbum). */
private fun sameAlbumTracks(all: List<AudioTrack>, sel: AudioTrack): List<AudioTrack> {
    if (sel.album.isBlank()) return emptyList()
    val owners = listOf(sel.artist, sel.albumArtist).map(::primaryArtist).filter { it.isNotBlank() }.toSet()
    return all.filter { other ->
        other.album.equals(sel.album, ignoreCase = true) &&
            (owners.isEmpty() || primaryArtist(other.artist) in owners || other.id == sel.id)
    }
}

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
