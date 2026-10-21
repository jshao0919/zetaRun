package com.zetarun.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zetarun.app.data.AuthStatus
import com.zetarun.app.domain.*
import com.zetarun.app.voice.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

private val Ink = Color(0xFF101411)
private val Panel = Color(0xFF1C221D)
private val Lime = Color(0xFFCDF86A)
private val Muted = Color(0xFFA5B0A3)
private val White = Color(0xFFF3F6EE)
private val AppColors = darkColorScheme(primary = Lime, onPrimary = Ink, background = Ink,
    surface = Ink, surfaceVariant = Panel, onSurface = White, onSurfaceVariant = Muted,
    secondary = Lime, outline = Color(0xFF384338))

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = AppColors) {
                Surface(Modifier.fillMaxSize(), color = Ink) { ZetaRunApp() }
            }
        }
    }
}

@Composable
private fun ZetaRunApp(vm: TrainerViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val voice by vm.voice.state.collectAsStateWithLifecycle()
    val history by vm.repository.history.collectAsStateWithLifecycle()
    val auth by vm.repository.auth.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    val activity = LocalActivity.current ?: return
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var permissionDenied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted) vm.start()
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) vm.pause()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(state.stage) {
        if (state.stage == Stage.RUNNING) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    LaunchedEffect(voice.status, state.stage, state.voiceEnabled) {
        if (voice.status == VoiceStatus.ERROR && state.stage == Stage.RUNNING && state.voiceEnabled) vm.pause()
    }
    BackHandler(state.stage == Stage.RUNNING || state.stage == Stage.PAUSED || state.stage == Stage.RESULT) {
        if (state.stage == Stage.RUNNING) vm.pause()
        else if (state.stage == Stage.RESULT) vm.home()
    }
    fun begin() {
        permissionDenied = false
        if (state.voiceEnabled && ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            permission.launch(Manifest.permission.RECORD_AUDIO)
        else vm.start()
    }
    Scaffold(containerColor = Ink, bottomBar = {
        if (state.stage == Stage.HOME) NavigationBar(containerColor = Ink, tonalElevation = 0.dp) {
            listOf("Train" to Icons.Outlined.Bolt, "Progress" to Icons.Outlined.Insights, "Account" to Icons.Outlined.PersonOutline)
                .forEachIndexed { index, item ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index },
                        icon = { Icon(item.second, null) }, label = { Text(item.first) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Panel, selectedIconColor = Lime, selectedTextColor = Lime))
                }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp)) {
            when (state.stage) {
                Stage.HOME -> when (tab) {
                    0 -> HomeScreen(state, voice, vm.voice.isPrepared, history, permissionDenied, vm::configure, vm::setVoice, vm::setQuietMillis, ::begin)
                    1 -> ProgressScreen(history)
                    else -> AccountScreen(auth, history.size,
                        onSignIn = { scope.launch { vm.repository.signIn(activity) } },
                        onSignOut = vm.repository::signOut,
                        onSync = { scope.launch { vm.repository.sync() } },
                        onExport = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/json"
                                putExtra(Intent.EXTRA_SUBJECT, "ZetaRun progress")
                                putExtra(Intent.EXTRA_TEXT, vm.repository.exportJson())
                            }
                            activity.startActivity(Intent.createChooser(intent, "Export your progress"))
                        },
                        onDelete = { scope.launch { vm.repository.deleteAllProgress() } },
                        onDeleteAccount = { scope.launch { vm.repository.deleteAccount() } })
                }
                Stage.RUNNING, Stage.PAUSED -> RoundScreen(state, voice, vm::manualAnswer, vm::pause, vm::resume, vm::finish, vm::skip, vm::setVoice)
                Stage.RESULT -> ResultScreen(state.result!!, state.savePending, history.any { it.id == state.result!!.id }, auth.error, onAgain = ::begin, onHome = vm::home)
            }
        }
    }
    state.error?.let { message ->
        AlertDialog(onDismissRequest = vm::clearError, title = { Text("Voice is preparing") },
            text = { Text(message) }, confirmButton = { TextButton(onClick = vm::clearError) { Text("OK") } })
    }
}

@Composable private fun Brand() {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Lime), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Bolt, null, tint = Ink)
        }
        Text("zetarun", Modifier.padding(start = 10.dp), fontSize = 23.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
        Spacer(Modifier.weight(1f))
        Text("MIND / MILES", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
    }
}

