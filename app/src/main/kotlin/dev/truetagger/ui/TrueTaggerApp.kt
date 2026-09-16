package dev.truetagger.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.truetagger.metadata.MetadataRepository
import dev.truetagger.model.AppSettings
import dev.truetagger.model.AudioTrack
import dev.truetagger.model.EditableTags
import dev.truetagger.model.ImportStatus
import dev.truetagger.model.MetadataResult
import dev.truetagger.model.TagWriteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

val TruePlayerDarkScheme = darkColorScheme(
    primary = Color(0xFFC77DFF),
    onPrimary = Color(0xFF1B0928),
    secondary = Color(0xFF9B4DFF),
    onSecondary = Color(0xFF170622),
    tertiary = Color(0xFFE0A3FF),
    background = Color(0xFF050307),
    onBackground = Color(0xFFF7F0FF),
    surface = Color(0xFF0D0812),
    onSurface = Color(0xFFF7F0FF),
    surfaceVariant = Color(0xFF171020),
    onSurfaceVariant = Color(0xFFBDB1C8),
    primaryContainer = Color(0xFF3A155B),
    onPrimaryContainer = Color(0xFFF3DFFF),
    secondaryContainer = Color(0xFF2B123D),
    onSecondaryContainer = Color(0xFFF0D8FF),
    errorContainer = Color(0xFF5B2028),
)

@Composable
fun CurrentTrackScreen(
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
        track.artworkBytes?.contentHashCode(),
    ) {
        if (!hasUserEdited && draft == null) {
            editor = track.toEditableTags()
        }
    }

    LaunchedEffect(track.id, query) {
        delay(350)
        if (query.isBlank()) {
            results = emptyList()
            searching = false
        } else {
            searching = true
            val value = broadMetadataSearch(repo, query, track)
            results = value
            searching = false
            if (value.isEmpty()) message = "Nenhum resultado encontrado."
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
                artworkBytes = if (result.artworkUrl.isNotBlank()) null else editor.artworkBytes,
                artworkUrl = result.artworkUrl.ifBlank { editor.artworkUrl },
            )
        )
        message = "Sugestão carregada."
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = UiTokens.screenPadding, vertical = UiTokens.compactPadding),
        verticalArrangement = Arrangement.spacedBy(UiTokens.sectionSpacing + UiTokens.itemSpacing),
    ) {
        item {
            SectionTitle("Buscar metadados", "A pesquisa começa pelo nome do arquivo/vídeo.")
            SearchBar(
                value = query,
                onValueChange = { query = it; message = null },
                placeholder = "Nome da música ou vídeo",
                trailingContent = if (searching) { { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) } } else null,
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
                Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.formFieldSpacing)) {
                    Box(Modifier.weight(1f)) { EditableField("Faixa", editor.trackNumber) { updateEditor(editor.copy(trackNumber = it)) } }
                    Box(Modifier.weight(1f)) { EditableField("Total de faixas", editor.trackTotal) { updateEditor(editor.copy(trackTotal = it)) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.formFieldSpacing)) {
                    Box(Modifier.weight(1f)) { EditableField("Disco", editor.discNumber) { updateEditor(editor.copy(discNumber = it)) } }
                    Box(Modifier.weight(1f)) { EditableField("Total de discos", editor.discTotal) { updateEditor(editor.copy(discTotal = it)) } }
                }
            }
        }

        item {
            SectionTitle("Detalhes")
            FormCard {
                Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.formFieldSpacing)) {
                    Box(Modifier.weight(1f)) { EditableField("Ano", editor.year) { updateEditor(editor.copy(year = it)) } }
                    Box(Modifier.weight(1f)) { EditableField("Gênero", editor.genre) { updateEditor(editor.copy(genre = it)) } }
                }
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


