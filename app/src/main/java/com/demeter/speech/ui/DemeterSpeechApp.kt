@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.demeter.speech.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.input.pointer.pointerInteropFilter
import com.demeter.speech.DemeterSpeechViewModel
import com.demeter.speech.R
import com.demeter.speech.core.AppAuthState
import com.demeter.speech.core.DemeterSpeechUiState
import com.demeter.speech.core.LoginUiState
import com.demeter.speech.core.MeetingPhase
import com.demeter.speech.core.MeetingRecordingState
import com.demeter.speech.core.MeetingReviewState
import com.demeter.speech.core.MeetingReportDraftUi
import com.demeter.speech.core.MeetingReportSection
import com.demeter.speech.core.ReportFormat
import com.demeter.speech.core.RootTab
import com.demeter.speech.core.SpeakerAssignmentUi
import com.demeter.speech.core.TranscriptionSourceMode
import kotlinx.coroutines.launch

@Composable
fun DemeterSpeechApp(
    state: DemeterSpeechUiState,
    viewModel: DemeterSpeechViewModel,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.infoMessage) {
        val message = state.infoMessage
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
            viewModel.clearInfoMessage()
        }
    }

    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
    ) {
        BackgroundOrbs()

        when {
            state.authState == AppAuthState.Checking -> LoadingGate()
            state.authState == AppAuthState.SignedOut -> LoginScreen(
                login = state.login,
                onEmailChanged = viewModel::onLoginEmailChanged,
                onPasswordChanged = viewModel::onLoginPasswordChanged,
                onLogin = viewModel::login,
            )
            else -> SignedInShell(
                state = state,
                viewModel = viewModel,
                snackbarHostState = snackbarHostState,
            )
        }
    }
}

@Composable
private fun SignedInShell(
    state: DemeterSpeechUiState,
    viewModel: DemeterSpeechViewModel,
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.startRecording()
        } else {
            viewModel.showInfoMessage("Autorisation micro refusée")
        }
    }
    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.importAudio(uri)
        }
    }
    val hasMicPermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
    val showTranscriptionOverlay = state.meetingPhase == MeetingPhase.Review &&
        state.review.isTranscribing &&
        state.review.transcriptChunks.isEmpty()
    val showFinalizeOverlay = state.review.isSubmitting

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                CenterAlignedTopAppBar(
                    title = {},
                    actions = {
                        IconButton(onClick = viewModel::logout) {
                            Icon(Icons.Default.PowerSettingsNew, contentDescription = "Déconnexion")
                        }
                    },
                )
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                Crossfade(
                    targetState = state.meetingPhase,
                    label = "meetingPhase",
                ) { phase ->
                    when (phase) {
                        MeetingPhase.Welcome,
                        MeetingPhase.Wizard -> WelcomeScreen(
                            onStartRecording = {
                                if (hasMicPermission) {
                                    viewModel.startRecording()
                                } else {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            onOpenAudio = {
                                audioPickerLauncher.launch(arrayOf("audio/*"))
                            },
                        )
                        MeetingPhase.Recording -> RecordingScreen(
                            state = state.recording,
                            onPause = viewModel::pauseRecording,
                            onResume = viewModel::resumeRecording,
                            onStop = viewModel::stopRecording,
                        )
                        MeetingPhase.Review -> ReviewScreen(
                            state = state.review,
                            meetingTitle = state.wizard.title,
                            onMeetingTitleChanged = viewModel::updateMeetingTitle,
                            onTranscriptChanged = viewModel::updateTranscriptEdited,
                            onAddSpeaker = viewModel::addSpeakerCard,
                            onRemoveSpeaker = viewModel::removeSpeakerCard,
                            onSpeakerChanged = viewModel::updateSpeakerCard,
                            onValidateSpeaker = viewModel::confirmSpeakerCard,
                            onAdditionalRecipientsChanged = viewModel::updateAdditionalRecipientEmails,
                            onFinalize = viewModel::finalizeMeeting,
                            onBackToStart = viewModel::resetMeeting,
                        )
                    }
                }
            }
        }
        if (showTranscriptionOverlay) {
            BlockingLoadingOverlay(
                title = "Transcription en cours",
                message = state.review.draftMessage ?: "Préparation du premier chunk…",
            )
        }
        if (showFinalizeOverlay) {
            BlockingLoadingOverlay(
                title = "Envoi du compte rendu",
                message = state.review.submitMessage ?: "Envoi du mail…",
            )
        }
    }
}