@Composable private fun HomeScreen(
    state: TrainerState, voice: VoiceState, voicePrepared: Boolean, history: List<SessionRecord>, denied: Boolean,
    configure: (GameConfig) -> Unit, setVoice: (Boolean) -> Unit, setQuietMillis: (Long) -> Unit, start: () -> Unit,
) {
    var settings by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Brand()
        Eyebrow("ZONE 2. BRAIN ON.")
        Text("Find your pace.\nSharpen your mind.", fontSize = 37.sp, lineHeight = 41.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.4).sp,
            modifier = Modifier.padding(top = 14.dp, bottom = 12.dp))
        Text("Mental math for the miles in between.\nJust look, solve, and say the answer.", color = Muted, lineHeight = 24.sp)
        Spacer(Modifier.height(28.dp))
        Surface(color = Panel, shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("YOUR NEXT ROUND")
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { settings = true }, contentPadding = PaddingValues(0.dp)) { Icon(Icons.Outlined.Tune, "Customize session", Modifier.size(20.dp)) }
                }
                Text("${state.config.durationSeconds / 60}:${(state.config.durationSeconds % 60).toString().padStart(2, '0')}", fontSize = 52.sp,
                    fontWeight = FontWeight.Light, fontFamily = FontFamily.Monospace)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(60, 120, 300).forEach { seconds ->
                        FilterChip(selected = state.config.durationSeconds == seconds,
                            onClick = { configure(state.config.copy(durationSeconds = seconds)) }, label = { Text("${seconds / 60} min") })
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 16.dp), color = Color(0xFF384338))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Operation.entries.forEach { op ->
                        FilterChip(selected = op in state.config.operations,
                            onClick = {
                                val next = if (op in state.config.operations) state.config.operations - op else state.config.operations + op
                                if (next.isNotEmpty()) configure(state.config.copy(operations = next))
                            }, label = { Text(op.symbol, fontSize = 24.sp) })
                    }
                }
                Text("Whole-number answers · custom ranges available", fontSize = 12.sp, color = Muted)
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(selected = state.voiceEnabled, onClick = { setVoice(true) }, label = { Text("Hands-free voice") }, leadingIcon = { Icon(Icons.Outlined.Mic, null, Modifier.size(17.dp)) })
            FilterChip(selected = !state.voiceEnabled, onClick = { setVoice(false) }, label = { Text("Keypad") }, leadingIcon = { Icon(Icons.Outlined.Dialpad, null, Modifier.size(17.dp)) })
        }
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (voice.status == VoiceStatus.READY) Lime else Muted))
            Text(if (state.voiceEnabled) voice.message else "Practice quietly with the keypad", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
        }
        if (state.voiceEnabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Fast" to 180L, "Balanced" to 300L, "Careful" to 450L).forEach { (label, millis) ->
                    FilterChip(selected = state.quietMillis == millis, onClick = { setQuietMillis(millis) }, label = { Text(label, fontSize = 12.sp) })
                }
            }
            Text("${state.quietMillis} ms silence check · faster settings can split long numbers", color = Muted, fontSize = 11.sp)
        }
        if (denied) Text("Microphone access was denied. Enable it in Android Settings → Apps → ZetaRun → Permissions, or choose Keypad.", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        Button(onClick = start, enabled = !state.voiceEnabled || voicePrepared,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(62.dp), shape = RoundedCornerShape(18.dp)) {
            Text("Start a round", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp)); Icon(Icons.AutoMirrored.Outlined.ArrowForward, null)
        }
        Text("OFFLINE VOICE  ·  NO AUDIO UPLOADS", Modifier.fillMaxWidth().padding(vertical = 16.dp),
            fontSize = 10.sp, letterSpacing = 1.2.sp, color = Muted, textAlign = TextAlign.Center)
        if (history.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            SmallStat("ROUNDS", history.size.toString())
            SmallStat("BEST / MIN", "%.1f".format(history.maxOf { it.answersPerMinute }))
            SmallStat("SOLVED", history.sumOf { it.score }.toString())
        }
        Spacer(Modifier.height(16.dp))
    }
    if (settings) ConfigDialog(state.config, onDismiss = { settings = false }, onSave = { configure(it); settings = false })
}