private fun cleanMetadataQuery(raw: String): String {
    var q = raw
    val removable = listOf(
        "official visualizer", "official visualization", "official video",
        "official audio", "official music video", "music video",
        "lyric video", "lyrics video", "lyrics", "visualizer",
        "audio oficial", "vídeo oficial", "video oficial"
    )
    removable.forEach { q = q.replace(Regex("\\b${Regex.escape(it)}\\b", RegexOption.IGNORE_CASE), " ") }
    q = q.replace(Regex("\\[[^\\]]*(official|visualizer|lyrics|audio|video)[^\\]]*\\]", RegexOption.IGNORE_CASE), " ")
    q = q.replace(Regex("\\([^)]*(official|visualizer|lyrics|audio|video)[^)]*\\)", RegexOption.IGNORE_CASE), " ")
    return q.replace(Regex("\\s+"), " ").trim()
}

private suspend fun broadMetadataSearch(repo: MetadataRepository, raw: String, track: AudioTrack): List<MetadataResult> {
    val cleaned = cleanMetadataQuery(raw)
    val title = cleanMetadataQuery(track.title.ifBlank { track.cleanDisplayTitle() })
    val artist = cleanMetadataQuery(track.artist)
    val album = cleanMetadataQuery(track.album)
    val candidates = linkedSetOf<String>()

    // Prioriza buscas com título + artista, mas também tenta cada parte isoladamente.
    listOf(
        listOf(cleaned, artist).filter { it.isNotBlank() }.distinct().joinToString(" "),
        listOf(title, artist).filter { it.isNotBlank() }.distinct().joinToString(" "),
        cleaned,
        title,
        listOf(artist, title).filter { it.isNotBlank() }.distinct().joinToString(" "),
        listOf(title, album).filter { it.isNotBlank() }.distinct().joinToString(" ")
    ).forEach { q -> if (q.isNotBlank()) candidates += q }

    val all = candidates.flatMap { query ->
        runCatching { repo.search(query) }.getOrDefault(emptyList())
    }

    val unique = all.distinctBy { "${it.source}:${it.sourceId}" }
    if (unique.isEmpty()) return emptyList()

    return unique
        .map { it to metadataMatchScore(cleaned, title, artist, album, it) }
        .sortedByDescending { it.second }
        .take(24)
        .map { it.first }
}

private fun normalizeSearchText(value: String): String =
    java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

private fun metadataMatchScore(
    query: String,
    expectedTitle: String,
    expectedArtist: String,
    expectedAlbum: String,
    result: MetadataResult,
): Int {
    val q = normalizeSearchText(query)
    val title = normalizeSearchText(result.title)
    val artist = normalizeSearchText(result.artist)
    val album = normalizeSearchText(result.album)

    var score = 0
    score += textSimilarity(expectedTitle, title) * 60
    score += textSimilarity(expectedArtist, artist) * 30
    score += textSimilarity(expectedAlbum, album) * 10
    score += textSimilarity(q, listOf(title, artist, album).filter { it.isNotBlank() }.joinToString(" ")) * 25
    if (q.isNotBlank() && (title.contains(q) || q.contains(title))) score += 40
    if (expectedTitle.isNotBlank() && (title.contains(expectedTitle) || expectedTitle.contains(title))) score += 50
    if (expectedArtist.isNotBlank() && (artist.contains(expectedArtist) || expectedArtist.contains(artist))) score += 35
    return score
}

private fun textSimilarity(a: String, b: String): Int {
    if (a.isBlank() || b.isBlank()) return 0
    if (a == b) return 100
    val aTokens = a.split(' ').filter { it.length >= 2 }.toSet()
    val bTokens = b.split(' ').filter { it.length >= 2 }.toSet()
    val overlap = if (aTokens.isEmpty()) 0 else (aTokens.intersect(bTokens).size * 100) / aTokens.size
    val distance = levenshtein(a, b)
    val edit = 100 - ((distance * 100) / maxOf(a.length, b.length))
    return maxOf(overlap, edit.coerceAtLeast(0))
}

private fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var prev = IntArray(b.length + 1) { it }
    var curr = IntArray(b.length + 1)
    for (i in a.indices) {
        curr[0] = i + 1
        for (j in b.indices) {
            val cost = if (a[i] == b[j]) 0 else 1
            curr[j + 1] = minOf(
                curr[j] + 1,
                prev[j + 1] + 1,
                prev[j] + cost,
            )
        }
        val tmp = prev
        prev = curr
        curr = tmp
    }
    return prev[b.length]
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
    Column(verticalArrangement = Arrangement.spacedBy(UiTokens.sectionSpacing)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sugestões", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (loading) Text("Buscando...", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        val suggestionState = rememberLazyListState()
        LazyRow(
            state = suggestionState,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(UiTokens.formFieldSpacing),
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
        modifier = Modifier.width(UiTokens.suggestionCardWidth).clickable(onClick = onOpen),
        shape = RoundedCornerShape(UiTokens.cardRadius),
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(UiTokens.cardPadding), verticalArrangement = Arrangement.spacedBy(UiTokens.itemSpacing + 2.dp)) {
            ArtworkBox(artworkBytes, artworkUrl, Modifier.fillMaxWidth().height(UiTokens.suggestionArtworkHeight))
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
        Card(shape = RoundedCornerShape(UiTokens.cardRadius), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(UiTokens.dialogPadding), verticalArrangement = Arrangement.spacedBy(UiTokens.formFieldSpacing)) {
                ArtworkBox(track.artworkBytes, "", Modifier.fillMaxWidth().height(UiTokens.dialogArtworkSize))
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
        Card(shape = RoundedCornerShape(UiTokens.cardRadius), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(UiTokens.dialogPadding), verticalArrangement = Arrangement.spacedBy(UiTokens.formFieldSpacing)) {
                ArtworkBox(null, result.artworkUrl, Modifier.fillMaxWidth().height(UiTokens.dialogArtworkSize))
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
                Row(horizontalArrangement = Arrangement.spacedBy(UiTokens.formFieldSpacing)) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Fechar") }
                    Button(onClick = onUse, modifier = Modifier.weight(1f)) { Text("Usar") }
                }
            }
        }
    }
}

@Composable
fun ArtworkBox(bytes: ByteArray?, url: String, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(initialValue = null, bytes?.contentHashCode(), url) {
        value = withContext(Dispatchers.IO) {
            when {
                bytes != null -> runCatching { decodeSampledArtwork(bytes, 768) }.getOrNull()
                url.isNotBlank() -> runCatching {
                    // Sem timeout, uma capa lenta prendia a thread de IO
                    // indefinidamente — e a imagem crua podia vir com 3000px.
                    val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 4000
                        readTimeout = 6000
                        requestMethod = "GET"
                        setRequestProperty("User-Agent", "TruePlayer/1.0")
                    }
                    try {
                        val raw = connection.inputStream.use { it.readBytes() }
                        if (raw.isEmpty()) null else decodeSampledArtwork(raw, 768)
                    } finally {
                        connection.disconnect()
                    }
                }.getOrNull()
                else -> null
            }
        }
    }
    Box(modifier.clip(RoundedCornerShape(UiTokens.cardRadius)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap!!.asImageBitmap(), contentDescription = "Capa", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(Icons.Rounded.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Decodifica a capa já reduzida ao tamanho que será realmente exibido. */
private fun decodeSampledArtwork(raw: ByteArray, maxDimension: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
    val max = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
    var sample = 1
    while (max / sample > maxDimension) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeByteArray(raw, 0, raw.size, options)
}

@Composable
private fun ArtworkEditor(artworkBytes: ByteArray?, artworkUrl: String, onPickArtwork: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(UiTokens.cardRadius),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(UiTokens.cardPadding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(UiTokens.formFieldSpacing)) {
            ArtworkBox(
                artworkBytes,
                artworkUrl,
                Modifier.size(UiTokens.editorArtworkSize).clickable(onClick = onPickArtwork),
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
fun ImportBanner(status: ImportStatus, onDismiss: () -> Unit) {
    val title = when {
        status.failed -> "Falha no download"
        status.completed -> "Download iniciado"
        status.progress != null -> "Baixando • ${status.progress}%"
        else -> "Download"
    }
    Surface(color = if (status.failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = UiTokens.screenHorizontalPadding, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(UiTokens.itemSpacing + 1.dp)) {
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

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    val minutes = seconds / 60
    val rest = (seconds % 60).toString().padStart(2, '0')
    return "$minutes:$rest"
}
