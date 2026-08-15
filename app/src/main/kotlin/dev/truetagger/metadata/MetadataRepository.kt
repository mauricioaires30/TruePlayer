package dev.truetagger.metadata

import dev.truetagger.model.MetadataResult

class MetadataRepository(
    private val client: io.ktor.client.HttpClient = createPlatformHttpClient(),
    geniusAccessToken: String = "",
) {
    private val primaryProviders: List<MetadataProvider> = listOf(
        AppleMetadataProvider(client),
        DeezerMetadataProvider(client),
    )

    private val fallbackProviders: List<MetadataProvider> = buildList {
        if (geniusAccessToken.isNotBlank()) {
            add(GeniusMetadataProvider(client, geniusAccessToken))
        }
    }

    suspend fun search(query: String): List<MetadataResult> {
        val primary = searchProviders(primaryProviders, query)
        if (primary.isNotEmpty()) return dedupe(primary)
        return dedupe(searchProviders(fallbackProviders, query))
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
