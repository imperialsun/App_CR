package com.demeter.speech.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

class AudioStaging(private val context: Context) {
    fun stageImportedAudio(uri: Uri): AudioAsset {
        val name = displayName(uri).ifBlank { "audio-import.wav" }
        val extension = name.substringAfterLast('.', "audio").lowercase()
        val target = File(context.cacheDir, "import-${System.currentTimeMillis()}.$extension")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Impossible d'ouvrir le fichier audio" }
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        return AudioAsset(
            file = target,
            displayName = name,
            origin = AudioOrigin.Imported,
            sourceUri = uri,
        )
    }

    fun newRecordingFile(): File {
        val dir = File(context.cacheDir, "recordings").apply { mkdirs() }
        return File(dir, "demeter-${System.currentTimeMillis()}.wav")
    }

    fun deleteCached(asset: AudioAsset?) {
        val file = asset?.file ?: return
        if (file.path.startsWith(context.cacheDir.path)) {
            file.delete()
        }
    }

    private fun displayName(uri: Uri): String {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index).orEmpty()
            }
        }
        return uri.lastPathSegment.orEmpty()
    }
}
