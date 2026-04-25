package com.demeter.speech.ui

import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.demeter.speech.MobileActions
import com.demeter.speech.R
import com.demeter.speech.core.AppScreen
import com.demeter.speech.core.DetailLevel
import com.demeter.speech.core.MobileUiState
import com.demeter.speech.core.OperationStatus
import com.demeter.speech.core.ReportFormat
import com.demeter.speech.core.SpeakerAssignment
import com.demeter.speech.core.TranscriptChunk
import com.demeter.speech.core.TranscriptSegment
import com.demeter.speech.core.resolveSpeakerLabel
import com.demeter.speech.core.speakerAssignmentKey
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemeterMobileApp(
    state: MobileUiState,
    actions: MobileActions,
    onRequireRecordingPermission: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) actions.importAudio(uri)
    }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri: Uri? ->
        if (uri != null) actions.saveAudioTo(uri)
    }

    LaunchedEffect(state.error) {
        val error = state.error
        if (error != null) {
            snackbarHostState.showSnackbar(error)
            actions.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (state.screen != AppScreen.Auth) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BrandLogo(
                                drawableRes = R.drawable.logo_splash,
                                size = 28,
                                contentDescription = "Demeter Sante",
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Demeter Sante", fontWeight = FontWeight.SemiBold)
                        }
                    },
                    navigationIcon = {
                        if (state.screen != AppScreen.Home) {
                            IconButton(onClick = actions::goHome) {
                                Icon(Icons.Default.Home, contentDescription = "Accueil")
                            }
                        }
                    },
                    actions = {
                        if (state.user != null) TextButton(onClick = actions::logout) { Text("Quitter") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (state.checkingSession) {
                CircularProgressIndicator()
            } else {
                AnimatedContent(
                    targetState = state.screen,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
                    label = "screen",
                ) { screen ->
                    when (screen) {
                        AppScreen.Auth -> LoginScreen(state, actions)
                        AppScreen.Home -> HomeScreen(
                            onRecord = onRequireRecordingPermission,
                            onImport = { importLauncher.launch(arrayOf("audio/*")) },
                        )
                        AppScreen.Recording -> RecordingScreen(state, actions)
                        AppScreen.AssignChoice -> AssignChoiceScreen(state, actions)
                        AppScreen.TranscriptionWait -> WaitScreen(state)
                        AppScreen.SpeakerReview -> SpeakerReviewScreen(state, actions)
                        AppScreen.ReportSettings -> ReportSettingsScreen(state, actions)
                        AppScreen.Processing -> ProcessingScreen(state)
                        AppScreen.Success -> SuccessScreen(
                            state = state,
                            onSave = { saveLauncher.launch("demeter-audio.wav") },
                            onDiscard = actions::discardAudioAndHome,
                            onHome = actions::goHome,
                        )
                    }
                }
            }
            if (!state.checkingSession && state.screen != AppScreen.Auth && state.user?.email?.isNotBlank() == true) {
                AccountEmailBadge(
                    email = state.user.email,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
        }
    }
}

@Composable
private fun BrandLogo(
    drawableRes: Int,
    size: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(drawableRes),
        contentDescription = contentDescription,
        modifier = modifier.size(size.dp),
        contentScale = ContentScale.Fit,
    )
}

@Composable
private fun LoginScreen(state: MobileUiState, actions: MobileActions) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.logo_login),
            contentDescription = "Demeter Sante",
            modifier = Modifier.size(132.dp),
            contentScale = ContentScale.Fit,
        )
        Spacer(Modifier.height(18.dp))
        Text("Bienvenue sur l'application de compte rendu Demeter.", textAlign = TextAlign.Center, color = Color(0xFF4B5563))
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(
            value = state.email,
            onValueChange = actions::updateEmail,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            shape = RoundedCornerShape(8.dp),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.password,
            onValueChange = actions::updatePassword,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Mot de passe") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            shape = RoundedCornerShape(8.dp),
        )
        Spacer(Modifier.height(22.dp))
        PrimaryActionButton(
            text = if (state.busy) "Connexion" else "Se connecter",
            enabled = !state.busy,
            onClick = actions::login,
        )
    }
}

