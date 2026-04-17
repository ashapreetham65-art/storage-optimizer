package com.example.storageoptimizer.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ImageEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun imageDao(): ImageDao

    companion object {

        // @Volatile ensures the instance is always read from main memory,
        // not from a CPU cache — prevents two threads creating separate instances.
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            // Double-checked locking: fast path avoids synchronisation overhead
            // after the first creation.
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "storage_optimizer.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}