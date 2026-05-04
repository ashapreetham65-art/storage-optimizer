package com.example.storageoptimizer.engine

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.example.storageoptimizer.data.FileItem

object FileEngine {

    fun loadFiles(contentResolver: ContentResolver): List<FileItem> {
        val seen     = mutableSetOf<Long>()
        val fileList = mutableListOf<FileItem>()

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.DATA
        )

        val selection =
            "${MediaStore.Files.FileColumns.SIZE} > 0 AND " +
                    "(" +
                    "${MediaStore.Files.FileColumns.MIME_TYPE} IS NULL OR (" +
                    "${MediaStore.Files.FileColumns.MIME_TYPE} NOT LIKE 'image/%' AND " +
                    "${MediaStore.Files.FileColumns.MIME_TYPE} NOT LIKE 'video/%' AND " +
                    "${MediaStore.Files.FileColumns.MIME_TYPE} NOT LIKE 'audio/%'" +
                    ")" +
                    ")"

        val sortOrder = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"

        // ── Query 1: MediaStore.Files ─────────────────────────────────────────
        contentResolver.query(collection, projection, selection, null, sortOrder)
            ?.use { cursor ->
                val idCol   = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val dmCol   = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val daCol   = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)

                while (cursor.moveToNext()) {
                    val id   = cursor.getLong(idCol)
                    if (id in seen) continue
                    seen += id

                    val size = cursor.getLong(sizeCol)
                    val name = cursor.getString(nameCol) ?: continue
                    if (size <= 0L || name.isBlank()) continue

                    val mime = cursor.getString(mimeCol) ?: guessMime(name)
                    val dm   = cursor.getLong(dmCol)
                    val da   = cursor.getLong(daCol)
                    val path = cursor.getString(dataCol) ?: ""
                    val uri  = Uri.withAppendedPath(collection, id.toString())

                    fileList.add(FileItem(id, uri, name, size, mime, dm, da, path))
                }
            }

        // ── Query 2: MediaStore.Downloads (Android 10+) ───────────────────────
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val dlCollection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL)
            contentResolver.query(dlCollection, projection, selection, null, sortOrder)
                ?.use { cursor ->
                    val idCol   = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                    val dmCol   = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                    val daCol   = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                    val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)

                    while (cursor.moveToNext()) {
                        val id   = cursor.getLong(idCol)
                        if (id in seen) continue
                        seen += id

                        val size = cursor.getLong(sizeCol)
                        val name = cursor.getString(nameCol) ?: continue
                        if (size <= 0L || name.isBlank()) continue

                        val mime = cursor.getString(mimeCol) ?: guessMime(name)
                        val dm   = cursor.getLong(dmCol)
                        val da   = cursor.getLong(daCol)
                        val path = cursor.getString(dataCol) ?: ""
                        val uri  = Uri.withAppendedPath(dlCollection, id.toString())

                        fileList.add(FileItem(id, uri, name, size, mime, dm, da, path))
                    }
                }
        }

        // Merge filesystem scan — fills gaps MediaStore misses
        val mediaStorePaths = fileList.map { it.path }.toSet()

        val fsFiles = loadFilesFromFilesystem()
            .filter { it.path.isNotBlank() && it.path !in mediaStorePaths }

        fileList.addAll(fsFiles)

        return fileList.sortedByDescending {
            if (it.dateAdded > 0L) it.dateAdded else it.dateModified
        }
    }


    fun loadFilesFromFilesystem(rootPath: String = "/storage/emulated/0"): List<FileItem> {
        val fileList = mutableListOf<FileItem>()
        val root     = java.io.File(rootPath)

        // Folders MediaStore completely misses — worth scanning directly
        val targetDirs = listOf(
            "Download",
            "Documents",
            "Android/data",   // app cache/data — visible with MANAGE_EXTERNAL_STORAGE
            "Android/obb"     // game data
        ).map { java.io.File(root, it) }
            .filter { it.exists() && it.isDirectory }

        fun scanDir(dir: java.io.File) {
            try {
                dir.listFiles()?.forEach { file ->
                    if (file.isDirectory) {
                        scanDir(file)   // recurse
                    } else if (file.isFile && file.length() > 0) {
                        val mime = guessMime(file.name)
                        // Skip media files — handled by ImageEngine
                        if (mime.startsWith("image/") ||
                            mime.startsWith("video/") ||
                            mime.startsWith("audio/")) return@forEach

                        fileList.add(
                            FileItem(
                                id           = file.absolutePath.hashCode().toLong(),
                                uri          = Uri.fromFile(file),
                                name         = file.name,
                                size         = file.length(),
                                mimeType     = mime,
                                dateModified = file.lastModified() / 1000L,
                                dateAdded    = file.lastModified() / 1000L,
                                path         = file.absolutePath,
                                hash         = null
                            )
                        )
                    }
                }
            } catch (_: SecurityException) {
                // Some subdirectories may still be restricted — skip them silently
            }
        }

        targetDirs.forEach { scanDir(it) }
        return fileList
    }

    // ── File hashing ──────────────────────────────────────────────────────────
    // Named calculateHash so MainViewModel can call FileEngine.calculateHash().
    // Reads first 64 KB + last 64 KB + mixes in file size — fast FNV-1a 64-bit.
    fun calculateHash(uri: Uri, contentResolver: ContentResolver): Long? {
        return try {
            val fileSize = contentResolver.openFileDescriptor(uri, "r")?.use {
                it.statSize
            } ?: return null

            contentResolver.openInputStream(uri)?.use { stream ->
                val chunkSize  = 65_536L
                val firstChunk = ByteArray(chunkSize.toInt())
                val firstRead  = stream.read(firstChunk)
                if (firstRead <= 0) return null

                var hash  = 0xcbf29ce484222325UL
                val prime = 0x100000001b3UL

                // FNV-1a over first chunk
                for (i in 0 until firstRead) {
                    hash = hash xor firstChunk[i].toLong().and(0xFF).toULong()
                    hash *= prime
                }

                // Mix in file size so truncated copies never collide
                hash = hash xor fileSize.toULong()
                hash *= prime

                // Also hash last 64 KB for large files
                if (fileSize > chunkSize * 2) {
                    val skipBytes = fileSize - chunkSize - firstRead
                    if (skipBytes > 0) stream.skip(skipBytes)
                    val lastChunk = ByteArray(chunkSize.toInt())
                    val lastRead  = stream.read(lastChunk)
                    if (lastRead > 0) {
                        for (i in 0 until lastRead) {
                            hash = hash xor lastChunk[i].toLong().and(0xFF).toULong()
                            hash *= prime
                        }
                    }
                }

                hash.toLong()
            }
        } catch (_: Exception) { null }
    }

    // ── Duplicate grouping ────────────────────────────────────────────────────
    // Named findDuplicateGroups so MainViewModel can call FileEngine.findDuplicateGroups().
    // Groups files sharing both size AND hash — exact duplicates only.
    fun findDuplicateGroups(files: List<FileItem>): List<List<FileItem>> {
        return files
            .filter { it.hash != null }
            .groupBy { it.size to it.hash!! }
            .values
            .filter { it.size > 1 }
            .sortedByDescending { it.size }
    }

    fun guessMime(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "pdf"          -> "application/pdf"
            "apk"          -> "application/vnd.android.package-archive"
            "zip"          -> "application/zip"
            "rar"          -> "application/x-rar-compressed"
            "7z"           -> "application/x-7z-compressed"
            "tar"          -> "application/x-tar"
            "gz"           -> "application/gzip"
            "doc"          -> "application/msword"
            "docx"         -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls"          -> "application/vnd.ms-excel"
            "xlsx"         -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt"          -> "application/vnd.ms-powerpoint"
            "pptx"         -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "txt"          -> "text/plain"
            "csv"          -> "text/csv"
            "json"         -> "application/json"
            "xml"          -> "text/xml"
            "html", "htm"  -> "text/html"
            "db", "sqlite" -> "application/x-sqlite3"
            "torrent"      -> "application/x-bittorrent"
            else           -> "application/octet-stream"
        }
    }

    fun categorize(files: List<FileItem>): Map<String, List<FileItem>> {
        return files.groupBy { file ->
            when {
                file.mimeType == "application/vnd.android.package-archive"        -> "APKs"
                file.mimeType.contains("zip") || file.mimeType.contains("rar") ||
                        file.mimeType.contains("tar") || file.mimeType.contains("7z") ||
                        file.mimeType.contains("compress")                                 -> "Archives"
                file.mimeType.contains("pdf")                                      -> "PDFs"
                file.mimeType.contains("word") || file.mimeType.contains("document") ||
                        file.mimeType == "text/plain"                                      -> "Documents"
                file.mimeType.contains("sheet") || file.mimeType.contains("excel") ||
                        file.mimeType.contains("csv")                                      -> "Spreadsheets"
                file.mimeType.contains("presentation") ||
                        file.mimeType.contains("powerpoint")                               -> "Presentations"
                else                                                               -> "Other"
            }
        }
    }

    fun formatBytes(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        val mb = bytes / (1024.0 * 1024.0)
        val kb = bytes / 1024.0
        return when {
            gb >= 1.0 -> "%.1f GB".format(gb)
            mb >= 1.0 -> "%.1f MB".format(mb)
            kb >= 1.0 -> "%.0f KB".format(kb)
            else      -> "${bytes} B"
        }
    }
}