@Composable private fun RoundScreen(
    state: TrainerState, voice: VoiceState, submit: (String) -> Unit,
    pause: () -> Unit, resume: () -> Unit, finish: () -> Unit, skip: () -> Unit, setVoice: (Boolean) -> Unit,
) {
    var showKeypad by remember { mutableStateOf(!state.voiceEnabled) }
    var digits by remember(state.problem) { mutableStateOf("") }
    var confirmEnd by remember { mutableStateOf(false) }
    val paused = state.stage == Stage.PAUSED
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { confirmEnd = true }) { Icon(Icons.Outlined.Close, null); Text("End") }
            Spacer(Modifier.weight(1f)); Text("${state.score} solved", fontWeight = FontWeight.Medium, color = Lime)
        }
        val seconds = ceil(state.remainingMs / 1000.0).toInt()
        Text("${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}", fontSize = 24.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 18.dp))
        LinearProgressIndicator(progress = { state.remainingMs.toFloat() / (state.config.durationSeconds * 1000f) },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp), color = Lime, trackColor = Panel)
        Spacer(Modifier.height(if (showKeypad) 30.dp else 64.dp))
        Eyebrow(if (paused) "TAKE A BREATHER" else "SAY THE ANSWER")
        // No transition animation: the next problem appears on the next display frame.
        Text(if (paused) "Paused" else state.problem, fontSize = if (state.problem.length > 10) 44.sp else 60.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = (-2).sp,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp))
        Text(if (paused) "Timer stopped · microphone off" else state.feedback.ifEmpty { "You've got this." },
            color = if (state.feedback == "Correct") Lime else Muted, fontSize = 16.sp, textAlign = TextAlign.Center)
        if (paused) {
            Spacer(Modifier.height(28.dp))
            if (voice.status == VoiceStatus.ERROR && state.voiceEnabled) {
                Text(voice.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                TextButton(onClick = { setVoice(false); showKeypad = true }) { Text("Continue with keypad") }
            }
            Button(onClick = resume, modifier = Modifier.fillMaxWidth().height(64.dp)) { Icon(Icons.Outlined.PlayArrow, null); Text("Resume round", fontSize = 20.sp) }
        } else {
            Spacer(Modifier.height(24.dp))
            if (state.voiceEnabled) {
                Box(Modifier.size(72.dp).clip(CircleShape).background(Lime.copy(alpha = (0.10f + voice.rms * 4).coerceIn(0.1f, 0.5f))), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Mic, null, tint = Lime, modifier = Modifier.size(30.dp))
                }
                Text(voice.transcript.ifEmpty { "Listening on this device" }, color = Muted, modifier = Modifier.padding(top = 10.dp), textAlign = TextAlign.Center)
                state.endpointMs?.let { Text("Approx. endpoint delay: ${it} ms", color = Muted, fontSize = 10.sp) }
            }
            if (showKeypad) {
                Text(digits.ifEmpty { "—" }, fontSize = 30.sp, color = Lime, modifier = Modifier.padding(vertical = 8.dp))
                listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "↵")).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { key ->
                            FilledTonalButton(onClick = {
                                when (key) {
                                    "⌫" -> digits = digits.dropLast(1)
                                    "↵" -> { submit(digits); digits = "" }
                                    else -> if (digits.length < 8) digits += key
                                }
                            }, modifier = Modifier.weight(1f).height(54.dp), shape = RoundedCornerShape(12.dp)) { Text(key, fontSize = 23.sp) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = pause) { Icon(Icons.Outlined.Pause, null); Text("Pause") }
                TextButton(onClick = { showKeypad = !showKeypad }) { Icon(Icons.Outlined.Dialpad, null); Text("Keypad") }
                TextButton(onClick = skip) { Text("Skip"); Icon(Icons.Outlined.SkipNext, null) }
            }
            if (!showKeypad) Text("Say “skip” or “pause” at any time.\nSpeak each full answer at a natural pace.", color = Muted,
                fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 16.dp))
        }
        Spacer(Modifier.height(20.dp))
    }
    if (confirmEnd) AlertDialog(onDismissRequest = { confirmEnd = false }, title = { Text("Finish this round?") },
        text = { Text("Your progress so far will be saved as a partial round.") },
        confirmButton = { TextButton(onClick = { confirmEnd = false; finish() }) { Text("Finish & save") } },
        dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Keep going") } })
}

@Composable private fun ResultScreen(result: SessionRecord, savePending: Boolean, saved: Boolean, error: String?, onAgain: () -> Unit, onHome: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Brand(); Spacer(Modifier.height(30.dp)); Eyebrow(if (result.completed) "ROUND COMPLETE" else "ROUND SAVED")
        Text("Nice work.\nKeep your rhythm.", fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 22.dp))
        Text(result.score.toString(), color = Lime, fontSize = 108.sp, fontWeight = FontWeight.Light)
        Text("PROBLEMS SOLVED", letterSpacing = 2.sp, color = Muted, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            SmallStat("PER MINUTE", "%.1f".format(result.answersPerMinute))
            SmallStat("ACCURACY", "${(result.accuracy * 100).roundToInt()}%")
            SmallStat("BEST STREAK", result.bestStreak.toString())
        }
        Text("Average solve time: ${"%.1f".format(result.meanResponseMs / 1000.0)}s\n${if (savePending) "Saving round…" else if (saved) "Saved on this device" else "Round could not be saved"} · ${result.inputMode} input", color = Muted,
            fontSize = 13.sp, textAlign = TextAlign.Center)
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
        Button(onClick = onAgain, modifier = Modifier.fillMaxWidth().padding(top = 32.dp).height(60.dp)) { Text("Go again", fontSize = 18.sp) }
        TextButton(onClick = onHome, modifier = Modifier.padding(12.dp)) { Text("Back to training") }
    }
}

