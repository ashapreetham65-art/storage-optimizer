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

    private val hashSemaphore = Semaphore(4)

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

    // ── File scanning ────────────────────────────────────────────────────────

    fun scanFiles(contentResolver: ContentResolver) {
        if (_isScanningFiles.value) return
        viewModelScope.launch {
            _isScanningFiles.value = true
            val scanned = withContext(Dispatchers.IO) {
                FileEngine.loadFiles(contentResolver)
            }
            _files.value           = scanned
            _isScanningFiles.value = false
        }
    }

    // ── Full scan (first time / HomeScreen "Scan Storage") ───────────────────

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

    // ── Incremental refresh ──────────────────────────────────────────────────

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

    // ── Hashing helper ───────────────────────────────────────────────────────

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

    // ── Deletion ─────────────────────────────────────────────────────────────

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