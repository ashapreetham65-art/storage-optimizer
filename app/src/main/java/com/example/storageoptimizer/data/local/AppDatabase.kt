package com.example.storageoptimizer.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities    = [ImageEntity::class, FileEntity::class, AppEntity::class],
    version     = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun imageDao(): ImageDao
    abstract fun fileDao():  FileDao
    abstract fun appDao():   AppDao

    companion object {

        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE images ADD COLUMN dateModified INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE images ADD COLUMN dateAdded INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS files (
                        id INTEGER PRIMARY KEY NOT NULL,
                        uri TEXT NOT NULL,
                        name TEXT NOT NULL,
                        size INTEGER NOT NULL,
                        mimeType TEXT NOT NULL,
                        dateModified INTEGER NOT NULL,
                        dateAdded INTEGER NOT NULL,
                        path TEXT NOT NULL,
                        hash INTEGER
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS apps (
                        packageName TEXT PRIMARY KEY NOT NULL,
                        name        TEXT NOT NULL,
                        apkSize     INTEGER NOT NULL,
                        dataSize    INTEGER NOT NULL,
                        versionName TEXT NOT NULL,
                        installedAt INTEGER NOT NULL,
                        lastUsed    INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "storage_optimizer.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}