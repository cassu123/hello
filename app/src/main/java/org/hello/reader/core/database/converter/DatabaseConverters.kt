package org.hello.reader.core.database.converter

import androidx.room.TypeConverter
import org.hello.reader.core.model.BookMediaType
import org.hello.reader.core.model.DownloadStatus
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class DatabaseConverters {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val stringListSerializer = ListSerializer(String.serializer())

    @TypeConverter
    fun fromBookMediaType(value: BookMediaType): String = value.name

    @TypeConverter
    fun toBookMediaType(value: String): BookMediaType =
        runCatching { BookMediaType.valueOf(value) }.getOrDefault(BookMediaType.EBOOK_ONLY)

    @TypeConverter
    fun fromDownloadStatus(value: DownloadStatus): String = value.name

    @TypeConverter
    fun toDownloadStatus(value: String): DownloadStatus =
        runCatching { DownloadStatus.valueOf(value) }.getOrDefault(DownloadStatus.NOT_DOWNLOADED)

    @TypeConverter
    fun fromStringList(values: List<String>): String =
        json.encodeToString(stringListSerializer, values)

    @TypeConverter
    fun toStringList(serialized: String): List<String> {
        if (serialized.isBlank()) return emptyList()
        return try {
            json.decodeFromString(stringListSerializer, serialized)
        } catch (e: SerializationException) {
            serialized.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
    }
}