@Composable private fun ProgressScreen(history: List<SessionRecord>) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Brand(); Eyebrow("THE LONG GAME")
        Text("A little sharper.\nEvery session.", fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 16.dp))
        if (history.isEmpty()) {
            InfoCard(Icons.Outlined.Insights, "Your first mile starts here", "Finish a round to see your pace, accuracy, and progress over time.")
            return@Column
        }
        val chronological = history.sortedBy { it.startedAtEpochMs }
        Surface(color = Panel, shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(22.dp)) {
                Text("SOLVING PACE", color = Muted, letterSpacing = 1.sp, fontSize = 11.sp)
                Text("${"%.1f".format(history.first().answersPerMinute)} / min", fontSize = 34.sp, color = Lime)
                val points = chronological.takeLast(20).map { it.answersPerMinute.toFloat() }
                Canvas(Modifier.fillMaxWidth().height(110.dp).padding(top = 20.dp, bottom = 10.dp)) {
                    val maximum = (points.maxOrNull() ?: 1f).coerceAtLeast(1f)
                    drawLine(Color(0xFF384338), Offset(0f, size.height), Offset(size.width, size.height), 1f)
                    val coords = points.mapIndexed { i, value -> Offset(if (points.size == 1) size.width / 2 else size.width * i / (points.size - 1), size.height * (1 - value / maximum)) }
                    coords.zipWithNext().forEach { (a, b) -> drawLine(Lime, a, b, 3.dp.toPx(), StrokeCap.Round) }
                    coords.forEach { drawCircle(Lime, 4.dp.toPx(), it) }
                }
                Text("Last ${points.size} rounds · oldest → newest", color = Muted, fontSize = 11.sp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            SmallStat("ROUNDS", history.size.toString())
            SmallStat("TOTAL SOLVED", history.sumOf { it.score }.toString())
            SmallStat("BEST / MIN", "%.1f".format(history.maxOf { it.answersPerMinute }))
        }
        Eyebrow("SESSION HISTORY")
        history.forEach { record ->
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(SimpleDateFormat("MMM d · h:mm a", Locale.getDefault()).format(Date(record.startedAtEpochMs)), fontWeight = FontWeight.Medium)
                    Text("${record.elapsedMs / 1000}s · ${record.operations.sortedBy { it.ordinal }.joinToString(" ") { it.symbol }} · ${record.inputMode}${if (!record.completed) " · partial" else ""}", color = Muted, fontSize = 12.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${record.score} solved", color = Lime)
                    Text("${(record.accuracy * 100).roundToInt()}% · ${"%.1f".format(record.answersPerMinute)}/min", color = Muted, fontSize = 12.sp)
                }
            }
            HorizontalDivider(color = Panel)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable private fun AccountScreen(auth: AuthStatus, count: Int, onSignIn: () -> Unit, onSignOut: () -> Unit, onSync: () -> Unit,
    onExport: () -> Unit, onDelete: () -> Unit, onDeleteAccount: () -> Unit) {
    var confirm by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Brand(); Eyebrow("YOUR TRAINING, YOURS")
        Text("Keep the\nmomentum.", fontSize = 38.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 18.dp))
        InfoCard(Icons.Outlined.PersonOutline, auth.displayName ?: "Training on this device",
            if (auth.userId != null) "${auth.email.orEmpty()}\n$count saved rounds · ${if (auth.syncing) "syncing…" else "cloud sync available"}"
            else "Your rounds stay on this phone. Sign in with Google to back them up and sync across devices.")
        if (!auth.configured) Text("Google sync needs a one-time Firebase setup by the app owner. Local training and progress are available now. See docs/FIREBASE.md.",
            color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 16.dp))
        auth.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 12.dp)) }
        if (auth.userId == null) Button(onClick = onSignIn, enabled = !auth.syncing && auth.configured,
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp).height(56.dp)) { Text("Sign in with Google") }
        else {
            Button(onClick = onSync, enabled = !auth.syncing, modifier = Modifier.fillMaxWidth().padding(top = 18.dp).height(56.dp)) { Text(if (auth.syncing) "Syncing…" else "Sync progress") }
            TextButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
        }
        Spacer(Modifier.height(28.dp)); Eyebrow("DATA & PRIVACY")
        Text("Voice recognition runs entirely on your phone. Audio and transcripts are never saved or uploaded. Google sync stores session results and your account identity.", color = Muted, lineHeight = 23.sp, modifier = Modifier.padding(vertical = 14.dp))
        OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.FileDownload, null); Spacer(Modifier.width(8.dp)); Text("Export progress as JSON") }
        TextButton(onClick = { confirm = "progress" }, modifier = Modifier.fillMaxWidth()) { Text("Delete all progress", color = MaterialTheme.colorScheme.error) }
        if (auth.userId != null) TextButton(onClick = { confirm = "account" }, modifier = Modifier.fillMaxWidth()) { Text("Delete account & cloud data", color = MaterialTheme.colorScheme.error) }
        Text("ZetaRun ${BuildConfig.VERSION_NAME} · independent arithmetic trainer\nInspired by Zetamac. Not affiliated with Zetamac.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 22.dp))
    }
    if (confirm.isNotEmpty()) AlertDialog(onDismissRequest = { confirm = "" },
        title = { Text(if (confirm == "account") "Delete your account?" else "Delete all progress?") },
        text = { Text("This permanently deletes ${if (confirm == "account") "your ZetaRun account and its results" else "your saved results"}. ${if (auth.userId != null) "An internet connection and recent sign-in are required for cloud deletion." else "This cannot be undone."}") },
        confirmButton = { TextButton(onClick = { if (confirm == "account") onDeleteAccount() else onDelete(); confirm = "" }) { Text("Delete permanently") } },
        dismissButton = { TextButton(onClick = { confirm = "" }) { Text("Cancel") } })
}

