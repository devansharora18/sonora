package dev.sonora.backend

import java.io.File
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** Recent search queries, stored as one JSON document. See [JsonFile]. */
class SearchHistoryStore(file: File) {

    private val json = JsonFile(file, ListSerializer(String.serializer()))

    fun load(): List<String> = json.read().orEmpty()

    fun save(queries: List<String>) = json.write(queries)
}
