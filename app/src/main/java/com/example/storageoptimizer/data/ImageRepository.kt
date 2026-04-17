package com.example.storageoptimizer.data

import android.net.Uri
import com.example.storageoptimizer.data.local.ImageDao
import com.example.storageoptimizer.data.local.ImageEntity

// Two-layer store:
//   - In-memory cache  → instant reads during a session (same as V8)
//   - Room DB via DAO  → survives app restarts (new in V9)
//
// The ViewModel is the only caller. Screens never touch the Repository directly.
class ImageRepository(private val dao: ImageDao) {

    // ── In-memory cache ──────────────────────────────────────────────────────

    private var images:        List<ImageItem>       = emptyList()
    private var exactGroups:   List<List<ImageItem>> = emptyList()
    private var similarGroups: List<List<ImageItem>> = emptyList()

    // ── Memory reads (used by ViewModel for derived values) ──────────────────

    fun getImages():        List<ImageItem>       = images
    fun getExactGroups():   List<List<ImageItem>> = exactGroups
    fun getSimilarGroups(): List<List<ImageItem>> = similarGroups
    fun hasData():          Boolean               = images.isNotEmpty()

    // ── Write: called after a full scan ─────────────────────────────────────

    // Saves to both memory and DB. DB is cleared first so stale data never
    // accumulates — this is a full replace, not a merge.
    suspend fun saveAll(
        images:        List<ImageItem>,
        exactGroups:   List<List<ImageItem>>,
        similarGroups: List<List<ImageItem>>
    ) {
        // Update memory immediately so StateFlows can be pushed right after
        this.images        = images
        this.exactGroups   = exactGroups
        this.similarGroups = similarGroups

        // Persist images to DB (groups are not stored — they are always
        // re-derived from hashes on load, which is fast and avoids a
        // complex relational schema for V9)
        dao.clearAll()
        dao.insertAll(images.map { it.toEntity() })
    }

    // ── Write: called after the user confirms a deletion ────────────────────

    // Updates memory and removes only the deleted rows from DB —
    // no need to re-insert everything, just a targeted DELETE.
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

    // ── Read from DB: called once on app start ───────────────────────────────

    // Loads persisted images from Room and restores the in-memory cache.
    // Groups are NOT stored in DB — they are re-derived here from the
    // saved hashes. This keeps the DB schema simple (one table, no relations).
    // Re-deriving groups for a typical library takes < 100ms on-device.
    suspend fun loadFromDb(): List<ImageItem> {
        val loaded = dao.getAllImages().map { it.toImageItem() }
        images = loaded
        // Note: exactGroups and similarGroups are populated by the ViewModel
        // after this call returns, using ImageEngine.findGroupsByThreshold.
        return loaded
    }

    // ── Mappers ──────────────────────────────────────────────────────────────

    private fun ImageItem.toEntity() = ImageEntity(
        id   = id,
        uri  = uri.toString(),
        hash = hash,
        size = size
    )

    private fun ImageEntity.toImageItem() = ImageItem(
        id   = id,
        uri  = Uri.parse(uri),
        hash = hash,
        size = size
    )
}