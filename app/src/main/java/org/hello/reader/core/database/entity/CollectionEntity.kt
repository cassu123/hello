package org.hello.reader.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import org.hello.reader.core.model.BookCollection

@Entity(
    tableName = "collections",
    primaryKeys = ["uuid", "server_id"],
    indices = [
        Index(value = ["server_id"]),
        Index(value = ["server_id", "name"]),
    ],
)
data class CollectionEntity(
    @ColumnInfo(name = "uuid") val uuid: String,
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "description") val description: String? = null,
    @ColumnInfo(name = "icon") val icon: String? = null,
    @ColumnInfo(name = "color") val color: String? = null,
    @ColumnInfo(name = "is_public", defaultValue = "1") val isPublic: Boolean = true,
    @ColumnInfo(name = "total_books", defaultValue = "0") val totalBooks: Int = 0,
) {
    fun toDomain(): BookCollection = BookCollection(
        uuid = uuid,
        serverId = serverId,
        name = name,
        description = description,
        icon = icon,
        color = color,
        isPublic = isPublic,
        totalBooks = totalBooks,
    )

    companion object {
        fun fromDomain(collection: BookCollection): CollectionEntity = CollectionEntity(
            uuid = collection.uuid,
            serverId = collection.serverId,
            name = collection.name,
            description = collection.description,
            icon = collection.icon,
            color = collection.color,
            isPublic = collection.isPublic,
            totalBooks = collection.totalBooks,
        )
    }
}
