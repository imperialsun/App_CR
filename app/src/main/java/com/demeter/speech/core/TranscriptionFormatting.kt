package com.demeter.speech.core

private val speakerLinePattern = Regex("""^\s*([^:]{1,64})\s*:\s*(.*)$""")
private val chunkSeparatorPattern = Regex("""\n\s*\n(?=Partie\s+\d+\n)""")
private val chunkHeaderPattern = Regex("""^\s*Partie\s+\d+\s*$""", RegexOption.IGNORE_CASE)

fun buildSpeakerAssignmentsFromTranscript(transcript: String): List<SpeakerAssignmentUi> {
    val labels = extractSpeakerLabels(transcript)
    return labels.map { label -> SpeakerAssignmentUi(speakerId = label) }
}

fun formatTranscriptForDisplay(transcript: String): String {
    val normalizedTranscript = transcript.trim()
    if (normalizedTranscript.isBlank()) {
        return ""
    }

    val speakerLabelMap = buildSpeakerLabelMap(normalizedTranscript)
    val lines = normalizedTranscript
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toList()

    if (speakerLabelMap.isEmpty()) {
        return lines.joinToString("\n\n")
    }

    return lines
        .map { line -> formatTranscriptLine(line, speakerLabelMap) }
        .joinToString("\n\n")
        .trim()
}

fun formatTranscriptForDisplay(transcript: String, segments: List<DemeterTranscriptionSegmentDto>): String {
    val segmentText = formatTranscriptSegments(segments)
    if (segmentText.isNotBlank()) {
        return segmentText
    }
    return formatTranscriptForDisplay(transcript)
}

fun joinChunkedTranscriptDisplay(chunks: List<String>): String {
    val normalizedChunks = chunks.mapNotNull { chunk ->
        chunk.trim().takeIf { it.isNotBlank() }
    }
    if (normalizedChunks.isEmpty()) {
        return ""
    }
    if (normalizedChunks.size == 1) {
        return normalizedChunks.first()
    }

    return normalizedChunks
        .mapIndexed { index, chunk ->
            buildString {
                append("Partie ")
                append(index + 1)
                append('\n')
                append(chunk)
            }
        }
        .joinToString("\n\n")
}

fun splitChunkedTranscriptDisplay(transcript: String): List<String> {
    val normalizedTranscript = transcript.trim()
    if (normalizedTranscript.isBlank()) {
        return emptyList()
    }
    if (!normalizedTranscript.startsWith("Partie ")) {
        return listOf(normalizedTranscript)
    }

    return normalizedTranscript
        .split(chunkSeparatorPattern)
        .mapNotNull { section ->
            val body = extractChunkBody(section)
            body.takeIf { it.isNotBlank() }
        }
}

fun renderTranscriptChunk(rawText: String, speakerAssignments: List<SpeakerAssignmentUi>): String {
    val normalizedRawText = rawText.trim()
    if (normalizedRawText.isBlank()) {
        return ""
    }

    val replacements = speakerAssignments.mapNotNull { assignment ->
        val rawLabel = assignment.speakerId.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val confirmedLabel = assignment.confirmedLabel?.trim()?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        if (!assignment.isValidated) {
            return@mapNotNull null
        }
        rawLabel to confirmedLabel
    }.toMap()

    if (replacements.isEmpty()) {
        return normalizedRawText
    }

    return normalizedRawText
        .lineSequence()
        .map { line -> replaceSpeakerLabel(line, replacements) }
        .joinToString("\n")
        .trim()
}

fun renderTranscriptFromChunks(chunks: List<MeetingTranscriptChunkUi>): String {
    if (chunks.isEmpty()) {
        return ""
    }
    return joinChunkedTranscriptDisplay(
        chunks.map { chunk ->
            renderTranscriptChunk(chunk.rawText, chunk.speakerAssignments)
        }
    )
}

