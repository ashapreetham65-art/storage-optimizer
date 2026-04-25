package com.example.storageoptimizer.data

import android.net.Uri
import com.example.storageoptimizer.data.local.ImageDao
import com.example.storageoptimizer.data.local.ImageEntity

class ImageRepository(private val dao: ImageDao) {

    // ── In-memory cache ──────────────────────────────────────────────────────

    private var images:        List<ImageItem>       = emptyList()
    private var exactGroups:   List<List<ImageItem>> = emptyList()
    private var similarGroups: List<List<ImageItem>> = emptyList()

    fun getImages():        List<ImageItem>       = images
    fun getExactGroups():   List<List<ImageItem>> = exactGroups
    fun getSimilarGroups(): List<List<ImageItem>> = similarGroups
    fun hasData():          Boolean               = images.isNotEmpty()

    // ── Load from DB on startup ──────────────────────────────────────────────
    // Only updates memory — does NOT write back to DB.
    // The V9 bug was calling saveAll() here which triggered clearAll()+insertAll()
    // on every app open, pointlessly rewriting the entire database.
    suspend fun loadFromDb(): List<ImageItem> {
        val loaded = dao.getAllImages().map { it.toImageItem() }
        images = loaded
        return loaded
    }

    // Called by the ViewModel after groups are derived on startup —
    // groups live only in memory, never in DB.
    fun storeGroupsInMemory(
        exactGroups:   List<List<ImageItem>>,
        similarGroups: List<List<ImageItem>>
    ) {
        this.exactGroups   = exactGroups
        this.similarGroups = similarGroups
    }

    // ── Full first scan ──────────────────────────────────────────────────────
    // Clears DB and inserts everything fresh. Used only when DB was empty.
    suspend fun saveAll(
        images:        List<ImageItem>,
        exactGroups:   List<List<ImageItem>>,
        similarGroups: List<List<ImageItem>>
    ) {
        this.images        = images
        this.exactGroups   = exactGroups
        this.similarGroups = similarGroups

        dao.clearAll()
        dao.insertAll(images.map { it.toEntity() })
    }

    // ── Incremental refresh (V10) ────────────────────────────────────────────
    // Only touches rows that actually changed — no clearAll().
    suspend fun applyIncrementalUpdate(
        finalImages:   List<ImageItem>,
        toUpsert:      List<ImageItem>,   // new + modified rows
        deletedIds:    Set<Long>,         // ids no longer in MediaStore
        exactGroups:   List<List<ImageItem>>,
        similarGroups: List<List<ImageItem>>
    ) {
        this.images        = finalImages
        this.exactGroups   = exactGroups
        this.similarGroups = similarGroups

        if (deletedIds.isNotEmpty()) dao.deleteByIds(deletedIds.toList())
        if (toUpsert.isNotEmpty())   dao.upsert(toUpsert.map { it.toEntity() })
    }

    // ── User-confirmed deletion ──────────────────────────────────────────────
    suspend fun deleteImages(
        deletedIds:    Set<Long>,
        exactGroups:   List<List<ImageItem>>,
        similarGroups: List<List<ImageItem>>
    ) {
        images             = images.filter { it.id !in deletedIds }
        this.exactGroups   = exactGroups
        this.similarGroups = similarGroups
        dao.deleteByIds(deletedIds.toList())
    }

    // ── Mappers ──────────────────────────────────────────────────────────────

    private fun ImageItem.toEntity() = ImageEntity(
        id           = id,
        uri          = uri.toString(),
        hash         = hash,
        size         = size,
        dateModified = dateModified,
        dateAdded    = dateAdded
    )

    private fun ImageEntity.toImageItem() = ImageItem(
        id           = id,
        uri          = Uri.parse(uri),
        hash         = hash,
        size         = size,
        dateModified = dateModified,
        dateAdded    = dateAdded
    )
}