@Composable
private fun HomeScreen(onRecord: () -> Unit, onImport: () -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SoundWaveAnimation()
        Spacer(Modifier.height(4.dp))
        LargeChoiceButton(
            icon = { Icon(Icons.Default.Mic, contentDescription = null) },
            title = "Enregistrement direct",
            onClick = onRecord,
        )
        LargeChoiceButton(
            icon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
            title = "Ouvrir fichier audio",
            onClick = onImport,
        )
    }
}

@Composable
private fun SoundWaveAnimation() {
    val transition = rememberInfiniteTransition(label = "home-wave")
    val bars = listOf(0, 110, 220, 330, 440, 550, 660)
    Row(
        modifier = Modifier
            .height(58.dp)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        bars.forEachIndexed { index, delayMillis ->
            val height by transition.animateFloat(
                initialValue = if (index % 2 == 0) 14f else 28f,
                targetValue = if (index % 2 == 0) 34f else 18f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 780, delayMillis = delayMillis),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "wave-bar-$index",
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .width(5.dp)
                    .height(height.dp)
                    .background(Color(0xFF16A34A), RoundedCornerShape(50)),
            )
        }
    }
}

@Composable
private fun AccountEmailBadge(email: String, modifier: Modifier = Modifier) {
    Text(
        text = email,
        modifier = modifier
            .background(Color.White.copy(alpha = 0.92f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        color = Color(0xFF4B5563),
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun RecordingScreen(state: MobileUiState, actions: MobileActions) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        RecordingOrb(recording = state.recording, paused = state.paused)
        Spacer(Modifier.height(28.dp))
        Text(formatElapsed(state.elapsedMs), fontSize = 36.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(26.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = if (state.paused) actions::resumeRecording else actions::pauseRecording,
                modifier = Modifier
                    .size(64.dp)
                    .background(Color.White, CircleShape),
            ) {
                Icon(if (state.paused) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = "Pause")
            }
            Button(
                onClick = actions::finishRecording,
                modifier = Modifier.height(56.dp),
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Terminer")
            }
        }
    }
}

@Composable
private fun RecordingOrb(recording: Boolean, paused: Boolean) {
    val transition = rememberInfiniteTransition(label = "recording")
    val scale by transition.animateFloat(
        initialValue = if (paused) 0.92f else 0.85f,
        targetValue = if (paused) 0.98f else 1.18f,
        animationSpec = infiniteRepeatable(tween(if (paused) 1200 else 760), RepeatMode.Reverse),
        label = "scale",
    )
    val alpha by transition.animateFloat(
        initialValue = if (paused) 0.32f else 0.18f,
        targetValue = if (paused) 0.48f else 0.34f,
        animationSpec = infiniteRepeatable(tween(if (paused) 1200 else 760), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(190.dp)) {
        Box(
            modifier = Modifier
                .size(170.dp)
                .scale(scale)
                .alpha(alpha)
                .background(if (paused) Color(0xFF94A3B8) else Color(0xFFEF4444), CircleShape),
        )
        Box(
            modifier = Modifier
                .size(118.dp)
                .background(if (paused) Color(0xFFCBD5E1) else Color(0xFFDC2626), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(targetState = paused, label = "record-icon") {
                Icon(
                    imageVector = if (it) Icons.Default.Pause else Icons.Default.Mic,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(42.dp),
                )
            }
        }
    }
}

@Composable
private fun AssignChoiceScreen(state: MobileUiState, actions: MobileActions) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(state.audio?.displayName.orEmpty(), color = Color(0xFF6B7280))
        LargeChoiceButton(title = "Assigner les interlocuteurs", icon = { Icon(Icons.Default.Check, null) }) {
            actions.chooseSpeakerAssignment(true)
        }
        LargeChoiceButton(title = "Passer cette étape", icon = { Icon(Icons.Default.Send, null) }) {
            actions.chooseSpeakerAssignment(false)
        }
    }
}

@Composable
private fun WaitScreen(state: MobileUiState) {
    WaitingExperience(
        title = transcriptionWaitTitle(state.operation, state.waitMessage),
        message = state.waitJoke,
        progress = state.operation?.progress?.toFloat()?.coerceIn(0f, 1f) ?: 0.12f,
        steps = transcriptionWaitingSteps(state.operation),
    )
}

@Composable
private fun SpeakerReviewScreen(state: MobileUiState, actions: MobileActions) {
    val speakerEntries = remember(state.transcriptSegments) {
        collectSpeakerAssignmentEntries(state.transcriptSegments)
    }
    val speakerEntriesByChunk = remember(speakerEntries) {
        speakerEntries.groupBy { it.chunkIndex }
    }
    val chunkGroups = remember(state.transcriptChunks, state.transcriptSegments) {
        speakerReviewChunks(state.transcriptChunks, state.transcriptSegments)
    }
    var expandedChunkIndexes by remember(chunkGroups.map { it.index }) {
        mutableStateOf(emptySet<Int>())
    }
    var assignmentChunkIndex by remember { mutableStateOf<Int?>(null) }
    var editingSegment by remember { mutableStateOf<TranscriptSegment?>(null) }

    Column(Modifier.fillMaxSize()) {
        Text("Interlocuteurs", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (chunkGroups.size <= 1) {
                val chunk = chunkGroups.firstOrNull()
                if (chunk != null) {
                    item(key = "chunk-header-${chunk.index}") {
                        SpeakerChunkHeader(
                            chunk = chunk,
                            expanded = null,
                            speakerCount = speakerEntriesByChunk[chunk.index].orEmpty().size,
                            onNameSpeakers = { assignmentChunkIndex = chunk.index },
                        )
                    }
                    item(key = "audio-player") {
                        ChunkAudioPlayer(
                            audioPath = state.audio?.file?.absolutePath,
                            startMs = chunkStartMs(chunk),
                            endMs = chunkEndMs(chunk),
                        )
                    }
                }
                items(state.transcriptSegments, key = { it.id + it.chunkIndex }) { segment ->
                    SpeakerSegmentCard(
                        segment = segment,
                        speakerOptions = speakerEntriesByChunk[segment.chunkIndex].orEmpty(),
                        assignments = state.speakerAssignments,
                        onSpeakerSelected = { speakerId -> actions.updateSegmentSpeaker(segment.id, speakerId) },
                        onTextClick = { editingSegment = segment },
                    )
                }
            } else {
                chunkGroups.forEach { chunk ->
                    val isExpanded = expandedChunkIndexes.contains(chunk.index)
                    item(key = "chunk-header-${chunk.index}") {
                        SpeakerChunkHeader(
                            chunk = chunk,
                            expanded = isExpanded,
                            speakerCount = speakerEntriesByChunk[chunk.index].orEmpty().size,
                            showNameSpeakers = isExpanded,
                            onToggleExpanded = {
                                expandedChunkIndexes = if (isExpanded) {
                                    expandedChunkIndexes - chunk.index
                                } else {
                                    expandedChunkIndexes + chunk.index
                                }
                            },
                            onNameSpeakers = { assignmentChunkIndex = chunk.index },
                        )
                    }
                    if (isExpanded) {
                        item(key = "chunk-audio-${chunk.index}") {
                            ChunkAudioPlayer(
                                audioPath = state.audio?.file?.absolutePath,
                                startMs = chunkStartMs(chunk),
                                endMs = chunkEndMs(chunk),
                            )
                        }
                        items(chunk.segments, key = { "chunk-${chunk.index}-segment-${it.id}" }) { segment ->
                            SpeakerSegmentCard(
                                segment = segment,
                                speakerOptions = speakerEntriesByChunk[segment.chunkIndex].orEmpty(),
                                assignments = state.speakerAssignments,
                                onSpeakerSelected = { speakerId -> actions.updateSegmentSpeaker(segment.id, speakerId) },
                                onTextClick = { editingSegment = segment },
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        PrimaryActionButton("Passer au compte rendu", onClick = actions::goToReportSettings)
    }

    assignmentChunkIndex?.let { chunkIndex ->
        val entries = speakerEntriesByChunk[chunkIndex].orEmpty()
        SpeakerAssignmentDialog(
            entries = entries,
            partLabel = "Partie ${chunkIndex + 1}",
            assignments = state.speakerAssignments,
            onApply = { assignments ->
                actions.applySpeakerAssignments(state.speakerAssignments + assignments)
                assignmentChunkIndex = null
            },
            onDismiss = { assignmentChunkIndex = null },
        )
    }

    editingSegment?.let { segment ->
        SegmentTextDialog(
            segment = segment,
            onSave = { value ->
                actions.updateSegmentText(segment.id, value)
                editingSegment = null
            },
            onDismiss = { editingSegment = null },
        )
    }
}

@Composable
private fun SpeakerChunkHeader(
    chunk: TranscriptChunk,
    expanded: Boolean?,
    speakerCount: Int,
    onNameSpeakers: () -> Unit,
    showNameSpeakers: Boolean = true,
    onToggleExpanded: (() -> Unit)? = null,
) {
    val startMs = remember(chunk) { chunkStartMs(chunk) }
    val endMs = remember(chunk) { chunkEndMs(chunk) }

    Surface(shape = RoundedCornerShape(8.dp), color = Color.White, tonalElevation = 0.dp) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (onToggleExpanded != null) Modifier.clickable(onClick = onToggleExpanded) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Partie ${chunk.index + 1}", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    Text(
                        "${formatShortDuration(startMs, endMs)} · ${formatSegmentCount(chunk.segments.size)}",
                        color = Color(0xFF6B7280),
                        fontSize = 12.sp,
                    )
                }
                if (expanded != null) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Replier" else "Ouvrir",
                    )
                }
            }
            if (showNameSpeakers) {
                SpeakerAssignmentButton(
                    speakerCount = speakerCount,
                    onClick = onNameSpeakers,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SpeakerAssignmentButton(
    speakerCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
    ) {
        Icon(Icons.Default.Person, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Nommer les intervenants (${formatSpeakerCount(speakerCount)})")
    }
}

@Composable
private fun SpeakerSegmentCard(
    segment: TranscriptSegment,
    speakerOptions: List<SpeakerAssignmentEntry>,
    assignments: Map<String, SpeakerAssignment>,
    onSpeakerSelected: (String) -> Unit,
    onTextClick: () -> Unit,
) {
    var expanded by remember(segment.id) { mutableStateOf(false) }
    val speakerLabel = resolveSpeakerLabel(segment, assignments)

    Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFF8FAFC), tonalElevation = 0.dp) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    enabled = speakerOptions.isNotEmpty(),
                ) {
                    Text(speakerLabel, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    speakerOptions.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(assignedSpeakerLabel(option, assignments), fontWeight = FontWeight.SemiBold)
                                    Text(option.speakerId, color = Color(0xFF6B7280), fontSize = 12.sp)
                                }
                            },
                            onClick = {
                                onSpeakerSelected(option.speakerId)
                                expanded = false
                            },
                        )
                    }
                }
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onTextClick),
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFF8FAFC),
            ) {
                Text(
                    text = segment.text.ifBlank { "Texte vide" },
                    modifier = Modifier.padding(12.dp),
                    color = if (segment.text.isBlank()) Color(0xFF6B7280) else Color(0xFF111827),
                    textAlign = TextAlign.Start,
                )
            }
        }
    }
}

