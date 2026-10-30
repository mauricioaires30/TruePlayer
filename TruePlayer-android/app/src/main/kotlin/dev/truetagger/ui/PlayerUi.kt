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
import dev.truetagger.data.StatsMetric
import dev.truetagger.data.formatDuration
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
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.animateColorAsState
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
import dev.truetagger.data.AppLanguages
import dev.truetagger.data.ThemeMode
import androidx.annotation.StringRes
import dev.truetagger.android.AppText
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
import dev.truetagger.android.t
import dev.truetagger.android.tp



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

private enum class HomeTab(@param:StringRes val labelRes: Int) {
    LIBRARY(R.string.biblioteca), MOST_PLAYED(R.string.playlists), PLAYLISTS(R.string.playlists), ARTISTS(R.string.artistas), ALBUMS(R.string.albuns_2), GENRES(R.string.generos), SETTINGS(R.string.config_short);
    val label: String get() = t(labelRes)
}
private enum class PlayerScreen { NONE, FULL, QUEUE }
private enum class Detail { NONE, ARTIST, ALBUM, GENRE, PLAYLIST }
private enum class PlayerSortMode(@param:StringRes val labelRes: Int) {
    NAME(R.string.nome), TITLE(R.string.titulo), ARTIST(R.string.artista), ALBUM(R.string.album), DATE(R.string.sort_date), DURATION(R.string.duracao);
    val label: String get() = t(labelRes)
}
private enum class PlaylistSortMode(@param:StringRes val labelRes: Int) {
    MANUAL(R.string.sort_manual), TITLE(R.string.titulo), ARTIST(R.string.artista), ALBUM(R.string.album), DATE(R.string.sort_date), DURATION(R.string.duracao);
    val label: String get() = t(labelRes)
}
private enum class AlbumSortMode(@param:StringRes val labelRes: Int) {
    NAME(R.string.nome), ARTIST(R.string.artista), YEAR(R.string.sort_year), DURATION(R.string.duracao), SONGS(R.string.sort_song_count);
    val label: String get() = t(labelRes)
}
private enum class MostPlayedMode(@param:StringRes val labelRes: Int) {
    OVERVIEW(R.string.playlists), TRACKS(R.string.songs_title), ALBUMS(R.string.albuns_2), ARTISTS(R.string.artistas), PLAYLISTS(R.string.playlists);
    val label: String get() = t(labelRes)
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

    fun topTracks(tracks: List<AudioTrack>, range: StatsRange, metric: StatsMetric, limit: Int = MOST_PLAYED_LIMIT): List<Pair<AudioTrack, StatValue>> {
        val values = valuesFor(tracks, range)
        return tracks.asSequence()
            .map { it to (values[it.id] ?: StatValue()) }
            .filter { metric.counts(it.second) }
            .sortedWith(metric.order({ it.second }, { it.first.displayTitle() }))
            .take(limit)
            .toList()
    }

    fun topAlbums(tracks: List<AudioTrack>, range: StatsRange, metric: StatsMetric, limit: Int = MOST_PLAYED_LIMIT): List<Triple<String, String, StatValue>> {
        val values = valuesFor(tracks, range)
        return tracks.asSequence()
            .filter { it.album.isNotBlank() }
            .groupBy { Normalizer.normalize(it.album, Normalizer.Form.NFKC).trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT) }
            .mapNotNull { (_, group) ->
                val total = group.fold(StatValue()) { acc, t -> acc + (values[t.id] ?: StatValue()) }
                if (!metric.counts(total)) return@mapNotNull null
                val first = group.first()
                Triple(first.album, first.albumArtist.ifBlank { first.artist }, total)
            }
            .sortedWith(metric.order({ it.third }, { it.first }))
            .take(limit)
            .toList()
    }

    fun topArtists(tracks: List<AudioTrack>, range: StatsRange, metric: StatsMetric, limit: Int = MOST_PLAYED_LIMIT): List<Pair<String, StatValue>> {
        val values = valuesFor(tracks, range)
        return tracks.asSequence()
            .filter { it.artist.isNotBlank() && it.artist != "<unknown>" }
            .groupBy { Normalizer.normalize(it.artist, Normalizer.Form.NFKC).trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT) }
            .mapNotNull { (_, group) ->
                val total = group.fold(StatValue()) { acc, t -> acc + (values[t.id] ?: StatValue()) }
                if (!metric.counts(total)) null else (group.first().artist to total)
            }
            .sortedWith(metric.order({ it.second }, { it.first }))
            .take(limit)
            .toList()
    }
}

private const val MOST_PLAYED_LIMIT = 100

private fun StatValue.minutesText(): String = when {
    ms <= 0L -> "—"
    ms < 60_000L -> t(R.string.less_than_1_min)
    else -> t(R.string.n_min, NumberFormat.getIntegerInstance(AppText.context().resources.configuration.locales[0]).format(minutes))
}

