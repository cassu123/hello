package org.hello.reader.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import org.hello.reader.core.model.Series

@Entity(
    tableName = "series",
    primaryKeys = ["uuid", "server_id"],
    indices = [
        Index(value = ["server_id"]),
        Index(value = ["server_id", "name"]),
    ],
)
data class SeriesEntity(
    @ColumnInfo(name = "uuid") val uuid: String,
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "description") val description: String? = null,
    @ColumnInfo(name = "total_books", defaultValue = "0") val totalBooks: Int = 0,
) {
    fun toDomain(): Series = Series(
        uuid = uuid,
        serverId = serverId,
        name = name,
        description = description,
        totalBooks = totalBooks,
    )

    companion object {
        fun fromDomain(series: Series): SeriesEntity = SeriesEntity(
            uuid = series.uuid,
            serverId = series.serverId,
            name = series.name,
            description = series.description,
            totalBooks = series.totalBooks,
        )
    }
}
