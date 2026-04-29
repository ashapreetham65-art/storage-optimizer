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

        // FIX 1: Removed "MIME_TYPE IS NOT NULL" — that was silently dropping
        // APKs, misc downloads, and anything MediaStore hadn't resolved yet.
        // Now we allow NULL mime and fall back to guessing from extension below.
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

                    // FIX 2: Null MIME → guess from extension instead of dropping the file
                    val mime = cursor.getString(mimeCol) ?: guessMime(name)
                    val dm   = cursor.getLong(dmCol)
                    val da   = cursor.getLong(daCol)
                    val path = cursor.getString(dataCol) ?: ""
                    val uri  = Uri.withAppendedPath(collection, id.toString())

                    fileList.add(FileItem(id, uri, name, size, mime, dm, da, path))
                }
            }

        // FIX 3: Also query MediaStore.Downloads (Android 10+).
        // Files downloaded via browser/apps often live here and nowhere else.
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
                        if (id in seen) continue   // skip anything already found above
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

        return fileList.sortedByDescending {
            if (it.dateAdded > 0L) it.dateAdded else it.dateModified
        }
    }

    /** Guess MIME type from file extension when MediaStore returns null */
    private fun guessMime(name: String): String {
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

    /** Groups files into broad categories by MIME type */
    fun categorize(files: List<FileItem>): Map<String, List<FileItem>> {
        return files.groupBy { file ->
            when {
                file.mimeType == "application/vnd.android.package-archive"   -> "APKs"
                file.mimeType.contains("zip") || file.mimeType.contains("rar") ||
                        file.mimeType.contains("tar") || file.mimeType.contains("7z") ||
                        file.mimeType.contains("compress")                            -> "Archives"
                file.mimeType.contains("pdf")                                 -> "PDFs"
                file.mimeType.contains("word") || file.mimeType.contains("document") ||
                        file.mimeType == "text/plain"                                 -> "Documents"
                file.mimeType.contains("sheet") || file.mimeType.contains("excel") ||
                        file.mimeType.contains("csv")                                 -> "Spreadsheets"
                file.mimeType.contains("presentation") || file.mimeType.contains("powerpoint") -> "Presentations"
                else                                                          -> "Other"
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