@Composable private fun ConfigDialog(config: GameConfig, onDismiss: () -> Unit, onSave: (GameConfig) -> Unit) {
    val values = remember { mutableStateListOf(config.addLeftMin.toString(), config.addLeftMax.toString(), config.addRightMin.toString(), config.addRightMax.toString(), config.multiplyLeftMin.toString(), config.multiplyLeftMax.toString(), config.multiplyRightMin.toString(), config.multiplyRightMax.toString()) }
    var duration by remember { mutableStateOf(config.durationSeconds.toString()) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Customize your round") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Subtraction reverses addition; division reverses multiplication for whole-number answers.", color = Muted, fontSize = 13.sp)
            OutlinedTextField(value = duration, onValueChange = { duration = it.filter(Char::isDigit).take(4) }, label = { Text("Duration in seconds (1–3600)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            listOf("Addition · first number", "Addition · second number", "Multiplication · first number", "Multiplication · second number").forEachIndexed { row, label ->
                Text(label, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    (0..1).forEach { col ->
                        val index = row * 2 + col
                        OutlinedTextField(value = values[index], onValueChange = { values[index] = it.filter(Char::isDigit).take(4) },
                            label = { Text(if (col == 0) "Min" else "Max") }, modifier = Modifier.weight(1f), singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                }
            }
            if (error) Text("Use ordered ranges up to 1000; addition starts at 0, multiplication at 1. Choose 1–3600 seconds.", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = {
        val updated = runCatching {
            val v = values.map { it.toInt() }
            config.copy(durationSeconds = duration.toInt(), addLeftMin = v[0], addLeftMax = v[1], addRightMin = v[2], addRightMax = v[3],
                multiplyLeftMin = v[4], multiplyLeftMax = v[5], multiplyRightMin = v[6], multiplyRightMax = v[7])
        }.getOrNull()
        if (updated != null) onSave(updated) else error = true
    }) { Text("Save setup") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable private fun Eyebrow(text: String) { Text(text, color = Lime, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold) }
@Composable private fun SmallStat(label: String, value: String) {
    Column { Text(value, fontSize = 25.sp, fontWeight = FontWeight.Medium); Text(label, color = Muted, fontSize = 9.sp, letterSpacing = 1.sp) }
}
@Composable private fun InfoCard(icon: ImageVector, title: String, text: String) {
    Surface(color = Panel, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(22.dp)) {
            Icon(icon, null, tint = Lime, modifier = Modifier.size(28.dp))
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
            Text(text, color = Muted, fontSize = 14.sp, lineHeight = 22.sp)
        }
    }
}
