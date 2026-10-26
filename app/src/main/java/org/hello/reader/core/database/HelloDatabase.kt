package org.hello.reader.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.hello.reader.core.database.converter.DatabaseConverters
import org.hello.reader.core.database.dao.AnnotationDao
import org.hello.reader.core.database.dao.BookDao
import org.hello.reader.core.database.dao.BookDownloadDao
import org.hello.reader.core.database.dao.CollectionDao
import org.hello.reader.core.database.dao.SeriesDao
import org.hello.reader.core.database.dao.SyncCheckpointDao
import org.hello.reader.core.database.entity.AnnotationEntity
import org.hello.reader.core.database.entity.BookCollectionCrossRef
import org.hello.reader.core.database.entity.BookDownloadEntity
import org.hello.reader.core.database.entity.BookEntity
import org.hello.reader.core.database.entity.BookSeriesCrossRef
import org.hello.reader.core.database.entity.CollectionEntity
import org.hello.reader.core.database.entity.SeriesEntity
import org.hello.reader.core.database.entity.SyncCheckpointEntity

@Database(
    entities = [
        BookEntity::class,
        SyncCheckpointEntity::class,
        AnnotationEntity::class,
        SeriesEntity::class,
        CollectionEntity::class,
        BookSeriesCrossRef::class,
        BookCollectionCrossRef::class,
        BookDownloadEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
@TypeConverters(DatabaseConverters::class)
abstract class HelloDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao
    abstract fun bookDownloadDao(): BookDownloadDao
    abstract fun syncCheckpointDao(): SyncCheckpointDao
    abstract fun annotationDao(): AnnotationDao
    abstract fun seriesDao(): SeriesDao
    abstract fun collectionDao(): CollectionDao

    companion object {
        const val DATABASE_NAME = "hello_local.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `annotations` (
                        `id` TEXT NOT NULL,
                        `server_id` TEXT NOT NULL,
                        `user_id` TEXT NOT NULL,
                        `book_id` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `selected_text` TEXT,
                        `note` TEXT,
                        `color` TEXT NOT NULL,
                        `text_href` TEXT,
                        `element_id` TEXT,
                        `start_offset` INTEGER NOT NULL,
                        `end_offset` INTEGER NOT NULL,
                        `reading_cfi` TEXT,
                        `chapter_index` INTEGER NOT NULL,
                        `chapter_title` TEXT,
                        `created_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_annotations_server_id_user_id_book_id` ON `annotations` (`server_id`, `user_id`, `book_id`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_annotations_book_id_created_at` ON `annotations` (`book_id`, `created_at`)",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `books` ADD COLUMN `has_readaloud` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `books` ADD COLUMN `has_ebook` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `books` ADD COLUMN `has_audiobook` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `series` (
                        `uuid` TEXT NOT NULL,
                        `server_id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `description` TEXT,
                        `total_books` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`uuid`, `server_id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_series_server_id` ON `series` (`server_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_series_server_id_name` ON `series` (`server_id`, `name`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `collections` (
                        `uuid` TEXT NOT NULL,
                        `server_id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `description` TEXT,
                        `icon` TEXT,
                        `color` TEXT,
                        `is_public` INTEGER NOT NULL DEFAULT 1,
                        `total_books` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`uuid`, `server_id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_collections_server_id` ON `collections` (`server_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_collections_server_id_name` ON `collections` (`server_id`, `name`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `book_series` (
                        `book_id` TEXT NOT NULL,
                        `server_id` TEXT NOT NULL,
                        `series_uuid` TEXT NOT NULL,
                        `position` REAL,
                        `featured` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`book_id`, `server_id`, `series_uuid`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_series_server_id_series_uuid` ON `book_series` (`server_id`, `series_uuid`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_series_server_id_book_id` ON `book_series` (`server_id`, `book_id`)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `book_collections` (
                        `book_id` TEXT NOT NULL,
                        `server_id` TEXT NOT NULL,
                        `collection_uuid` TEXT NOT NULL,
                        PRIMARY KEY(`book_id`, `server_id`, `collection_uuid`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_collections_server_id_collection_uuid` ON `book_collections` (`server_id`, `collection_uuid`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_collections_server_id_book_id` ON `book_collections` (`server_id`, `book_id`)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `books` ADD COLUMN `reading_status` TEXT")
                db.execSQL("ALTER TABLE `books` ADD COLUMN `reading_status_label` TEXT")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sync_checkpoints` ADD COLUMN `chapter_progression` REAL")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `book_downloads` (
                        `book_id` TEXT NOT NULL,
                        `server_id` TEXT NOT NULL,
                        `user_id` TEXT NOT NULL,
                        `format` TEXT NOT NULL,
                        `local_path` TEXT NOT NULL,
                        `size_bytes` INTEGER NOT NULL DEFAULT 0,
                        `download_status` TEXT NOT NULL DEFAULT 'NOT_DOWNLOADED',
                        `download_progress` REAL NOT NULL DEFAULT 0.0,
                        `sha256` TEXT,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        PRIMARY KEY(`book_id`, `server_id`, `user_id`, `format`),
                        FOREIGN KEY(`book_id`, `server_id`, `user_id`) REFERENCES `books`(`id`, `server_id`, `user_id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_downloads_server_id_user_id` ON `book_downloads` (`server_id`, `user_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_downloads_book_id_server_id_user_id` ON `book_downloads` (`book_id`, `server_id`, `user_id`)")

                // Add Storyteller metadata columns to books table
                db.execSQL("ALTER TABLE `books` ADD COLUMN `status_uuid` TEXT")
                db.execSQL("ALTER TABLE `books` ADD COLUMN `rating` REAL")
                db.execSQL("ALTER TABLE `books` ADD COLUMN `publication_date` TEXT")
                db.execSQL("ALTER TABLE `books` ADD COLUMN `language` TEXT")
                db.execSQL("ALTER TABLE `books` ADD COLUMN `publisher` TEXT")
                db.execSQL("ALTER TABLE `books` ADD COLUMN `isbn` TEXT")
                db.execSQL("ALTER TABLE `books` ADD COLUMN `external_ratings` TEXT")

                // Backfill book_downloads from existing downloaded books
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO `book_downloads` (`book_id`, `server_id`, `user_id`, `format`, `local_path`, `size_bytes`, `download_status`, `download_progress`, `sha256`, `created_at`, `updated_at`)
                    SELECT `id`, `server_id`, `user_id`,
                           CASE WHEN `media_type` = 'READALOUD' THEN 'READALOUD' ELSE 'EBOOK_ONLY' END,
                           `local_file_path`, 0, 'DOWNLOADED', 1.0, `sha256_checksum`, `created_at`, `created_at`
                    FROM `books`
                    WHERE `local_file_path` IS NOT NULL AND `local_file_path` != ''
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO `book_downloads` (`book_id`, `server_id`, `user_id`, `format`, `local_path`, `size_bytes`, `download_status`, `download_progress`, `sha256`, `created_at`, `updated_at`)
                    SELECT `id`, `server_id`, `user_id`, 'AUDIO_ONLY',
                           `local_audio_path`, 0, 'DOWNLOADED', 1.0, `sha256_checksum`, `created_at`, `created_at`
                    FROM `books`
                    WHERE `local_audio_path` IS NOT NULL AND `local_audio_path` != '' AND `media_type` = 'AUDIO_ONLY'
                    """.trimIndent(),
                )
            }
        }

        fun build(context: Context): HelloDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                HelloDatabase::class.java,
                DATABASE_NAME,
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .build()
    }
}