@Composable
private fun PermissionGate(
    requiredPermissions: List<String>,
    onRequestPermissions: () -> Unit,
    onContinueWithoutPermissions: () -> Unit,
) {
    CenteredPage {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Permissions requises", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Le micro et les notifications sont nécessaires pour enregistrer localement puis suivre la transcription.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                requiredPermissions.forEach { permission ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Notifications, contentDescription = null)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(permission.substringAfterLast('.'))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onRequestPermissions) {
                        Text("Autoriser")
                    }
                    OutlinedButton(onClick = onContinueWithoutPermissions) {
                        Text("Réessayer")
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingGate() {
    CenteredPage {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val transition = rememberInfiniteTransition(label = "loading")
                val scale by transition.animateFloat(
                    initialValue = 0.95f,
                    targetValue = 1.05f,
                    animationSpec = infiniteRepeatable(animation = tween(1200, easing = LinearEasing)),
                    label = "loadingScale",
                )
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .scale(scale)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Text("Connexion en cours", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Chargement de la session et des permissions.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LoginScreen(
    login: LoginUiState,
    onEmailChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onLogin: () -> Unit,
) {
    CenteredPage {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .height(180.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(id = R.drawable.logo_login),
                    contentDescription = "Logo Demeter Santé",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                ),
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text("Connexion", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Connectez-vous au backend Demeter Santé pour retrouver votre session et envoyer les comptes rendus.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = login.email,
                        onValueChange = onEmailChanged,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Email") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                    )
                    OutlinedTextField(
                        value = login.password,
                        onValueChange = onPasswordChanged,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Mot de passe") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    login.errorMessage?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    Button(
                        onClick = onLogin,
                        enabled = !login.isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                    ) {
                        Text(if (login.isLoading) "Connexion..." else "Continuer")
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeScreen(
    onStartRecording: () -> Unit,
    onOpenAudio: () -> Unit,
) {
    CenteredPage {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Image(
                painter = painterResource(id = R.drawable.logo),
                contentDescription = "Logo Demeter Santé",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .height(160.dp),
                contentScale = ContentScale.Fit,
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onStartRecording,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Enregistrer")
                }
                OutlinedButton(
                    onClick = onOpenAudio,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                ) {
                    Icon(Icons.Default.Description, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Ouvrir un audio")
                }
            }
        }
    }
}

@Composable
private fun RecordingScreen(
    state: MeetingRecordingState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "recording")
    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(animation = tween(1100, easing = LinearEasing)),
        label = "recordingPulse",
    )
    val alpha by transition.animateFloat(
        initialValue = 0.58f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1100, easing = LinearEasing)),
        label = "recordingAlpha",
    )

    CenteredPage {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(132.dp)
                        .scale(pulse)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = alpha),
                            CircleShape,
                        )
                        .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Text(
                    if (state.isPaused) "Enregistrement en pause" else "Enregistrement en cours",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "L'audio est stocke en local jusqu'a l'envoi.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Duree: ${formatDuration(state.elapsedMs)}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = if (state.isPaused) onResume else onPause,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(if (state.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (state.isPaused) "Reprendre" else "Pause")
                    }
                    Button(onClick = onStop, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Arreter")
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewScreen(
    state: MeetingReviewState,
    meetingTitle: String,
    onMeetingTitleChanged: (String) -> Unit,
    onTranscriptChanged: (String) -> Unit,
    onAddSpeaker: () -> Unit,
    onRemoveSpeaker: (String) -> Unit,
    onSpeakerChanged: (String, String, String) -> Unit,
    onValidateSpeaker: (String) -> Unit,
    onAdditionalRecipientsChanged: (String) -> Unit,
    onFinalize: () -> Unit,
    onBackToStart: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Description, contentDescription = null)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Relecture", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Ajustez le texte, les speakers et les destinataires avant l'envoi.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.isSubmitting) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Nom de la réunion", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = meetingTitle,
                        onValueChange = onMeetingTitleChanged,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Nom de la réunion") },
                        placeholder = { Text("Réunion du 29/03/2026") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    )
                    Text(
                        "Ce titre apparaîtra dans les rapports et dans l'email envoyé.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Diarisation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${state.selectedSpeakerAssignments.size} speaker(s)",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    state.selectedSpeakerAssignments.forEach { assignment ->
                        SpeakerAssignmentCard(
                            assignment = assignment,
                            onChanged = { firstName, lastName ->
                                onSpeakerChanged(assignment.speakerId, firstName, lastName)
                            },
                            onValidate = { onValidateSpeaker(assignment.speakerId) },
                            onRemove = { onRemoveSpeaker(assignment.speakerId) },
                        )
                    }
                    OutlinedButton(onClick = onAddSpeaker) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Ajouter un speaker")
                    }
                }

                HorizontalDivider()

                OutlinedTextField(
                    value = state.transcriptEdited,
                    onValueChange = onTranscriptChanged,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Transcription") },
                    minLines = 8,
                    readOnly = state.isTranscribing,
                )

                if (!state.errorMessage.isNullOrBlank()) {
                    StatusMessage(
                        text = state.errorMessage,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (!state.submitMessage.isNullOrBlank()) {
                    StatusMessage(
                        text = state.submitMessage,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                if (!state.draftMessage.isNullOrBlank()) {
                    Text(
                        text = state.draftMessage,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Destinataires supplementaires", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = state.additionalRecipientEmails,
                        onValueChange = onAdditionalRecipientsChanged,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Copie mail") },
                        placeholder = { Text("copie1@exemple.fr, copie2@exemple.fr") },
                        minLines = 2,
                    )
                    Text(
                        "Le destinataire principal est le compte connecte. Separes les copies par virgule ou retour a la ligne.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                HorizontalDivider()

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("3 comptes rendus", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReportBadge(label = ReportFormat.CRI.apiValue)
                        ReportBadge(label = ReportFormat.CRO.apiValue)
                        ReportBadge(label = ReportFormat.CRS.apiValue)
                    }
                    Text(
                        "Les trois modeles seront generes et joints automatiquement a l'email.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = onBackToStart,
                        modifier = Modifier.weight(1f),
                        enabled = !state.isSubmitting && !state.isTranscribing,
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Nouvelle prise")
                    }
                    Button(
                        onClick = onFinalize,
                        modifier = Modifier.weight(1f),
                        enabled = !state.isSubmitting && !state.isTranscribing,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (state.isSubmitting) "Envoi..." else "Envoyer")
                    }
                }

                Text(
                    "Le fichier audio sera supprime apres un envoi reussi.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SpeakerAssignmentCard(
    assignment: SpeakerAssignmentUi,
    onChanged: (String, String) -> Unit,
    onValidate: () -> Unit,
    onRemove: () -> Unit,
) {
    val title = assignment.confirmedLabel?.takeIf { assignment.isValidated && it.isNotBlank() }
        ?: assignment.speakerId
    val currentName = listOf(assignment.firstName.trim(), assignment.lastName.trim())
        .filter { it.isNotBlank() }
        .joinToString(" ")
    val canValidate = currentName.isNotBlank()

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold)
                    if (assignment.isValidated && title != assignment.speakerId) {
                        Text(
                            "Validé pour ce chunk",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                TextButton(
                    onClick = onValidate,
                    enabled = canValidate,
                ) {
                    Text(if (assignment.isValidated) "Revalider" else "Valider")
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, contentDescription = "Supprimer")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = assignment.firstName,
                    onValueChange = { onChanged(it, assignment.lastName) },
                    modifier = Modifier.weight(1f),
                    label = { Text("Prenom") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = assignment.lastName,
                    onValueChange = { onChanged(assignment.firstName, it) },
                    modifier = Modifier.weight(1f),
                    label = { Text("Nom") },
                    singleLine = true,
                )
            }
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun BlockingLoadingOverlay(title: String, message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f))
            .pointerInteropFilter { true },
        contentAlignment = Alignment.Center,
    ) {
        ElevatedCard(
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(52.dp),
                    strokeWidth = 4.dp,
                )
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    message,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ReportBadge(label: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        ),
        shape = RoundedCornerShape(999.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(label, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SessionChip(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    if (text.isBlank()) {
        return
    }
    Card(
        shape = RoundedCornerShape(999.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatusMessage(
    text: String,
    color: Color,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.10f),
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            color = color,
        )
    }
}

@Composable
private fun CenteredPage(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.fillMaxWidth(0.96f)) {
            content()
        }
    }
}

@Composable
private fun BackgroundOrbs() {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 36.dp, end = 16.dp)
                .size(180.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.09f), CircleShape)
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 0.dp, bottom = 48.dp)
                .size(220.dp)
                .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f), CircleShape)
        )
    }
}

private fun formatDuration(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs / 1000).coerceAtLeast(0)
    val hours = (totalSeconds / 3600).toInt()
    val minutes = ((totalSeconds % 3600) / 60).toInt()
    val seconds = (totalSeconds % 60).toInt()
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
