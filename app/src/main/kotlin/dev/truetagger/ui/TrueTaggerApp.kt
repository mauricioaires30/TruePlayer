package dev.truetagger.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.truetagger.android.R
import dev.truetagger.metadata.MetadataRepository
import dev.truetagger.model.AppSettings
import dev.truetagger.model.AudioTrack
import dev.truetagger.model.EditableTags
import dev.truetagger.model.ImportStatus
import dev.truetagger.model.MetadataResult
import dev.truetagger.model.TagWriteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class AppScreen { Library, Editor, Settings }

private enum class SortMode(val label: String) {
    Modified("Data de modificação"),
    Title("Título"),
    Artist("Artista"),
    Album("Álbum"),
    Size("Tamanho"),
    Duration("Duração"),
}

private val TrueTaggerDarkScheme = darkColorScheme(
    primary = Color(0xFFA98BFF),
    onPrimary = Color(0xFF1A1035),
    secondary = Color(0xFF71DEC0),
    onSecondary = Color(0xFF092D24),
    tertiary = Color(0xFFFFC17B),
    background = Color(0xFF090A10),
    onBackground = Color(0xFFF5F2FF),
    surface = Color(0xFF11131C),
    onSurface = Color(0xFFF5F2FF),
    surfaceVariant = Color(0xFF1B1E2A),
    onSurfaceVariant = Color(0xFFB9BDCF),
    primaryContainer = Color(0xFF2A1E49),
    onPrimaryContainer = Color(0xFFE8DDFF),
    secondaryContainer = Color(0xFF173E35),
    onSecondaryContainer = Color(0xFFBEF5E7),
    errorContainer = Color(0xFF5B2028),
)