@Composable
private fun ChunkAudioPlayer(
    audioPath: String?,
    startMs: Int,
    endMs: Int,
) {
    if (audioPath.isNullOrBlank() || endMs <= startMs) {
        Text("Extrait audio indisponible", color = Color(0xFF6B7280), fontSize = 12.sp)
        return
    }

    var mediaPlayer by remember(audioPath, startMs, endMs) { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember(audioPath, startMs, endMs) { mutableStateOf(false) }
    var currentMs by remember(audioPath, startMs, endMs) { mutableStateOf(startMs) }
    var playbackError by remember(audioPath, startMs, endMs) { mutableStateOf<String?>(null) }

    fun releasePlayer() {
        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        }
        mediaPlayer = null
        isPlaying = false
    }

    fun ensurePlayer(): MediaPlayer? {
        val existing = mediaPlayer
        if (existing != null) return existing
        return runCatching {
            MediaPlayer().apply {
                setDataSource(audioPath)
                setOnCompletionListener {
                    isPlaying = false
                    currentMs = startMs
                    runCatching { seekTo(startMs) }
                }
                prepare()
                seekTo(startMs)
            }
        }.onSuccess {
            mediaPlayer = it
            playbackError = null
        }.onFailure {
            playbackError = "Lecture audio indisponible"
        }.getOrNull()
    }

    DisposableEffect(audioPath, startMs, endMs) {
        onDispose { releasePlayer() }
    }

    LaunchedEffect(isPlaying, startMs, endMs, mediaPlayer) {
        while (isPlaying) {
            val position = mediaPlayer?.currentPosition ?: startMs
            if (position >= endMs) {
                mediaPlayer?.pause()
                mediaPlayer?.seekTo(startMs)
                currentMs = startMs
                isPlaying = false
            } else {
                currentMs = position.coerceIn(startMs, endMs)
                delay(250)
            }
        }
    }

    Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFF1F5F9), tonalElevation = 0.dp) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        val player = ensurePlayer() ?: return@Button
                        if (isPlaying) {
                            player.pause()
                            isPlaying = false
                        } else {
                            if (player.currentPosition < startMs || player.currentPosition >= endMs) {
                                player.seekTo(startMs)
                                currentMs = startMs
                            }
                            player.start()
                            isPlaying = true
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(42.dp),
                ) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (isPlaying) "Pause" else "Lire")
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    "${formatClockMs(currentMs)} / ${formatClockMs(endMs)}",
                    color = Color(0xFF4B5563),
                    fontSize = 13.sp,
                )
            }
            Slider(
                value = currentMs.coerceIn(startMs, endMs).toFloat(),
                onValueChange = { value ->
                    val target = value.roundToInt().coerceIn(startMs, endMs)
                    currentMs = target
                    mediaPlayer?.seekTo(target)
                },
                valueRange = startMs.toFloat()..endMs.toFloat(),
            )
            playbackError?.let {
                Text(it, color = Color(0xFFB91C1C), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SpeakerAssignmentDialog(
    entries: List<SpeakerAssignmentEntry>,
    partLabel: String,
    assignments: Map<String, SpeakerAssignment>,
    onApply: (Map<String, SpeakerAssignment>) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(entries, assignments) {
        mutableStateOf(entries.associate { it.key to (assignments[it.key] ?: SpeakerAssignment()) })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nommer les intervenants") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(partLabel, fontWeight = FontWeight.SemiBold)
                if (entries.isEmpty()) {
                    Text("Aucun intervenant détecté.", color = Color(0xFF6B7280))
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(entries, key = { it.key }) { entry ->
                            val current = draft[entry.key] ?: SpeakerAssignment()
                            Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFF8FAFC)) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(entry.speakerId, color = Color(0xFF6B7280), fontSize = 12.sp)
                                    OutlinedTextField(
                                        value = current.lastName,
                                        onValueChange = { value ->
                                            draft = draft.toMutableMap().apply {
                                                put(entry.key, current.copy(lastName = value))
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("Nom") },
                                        singleLine = true,
                                        shape = RoundedCornerShape(8.dp),
                                    )
                                    OutlinedTextField(
                                        value = current.firstName,
                                        onValueChange = { value ->
                                            draft = draft.toMutableMap().apply {
                                                put(entry.key, current.copy(firstName = value))
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("Prénom") },
                                        singleLine = true,
                                        shape = RoundedCornerShape(8.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(draft) }) {
                Text("Appliquer")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        },
    )
}

@Composable
private fun SegmentTextDialog(
    segment: TranscriptSegment,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(segment.id, segment.text) { mutableStateOf(segment.text) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modifier le segment") },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 6,
                shape = RoundedCornerShape(8.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }) {
                Text("Enregistrer")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annuler")
            }
        },
    )
}

private data class SpeakerAssignmentEntry(
    val key: String,
    val chunkIndex: Int,
    val speakerId: String,
)

private fun collectSpeakerAssignmentEntries(segments: List<TranscriptSegment>): List<SpeakerAssignmentEntry> {
    return segments
        .mapNotNull { segment ->
            val speakerId = segment.speaker.trim()
            if (speakerId.isBlank()) {
                null
            } else {
                SpeakerAssignmentEntry(
                    key = speakerAssignmentKey(segment.chunkIndex, speakerId),
                    chunkIndex = segment.chunkIndex,
                    speakerId = speakerId,
                )
            }
        }
        .distinctBy { it.key }
        .sortedWith(compareBy<SpeakerAssignmentEntry> { it.chunkIndex }.thenBy { it.speakerId })
}

private fun assignedSpeakerLabel(
    entry: SpeakerAssignmentEntry,
    assignments: Map<String, SpeakerAssignment>,
): String {
    return assignments[entry.key]?.displayName?.ifBlank { entry.speakerId } ?: entry.speakerId
}

private fun formatSpeakerCount(count: Int): String {
    return if (count <= 1) "$count intervenant" else "$count intervenants"
}

private fun formatSegmentCount(count: Int): String {
    return if (count <= 1) "$count segment" else "$count segments"
}

private fun speakerReviewChunks(
    chunks: List<TranscriptChunk>,
    segments: List<TranscriptSegment>,
): List<TranscriptChunk> {
    if (chunks.isNotEmpty()) {
        return chunks.sortedBy { it.index }
    }
    return segments
        .groupBy { it.chunkIndex }
        .toSortedMap()
        .map { (chunkIndex, chunkSegments) ->
            TranscriptChunk(
                index = chunkIndex,
                text = chunkSegments.joinToString("\n") { it.text },
                segments = chunkSegments,
            )
        }
}

private fun chunkStartMs(chunk: TranscriptChunk): Int {
    if (chunk.endMs > chunk.startMs) return chunk.startMs.roundToInt().coerceAtLeast(0)
    return chunk.segments
        .map { it.startMs.roundToInt() }
        .filter { it >= 0 }
        .minOrNull()
        ?: 0
}

private fun chunkEndMs(chunk: TranscriptChunk): Int {
    if (chunk.endMs > chunk.startMs) return chunk.endMs.roundToInt().coerceAtLeast(chunkStartMs(chunk))
    return chunk.segments
        .map { it.endMs.roundToInt() }
        .filter { it > 0 }
        .maxOrNull()
        ?: 0
}

private fun formatShortDuration(startMs: Int, endMs: Int): String {
    if (endMs <= startMs) return "Audio"
    return "${formatClockMs(startMs)} - ${formatClockMs(endMs)}"
}

private fun formatClockMs(ms: Int): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

@Composable
private fun ReportSettingsScreen(state: MobileUiState, actions: MobileActions) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Réglages des comptes rendus", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        DetailRow(ReportFormat.CRI.title, state.reportDetails.cri) { actions.updateDetailLevel(ReportFormat.CRI, it) }
        DetailRow(ReportFormat.CRO.title, state.reportDetails.cro) { actions.updateDetailLevel(ReportFormat.CRO, it) }
        DetailRow(ReportFormat.CRS.title, state.reportDetails.crs) { actions.updateDetailLevel(ReportFormat.CRS, it) }
        Spacer(Modifier.height(8.dp))
        PrimaryActionButton("Générer les comptes rendus", enabled = !state.busy, onClick = actions::generateReports)
    }
}

