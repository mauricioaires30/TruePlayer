package dev.truetagger.metadata

import dev.truetagger.model.MetadataResult

interface MetadataProvider {
    val name: String
    suspend fun search(query: String): List<MetadataResult>
}
