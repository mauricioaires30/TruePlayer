package dev.truetagger.metadata

import dev.truetagger.model.MetadataResult

class MetadataRepository(
    private val client: io.ktor.client.HttpClient = createPlatformHttpClient(),
) {
    private val primaryProviders: List<MetadataProvider> = listOf(
        AppleMetadataProvider(client),
        DeezerMetadataProvider(client),
    )

    // O Genius só entra quando Apple e Deezer não acham nada; a busca é a mesma que o site faz, sem chave.
    private val fallbackProviders: List<MetadataProvider> = listOf(GeniusMetadataProvider(client))

    suspend fun search(query: String): List<MetadataResult> {
        val cleanedQuery = removeParenthesizedText(query)
        if (cleanedQuery.isBlank()) return emptyList()

        val primary = searchProviders(primaryProviders, cleanedQuery)
        if (primary.isNotEmpty()) return dedupe(primary)
        return dedupe(searchProviders(fallbackProviders, cleanedQuery))
    }

    private fun removeParenthesizedText(value: String): String {
        if (value.isBlank()) return ""
        val out = StringBuilder(value.length)
        var depth = 0
        for (ch in value) {
            when (ch) {
                '(' -> depth++
                ')' -> if (depth > 0) depth--
                else -> if (depth == 0) out.append(ch)
            }
        }
        return out.toString().replace(Regex("\\s+"), " ").trim()
    }

    private suspend fun searchProviders(providers: List<MetadataProvider>, query: String): List<MetadataResult> {
        val all = mutableListOf<MetadataResult>()
        providers.forEach { provider ->
            runCatching { provider.search(query) }.onSuccess(all::addAll)
        }
        return all
    }

    private fun dedupe(items: List<MetadataResult>): List<MetadataResult> = items.distinctBy {
        listOf(it.title, it.artist, it.album).joinToString("|").lowercase()
    }
}
