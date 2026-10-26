package org.hello.reader.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "book_series",
    primaryKeys = ["book_id", "server_id", "series_uuid"],
    indices = [
        Index(value = ["server_id", "series_uuid"]),
        Index(value = ["server_id", "book_id"]),
    ],
)
data class BookSeriesCrossRef(
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "series_uuid") val seriesUuid: String,
    @ColumnInfo(name = "position") val position: Double? = null,
    @ColumnInfo(name = "featured", defaultValue = "0") val featured: Boolean = false,
)