@Composable
fun TrueTaggerApp(
    tracks: List<AudioTrack>,
    selectedTrack: AudioTrack?,
    libraryLoading: Boolean,
    onSelectTrack: (AudioTrack) -> Unit,
    onBackToLibrary: () -> Unit,
    onRefreshLibrary: () -> Unit,
    onSaveEdits: suspend (AudioTrack, EditableTags) -> TagWriteResult,
    downloadFolderLabel: String = "",
    onChooseDownloadFolder: (() -> Unit)? = null,
    settings: AppSettings = AppSettings(),
    onSettingsChange: (AppSettings) -> Unit = {},
    appVersion: String = "",
    importStatus: ImportStatus? = null,
    onDismissImportStatus: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var screen by rememberSaveable { mutableStateOf(AppScreen.Library) }
    val drafts = remember { mutableStateMapOf<String, EditableTags>() }

    LaunchedEffect(screen) {
        if (screen == AppScreen.Library) onRefreshLibrary()
    }

    fun goHome() {
        screen = AppScreen.Library
        onBackToLibrary()
    }

    BackHandler(enabled = screen != AppScreen.Library) {
        goHome()
    }

    MaterialTheme(colorScheme = TrueTaggerDarkScheme) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                AppHeader(
                    screen = screen,
                    trackTitle = selectedTrack?.displayTitle().orEmpty(),
                    onBack = ::goHome,
                    onSettings = { screen = AppScreen.Settings },
                )
            },
        ) { innerPadding ->
            Column(Modifier.fillMaxSize().padding(innerPadding)) {
                importStatus?.let { ImportBanner(it, onDismissImportStatus) }
                when (screen) {
                    AppScreen.Library -> LibraryScreen(
                        tracks = tracks,
                        loading = libraryLoading,
                        onSelectTrack = {
                            onSelectTrack(it)
                            screen = AppScreen.Editor
                        },
                        onRefreshLibrary = onRefreshLibrary,
                    )

                    AppScreen.Editor -> CurrentTrackScreen(
                        track = selectedTrack,
                        draft = selectedTrack?.let { drafts[it.id] },
                        settings = settings,
                        onDraftChange = { trackId, draft -> drafts[trackId] = draft },
                        onSaveEdits = { track, edits ->
                            val result = onSaveEdits(track, edits)
                            if (result.success) {
                                drafts[track.id] = result.updatedTrack?.toEditableTags() ?: edits
                            }
                            result
                        },
                    )

                    AppScreen.Settings -> SettingsScreen(
                        downloadFolderLabel = downloadFolderLabel,
                        onChooseDownloadFolder = onChooseDownloadFolder,
                        settings = settings,
                        onSettingsChange = onSettingsChange,
                        appVersion = appVersion,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppHeader(
    screen: AppScreen,
    trackTitle: String,
    onBack: () -> Unit,
    onSettings: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (screen != AppScreen.Library) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "Voltar")
                }
            } else {
                Image(
                    painter = painterResource(R.drawable.truetagger_icon),
                    contentDescription = null,
                    modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)),
                )
            }

            Column(Modifier.weight(1f)) {
                Text(
                    when (screen) {
                        AppScreen.Library -> "TrueTagger"
                        AppScreen.Editor -> "Editar música"
                        AppScreen.Settings -> "Configurações"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                if (screen == AppScreen.Editor && trackTitle.isNotBlank()) {
                    Text(
                        trackTitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (screen == AppScreen.Library) {
                IconButton(onClick = onSettings) {
                    Icon(Icons.Rounded.Settings, contentDescription = "Configurações")
                }
            }
        }
    }
}

@Composable
private fun LibraryScreen(
    tracks: List<AudioTrack>,
    loading: Boolean,
    onSelectTrack: (AudioTrack) -> Unit,
    onRefreshLibrary: () -> Unit,
) {
    var search by rememberSaveable { mutableStateOf("") }
    var sortMode by rememberSaveable { mutableStateOf(SortMode.Modified) }
    var ascending by rememberSaveable { mutableStateOf(false) }

    val shown = remember(tracks, search, sortMode, ascending) {
        val filtered = if (search.isBlank()) tracks else tracks.filter { track ->
            sequenceOf(track.title, track.artist, track.album, track.displayName)
                .any { it.contains(search, ignoreCase = true) }
        }
        filtered.sortedWith { a, b ->
            val result = when (sortMode) {
                SortMode.Modified -> a.modifiedAtSeconds.compareTo(b.modifiedAtSeconds)
                SortMode.Title -> a.displayTitle().compareTo(b.displayTitle(), ignoreCase = true)
                SortMode.Artist -> a.artist.ifBlank { a.displayTitle() }.compareTo(b.artist.ifBlank { b.displayTitle() }, ignoreCase = true)
                SortMode.Album -> a.album.ifBlank { a.displayTitle() }.compareTo(b.album.ifBlank { b.displayTitle() }, ignoreCase = true)
                SortMode.Size -> a.sizeBytes.compareTo(b.sizeBytes)
                SortMode.Duration -> a.durationMs.compareTo(b.durationMs)
            }
            if (ascending) result else -result
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Biblioteca", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "${sortMode.label} • ${if (ascending) "mais antigos primeiro" else "mais recentes primeiro"}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            CompactSortMenu(sortMode, ascending) { mode ->
                if (mode == sortMode) ascending = !ascending
                else {
                    sortMode = mode
                    ascending = mode != SortMode.Modified
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                placeholder = { Text("Pesquisar músicas...") },
            )
            IconButton(
                onClick = onRefreshLibrary,
                enabled = !loading,
                modifier = Modifier.size(50.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface),
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Atualizar")
            }
        }

        if (loading && tracks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.55f))
            }
        } else if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (tracks.isEmpty()) "Nenhuma música encontrada" else "Nenhum resultado encontrado",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val listState = rememberLazyListState()
            Box(Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(end = 7.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown, key = { it.id }) { track ->
                        LibraryTrackRow(track = track, sortMode = sortMode, onClick = { onSelectTrack(track) })
                    }
                }
                VerticalLazyScrollbar(
                    state = listState,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(4.dp),
                )
            }
        }
    }
}

