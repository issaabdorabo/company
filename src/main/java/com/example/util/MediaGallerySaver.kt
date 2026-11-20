package com.example.util

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

data class SavedFileResult(
    val contentUri: Uri,
    val displayPath: String,
    val savedToGallery: Boolean,
    val isPrivateVault: Boolean
)

object MediaGallerySaver {

    fun saveFile(
        context: Context,
        sourceTempFile: File,
        fileName: String,
        mimeType: String,
        autoSaveToGallery: Boolean,
        isPrivateVault: Boolean = false
    ): SavedFileResult {
        if (isPrivateVault) {
            return saveToPrivateHiddenVault(context, sourceTempFile, fileName)
        }

        val isVideo = mimeType.startsWith("video/", ignoreCase = true) ||
            fileName.endsWith(".mp4", true) ||
            fileName.endsWith(".webm", true) ||
            fileName.endsWith(".mov", true) ||
            fileName.endsWith(".mkv", true) ||
            fileName.endsWith(".m4v", true)

        val isImage = mimeType.startsWith("image/", ignoreCase = true) ||
            fileName.endsWith(".jpg", true) ||
            fileName.endsWith(".jpeg", true) ||
            fileName.endsWith(".png", true) ||
            fileName.endsWith(".webp", true)

        if (autoSaveToGallery && (isVideo || isImage)) {
            return saveToMediaStoreGallery(context, sourceTempFile, fileName, mimeType, isVideo)
        }

        return saveToDownloadsFolder(context, sourceTempFile, fileName, mimeType)
    }

    private fun saveToPrivateHiddenVault(
        context: Context,
        sourceFile: File,
        fileName: String
    ): SavedFileResult {
        val vaultDir = File(context.filesDir, ".private_vault").apply {
            mkdirs()
            val noMedia = File(this, ".nomedia")
            if (!noMedia.exists()) {
                runCatching { noMedia.createNewFile() }
            }
        }
        val destFile = resolveUniqueFile(vaultDir, fileName)
        FileInputStream(sourceFile).use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output, bufferSize = 64 * 1024)
            }
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            destFile
        )
        return SavedFileResult(
            contentUri = uri,
            displayPath = destFile.absolutePath,
            savedToGallery = false,
            isPrivateVault = true
        )
    }

    private fun saveToMediaStoreGallery(
        context: Context,
        sourceFile: File,
        fileName: String,
        mimeType: String,
        isVideo: Boolean
    ): SavedFileResult {
        val normalizedMime = when {
            isVideo && !mimeType.startsWith("video/") -> "video/mp4"
            !isVideo && !mimeType.startsWith("image/") -> "image/jpeg"
            else -> mimeType
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (isVideo) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }

            val relativeDir = if (isVideo) {
                "${Environment.DIRECTORY_MOVIES}/SmartBrowser"
            } else {
                "${Environment.DIRECTORY_PICTURES}/SmartBrowser"
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, normalizedMime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val itemUri = resolver.insert(collection, values)
                ?: return saveToDownloadsFolder(context, sourceFile, fileName, normalizedMime)

            try {
                resolver.openOutputStream(itemUri)?.use { output ->
                    FileInputStream(sourceFile).use { input ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    }
                } ?: throw IllegalStateException("Failed to open gallery output stream")

                val publishValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(itemUri, publishValues, null, null)

                val diskFile = File(
                    Environment.getExternalStoragePublicDirectory(
                        if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                    ),
                    "SmartBrowser/$fileName"
                )
                return SavedFileResult(
                    contentUri = itemUri,
                    displayPath = diskFile.absolutePath,
                    savedToGallery = true,
                    isPrivateVault = false
                )
            } catch (e: Exception) {
                runCatching { resolver.delete(itemUri, null, null) }
                throw e
            }
        } else {
            val publicRoot = Environment.getExternalStoragePublicDirectory(
                if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
            )
            val targetDir = File(publicRoot, "SmartBrowser").apply { mkdirs() }
            val destFile = resolveUniqueFile(targetDir, fileName)
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }
            MediaScannerConnection.scanFile(
                context,
                arrayOf(destFile.absolutePath),
                arrayOf(normalizedMime),
                null
            )
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                destFile
            )
            return SavedFileResult(
                contentUri = uri,
                displayPath = destFile.absolutePath,
                savedToGallery = true,
                isPrivateVault = false
            )
        }
    }

    private fun saveToDownloadsFolder(
        context: Context,
        sourceFile: File,
        fileName: String,
        mimeType: String
    ): SavedFileResult {
        val downloadsDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "SmartBrowser"
        ).apply { mkdirs() }

        val destFile = resolveUniqueFile(downloadsDir, fileName)
        FileInputStream(sourceFile).use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output, bufferSize = 64 * 1024)
            }
        }
        MediaScannerConnection.scanFile(
            context,
            arrayOf(destFile.absolutePath),
            arrayOf(mimeType),
            null
        )
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            destFile
        )
        return SavedFileResult(
            contentUri = uri,
            displayPath = destFile.absolutePath,
            savedToGallery = false,
            isPrivateVault = false
        )
    }

    private fun resolveUniqueFile(directory: File, fileName: String): File {
        val base = fileName.substringBeforeLast('.', fileName)
        val ext = fileName.substringAfterLast('.', "")
        var candidate = File(directory, fileName)
        var counter = 1
        while (candidate.exists()) {
            val suffixName = if (ext.isNotEmpty()) "${base}_$counter.$ext" else "${base}_$counter"
            candidate = File(directory, suffixName)
            counter++
        }
        return candidate
    }
}
