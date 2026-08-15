package dev.truetagger.metadata

import dev.truetagger.model.MetadataResult

class MetadataRepository(
    private val client: io.ktor.client.HttpClient = createPlatformHttpClient(),
) {
    private val providers: List<MetadataProvider> = listOf(
        AppleMetadataProvider(client),
        DeezerMetadataProvider(client),
    )

    suspend fun search(query: String): List<MetadataResult> {
        val all = mutableListOf<MetadataResult>()
        providers.forEach { provider ->
            runCatching { provider.search(query) }
                .onSuccess(all::addAll)
        }
        return all.distinctBy {
            listOf(it.title, it.artist, it.album)
                .joinToString("|")
                .lowercase()
        }
    }
}