@Composable
private fun CompactSortMenu(sortMode: SortMode, ascending: Boolean, onSelect: (SortMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Icon(Icons.Rounded.FilterList, contentDescription = "Ordenar")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortMode.entries.forEach { mode ->
                val selected = mode == sortMode
                DropdownMenuItem(
                    text = {
                        Text(
                            buildString {
                                if (selected) append(if (ascending) "↑ " else "↓ ")
                                append(mode.label)
                            },
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        onSelect(mode)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun LibraryTrackRow(track: AudioTrack, sortMode: SortMode, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TrackArtworkThumbnail(track = track, modifier = Modifier.size(54.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(track.displayTitle(), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (track.artist.isNotBlank()) {
                    Text(track.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (track.album.isNotBlank()) {
                    Text(track.album, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                val top = when (sortMode) {
                    SortMode.Modified -> formatModifiedDate(track.modifiedAtSeconds)
                    SortMode.Size -> formatFileSize(track.sizeBytes)
                    else -> ""
                }
                if (top.isNotBlank()) Text(top, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (track.durationMs > 0) Text(formatDuration(track.durationMs), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun TrackArtworkThumbnail(track: AudioTrack, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, track.id, track.artworkBytes?.contentHashCode()) {
        value = withContext(Dispatchers.IO) {
            val bytes = track.artworkBytes ?: runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, Uri.parse(track.id))
                    retriever.embeddedPicture
                } finally {
                    runCatching { retriever.release() }
                }
            }.getOrNull()
            bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
        }
    }
    Box(
        modifier = modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap!!.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun CurrentTrackScreen(
    track: AudioTrack?,
    draft: EditableTags?,
    settings: AppSettings,
    onDraftChange: (String, EditableTags) -> Unit,
    onSaveEdits: suspend (AudioTrack, EditableTags) -> TagWriteResult,
) {
    if (track == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nenhuma música selecionada", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val repo = remember(settings.geniusAccessToken) {
        MetadataRepository(
            geniusAccessToken = settings.geniusAccessToken,
        )
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var query by remember(track.id) { mutableStateOf(track.cleanDisplayTitle()) }
    var results by remember(track.id) { mutableStateOf<List<MetadataResult>>(emptyList()) }
    var searching by remember(track.id) { mutableStateOf(false) }
    var saving by remember(track.id) { mutableStateOf(false) }
    var message by remember(track.id) { mutableStateOf<String?>(null) }
    var editor by remember(track.id) { mutableStateOf(draft ?: track.toEditableTags()) }
    var hasUserEdited by remember(track.id) { mutableStateOf(draft != null) }
    var expandedSuggestion by remember(track.id) { mutableStateOf<MetadataResult?>(null) }
    var showCurrentPreview by remember(track.id) { mutableStateOf(false) }

    fun updateEditor(next: EditableTags) {
        editor = next
        hasUserEdited = true
        onDraftChange(track.id, next)
    }

    LaunchedEffect(
        track.id,
        track.albumArtist,
        track.trackTotal,
        track.discNumber,
        track.discTotal,
        track.genre,
        track.isrc,
        track.artworkBytes?.contentHashCode(),
    ) {
        if (!hasUserEdited && draft == null) {
            editor = track.toEditableTags()
        }
    }

    LaunchedEffect(track.id) {
        if (query.isNotBlank()) {
            searching = true
            val primary = repo.search(query)
            results = if (primary.isNotEmpty()) {
                primary
            } else {
                val fallback = listOf(track.artist, track.title).filter(String::isNotBlank).joinToString(" ")
                if (fallback.isNotBlank() && !fallback.equals(query, ignoreCase = true)) repo.search(fallback) else emptyList()
            }
            searching = false
        }
    }

    val artworkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                }
                if (bytes != null) updateEditor(editor.copy(artworkBytes = bytes, artworkUrl = ""))
            }
        }
    }

    fun applyResult(result: MetadataResult) {
        updateEditor(
            editor.copy(
                title = result.title,
                artist = result.artist,
                album = result.album,
                albumArtist = result.albumArtist.ifBlank { result.artist },
                trackNumber = result.trackNumber?.toString() ?: editor.trackNumber,
                trackTotal = result.trackTotal?.toString() ?: editor.trackTotal,
                discNumber = result.discNumber?.toString() ?: editor.discNumber,
                discTotal = result.discTotal?.toString() ?: editor.discTotal,
                year = result.year.ifBlank { editor.year },
                genre = result.genre.ifBlank { editor.genre },
                isrc = result.isrc.ifBlank { editor.isrc },
                artworkBytes = if (result.artworkUrl.isNotBlank()) null else editor.artworkBytes,
                artworkUrl = result.artworkUrl.ifBlank { editor.artworkUrl },
            )
        )
        message = "Sugestão carregada."
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SectionTitle("Buscar metadados", "A pesquisa começa pelo nome do arquivo/vídeo.")
            SearchBar(
                query = query,
                searching = searching,
                onQueryChange = { query = it },
                onSearch = {
                    searching = true
                    message = null
                    scope.launch {
                        results = repo.search(query)
                        searching = false
                        if (results.isEmpty()) message = "Nenhum resultado encontrado."
                    }
                },
            )
        }

        item {
            SuggestionStrip(
                track = track,
                results = results.take(12),
                loading = searching,
                onOpenCurrent = { showCurrentPreview = true },
                onOpenResult = { expandedSuggestion = it },
                onUseResult = { applyResult(it) },
            )
        }

        item {
            SectionTitle("Capa", "Toque na imagem para trocar.")
            ArtworkEditor(
                artworkBytes = editor.artworkBytes,
                artworkUrl = editor.artworkUrl,
                onPickArtwork = { artworkPicker.launch(arrayOf("image/*")) },
            )
        }

        item {
            SectionTitle("Informações básicas")
            FormCard {
                EditableField("Título", editor.title) { updateEditor(editor.copy(title = it)) }
                EditableField("Artista da música", editor.artist) { updateEditor(editor.copy(artist = it)) }
                EditableField("Artista do álbum", editor.albumArtist) { updateEditor(editor.copy(albumArtist = it)) }
                EditableField("Álbum", editor.album) { updateEditor(editor.copy(album = it)) }
            }
        }

        item {
            SectionTitle("Organização do álbum")
            FormCard {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { EditableField("Faixa", editor.trackNumber) { updateEditor(editor.copy(trackNumber = it)) } }
                    Box(Modifier.weight(1f)) { EditableField("Total de faixas", editor.trackTotal) { updateEditor(editor.copy(trackTotal = it)) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { EditableField("Disco", editor.discNumber) { updateEditor(editor.copy(discNumber = it)) } }
                    Box(Modifier.weight(1f)) { EditableField("Total de discos", editor.discTotal) { updateEditor(editor.copy(discTotal = it)) } }
                }
            }
        }

        item {
            SectionTitle("Detalhes")
            FormCard {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { EditableField("Ano", editor.year) { updateEditor(editor.copy(year = it)) } }
                    Box(Modifier.weight(1f)) { EditableField("Gênero", editor.genre) { updateEditor(editor.copy(genre = it)) } }
                }
                EditableField("ISRC", editor.isrc) { updateEditor(editor.copy(isrc = it)) }
                if (track.durationMs > 0) {
                    Text("Duração: ${formatDuration(track.durationMs)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        message?.let { feedback ->
            item { Text(feedback, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium) }
        }

        item {
            Button(
                enabled = !saving && editor.title.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                onClick = {
                    saving = true
                    message = null
                    scope.launch {
                        val result = onSaveEdits(track, editor)
                        result.updatedTrack?.let { updated -> updateEditor(updated.toEditableTags()) }
                        message = result.message
                        saving = false
                    }
                },
            ) {
                Icon(Icons.Rounded.Save, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (saving) "Salvando..." else "Salvar alterações")
            }
        }
    }

    if (showCurrentPreview) {
        CurrentTrackPreviewDialog(track = track, onDismiss = { showCurrentPreview = false })
    }
    expandedSuggestion?.let { suggestion ->
        SuggestionPreviewDialog(
            result = suggestion,
            onDismiss = { expandedSuggestion = null },
            onUse = {
                applyResult(suggestion)
                expandedSuggestion = null
            },
        )
    }
}

@Composable
private fun SearchBar(query: String, searching: Boolean, onQueryChange: (String) -> Unit, onSearch: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            placeholder = { Text("Nome da música ou vídeo") },
        )
        Button(enabled = query.isNotBlank() && !searching, onClick = onSearch, shape = RoundedCornerShape(15.dp)) {
            Text(if (searching) "..." else "Buscar")
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String = "") {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FormCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun SuggestionStrip(
    track: AudioTrack,
    results: List<MetadataResult>,
    loading: Boolean,
    onOpenCurrent: () -> Unit,
    onOpenResult: (MetadataResult) -> Unit,
    onUseResult: (MetadataResult) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sugestões", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (loading) Text("Buscando...", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        val suggestionState = rememberLazyListState()
        LazyRow(
            state = suggestionState,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                SuggestionCard(
                    title = track.displayTitle(),
                    subtitle = track.artist.ifBlank { "Arquivo atual" },
                    artworkBytes = track.artworkBytes,
                    artworkUrl = "",
                    label = "Atual",
                    selected = true,
                    onOpen = onOpenCurrent,
                    onUse = null,
                )
            }
            items(results, key = { "${it.source}:${it.sourceId}" }) { result ->
                SuggestionCard(
                    title = result.title,
                    subtitle = result.artist,
                    artworkBytes = null,
                    artworkUrl = result.artworkUrl,
                    label = result.source,
                    selected = false,
                    onOpen = { onOpenResult(result) },
                    onUse = { onUseResult(result) },
                )
            }
        }
        HorizontalLazyScrollbar(
            state = suggestionState,
            modifier = Modifier.fillMaxWidth().height(4.dp),
        )
    }
}

@Composable
private fun SuggestionCard(
    title: String,
    subtitle: String,
    artworkBytes: ByteArray?,
    artworkUrl: String,
    label: String,
    selected: Boolean,
    onOpen: () -> Unit,
    onUse: (() -> Unit)?,
) {
    Card(
        modifier = Modifier.width(154.dp).clickable(onClick = onOpen),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            ArtworkBox(artworkBytes, artworkUrl, Modifier.fillMaxWidth().height(130.dp))
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                if (onUse != null) {
                    IconButton(onClick = onUse, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Rounded.Check, contentDescription = "Usar", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CurrentTrackPreviewDialog(track: AudioTrack, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ArtworkBox(track.artworkBytes, "", Modifier.fillMaxWidth().height(260.dp))
                Text(track.displayTitle(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (track.artist.isNotBlank()) Text(track.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (track.album.isNotBlank()) Text(track.album, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (track.durationMs > 0) Text(formatDuration(track.durationMs), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Fechar") }
            }
        }
    }
}

@Composable
private fun SuggestionPreviewDialog(result: MetadataResult, onDismiss: () -> Unit, onUse: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ArtworkBox(null, result.artworkUrl, Modifier.fillMaxWidth().height(260.dp))
                Text(result.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(result.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (result.album.isNotBlank()) Text(result.album, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val details = buildList {
                    result.trackNumber?.let { add("Faixa $it${result.trackTotal?.let { total -> "/$total" } ?: ""}") }
                    result.discNumber?.let { add("Disco $it${result.discTotal?.let { total -> "/$total" } ?: ""}") }
                    if (result.year.isNotBlank()) add(result.year)
                    if (result.genre.isNotBlank()) add(result.genre)
                }.joinToString(" • ")
                if (details.isNotBlank()) Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Fechar") }
                    Button(onClick = onUse, modifier = Modifier.weight(1f)) { Text("Usar") }
                }
            }
        }
    }
}

@Composable
private fun ArtworkBox(bytes: ByteArray?, url: String, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(initialValue = null, bytes?.contentHashCode(), url) {
        value = withContext(Dispatchers.IO) {
            when {
                bytes != null -> runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                url.isNotBlank() -> runCatching { URL(url).openStream().use { BitmapFactory.decodeStream(it) } }.getOrNull()
                else -> null
            }
        }
    }
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap!!.asImageBitmap(), contentDescription = "Capa", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Rounded.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ArtworkEditor(artworkBytes: ByteArray?, artworkUrl: String, onPickArtwork: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ArtworkBox(
                artworkBytes,
                artworkUrl,
                Modifier.size(230.dp).clickable(onClick = onPickArtwork),
            )
            TextButton(onClick = onPickArtwork) {
                Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Trocar capa")
            }
        }
    }
}

@Composable
private fun EditableField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onValueChange, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text(label) })
}

@Composable
private fun ImportBanner(status: ImportStatus, onDismiss: () -> Unit) {
    val title = when {
        status.failed -> "Falha no download"
        status.completed -> "Download iniciado"
        status.progress != null -> "Baixando • ${status.progress}%"
        else -> "Download"
    }
    Surface(color = if (status.failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold)
                    if (status.mediaTitle.isNotBlank()) Text(status.mediaTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(status.message, style = MaterialTheme.typography.bodySmall)
                }
                if (status.completed || status.failed) TextButton(onClick = onDismiss) { Text("Fechar") }
            }
            status.progress?.let { progress ->
                LinearProgressIndicator(progress = { progress.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    downloadFolderLabel: String,
    onChooseDownloadFolder: (() -> Unit)?,
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    appVersion: String,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SettingsSectionTitle("Biblioteca")
            SettingsCard {
                SettingSwitchRow(
                    icon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                    title = "Atualização automática",
                    subtitle = "Detectar novas músicas sem precisar atualizar manualmente",
                    checked = settings.autoRefreshLibrary,
                    onCheckedChange = { onSettingsChange(settings.copy(autoRefreshLibrary = it)) },
                )
            }
        }

        item {
            SettingsSectionTitle("Downloads")
            SettingsCard {
                SettingActionRow(
                    icon = { Icon(Icons.Rounded.Folder, contentDescription = null) },
                    title = "Pasta de download",
                    subtitle = downloadFolderLabel.ifBlank { "Nenhuma pasta escolhida" },
                    onClick = { onChooseDownloadFolder?.invoke() },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                SettingSwitchRow(
                    icon = { Icon(Icons.Rounded.Image, contentDescription = null) },
                    title = "Usar thumbnail como capa",
                    subtitle = "Incorporar a imagem do vídeo no MP3 baixado",
                    checked = settings.embedYoutubeThumbnail,
                    onCheckedChange = { onSettingsChange(settings.copy(embedYoutubeThumbnail = it)) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                SettingSwitchRow(
                    icon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                    title = "Atualizar downloader",
                    subtitle = "Manter a compatibilidade com mudanças do YouTube",
                    checked = settings.autoUpdateDownloader,
                    onCheckedChange = { onSettingsChange(settings.copy(autoUpdateDownloader = it)) },
                )
            }
        }

        item {
            SettingsSectionTitle("Fontes extras")
            SettingsCard {
                SettingCredentialField(
                    title = "Genius Access Token",
                    value = settings.geniusAccessToken,
                    secret = true,
                    onValueChange = { onSettingsChange(settings.copy(geniusAccessToken = it)) },
                )
            }
        }

        item {
            SettingsSectionTitle("Aplicativo")
            SettingsCard {
                SettingActionRow(
                    icon = { Icon(Icons.Rounded.Info, contentDescription = null) },
                    title = "TrueTagger",
                    subtitle = if (appVersion.isBlank()) "Versão atual" else "Versão $appVersion",
                    onClick = {},
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun SettingSwitchRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { icon() }
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingCredentialField(
    title: String,
    value: String,
    secret: Boolean,
    onValueChange: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        )
    }
}

@Composable
private fun SettingActionRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { icon() }
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun VerticalLazyScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    val info by remember { derivedStateOf { state.layoutInfo } }
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo.size
    if (total <= visible || total <= 0) return
    val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
    Canvas(modifier) {
        val minThumb = 28.dp.toPx()
        val thumb = maxOf(minThumb, size.height * (visible.toFloat() / total.toFloat()))
        val maxIndex = maxOf(1, total - visible)
        val top = (size.height - thumb) * (first.toFloat() / maxIndex.toFloat()).coerceIn(0f, 1f)
        drawRoundRect(
            color = Color.White.copy(alpha = 0.28f),
            topLeft = androidx.compose.ui.geometry.Offset(0f, top),
            size = androidx.compose.ui.geometry.Size(size.width, thumb),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width / 2f),
        )
    }
}

@Composable
private fun HorizontalLazyScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    val info by remember { derivedStateOf { state.layoutInfo } }
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo.size
    if (total <= visible || total <= 0) return
    val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
    Canvas(modifier) {
        val minThumb = 36.dp.toPx()
        val thumb = maxOf(minThumb, size.width * (visible.toFloat() / total.toFloat()))
        val maxIndex = maxOf(1, total - visible)
        val left = (size.width - thumb) * (first.toFloat() / maxIndex.toFloat()).coerceIn(0f, 1f)
        drawRoundRect(
            color = Color.White.copy(alpha = 0.28f),
            topLeft = androidx.compose.ui.geometry.Offset(left, 0f),
            size = androidx.compose.ui.geometry.Size(thumb, size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
        )
    }
}

private fun AudioTrack.toEditableTags() = EditableTags(
    title = title.ifBlank { cleanDisplayTitle() },
    artist = artist,
    album = album,
    albumArtist = albumArtist,
    trackNumber = trackNumber,
    trackTotal = trackTotal,
    discNumber = discNumber,
    discTotal = discTotal,
    year = year,
    genre = genre,
    isrc = isrc,
    artworkBytes = artworkBytes,
)

private fun AudioTrack.displayTitle(): String = title.ifBlank { cleanDisplayTitle() }

private fun AudioTrack.cleanDisplayTitle(): String = displayName
    .substringBeforeLast('.')
    .replace(Regex("\\s*\\[[^]]+]$"), "")
    .trim()
    .ifBlank { "Sem título" }

private fun formatModifiedDate(seconds: Long): String {
    if (seconds <= 0) return ""
    return SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(Date(seconds * 1000L))
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1.0) String.format(Locale.getDefault(), "%.1f MB", mb) else "${bytes / 1024} KB"
}

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    val minutes = seconds / 60
    val rest = (seconds % 60).toString().padStart(2, '0')
    return "$minutes:$rest"
}