@Composable
private fun DetailRow(title: String, selected: DetailLevel, onSelected: (DetailLevel) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(selected.label, color = Color(0xFF4B5563))
        }
        Slider(
            value = selected.ordinal.toFloat(),
            onValueChange = { raw ->
                val index = raw.roundToInt().coerceIn(0, DetailLevel.entries.lastIndex)
                onSelected(DetailLevel.entries[index])
            },
            valueRange = 0f..2f,
            steps = 1,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DetailLevel.entries.forEach { level ->
                Text(level.label, color = Color(0xFF6B7280), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ProcessingScreen(state: MobileUiState) {
    val operation = state.operation
    WaitingExperience(
        title = reportWaitTitle(operation),
        message = state.waitJoke,
        progress = operation?.progress?.toFloat()?.coerceIn(0f, 1f) ?: 0.1f,
        steps = reportWaitingSteps(state),
    )
}

@Composable
private fun WaitingExperience(
    title: String,
    message: String,
    progress: Float,
    steps: List<WaitingStep>,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedBrandMark()
        Spacer(Modifier.height(22.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(18.dp))
        WaitingTimeline(steps)
        Spacer(Modifier.height(14.dp))
        WaitingMessageSlot(message)
    }
}

@Composable
private fun WaitingMessageSlot(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (message.isNotBlank()) {
            Text(
                text = message,
                color = Color(0xFF6B7280),
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AnimatedBrandMark() {
    val transition = rememberInfiniteTransition(label = "brand-wait")
    val scale by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1300), RepeatMode.Reverse),
        label = "brand-scale",
    )
    val haloAlpha by transition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(tween(1300), RepeatMode.Reverse),
        label = "brand-halo",
    )

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(118.dp)) {
        Box(
            modifier = Modifier
                .size(104.dp)
                .scale(scale)
                .alpha(haloAlpha)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
        Surface(shape = CircleShape, color = Color.White, tonalElevation = 0.dp) {
            BrandLogo(
                drawableRes = R.drawable.logo_login,
                size = 76,
                contentDescription = "Demeter Sante",
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}

@Composable
private fun WaitingTimeline(steps: List<WaitingStep>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        steps.forEach { step ->
            WaitingTimelineRow(step)
        }
    }
}

@Composable
private fun WaitingTimelineRow(step: WaitingStep) {
    val isDone = step.state == WaitingStepState.Done
    val isActive = step.state == WaitingStepState.Active
    val transition = rememberInfiniteTransition(label = "timeline-${step.label}")
    val activeScale by transition.animateFloat(
        initialValue = 0.86f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "timeline-dot",
    )
    val dotColor = when (step.state) {
        WaitingStepState.Done -> Color(0xFF16A34A)
        WaitingStepState.Active -> MaterialTheme.colorScheme.primary
        WaitingStepState.Upcoming -> Color(0xFFCBD5E1)
    }
    val textColor = if (isDone || isActive) Color(0xFF111827) else Color(0xFF6B7280)

    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .scale(if (isActive) activeScale else 1f)
                .background(dotColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (isDone) {
                Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(step.label, color = textColor, fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal)
    }
}

private data class WaitingStep(
    val label: String,
    val state: WaitingStepState,
)

private enum class WaitingStepState {
    Done,
    Active,
    Upcoming,
}

private fun transcriptionWaitTitle(operation: OperationStatus?, fallback: String): String {
    operation?.queueLabel()?.let { return it }
    operation?.uploadProgressLabel()?.let { return it }
    operation?.transcriptionProgressLabel()?.let { return it }
    return fallback
        .takeUnless { it.equals("chunk_completed", ignoreCase = true) }
        ?.takeUnless { it.equals("queue", ignoreCase = true) }
        ?.takeUnless { it.equals("queued", ignoreCase = true) }
        ?.ifBlank { null }
        ?: "Transcription en cours"
}

private fun reportWaitTitle(operation: OperationStatus?): String {
    if (operation == null) return "Traitement"
    operation.queueLabel()?.let { return it }
    operation.uploadProgressLabel()?.let { return it }
    operation.transcriptionProgressLabel()?.let { return it }
    val stage = operation.stage.lowercase()
    return when {
        stage.contains("generation") -> "Génération des comptes rendus"
        stage.contains("email") -> "Envoi email"
        else -> "Traitement"
    }
}

private fun OperationStatus.queueLabel(): String? {
    return if (isQueueStage()) "En file d'attente" else null
}

private fun OperationStatus.uploadProgressLabel(): String? {
    if (!isUploadStage()) return null
    return progressLabel("Morceau")
}

private fun OperationStatus.transcriptionProgressLabel(): String? {
    if (!isTranscriptionStage()) return null
    return progressLabel("Partie")
}

private fun OperationStatus.progressLabel(prefix: String): String? {
    if (chunkCount <= 0) return null
    val currentChunk = chunkIndex.coerceIn(1, chunkCount)
    return "$prefix $currentChunk/$chunkCount"
}

private fun OperationStatus.isUploadStage(): Boolean {
    val stageOrStatus = "${stage.lowercase()} ${status.lowercase()}"
    return stageOrStatus.contains("upload")
}

private fun OperationStatus.isQueueStage(): Boolean {
    val normalizedStage = stage.lowercase()
    return normalizedStage == "queue" || normalizedStage == "queued"
}

private fun OperationStatus.isTranscriptionStage(): Boolean {
    val normalizedStage = stage.lowercase()
    return !isUploadStage() &&
        !isQueueStage() &&
        (normalizedStage.contains("transcription") ||
            normalizedStage == "running" ||
            normalizedStage == "chunk_completed")
}

private fun transcriptionWaitingSteps(operation: OperationStatus?): List<WaitingStep> {
    val activeIndex = when {
        operation?.status == "completed" -> 2
        operation?.stage.orEmpty().lowercase().contains("upload") -> 0
        operation == null -> 0
        else -> 1
    }
    val uploadLabel = listOfNotNull("Upload audio", operation?.uploadProgressLabel()).joinToString(" · ")
    val transcriptionLabel = listOfNotNull("Transcription", operation?.transcriptionProgressLabel()).joinToString(" · ")
    return waitingSteps(listOf(uploadLabel, transcriptionLabel, "Préparation des segments"), activeIndex, operation?.status == "completed")
}

private fun reportWaitingSteps(state: MobileUiState): List<WaitingStep> {
    val operation = state.operation
    val labels = if (state.wantsSpeakerAssignment == true) {
        listOf("Préparation", "Génération des comptes rendus", "Envoi email")
    } else {
        listOf(
            listOfNotNull("Upload audio", operation?.uploadProgressLabel()).joinToString(" · "),
            listOfNotNull("Transcription", operation?.transcriptionProgressLabel()).joinToString(" · "),
            "Génération des comptes rendus",
            "Envoi email",
        )
    }
    val stage = operation?.stage.orEmpty().lowercase()
    val activeIndex = if (state.wantsSpeakerAssignment == true) {
        when {
            operation?.status == "completed" -> labels.lastIndex
            stage.contains("email") -> 2
            stage.contains("generation") -> 1
            stage == "queue" || stage == "queued" -> 0
            else -> 0
        }
    } else {
        when {
            operation?.status == "completed" -> labels.lastIndex
            stage.contains("email") -> 3
            stage.contains("generation") -> 2
            operation?.isTranscriptionStage() == true || stage == "queue" || stage == "queued" -> 1
            else -> 0
        }
    }
    return waitingSteps(labels, activeIndex, operation?.status == "completed")
}

private fun waitingSteps(labels: List<String>, activeIndex: Int, completed: Boolean): List<WaitingStep> {
    return labels.mapIndexed { index, label ->
        val state = when {
            completed || index < activeIndex -> WaitingStepState.Done
            index == activeIndex -> WaitingStepState.Active
            else -> WaitingStepState.Upcoming
        }
        WaitingStep(label, state)
    }
}

@Composable
private fun SuccessScreen(
    state: MobileUiState,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onHome: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        BrandLogo(
            drawableRes = R.drawable.logo_login,
            size = 72,
            contentDescription = "Demeter Sante",
        )
        Spacer(Modifier.height(16.dp))
        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(54.dp), tint = Color(0xFF16A34A))
        Spacer(Modifier.height(18.dp))
        Text("Merci d'avoir utilisé Demeter Sante", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        state.successFiles.forEach {
            Text(it.filename, color = Color(0xFF4B5563), fontSize = 14.sp)
        }
        Spacer(Modifier.height(26.dp))
        if (state.successCanSaveAudio) {
            Text("Conserver l'audio ?", color = Color(0xFF4B5563))
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDiscard, shape = RoundedCornerShape(8.dp)) { Text("Non") }
                Button(onClick = onSave, shape = RoundedCornerShape(8.dp)) { Text("Oui") }
            }
        } else {
            PrimaryActionButton("Retour accueil", onClick = onHome)
        }
    }
}

@Composable
private fun LargeChoiceButton(
    title: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = MaterialTheme.colorScheme.primary),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(Color(0xFFF1F5F9), CircleShape), contentAlignment = Alignment.Center) { icon() }
            Spacer(Modifier.width(14.dp))
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PrimaryActionButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatElapsed(ms: Long): String {
    val totalSeconds = (ms / 1000.0).roundToInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