/** Só a medida escolhida: "25 min" ou "12 vezes". */
private fun StatValue.text(metric: StatsMetric): String =
    if (metric == StatsMetric.MINUTES) minutesText() else tp(R.plurals.n_plays, plays.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatsMetricToggle(metric: StatsMetric, onChange: (StatsMetric) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = UiTokens.space2)) {
        StatsMetric.entries.forEachIndexed { index, m ->
            SegmentedButton(
                selected = m == metric,
                onClick = { onChange(m) },
                shape = SegmentedButtonDefaults.itemShape(index, StatsMetric.entries.size),
                label = { Text(t(if (m == StatsMetric.MINUTES) R.string.stats_minutes else R.string.stats_plays), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
private fun StatsRangeChips(range: StatsRange, onChange: (StatsRange) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = UiTokens.sectionSpacing),
        horizontalArrangement = Arrangement.spacedBy(UiTokens.toolbarGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatsRange.entries.forEach { r ->
            FilterChip(
                selected = r == range,
                onClick = { onChange(r) },
                label = { Text(rangeLabel(r)) },
                shape = RoundedCornerShape(UiTokens.buttonRadius),
                border = null,
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    labelColor = MaterialTheme.colorScheme.onSurface,
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        }
    }
}

@Composable
private fun StatsResetDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AppDialog(
        title = t(R.string.zerar_estatisticas),
        onDismiss = onDismiss,
        confirmText = t(R.string.zerar),
        onConfirm = onConfirm,
        destructive = true,
    ) {
        Text(t(R.string.apaga_o_tempo_ouvido_e))
        DialogNote(t(R.string.uma_copia_fica_guardada_em))
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
            // Só o logo, sem o quadrado preto do ícone da tela inicial: serve nos temas claro e escuro.
            Image(
                painter = painterResource(R.drawable.trueplayer_logo),
                contentDescription = "TruePlayer",
                modifier = Modifier.size(112.dp),
                contentScale = ContentScale.Fit,
            )
            Text(
                text = "TruePlayer",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = t(R.string.carregando_app),
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
    remember { ScrollMemory.beginSession(context); true }
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
        }, t(R.string.compartilhar_musica)))
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
                                    HomeTab.PLAYLISTS -> Icons.AutoMirrored.Rounded.QueueMusic
                                    HomeTab.ARTISTS -> Icons.Rounded.Person
                                    HomeTab.ALBUMS -> Icons.Rounded.Album
                                    HomeTab.GENRES -> Icons.Rounded.Category
                                    HomeTab.MOST_PLAYED -> Icons.AutoMirrored.Rounded.QueueMusic
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
          // Sem o espaço de baixo: o mini player cuida dele e a cor dele desce até a borda, como a barra de abas.
          Box(Modifier.fillMaxSize().windowInsetsPadding(AppInsets.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
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
    /** Fora do Scaffold (fila): deixa o espaço da barra de navegação do Android embaixo do cartão. */
    extendBehindNavBar: Boolean = false,
) {
    val context = LocalContext.current
    val tint = rememberCoverTint(s.current?.artworkBytes, s.current?.id)
    val card by animateColorAsState(tint?.let { lerp(it, Color.Black, 0.42f) } ?: TruePlayerDarkScheme.surfaceVariant, label = "miniTint")
    // Cartão flutuante na cor da capa, como no Spotify. Por dentro é sempre escuro, com texto claro.
    TruePlayerTheme(ThemeMode.DARK) {
        val accent = MaterialTheme.colorScheme.primary
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (extendBehindNavBar) Modifier.navigationBarsPadding() else Modifier)
                .padding(horizontal = UiTokens.space2, vertical = UiTokens.space1),
        ) {
            Surface(color = card, shape = RoundedCornerShape(UiTokens.space3), modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(UiTokens.miniPlayerHeight)
                        .clickable(onClick = onOpen),
                ) {
                    // Linha de progresso no topo, com os tempos nas pontas: anda com a música e dá para tocar ou
                    // arrastar nela para mudar o ponto da música sem abrir o player grande.
                    SeekableWavyBar(
                        s = s,
                        onSeek = { f -> MusicPlaybackService.command(context, MusicPlaybackService.ACTION_SEEK, (f * s.durationMs).toLong()) },
                        modifier = Modifier.fillMaxWidth().height(20.dp).padding(horizontal = UiTokens.space3),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(start = UiTokens.space3, end = UiTokens.space2),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Esquerda: capa e informações da música (nome grande rola em loop).
                        key(s.current?.id) {
                            PlayerArtworkBox(
                                s.current?.artworkBytes,
                                Modifier.size(UiTokens.miniPlayerArtworkSize),
                                s.current?.id,
                                maxDimension = 256,
                                corner = 4.dp,
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f).padding(horizontal = UiTokens.compactPadding),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            ScrollingText(
                                s.current?.displayTitle().orEmpty(),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            // Álbum em cima, artista embaixo; cada linha rola sozinha se o nome for grande.
                            subtitleLines(s.current?.album, s.current?.artist).forEach { line ->
                                ScrollingText(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f))
                            }
                        }
                        // Direita, em duas linhas: [anterior] [tocar/pausar] [próxima] em cima; embaixo, na mesma ordem,
                        // [aleatório] [fila] [repetir].
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = onPrevious, modifier = Modifier.size(MINI_SIDE_BUTTON, MINI_MAIN_BUTTON)) {
                                    Icon(Icons.Rounded.SkipPrevious, t(R.string.anterior), Modifier.size(28.dp))
                                }
                                FilledIconButton(
                                    onClick = onToggle,
                                    modifier = Modifier.size(MINI_MAIN_BUTTON),
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.onSurface,
                                        contentColor = MaterialTheme.colorScheme.background,
                                    ),
                                ) {
                                    Icon(if (s.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (s.playing) t(R.string.pausar) else t(R.string.reproduzir), Modifier.size(30.dp))
                                }
                                IconButton(onClick = onNext, modifier = Modifier.size(MINI_SIDE_BUTTON, MINI_MAIN_BUTTON)) {
                                    Icon(Icons.Rounded.SkipNext, t(R.string.proxima), Modifier.size(28.dp))
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = onShuffle, modifier = Modifier.size(MINI_SIDE_BUTTON, MINI_SMALL_BUTTON)) {
                                    Icon(
                                        painterResource(if (s.shuffle) R.drawable.truetagger_shuffle_on else R.drawable.truetagger_shuffle_off),
                                        t(R.string.aleatorio), modifier = Modifier.size(22.dp), tint = if (s.shuffle) accent else LocalContentColor.current,
                                    )
                                }
                                // No meio, o botão da fila (debaixo do tocar/pausar); sem ele, o espaço fica vazio.
                                if (onQueue != null) {
                                    IconButton(onClick = onQueue, modifier = Modifier.size(MINI_MAIN_BUTTON, MINI_SMALL_BUTTON)) {
                                        Icon(Icons.AutoMirrored.Rounded.QueueMusic, t(R.string.ver_fila), modifier = Modifier.size(22.dp))
                                    }
                                } else {
                                    Spacer(Modifier.width(MINI_MAIN_BUTTON))
                                }
                                IconButton(onClick = onRepeat, modifier = Modifier.size(MINI_SIDE_BUTTON, MINI_SMALL_BUTTON)) {
                                    Icon(
                                        painterResource(if (s.repeat == RepeatMode.ONE) R.drawable.truetagger_repeat_on else R.drawable.truetagger_repeat_off),
                                        t(R.string.repeticao), modifier = Modifier.size(22.dp), tint = if (s.repeat != RepeatMode.OFF) accent else LocalContentColor.current,
                                    )
                                }
                            }
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
    val tint = rememberCoverTint(s.current?.artworkBytes, s.current?.id)
    val top by animateColorAsState(tint?.let { lerp(it, Color.Black, 0.30f) } ?: TruePlayerDarkScheme.surfaceVariant, label = "playerTint")
    // Como o "Tocando agora" do Spotify: fundo que sai da cor da capa e escurece para baixo, sempre com texto claro.
    TruePlayerTheme(ThemeMode.DARK) {
        val bg = MaterialTheme.colorScheme.background
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to top, 0.7f to bg, 1f to bg))) {
            Column(Modifier.fillMaxSize().padding(horizontal = UiTokens.space5, vertical = UiTokens.screenVerticalPadding)) {
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose, modifier = Modifier.size(UiTokens.iconButtonSize)) {
                        Icon(Icons.Rounded.KeyboardArrowDown, t(R.string.voltar), Modifier.size(30.dp))
                    }
                    Text(
                        t(R.string.reproduzindo),
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.size(UiTokens.iconButtonSize))
                }
                // Capa, nomes, barra e botões ficam juntos no meio do espaço que sobra.
                Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center) {
                    key(s.current?.id) {
                        PlayerArtworkBox(
                            s.current?.artworkBytes,
                            Modifier.fillMaxWidth().weight(1f, fill = false).aspectRatio(1f).shadow(24.dp, RoundedCornerShape(8.dp)),
                            s.current?.id,
                            maxDimension = 1024,
                            corner = 8.dp,
                        )
                    }
                    Spacer(Modifier.height(UiTokens.space6))
                    ScrollingText(
                        s.current?.displayTitle().orEmpty(),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Álbum e artista logo abaixo do título, cada um tocável (abre a tela dele) e com rolagem se for grande.
                    val album = s.current?.album.orEmpty()
                    val artist = s.current?.artist.orEmpty()
                    if (album.isNotBlank()) PlayerInfoLink(album, MaterialTheme.colorScheme.primary, onAlbum, Modifier.padding(top = 2.dp))
                    if (artist.isNotBlank()) PlayerInfoLink(artist, MaterialTheme.colorScheme.primary, onArtist)
                    Spacer(Modifier.height(UiTokens.space3))
                    // O Slider cuida do toque e da acessibilidade; o trilho e a bolinha são desenhados por nós.
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
                    LtrContent {
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
                            thumb = { Spacer(Modifier.size(12.dp, 32.dp)) },
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
                        Row(Modifier.fillMaxWidth().padding(horizontal = UiTokens.space1), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatDuration(s.positionMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formatDuration(s.durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().padding(vertical = UiTokens.space2)) {
                        IconButton(onClick = { MusicPlaybackService.command(context, MusicPlaybackService.ACTION_SHUFFLE) }, modifier = Modifier.size(UiTokens.iconButtonSize)) { Icon(painterResource(if (s.shuffle) dev.truetagger.android.R.drawable.truetagger_shuffle_on else dev.truetagger.android.R.drawable.truetagger_shuffle_off), t(R.string.aleatorio), modifier = Modifier.size(UiTokens.primaryIconSize + 4.dp), tint = if (s.shuffle) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
                        IconButton(onClick = { MusicPlaybackService.command(context, MusicPlaybackService.ACTION_PREVIOUS) }, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipPrevious, t(R.string.anterior), Modifier.size(40.dp)) }
                        FilledIconButton(
                            onClick = { MusicPlaybackService.command(context, MusicPlaybackService.ACTION_TOGGLE) },
                            modifier = Modifier.size(72.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.background),
                        ) { Icon(if (s.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (s.playing) t(R.string.pausar) else t(R.string.reproduzir), Modifier.size(40.dp)) }
                        IconButton(onClick = { MusicPlaybackService.command(context, MusicPlaybackService.ACTION_NEXT) }, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipNext, t(R.string.proxima), Modifier.size(40.dp)) }
                        IconButton(onClick = { MusicPlaybackService.command(context, MusicPlaybackService.ACTION_REPEAT) }, modifier = Modifier.size(UiTokens.iconButtonSize)) { Icon(painterResource(if (s.repeat == RepeatMode.ONE) dev.truetagger.android.R.drawable.truetagger_repeat_on else dev.truetagger.android.R.drawable.truetagger_repeat_off), t(R.string.repeticao), modifier = Modifier.size(UiTokens.primaryIconSize + 4.dp), tint = if (s.repeat != RepeatMode.OFF) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
                    }
                }
                // Fila embaixo, à direita, como no Spotify.
                Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onQueue) {
                        Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, Modifier.size(UiTokens.primaryIconSize + 2.dp))
                        Spacer(Modifier.width(UiTokens.space2))
                        Text(t(R.string.abrir_fila))
                    }
                }
            }
        }
    }
}

/** Nome do álbum ou do artista no player completo: uma linha tocável, com rolagem se for grande. */
@Composable
private fun PlayerInfoLink(text: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ScrollingText(
        text,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(UiTokens.space1))
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(vertical = 3.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = color,
    )
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
            title = t(R.string.fila_atual),
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
                        onEdit = {}, onShare = {}, onAddToPlaylist = {}, onAlbum = null, onArtist = null, onGenre = {},
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
                extendBehindNavBar = true,
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
            } catch (error: SecurityException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && error is RecoverableSecurityException) {
                    recoverableAction = error.userAction.actionIntent.intentSender
                    break
                }
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
            deleteLauncher.launch(IntentSenderRequest.Builder(recoverableAction).build())
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
            title = t(R.string.biblioteca),
            modifier = Modifier.padding(
                horizontal = UiTokens.screenHorizontalPadding,
                vertical = UiTokens.screenVerticalPadding,
            ),
        ) {
            IconButton(onClick = refresh, modifier = Modifier.size(UiTokens.iconButtonSize)) {
                Icon(Icons.Rounded.Refresh, t(R.string.atualizar), modifier = Modifier.size(UiTokens.primaryIconSize))
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
            title = t(R.string.excluir, tp(R.plurals.n_musicas, selectedIds.size)),
            onDismiss = { showDeleteConfirm = false },
            confirmText = t(R.string.excluir_2),
            onConfirm = { performDelete() },
            destructive = true,
        ) {
            Text(t(R.string.apaga_do_celular_nao_da))
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
            SearchBar(value = librarySearch, onValueChange = onLibrarySearchChange, placeholder = t(R.string.pesquisar_musicas), modifier = Modifier.weight(1f))
            SortMenuButton(
                open = sortOpen,
                onOpenChange = onSortOpenChange,
                title = t(R.string.ordenar_por),
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
                    contentDescription = if (selectionMode) t(R.string.encerrar_selecao) else t(R.string.selecionar),
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
                ActionButton(t(R.string.remover), Icons.Rounded.Delete, onDeleteClick, Modifier.weight(1f), enabled = selectedIds.isNotEmpty(), contentColor = MaterialTheme.colorScheme.error)
                ActionButton(t(R.string.playlist), Icons.AutoMirrored.Rounded.PlaylistAdd, onPlaylistClick, Modifier.weight(1f), enabled = selectedIds.isNotEmpty())
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(top = UiTokens.space1), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (shown.isNotEmpty()) tp(R.plurals.n_musicas, shown.size) else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(start = UiTokens.space1),
                )
                PlayControls(shown.isNotEmpty(), onPlayRandom, onPlayAll)
            }
        }
        if (shown.isEmpty()) {
            EmptyState(
                if (librarySearch.isBlank()) Icons.Rounded.LibraryMusic else Icons.Rounded.Search,
                if (librarySearch.isBlank()) t(R.string.nenhuma_musica) else t(R.string.nada_encontrado),
                if (librarySearch.isBlank()) t(R.string.escolha_a_pasta_de_musicas) else t(R.string.tente_outro_nome_de_musica),
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
            Icon(Icons.Rounded.FilterAlt, t(R.string.filtros), modifier = Modifier.size(UiTokens.primaryIconSize))
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
                    text = { Text(if (ascending) t(R.string.crescente) else t(R.string.decrescente)) },
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
    var metric by rememberSaveable { mutableStateOf(StatsMetric.MINUTES) }
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
    val liveTopTrack = remember(statsRevision, tracks, range, metric) { store.topTracks(tracks, range, metric, 1).firstOrNull() }
    val liveTopAlbum = remember(statsRevision, tracks, range, metric) { store.topAlbums(tracks, range, metric, 1).firstOrNull() }
    val liveTopArtist = remember(statsRevision, tracks, range, metric) { store.topArtists(tracks, range, metric, 1).firstOrNull() }

    BackHandler(enabled = mode != MostPlayedMode.OVERVIEW) {
        mode = MostPlayedMode.OVERVIEW
    }

    StandardPage {
        Column(Modifier.fillMaxSize()) {
            ScreenHeaderRow(
                title = if (mode == MostPlayedMode.OVERVIEW) t(R.string.playlists) else t(R.string.mais_ouvidas),
                onBack = if (mode != MostPlayedMode.OVERVIEW) ({ mode = MostPlayedMode.OVERVIEW }) else null,
            )
            Spacer(Modifier.height(UiTokens.listHeaderGap))

            if (mode == MostPlayedMode.OVERVIEW) {
                val state = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
                PersistListScroll(state, "playlists_home")
                Column(Modifier.fillMaxSize()) {
                    ActionButtonRow {
                        ActionButton(t(R.string.criar), Icons.Rounded.Add, { creating = true; createName = "" }, Modifier.weight(1f))
                        ActionButton(t(R.string.importar), Icons.Rounded.FileOpen, onImport, Modifier.weight(1f))
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
                                        ColorTile(playlist.name, UiTokens.songItemArtworkSize, Icons.AutoMirrored.Rounded.QueueMusic)
                                        Column(Modifier.weight(1f).padding(horizontal = UiTokens.cardItemHorizontalPadding), verticalArrangement = Arrangement.spacedBy(UiTokens.songItemSpacing)) {
                                            ScrollingText(playlist.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                            Text(tp(R.plurals.n_musicas, playlist.trackIds.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        Box {
                                            IconButton(onClick = { expanded = true }, modifier = Modifier.size(UiTokens.iconButtonSize)) {
                                                Icon(Icons.Rounded.MoreVert, t(R.string.opcoes), modifier = Modifier.size(UiTokens.primaryIconSize))
                                            }
                                            DropdownMenu(expanded, { expanded = false }) {
                                                DropdownMenuItem({ Text(t(R.string.exportar_m3u)) }, onClick = { expanded = false; onExport(playlist) })
                                                DropdownMenuItem({ Text(t(R.string.renomear)) }, onClick = { expanded = false; renameText = playlist.name; renaming = playlist })
                                                DropdownMenuItem({ Text(t(R.string.excluir_2)) }, onClick = { expanded = false; onDelete(playlist.name) })
                                            }
                                        }
                                    }
                                }
                            } else {
                                item { EmptyState(Icons.AutoMirrored.Rounded.QueueMusic, t(R.string.nenhuma_playlist), t(R.string.crie_uma_na_aba_playlists)) }
                            }

                            item {
                                HorizontalDivider(Modifier.padding(vertical = UiTokens.sectionSpacing))
                            }
                            item {
                                Text(t(R.string.mais_ouvidas_2), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = UiTokens.space2))
                            }
                            item { StatsMetricToggle(metric) { metric = it } }
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
                                        title = t(R.string.musicas),
                                        subtitle = liveTopTrack?.let { "${it.first.displayTitle()} • ${it.second.text(metric)}" },
                                        artwork = topTrack?.artworkBytes,
                                        artworkUri = topTrack?.id,
                                        modifier = Modifier.weight(1f),
                                        onClick = { mode = MostPlayedMode.TRACKS },
                                    )
                                    MostPlayedCategoryCard(
                                        title = t(R.string.albuns),
                                        subtitle = liveTopAlbum?.let { "${it.first} • ${it.third.text(metric)}" },
                                        artwork = topAlbumTrack?.artworkBytes,
                                        artworkUri = topAlbumTrack?.id,
                                        modifier = Modifier.weight(1f),
                                        onClick = { mode = MostPlayedMode.ALBUMS },
                                    )
                                        MostPlayedCategoryCard(
                                            title = t(R.string.artistas_caps),
                                            subtitle = liveTopArtist?.let { "${it.first} • ${it.second.text(metric)}" },
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
                StatsMetricToggle(metric) { metric = it }
                StatsRangeChips(range) { range = it }
                // A ordem muda com a medida e o período: volta para o topo da lista.
                LaunchedEffect(metric, range) { if (state.firstVisibleItemIndex > 0) state.scrollToItem(0) }
                when (mode) {
                    MostPlayedMode.TRACKS -> {
                        val values = remember(statsRevision, tracks, range, metric) { store.topTracks(tracks, range, metric) }
                        if (values.isEmpty()) EmptyMostPlayed() else SongListContainer(state = state, modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(values, key = { _, pair -> pair.first.id }) { index, (track, value) ->
                                MostPlayedRow(
                                    index + 1,
                                    track.displayTitle(),
                                    track.artist,
                                    track.album,
                                    value.text(metric),
                                    track.artworkBytes,
                                    track.id,
                                ) { play(listOf(track), 0) }
                            }
                        }
                    }
                    MostPlayedMode.ALBUMS -> {
                        val values = remember(statsRevision, tracks, range, metric) { store.topAlbums(tracks, range, metric) }
                        if (values.isEmpty()) EmptyMostPlayed() else SongListContainer(state = state, modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(values, key = { _, item -> item.first + "\u0000" + item.second }) { index, (album, artist, value) ->
                                val artworkTrack = tracks.firstOrNull { it.album.equals(album, true) && it.artworkBytes?.isNotEmpty() == true }
                                    ?: tracks.firstOrNull { it.album.equals(album, true) }
                                MostPlayedRow(
                                    index + 1,
                                    album,
                                    artist,
                                    "",
                                    value.text(metric),
                                    artworkTrack?.artworkBytes,
                                    artworkTrack?.id,
                                ) { onAlbum(album) }
                            }
                        }
                    }
                    MostPlayedMode.ARTISTS -> {
                        val values = remember(statsRevision, tracks, range, metric) { store.topArtists(tracks, range, metric) }
                        if (values.isEmpty()) EmptyMostPlayed() else SongListContainer(state = state, modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(values, key = { _, item -> item.first }) { index, (artist, value) ->
                                MostPlayedRow(
                                    index + 1,
                                    artist,
                                    "",
                                    "",
                                    value.text(metric),
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
        title = { Text(t(R.string.nova_playlist)) },
        text = { OutlinedTextField(value = createName, onValueChange = { createName = it }, label = { Text(t(R.string.nome)) }, singleLine = true) },
        confirmButton = { TextButton(enabled = createName.isNotBlank(), onClick = { onCreate(createName.trim()); creating = false }) { Text(t(R.string.criar)) } },
        dismissButton = { TextButton(onClick = { creating = false }) { Text(t(R.string.cancelar)) } },
    )
    renaming?.let { playlist ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(t(R.string.renomear_playlist)) },
            text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true) },
            confirmButton = { TextButton(enabled = renameText.isNotBlank(), onClick = { onRename(playlist.name, renameText.trim()); renaming = null }) { Text(t(R.string.salvar)) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(t(R.string.cancelar)) } },
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
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
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
        EmptyState(Icons.Rounded.MusicNote, t(R.string.nada_tocado_ainda), t(R.string.as_musicas_que_voce_mais))
    }
}

@Composable
private fun MostPlayedRow(rank: Int, title: String, artist: String, album: String, value: String, artwork: ByteArray?, artworkUri: String? = null, artistImageName: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = UiTokens.mostPlayedRowHeight)
            .clip(RoundedCornerShape(UiTokens.songItemRadius))
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
        Text(value, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, maxLines = 1)
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
            contentDescription = if (selected) t(R.string.desselecionar) else t(R.string.selecionar),
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
    onAlbum: (() -> Unit)?,
    onArtist: (() -> Unit)?,
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
            // Linha plana, sem borda: só a música atual (e a destacada) ganha um tom suave de fundo.
            .background(
                when {
                    isCurrent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    emphasizedBorder -> MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
                    else -> Color.Transparent
                }
            )
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
                    Icon(Icons.Rounded.KeyboardArrowUp, t(R.string.subir_musica), modifier = Modifier.size(UiTokens.primaryIconSize))
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(UiTokens.smallIconButtonSize)) {
                    Icon(Icons.Rounded.KeyboardArrowDown, t(R.string.abaixar_musica), modifier = Modifier.size(UiTokens.primaryIconSize))
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
                Icon(Icons.Rounded.MoreVert, t(R.string.opcoes), modifier = Modifier.size(UiTokens.primaryIconSize))
            }
            DropdownMenu(expanded, { expanded = false }) {
                DropdownMenuItem({ Text(t(R.string.informacoes)) }, leadingIcon = { Icon(Icons.Rounded.Info, null) }, onClick = { expanded = false; info = true })
                DropdownMenuItem({ Text(t(R.string.editar_tags)) }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { expanded = false; onEdit() })
                if (showAddToPlaylist) DropdownMenuItem({ Text(t(R.string.adicionar_a_playlist)) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, null) }, onClick = { expanded = false; onAddToPlaylist() })
                DropdownMenuItem({ Text(t(R.string.compartilhar)) }, leadingIcon = { Icon(Icons.Rounded.Share, null) }, onClick = { expanded = false; onShare() })
                onRemoveFromPlaylist?.let { remove ->
                    DropdownMenuItem({ Text(t(R.string.tirar_da_playlist)) }, leadingIcon = { Icon(Icons.Rounded.RemoveCircleOutline, null) }, onClick = { expanded = false; remove() })
                }
                if (onRemoveFromPlaylist == null && onDelete != null) {
                    DropdownMenuItem(
                        { Text(t(R.string.excluir_do_celular), color = MaterialTheme.colorScheme.error) },
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
private fun InfoDialog(listed: AudioTrack, onArtist: (() -> Unit)?, onAlbum: (() -> Unit)?, onDismiss: () -> Unit) {
    // A lista vem do MediaStore, que demora a refletir tags editadas (ano, gênero): a ficha confere no arquivo.
    val context = LocalContext.current
    val track by produceState(listed, listed.id) {
        val fromFile = withContext(Dispatchers.IO) {
            runCatching { dev.truetagger.android.AndroidAudioService(context).read(Uri.parse(listed.id)) }.getOrNull()
        }
        if (fromFile != null) {
            value = listed.copy(
                year = fromFile.year.ifBlank { listed.year },
                genre = fromFile.genre.ifBlank { listed.genre },
                trackNumber = fromFile.trackNumber.ifBlank { listed.trackNumber },
            )
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(track.displayTitle()) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                InfoRow(t(R.string.album), track.album, onAlbum)
                InfoRow(t(R.string.artista), track.artist, onArtist)
                InfoRow(t(R.string.sort_year), track.year)
                InfoRow(t(R.string.genero), track.genre)
                InfoRow(t(R.string.faixa), track.trackNumber)
                InfoRow(t(R.string.duracao), formatDuration(track.durationMs))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t(R.string.fechar)) } },
    )
}

/** Uma linha da ficha da música: rótulo apagado à esquerda, valor ao lado. Com [onClick], o valor abre a tela correspondente. */
@Composable
private fun InfoRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    val clickable = onClick != null && value.isNotBlank()
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (clickable) Modifier.clickable { onClick() } else Modifier)
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
    AlertDialog(onDismissRequest = onDismiss, title = { Text(t(R.string.adicionar_a_playlist)) }, text = {
        if (playlists.isEmpty()) Text(t(R.string.crie_uma_playlist_primeiro_na)) else Column {
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
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(t(R.string.cancelar)) } })
}

@Composable
private fun AddSelectedToPlaylistDialog(trackIds: List<String>, onDismiss: () -> Unit, onDone: (List<String>, List<String>) -> Unit) {
    var playlists by remember { mutableStateOf(emptyList<PlaylistData>()) }
    val context = LocalContext.current
    val store = remember { PlaylistStore(context) }
    LaunchedEffect(Unit) { playlists = store.load() }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(t(R.string.adicionar_a_playlist_2, tp(R.plurals.n_musicas, trackIds.size))) }, text = {
        if (playlists.isEmpty()) Text(t(R.string.crie_uma_playlist_primeiro_na)) else Column {
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
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(t(R.string.cancelar)) } })
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
                ScreenHeaderRow(title = t(R.string.playlists))
                Spacer(Modifier.height(UiTokens.listHeaderGap))
                ActionButtonRow {
                    ActionButton(t(R.string.criar), Icons.Rounded.Add, { creating = true; name = "" }, Modifier.weight(1f))
                    ActionButton(t(R.string.importar), Icons.Rounded.FileOpen, onImport, Modifier.weight(1f))
                }
                HorizontalDivider(Modifier.padding(vertical = UiTokens.sectionSpacing))
            }
            if (playlists.isEmpty()) item { EmptyState(Icons.AutoMirrored.Rounded.QueueMusic, t(R.string.nenhuma_playlist), t(R.string.toque_em_criar_ou_em)) }
            else items(playlists, key = { it.name }) { playlist ->
                var expanded by remember(playlist.name) { mutableStateOf(false) }
                ListCardRow(onClick = { onOpen(playlist.name) }) {
                    ColorTile(playlist.name, UiTokens.songItemArtworkSize, Icons.AutoMirrored.Rounded.QueueMusic)
                    Column(Modifier.weight(1f).padding(horizontal = UiTokens.cardItemHorizontalPadding), verticalArrangement = Arrangement.spacedBy(UiTokens.songItemSpacing)) {
                        ScrollingText(playlist.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(tp(R.plurals.n_musicas, playlist.trackIds.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box {
                        IconButton(onClick = { expanded = true }, modifier = Modifier.size(UiTokens.iconButtonSize)) {
                            Icon(Icons.Rounded.MoreVert, t(R.string.opcoes), modifier = Modifier.size(UiTokens.primaryIconSize))
                        }
                        DropdownMenu(expanded, { expanded = false }) {
                            DropdownMenuItem({ Text(t(R.string.exportar_m3u)) }, onClick = { expanded = false; onExport(playlist) })
                            DropdownMenuItem({ Text(t(R.string.renomear)) }, onClick = { expanded = false; renameText = playlist.name; renaming = playlist })
                            DropdownMenuItem({ Text(t(R.string.excluir_2)) }, onClick = { expanded = false; onDelete(playlist.name) })
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
        title = { Text(t(R.string.nova_playlist)) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(t(R.string.nome)) }) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name); creating = false }) { Text(t(R.string.criar)) } },
        dismissButton = { TextButton(onClick = { creating = false }) { Text(t(R.string.cancelar)) } },
    )
    renaming?.let { playlist ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(t(R.string.renomear_playlist)) },
            text = { OutlinedTextField(renameText, { renameText = it }, singleLine = true, label = { Text(t(R.string.nome)) }) },
            confirmButton = { TextButton(enabled = renameText.isNotBlank(), onClick = { onRename(playlist.name, renameText); renaming = null }) { Text(t(R.string.salvar)) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(t(R.string.cancelar)) } },
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
        ScreenHeaderRow(title = t(R.string.artistas))
        Spacer(Modifier.height(UiTokens.listHeaderGap))
        SearchBar(value = search, onValueChange = { search = it }, placeholder = t(R.string.pesquisar_artistas))
        if (artists.isNotEmpty()) {
            Text(
                tp(R.plurals.n_artistas, artists.size),
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
                if (search.isBlank()) t(R.string.nenhum_artista) else t(R.string.nada_encontrado),
                if (search.isBlank()) t(R.string.os_artistas_aparecem_quando_a) else t(R.string.tente_outro_nome),
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
                                tp(R.plurals.n_musicas, item.songs),
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
            .clip(androidx.compose.foundation.shape.CircleShape)
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
        if (search.isBlank() && untagged > 0) tagged + (t(R.string.sem_genero) to untagged) else tagged
    }
    StandardPage {
        ScreenHeaderRow(title = t(R.string.generos))
        Spacer(Modifier.height(UiTokens.listHeaderGap))
        SearchBar(value = search, onValueChange = { search = it }, placeholder = t(R.string.pesquisar_generos))
        Spacer(Modifier.height(UiTokens.standardListTopSpacing))
        if (genres.isEmpty()) {
            EmptyState(
                if (search.isBlank()) Icons.Rounded.Category else Icons.Rounded.Search,
                if (search.isBlank()) t(R.string.nenhum_genero) else t(R.string.nada_encontrado),
                if (search.isBlank()) t(R.string.os_generos_aparecem_quando_as) else t(R.string.tente_outro_nome),
            )
        }
        GenreList(genres, open, Modifier.weight(1f))
    }
}

@Composable
private fun GenreList(values: List<Pair<String, Int>>, open: (String) -> Unit, modifier: Modifier = Modifier) {
    val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    PersistGridScroll(gridState, "genres")
    Box(modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().songListSpace(),
            contentPadding = PaddingValues(bottom = UiTokens.songListBottomPadding),
            verticalArrangement = Arrangement.spacedBy(UiTokens.space2),
            horizontalArrangement = Arrangement.spacedBy(UiTokens.space2),
        ) {
            items(values, key = { it.first }) { (name, songs) -> GenreTile(name, tp(R.plurals.n_musicas, songs), onClick = { open(name) }) }
        }
        TouchGridScrollbar(gridState, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = UiTokens.scrollbarInset))
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
    val albumTrackIds = remember(tracks) { tracks.groupBy { it.album.ifBlank { t(R.string.sem_album) } }.mapValues { (_, list) -> list.firstOrNull()?.id } }
    Box(Modifier.fillMaxSize()) {
        StandardScreenColumn {
            ScreenHeaderRow(title = t(R.string.albuns_2))
            Spacer(Modifier.height(UiTokens.listHeaderGap))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(UiTokens.space1),
            ) {
                SearchBar(value = search, onValueChange = changeAlbumSearch, placeholder = t(R.string.pesquisar_albuns), modifier = Modifier.weight(1f))
                SortMenuButton(
                    open = filterOpen,
                    onOpenChange = { filterOpen = it },
                    title = t(R.string.ordenar_por),
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
                        if (search.isBlank()) t(R.string.nenhum_album) else t(R.string.nada_encontrado),
                        if (search.isBlank()) t(R.string.os_albuns_aparecem_quando_as) else t(R.string.tente_outro_nome_de_album),
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
                                .clip(RoundedCornerShape(UiTokens.songItemRadius))
                                .clickable { open(item.key) },
                            shape = RoundedCornerShape(UiTokens.songItemRadius),
                            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
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
                                ScrollingText(item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                ScrollingText(
                                    listOfNotNull(item.year.takeIf { it.isNotBlank() }, item.artist.takeIf { it.isNotBlank() }).joinToString(" • ").ifBlank { "—" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        Detail.GENRE -> if (key.equals(t(R.string.sem_genero), true)) tracks.filter { it.genre.isBlank() } else tracks.filter { it.genre.equals(key, true) }
        // Sem repetidas: a lista usa o id da música como chave, e chave repetida fecha o app.
        Detail.PLAYLIST -> playlistOrderIds.distinct().mapNotNull(detailTrackById::get)
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
    val coverId = shown.firstOrNull()?.id
    val albumArtist = shown.firstOrNull()?.albumArtist?.ifBlank { shown.firstOrNull()?.artist.orEmpty() }.orEmpty()
    // Artista: a fila segue exatamente a ordem da lista na tela (álbum por álbum).
    val artistAlbums = if (type == Detail.ARTIST) shown.groupBy { it.album.ifBlank { t(R.string.sem_album) } }.toSortedMap(String.CASE_INSENSITIVE_ORDER) else null
    val playOrder = artistAlbums?.values?.flatten() ?: shown

    // Fundo colorido do cabeçalho: cor da capa (álbum, playlist), do destaque (artista) ou do nome (gênero).
    val coverTint = rememberCoverTint(artwork, coverId)
    val baseTint = when (type) {
        Detail.GENRE -> genreColor(key)
        Detail.ARTIST -> MaterialTheme.colorScheme.primary
        else -> coverTint ?: MaterialTheme.colorScheme.surfaceVariant
    }
    val backdrop by animateColorAsState(heroBackdropColor(baseTint), label = "heroTint")
    val pageBackground = MaterialTheme.colorScheme.background
    val meta = when (type) {
        Detail.ALBUM -> listOfNotNull(tp(R.plurals.n_musicas, shown.size), formatDuration(duration), shown.firstOrNull()?.year?.takeIf { it.isNotBlank() }).joinToString(" • ")
        Detail.ARTIST -> listOf(tp(R.plurals.n_musicas, shown.size), tp(R.plurals.n_albuns, artistAlbums?.size ?: 0), formatDuration(duration)).joinToString(" • ")
        else -> "${tp(R.plurals.n_musicas, shown.size)} • ${formatDuration(duration)}"
    }

    Box(Modifier.fillMaxSize()) {
        // Fundo que rola junto com o cabeçalho e se dissolve na cor da tela.
        Box(
            Modifier
                .fillMaxWidth()
                .height(400.dp)
                .offset { IntOffset(0, -(if (state.firstVisibleItemIndex == 0) state.firstVisibleItemScrollOffset else 100_000)) }
                .background(Brush.verticalGradient(listOf(backdrop, pageBackground))),
        )
        SongListContainer(state = state, modifier = Modifier.fillMaxSize().padding(start = UiTokens.screenHorizontalPadding)) {
            item(key = "hero") {
                DetailHero(
                    title = key,
                    meta = meta,
                    playEnabled = shown.isNotEmpty(),
                    onPlay = { onPlay(playOrder, 0) },
                    onShuffle = { onPlayRandom(playOrder) },
                    cover = when (type) {
                        Detail.GENRE -> null
                        Detail.ARTIST -> ({ ArtistAvatar(key, Modifier.size(DETAIL_COVER)) })
                        else -> ({ PlayerArtworkBox(artwork, Modifier.size(DETAIL_COVER).shadow(20.dp, RoundedCornerShape(4.dp)), coverId, maxDimension = 768) })
                    },
                    subtitle = if (type == Detail.ALBUM) ({
                        ScrollingText(
                            albumArtist.ifBlank { t(R.string.artista_desconhecido) },
                            modifier = Modifier.clip(RoundedCornerShape(UiTokens.space1)).clickable { onOpenArtist(albumArtist) },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }) else null,
                )
            }
            when (type) {
                Detail.PLAYLIST -> {
                    item(key = "playlist-tools") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(UiTokens.space1)) {
                            SearchBar(value = playlistSearch, onValueChange = { playlistSearch = it }, placeholder = t(R.string.pesquisar_musicas), modifier = Modifier.weight(1f))
                            SortMenuButton(
                                open = playlistSortOpen,
                                onOpenChange = { playlistSortOpen = it },
                                title = t(R.string.ordenar_por),
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
                                    contentDescription = if (playlistSelectionMode) t(R.string.encerrar_selecao) else t(R.string.selecionar),
                                    tint = if (playlistSelectionMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(UiTokens.primaryIconSize),
                                )
                            }
                        }
                    }
                    if (playlistSelectionMode) {
                        item(key = "playlist-selection") {
                            Column {
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
                                        t(R.string.remover_da_playlist),
                                        Icons.Rounded.Delete,
                                        { onRemoveSelectedFromPlaylist(key, selectedPlaylistIds.toList()); exitPlaylistSelection() },
                                        Modifier.weight(1f),
                                        enabled = selectedPlaylistIds.isNotEmpty(),
                                        contentColor = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                    if (playlistSortMode != PlaylistSortMode.MANUAL || playlistSearch.isNotBlank()) {
                        item(key = "playlist-note") { Text(t(R.string.para_reordenar_manualmente_use_manual), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = UiTokens.space1)) }
                    }
                    if (shown.isEmpty()) {
                        item(key = "playlist-empty") {
                            EmptyState(
                                if (playlistSearch.isBlank()) Icons.AutoMirrored.Rounded.QueueMusic else Icons.Rounded.Search,
                                if (playlistSearch.isBlank()) t(R.string.playlist_vazia) else t(R.string.nada_encontrado),
                                if (playlistSearch.isBlank()) t(R.string.abra_o_menu_de_uma) else t(R.string.tente_outro_nome_de_musica),
                            )
                        }
                    }
                    items(shown, key = { it.id }) { t ->
                        val orderIndex = playlistOrderIds.indexOf(t.id)
                        SongItem(
                            t,
                            { onPlay(shown, shown.indexOfFirst { it.id == t.id }) },
                            { onEdit(t) },
                            { onShare(t) },
                            {}, { onOpenAlbum(t.album) }, { onOpenArtist(t.artist) }, {},
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
                Detail.ALBUM -> items(shown, key = { it.id }) { t ->
                    SongItem(t, { onPlay(shown, shown.indexOfFirst { it.id == t.id }.coerceAtLeast(0)) }, { onEdit(t) }, { onShare(t) }, {}, null, { onOpenArtist(t.artist) }, {}, onRemoveFromPlaylist = null, isCurrent = t.id == currentTrackId, showTrackNumber = true)
                }
                Detail.ARTIST -> artistAlbums?.forEach { (album, albumTracks) ->
                    item(key = "album-$album") {
                        Column(Modifier.fillMaxWidth().padding(top = UiTokens.space4, bottom = UiTokens.space1)) {
                            ScrollingText(album, modifier = Modifier.clip(RoundedCornerShape(UiTokens.space1)).clickable { onOpenAlbum(album) }, style = MaterialTheme.typography.titleMedium)
                            Text("${tp(R.plurals.n_musicas, albumTracks.size)} • ${formatDuration(albumTracks.sumOf { it.durationMs })}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(albumTracks, key = { it.id }) { t ->
                        SongItem(t, { onPlay(playOrder, playOrder.indexOfFirst { it.id == t.id }.coerceAtLeast(0)) }, { onEdit(t) }, { onShare(t) }, {}, { onOpenAlbum(t.album) }, null, {}, onRemoveFromPlaylist = null, isCurrent = t.id == currentTrackId)
                    }
                }
                else -> items(shown, key = { it.id }) { t ->
                    SongItem(t, { onPlay(shown, shown.indexOfFirst { it.id == t.id }.coerceAtLeast(0)) }, { onEdit(t) }, { onShare(t) }, {}, { onOpenAlbum(t.album) }, { onOpenArtist(t.artist) }, {}, onRemoveFromPlaylist = null, isCurrent = t.id == currentTrackId)
                }
            }
        }
        // Voltar fica sobre o cabeçalho colorido (na seleção da playlist, sai da seleção).
        IconButton(
            onClick = { if (playlistSelectionMode) exitPlaylistSelection() else onBack() },
            modifier = Modifier.padding(start = UiTokens.space2, top = UiTokens.space1).size(UiTokens.iconButtonSize).background(Color.Black.copy(alpha = 0.35f), androidx.compose.foundation.shape.CircleShape),
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, t(R.string.voltar), tint = Color.White, modifier = Modifier.size(UiTokens.primaryIconSize))
        }
    }
}

private val DETAIL_COVER = 208.dp

private fun rangeLabel(range: StatsRange): String = when (range) {
    StatsRange.ALL -> t(R.string.range_all)
    StatsRange.WEEK -> t(R.string.range_week)
    StatsRange.MONTH -> t(R.string.range_month)
    StatsRange.SIX_MONTHS -> t(R.string.range_6months)
    StatsRange.YEAR -> t(R.string.range_year)
}

private fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> t(R.string.sistema)
    ThemeMode.LIGHT -> t(R.string.claro)
    ThemeMode.DARK -> t(R.string.escuro)
}

private fun languageLabel(tag: String): String =
    AppLanguages.all.firstOrNull { it.tag == tag }?.nativeName ?: t(R.string.sistema)

@Composable
private fun LanguageDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AppDialog(title = t(R.string.language), onDismiss = onDismiss) {
        (listOf(AppLanguages.SYSTEM to t(R.string.sistema)) + AppLanguages.all.map { it.tag to it.nativeName }).forEach { (tag, label) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(tag) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = tag == current, onClick = { onPick(tag) })
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun ThemeModeDialog(current: ThemeMode, onPick: (ThemeMode) -> Unit, onDismiss: () -> Unit) {
    AppDialog(title = t(R.string.tema), onDismiss = onDismiss) {
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
    var languageDialog by remember { mutableStateOf(false) }
    var confirmResetStats by remember { mutableStateOf(false) }
    val settingsScope = rememberCoroutineScope()

    if (confirmReleaseFolder) {
        AppDialog(
            title = t(R.string.liberar_a_pasta),
            onDismiss = { confirmReleaseFolder = false },
            confirmText = t(R.string.liberar),
            onConfirm = { confirmReleaseFolder = false; onReleaseDownloadFolder?.invoke() },
        ) {
            Text(t(R.string.a_biblioteca_some_da_tela))
        }
    }
    if (themeDialog) {
        ThemeModeDialog(
            current = settings.themeMode,
            onPick = { themeDialog = false; onChange(settings.copy(themeMode = it)) },
            onDismiss = { themeDialog = false },
        )
    }
    if (languageDialog) {
        LanguageDialog(
            current = settings.language,
            onPick = { languageDialog = false; onChange(settings.copy(language = it)) },
            onDismiss = { languageDialog = false },
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
        SettingsGroup(if (missingPermissions > 0) t(R.string.permissoes_faltam, missingPermissions) else t(R.string.permissoes)) {
            permissions.forEachIndexed { index, item ->
                if (index > 0) SettingsDivider()
                SettingsRow(
                    icon = if (item.granted) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                    title = item.label,
                    subtitle = item.description.ifBlank { null },
                    trailing = { if (item.granted) RowHint("OK") else RowHint(t(R.string.conceder), highlighted = true) },
                    onClick = if (item.granted) null else item.onGrant,
                )
            }
        }
    }

    StandardPage {
        ScreenHeaderRow(title = t(R.string.configuracoes))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = state,
                modifier = Modifier.fillMaxSize().songListSpace(),
                contentPadding = PaddingValues(bottom = UiTokens.songListBottomPadding),
            ) {
                if (missingPermissions > 0) item { permissionsGroup() }
                item {
                    SettingsGroup(t(R.string.aparencia)) {
                        SettingsRow(
                            Icons.Rounded.Palette, t(R.string.tema), t(R.string.claro_escuro_ou_o_mesmo),
                            trailing = { RowHint(themeModeLabel(settings.themeMode)) },
                        ) { themeDialog = true }
                        SettingsDivider()
                        SettingsRow(
                            Icons.Rounded.Language, t(R.string.language), t(R.string.language_hint),
                            trailing = { RowHint(languageLabel(settings.language)) },
                        ) { languageDialog = true }
                    }
                }
                item {
                    SettingsGroup(t(R.string.biblioteca)) {
                        SettingsRow(
                            Icons.Rounded.Folder, t(R.string.pasta_de_musicas), downloadFolderLabel.ifBlank { t(R.string.nenhuma_escolhida) },
                            trailing = { RowHint(if (hasFolder) t(R.string.trocar) else t(R.string.escolher), highlighted = !hasFolder) },
                        ) { onChooseDownloadFolder?.invoke() }
                        SettingsDivider()
                        SettingsSwitchRow(
                            Icons.Rounded.Sync, t(R.string.atualizar_sozinha), t(R.string.le_a_pasta_de_novo),
                            settings.autoRefreshLibrary,
                        ) { onChange(settings.copy(autoRefreshLibrary = it)) }
                        SettingsDivider()
                        SettingsRow(
                            Icons.AutoMirrored.Rounded.DriveFileMove, t(R.string.organizar_pasta), t(R.string.poe_musicas_em_songs_e),
                            enabled = hasFolder,
                        ) { onOrganizeFolder?.invoke() }
                        SettingsDivider()
                        SettingsRow(
                            Icons.Rounded.CleaningServices, t(R.string.limpar_pasta), t(R.string.move_o_que_nao_e),
                            enabled = hasFolder,
                        ) { onCleanFolder?.invoke() }
                        SettingsDivider()
                        SettingsRow(
                            Icons.Rounded.FolderOff, t(R.string.liberar_pasta), t(R.string.solta_a_pasta_para_renomear),
                            enabled = hasFolder,
                        ) { confirmReleaseFolder = true }
                    }
                }
                item {
                    SettingsGroup(t(R.string.downloads)) {
                        SettingsSwitchRow(
                            Icons.Rounded.AutoFixHigh, t(R.string.corrigir_tags), t(R.string.busca_titulo_artista_e_capa),
                            settings.autoTag,
                        ) { onChange(settings.copy(autoTag = it)) }
                        SettingsDivider()
                        SettingsSwitchRow(
                            Icons.Rounded.Image, t(R.string.capa_do_video), t(R.string.usa_a_imagem_do_video),
                            settings.embedYoutubeThumbnail,
                        ) { onChange(settings.copy(embedYoutubeThumbnail = it)) }
                        SettingsDivider()
                        SettingsSwitchRow(
                            Icons.Rounded.Download, t(R.string.atualizar_o_baixador), t(R.string.mantem_os_downloads_funcionando),
                            settings.autoUpdateDownloader,
                        ) { onChange(settings.copy(autoUpdateDownloader = it)) }
                    }
                }
                item { GitSection() }
                item {
                    SettingsGroup(t(R.string.estatisticas)) {
                        SettingsRow(
                            Icons.Rounded.Delete, t(R.string.zerar_estatisticas_2), t(R.string.apaga_tempo_ouvido_e_vezes),
                            destructive = true,
                        ) { confirmResetStats = true }
                    }
                }
                if (missingPermissions == 0 && permissions.isNotEmpty()) item { permissionsGroup() }
                item {
                    SettingsGroup(t(R.string.atualizacoes)) {
                        SettingsSwitchRow(
                            Icons.Rounded.Notifications, t(R.string.avisar_sobre_versao_nova), t(R.string.confere_sozinho_e_avisa_por),
                            settings.autoCheckUpdates,
                        ) { onChange(settings.copy(autoCheckUpdates = it)) }
                        SettingsDivider()
                        val lastBackground = remember { UpdateManager.lastBackgroundCheckMs(context) }
                        SettingsRow(
                            Icons.Rounded.SystemUpdate, t(R.string.procurar_atualizacao),
                            t(R.string.versao, appVersion) + if (lastBackground > 0) {
                                t(R.string.conferiu_sozinho) + android.text.format.DateUtils.getRelativeTimeSpanString(
                                    lastBackground, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
                                )
                            } else {
                                t(R.string.ainda_nao_conferiu_sozinho)
                            },
                        ) { onCheckUpdate?.invoke() }
                    }
                }
                item {
                    Text(
                        t(R.string.trueplayer, appVersion),
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
        FilledTonalIconButton(onClick = { scope.launch { state.scrollToItem(0) } }) { Icon(Icons.Rounded.KeyboardArrowUp, t(R.string.ir_para_o_topo)) }
        FilledTonalIconButton(onClick = { scope.launch { val last = (state.layoutInfo.totalItemsCount - 1).coerceAtLeast(0); state.scrollToItem(last) } }) { Icon(Icons.Rounded.KeyboardArrowDown, t(R.string.ir_para_o_final)) }
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
        FilledTonalIconButton(onClick = { scope.launch { state.scrollToItem(0) } }) { Icon(Icons.Rounded.KeyboardArrowUp, t(R.string.ir_para_o_topo)) }
        FilledTonalIconButton(onClick = { scope.launch { val last = (state.layoutInfo.totalItemsCount - 1).coerceAtLeast(0); state.scrollToItem(last) } }) { Icon(Icons.Rounded.KeyboardArrowDown, t(R.string.ir_para_o_final)) }
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

private fun AudioTrack.displayTitle(): String = title.ifBlank { displayName.substringBeforeLast('.').ifBlank { t(R.string.sem_titulo) } }
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
                    dev.truetagger.android.withRetriever { retriever ->
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

/** Cor que representa a capa (null enquanto carrega ou se não há capa): fundo do player e dos cabeçalhos. */
@Composable
private fun rememberCoverTint(bytes: ByteArray?, uri: String?): Color? {
    val bitmap = rememberArtworkBitmap(bytes, uri, 96)
    return remember(bitmap) {
        bitmap?.let { source ->
            runCatching {
                val small = Bitmap.createScaledBitmap(source, 24, 24, true)
                val pixels = IntArray(24 * 24)
                small.getPixels(pixels, 0, 24, 0, 0, 24, 24)
                Color(0xFF000000.toInt() or dev.truetagger.data.dominantRgb(pixels))
            }.getOrNull()
        }
    }
}

@Composable
private fun PlayerArtworkBox(bytes: ByteArray?, modifier: Modifier, uri: String? = null, maxDimension: Int = 512, corner: Dp = 4.dp, onClick: (() -> Unit)? = null) {
    val bitmap = rememberArtworkBitmap(bytes, uri, maxDimension)
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Rounded.MusicNote, null, Modifier.size(UiTokens.toolbarIconButtonSize))
    }
}
