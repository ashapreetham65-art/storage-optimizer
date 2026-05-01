package com.example.storageoptimizer.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.SharedPreferences
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.storageoptimizer.engine.FileEngine
import com.example.storageoptimizer.engine.ImageEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class MainViewModel(
    private val repository: ImageRepository,
    private val prefs:      SharedPreferences
) : ViewModel() {

    companion object {
        private const val KEY_LAST_SCANNED = "last_scanned_at_ms"
    }

    private val hashSemaphore     = Semaphore(4)
    private val fileHashSemaphore = Semaphore(4)

    // ── Image StateFlows ─────────────────────────────────────────────────────

    private val _images        = MutableStateFlow<List<ImageItem>>(emptyList())
    val images: StateFlow<List<ImageItem>> = _images.asStateFlow()

    private val _exactGroups   = MutableStateFlow<List<List<ImageItem>>>(emptyList())
    val exactGroups: StateFlow<List<List<ImageItem>>> = _exactGroups.asStateFlow()

    private val _similarGroups = MutableStateFlow<List<List<ImageItem>>>(emptyList())
    val similarGroups: StateFlow<List<List<ImageItem>>> = _similarGroups.asStateFlow()

    private val _isScanning      = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isLoadingFromDb = MutableStateFlow(false)
    val isLoadingFromDb: StateFlow<Boolean> = _isLoadingFromDb.asStateFlow()

    private val _lastScannedAt = MutableStateFlow<Long?>(
        prefs.getLong(KEY_LAST_SCANNED, -1L).takeIf { it != -1L }
    )
    val lastScannedAt: StateFlow<Long?> = _lastScannedAt.asStateFlow()

    // ── File StateFlows ──────────────────────────────────────────────────────

    private val _files          = MutableStateFlow<List<FileItem>>(emptyList())
    val files: StateFlow<List<FileItem>> = _files.asStateFlow()

    private val _isScanningFiles = MutableStateFlow(false)
    val isScanningFiles: StateFlow<Boolean> = _isScanningFiles.asStateFlow()

    /** True while file hashing is in progress (after initial load completes) */
    private val _isHashingFiles  = MutableStateFlow(false)
    val isHashingFiles: StateFlow<Boolean> = _isHashingFiles.asStateFlow()

    /** Exact-duplicate file groups — non-empty only after hashing completes */
    private val _fileDuplicateGroups = MutableStateFlow<List<List<FileItem>>>(emptyList())
    val fileDuplicateGroups: StateFlow<List<List<FileItem>>> = _fileDuplicateGroups.asStateFlow()

    // ── Init ─────────────────────────────────────────────────────────────────

    init { loadFromDb() }

    private fun loadFromDb() {
        viewModelScope.launch {
            _isLoadingFromDb.value = true
            val saved = withContext(Dispatchers.IO) { repository.loadFromDb() }

            if (saved.isNotEmpty()) {
                val exact: List<List<ImageItem>>
                val similar: List<List<ImageItem>>
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        val ed = async { ImageEngine.findGroupsByThreshold(saved, threshold = 0) }
                        val sd = async { ImageEngine.findGroupsByThreshold(saved, threshold = 5) }
                        exact   = ed.await()
                        similar = sd.await()
                    }
                }
                repository.storeGroupsInMemory(exact, similar)

                _images.value        = saved
                _exactGroups.value   = exact
                _similarGroups.value = similar
            }
            _isLoadingFromDb.value = false
        }
    }

    // ── Derived values ───────────────────────────────────────────────────────

    fun hasData(): Boolean = repository.hasData()

    val reclaimableBytes: Long
        get() = repository.getExactGroups().sumOf { group ->
            group.sortedByDescending { it.size }.drop(1).sumOf { it.size }
        }

    val totalFileSize: Long
        get() = _files.value.sumOf { it.size }

    val fileCount: Int
        get() = _files.value.size

    // ── File scanning ─────────────────────────────────────────────────────────
    // Phase 1: load file metadata from MediaStore (fast).
    // Phase 2: hash every file to detect exact duplicates (slow, background).

    fun scanFiles(contentResolver: ContentResolver) {
        if (_isScanningFiles.value) return
        viewModelScope.launch {
            _isScanningFiles.value    = true
            _fileDuplicateGroups.value = emptyList()

            // Phase 1 — metadata only (fast)
            val unhashedFiles = withContext(Dispatchers.IO) {
                FileEngine.loadFiles(contentResolver)
            }
            _files.value           = unhashedFiles
            _isScanningFiles.value = false

            // Phase 2 — hash every file to find duplicates (slow, but non-blocking)
            hashFilesAndFindDuplicates(unhashedFiles, contentResolver)
        }
    }

    private fun hashFilesAndFindDuplicates(
        files: List<FileItem>,
        contentResolver: ContentResolver
    ) {
        viewModelScope.launch {
            _isHashingFiles.value = true

            val hashedFiles = withContext(Dispatchers.IO) {
                coroutineScope {
                    files.map { file ->
                        async {
                            fileHashSemaphore.withPermit {
                                val hash = FileEngine.hashFile(file.uri, contentResolver, file.size)
                                file.copy(hash = hash)
                            }
                        }
                    }.awaitAll()
                }
            }

            // Update file list with hashes (allows UI to show sizes etc. correctly)
            _files.value = hashedFiles

            // Derive duplicate groups
            val groups = withContext(Dispatchers.IO) {
                FileEngine.findExactDuplicateGroups(hashedFiles)
            }
            _fileDuplicateGroups.value = groups
            _isHashingFiles.value      = false
        }
    }

    // ── Auto-select duplicates (keep largest, mark rest for deletion) ─────────
    fun autoSelectedFileDuplicateIds(): Set<Long> =
        _fileDuplicateGroups.value.flatMap { group ->
            group.sortedByDescending { it.size }.drop(1)
        }.map { it.id }.toSet()

    // ── File deletion ─────────────────────────────────────────────────────────
    // Removes deleted IDs from both _files and _fileDuplicateGroups in memory,
    // then deletes from MediaStore via the ContentResolver.
    fun deleteFiles(
        toDelete: Set<Long>,
        contentResolver: ContentResolver,
        onComplete: () -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            toDelete.forEach { id ->
                try {
                    contentResolver.delete(
                        ContentUris.withAppendedId(
                            MediaStore.Files.getContentUri("external"), id
                        ), null, null
                    )
                } catch (_: Exception) { /* file may already be gone */ }
            }

            // Also try the Downloads URI (Android 10+) in case the file lives there
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                toDelete.forEach { id ->
                    try {
                        contentResolver.delete(
                            ContentUris.withAppendedId(
                                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL), id
                            ), null, null
                        )
                    } catch (_: Exception) { }
                }
            }

            withContext(Dispatchers.Main) {
                // Remove from flat list
                _files.value = _files.value.filter { it.id !in toDelete }

                // Remove from duplicate groups; drop groups that collapse to < 2 items
                _fileDuplicateGroups.value = _fileDuplicateGroups.value
                    .map { group -> group.filter { it.id !in toDelete } }
                    .filter { it.size > 1 }

                onComplete()
            }
        }
    }

    // ── Image scanning ───────────────────────────────────────────────────────

    fun scan(contentResolver: ContentResolver) {
        if (_isScanning.value) return
        viewModelScope.launch {
            _isScanning.value = true

            val baseImages = withContext(Dispatchers.IO) {
                ImageEngine.loadImages(contentResolver)
            }

            val hashedImages = hashImages(baseImages, contentResolver)

            val exact: List<List<ImageItem>>
            val similar: List<List<ImageItem>>
            withContext(Dispatchers.IO) {
                coroutineScope {
                    val ed = async { ImageEngine.findGroupsByThreshold(hashedImages, threshold = 0) }
                    val sd = async { ImageEngine.findGroupsByThreshold(hashedImages, threshold = 5) }
                    exact   = ed.await()
                    similar = sd.await()
                }
            }

            withContext(Dispatchers.IO) {
                repository.saveAll(hashedImages, exact, similar)
            }
            _images.value        = hashedImages
            _exactGroups.value   = exact
            _similarGroups.value = similar
            val nowMs = System.currentTimeMillis()
            _lastScannedAt.value = nowMs
            prefs.edit().putLong(KEY_LAST_SCANNED, nowMs).apply()
            _isScanning.value    = false
        }
    }

    fun refresh(contentResolver: ContentResolver) {
        if (_isScanning.value) return
        viewModelScope.launch {
            _isScanning.value = true

            val currentFromDevice = withContext(Dispatchers.IO) {
                ImageEngine.loadImages(contentResolver)
            }

            val dbImages   = repository.getImages()
            val dbMap      = dbImages.associateBy { it.id }
            val currentMap = currentFromDevice.associateBy { it.id }

            val newIds      = currentMap.keys - dbMap.keys
            val deletedIds  = dbMap.keys - currentMap.keys
            val modifiedIds = currentMap.keys
                .intersect(dbMap.keys)
                .filter { id ->
                    val current = currentMap.getValue(id)
                    val stored  = dbMap.getValue(id)
                    current.size != stored.size || current.dateModified != stored.dateModified
                }.toSet()

            val toHashIds = newIds + modifiedIds

            val rehashed = if (toHashIds.isNotEmpty()) {
                val toHashItems = toHashIds.map { id -> currentMap.getValue(id) }
                hashImages(toHashItems, contentResolver)
            } else {
                emptyList()
            }

            val rehashedMap = rehashed.associateBy { it.id }

            val finalImages = (dbImages
                .filter { it.id !in deletedIds && it.id !in modifiedIds }
                    + rehashedMap.values)
                .sortedByDescending { it.id }

            val toUpsert = rehashed
            withContext(Dispatchers.IO) {
                repository.applyIncrementalUpdate(
                    finalImages   = finalImages,
                    toUpsert      = toUpsert,
                    deletedIds    = deletedIds,
                    exactGroups   = emptyList(),
                    similarGroups = emptyList()
                )
            }

            val exact: List<List<ImageItem>>
            val similar: List<List<ImageItem>>
            withContext(Dispatchers.IO) {
                coroutineScope {
                    val ed = async { ImageEngine.findGroupsByThreshold(finalImages, threshold = 0) }
                    val sd = async { ImageEngine.findGroupsByThreshold(finalImages, threshold = 5) }
                    exact   = ed.await()
                    similar = sd.await()
                }
            }

            repository.storeGroupsInMemory(exact, similar)

            _images.value        = finalImages
            _exactGroups.value   = exact
            _similarGroups.value = similar
            val nowMs = System.currentTimeMillis()
            _lastScannedAt.value = nowMs
            prefs.edit().putLong(KEY_LAST_SCANNED, nowMs).apply()
            _isScanning.value    = false
        }
    }

    private suspend fun hashImages(
        items: List<ImageItem>,
        contentResolver: ContentResolver
    ): List<ImageItem> = withContext(Dispatchers.IO) {
        coroutineScope {
            items.map { image ->
                async {
                    hashSemaphore.withPermit {
                        image.copy(
                            hash = ImageEngine.calculatePerceptualHash(image.uri, contentResolver)
                        )
                    }
                }
            }.awaitAll()
        }
    }

    // ── Image deletion ───────────────────────────────────────────────────────

    fun onDeleteConfirmed(deletedIds: Set<Long>) {
        viewModelScope.launch {
            val updatedImages = repository.getImages().filter { it.id !in deletedIds }
            val newExact   = ImageEngine.findGroupsByThreshold(updatedImages, threshold = 0)
            val newSimilar = ImageEngine.findGroupsByThreshold(updatedImages, threshold = 5)

            withContext(Dispatchers.IO) {
                repository.deleteImages(deletedIds, newExact, newSimilar)
            }

            _images.value        = updatedImages
            _exactGroups.value   = newExact
            _similarGroups.value = newSimilar
        }
    }

    fun viewModelScopeDelete(
        toDelete:        Set<Long>,
        contentResolver: ContentResolver,
        onComplete:      () -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            toDelete.forEach { id ->
                contentResolver.delete(
                    ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                    ), null, null
                )
            }
            withContext(Dispatchers.Main) { onComplete() }
        }
    }

    fun autoSelectedDuplicateIds(): Set<Long> =
        repository.getExactGroups().flatMap { group ->
            group.sortedByDescending { it.size }.drop(1)
        }.map { it.id }.toSet()

    // ── Factory ──────────────────────────────────────────────────────────────

    class Factory(
        private val repository: ImageRepository,
        private val prefs:      SharedPreferences
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MainViewModel(repository, prefs) as T
    }
}