private fun formatTranscriptSegments(segments: List<DemeterTranscriptionSegmentDto>): String {
    if (segments.isEmpty()) {
        return ""
    }

    val speakerLabelMap = linkedMapOf<String, String>()
    var genericSpeakerIndex = 1

    val lines = buildList {
        segments.forEach { segment ->
            val text = segment.text.trim()
            if (text.isBlank()) {
                return@forEach
            }
            val rawSpeaker = segment.preferredSpeakerLabel()
            val displaySpeaker = if (rawSpeaker.isNullOrBlank()) {
                ""
            } else {
                speakerLabelMap.getOrPut(rawSpeaker) {
                    if (looksLikeGenericSpeakerLabel(rawSpeaker)) {
                        val label = "Speaker $genericSpeakerIndex"
                        genericSpeakerIndex += 1
                        label
                    } else {
                        rawSpeaker
                    }
                }
            }
            add(if (displaySpeaker.isBlank()) text else "$displaySpeaker: $text")
        }
    }

    return lines.joinToString("\n\n").trim()
}

private fun extractChunkBody(section: String): String {
    val trimmedSection = section.trim()
    if (trimmedSection.isBlank()) {
        return ""
    }
    if (!chunkHeaderPattern.matches(trimmedSection.lineSequence().firstOrNull().orEmpty())) {
        return trimmedSection
    }
    return trimmedSection
        .lineSequence()
        .drop(1)
        .joinToString("\n")
        .trim()
}

private fun replaceSpeakerLabel(line: String, replacements: Map<String, String>): String {
    val match = speakerLinePattern.matchEntire(line) ?: return line
    val candidate = match.groupValues[1].trim()
    val body = match.groupValues[2].trimStart()
    val replacement = replacements[candidate] ?: return line
    if (body.isBlank()) {
        return line
    }
    return "$replacement: $body"
}

private fun buildSpeakerLabelMap(transcript: String): Map<String, String> {
    val speakerLabelMap = linkedMapOf<String, String>()
    var genericSpeakerIndex = 1

    transcript.lineSequence().forEach { line ->
        val candidate = extractSpeakerLabelCandidate(line) ?: return@forEach
        if (speakerLabelMap.containsKey(candidate)) {
            return@forEach
        }
        val displayLabel = if (looksLikeGenericSpeakerLabel(candidate)) {
            val label = "Speaker $genericSpeakerIndex"
            genericSpeakerIndex += 1
            label
        } else {
            candidate
        }
        speakerLabelMap[candidate] = displayLabel
    }

    return speakerLabelMap
}

private fun extractSpeakerLabels(transcript: String): List<String> {
    val labels = linkedSetOf<String>()
    val speakerLabelMap = buildSpeakerLabelMap(transcript)
    speakerLabelMap.values.forEach { label ->
        if (label.isNotBlank()) {
            labels.add(label)
        }
    }
    if (labels.isEmpty()) {
        transcript.lineSequence().forEach { line ->
            val candidate = extractSpeakerLabelCandidate(line) ?: return@forEach
            if (candidate.isNotBlank()) {
                labels.add(candidate)
            }
        }
    }
    return labels.toList()
}

private fun extractSpeakerLabelCandidate(line: String): String? {
    val match = speakerLinePattern.matchEntire(line) ?: return null
    val candidate = match.groupValues[1].trim()
    if (candidate.isBlank() || candidate.length > 64) {
        return null
    }
    if (!candidate.any(Char::isLetter)) {
        return null
    }
    return candidate
}

private fun formatTranscriptLine(line: String, speakerLabelMap: Map<String, String>): String {
    val match = speakerLinePattern.matchEntire(line) ?: return line
    val candidate = match.groupValues[1].trim()
    val body = match.groupValues[2].trimStart()
    val displayLabel = speakerLabelMap[candidate] ?: candidate
    return if (displayLabel.isBlank() || body.isBlank()) {
        line
    } else {
        "$displayLabel: $body"
    }
}

private fun DemeterTranscriptionSegmentDto.preferredSpeakerLabel(): String? {
    val speakerName = speakerName?.trim().orEmpty()
    if (speakerName.isNotBlank()) {
        return speakerName
    }
    val speakerId = speakerId?.trim().orEmpty()
    if (speakerId.isNotBlank()) {
        return speakerId
    }
    return null
}

private fun looksLikeGenericSpeakerLabel(value: String): Boolean {
    val normalized = value.trim().lowercase()
    if (normalized == "speaker") {
        return true
    }
    if (!normalized.startsWith("speaker")) {
        return false
    }
    val suffix = normalized.removePrefix("speaker").trim()
    if (suffix.isBlank()) {
        return true
    }
    return suffix.all { it.isDigit() || it == '_' || it == '-' || it.isWhitespace() }
}
