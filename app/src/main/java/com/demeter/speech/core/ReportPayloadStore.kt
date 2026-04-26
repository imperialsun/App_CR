package com.demeter.speech.core

import android.content.Context
import com.google.gson.Gson
import java.io.File

data class CorrectedReportPayload(
    val title: String,
    val rawTranscript: String,
    val editedTranscript: String,
    val segments: List<TranscriptSegment>,
    val detailLevels: ReportDetailLevels,
)

class ReportPayloadStore(
    context: Context,
    private val gson: Gson = Gson(),
) {
    private val directory = File(context.cacheDir, "report-payloads").apply { mkdirs() }

    fun write(payload: CorrectedReportPayload): File {
        val file = File.createTempFile("report-", ".json", directory)
        file.writeText(gson.toJson(payload))
        return file
    }

    fun read(file: File): CorrectedReportPayload {
        require(file.exists()) { "Compte rendu temporaire introuvable" }
        return gson.fromJson(file.readText(), CorrectedReportPayload::class.java)
    }

    fun delete(file: File?) {
        if (file?.path?.startsWith(directory.path) == true) {
            file.delete()
        }
    }

    fun clearAll() {
        directory.listFiles()?.forEach { it.deleteRecursively() }
    }
}
