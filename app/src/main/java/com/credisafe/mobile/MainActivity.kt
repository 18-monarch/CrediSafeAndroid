package com.credisafe.mobile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.credisafe.mobile.data.AuthManager
import com.credisafe.mobile.data.CrediSafeDb
import com.credisafe.mobile.data.DrivingEvent
import com.credisafe.mobile.data.TripRecord
import com.credisafe.mobile.domain.CompatibilityChecker
import com.credisafe.mobile.domain.CompatibilityState
import com.credisafe.mobile.domain.IssueLevel
import com.credisafe.mobile.domain.LiveTelemetry
import com.credisafe.mobile.domain.XpEngine
import com.credisafe.mobile.service.TelemetryForegroundService
import com.credisafe.mobile.service.TripSession
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

private val Night = Color(0xFF050B18)
private val Surface1 = Color(0xFF0D172E)
private val Surface2 = Color(0xFF15223D)
private val Surface3 = Color(0xFF1D2D4D)
private val Green = Color(0xFF00E676)
private val GreenSoft = Color(0xFF69F0AE)
private val Gold = Color(0xFFFFD700)
private val White = Color(0xFFF5F8F6)
private val Muted = Color(0xFF8A9AB0)
private val Border = Color(0xFF1E2F4D)
private val Warning = Color(0xFFF8C86A)
private val Error = Color(0xFFFF5252)

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.credisafe.mobile.service.SyncWorker.enqueue(applicationContext)
        setContent {
            CrediSafeTheme {
                Surface(Modifier.fillMaxSize(), color = Night) {
                    CrediSafeApp()
                }
            }
        }
    }

    fun consentAccepted() =
        getPreferences(MODE_PRIVATE).getBoolean("pilot_consent", false)

    fun acceptConsent() {
        getPreferences(MODE_PRIVATE).edit().putBoolean("pilot_consent", true).apply()
        requestPermissions()
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        if (permissions.isNotEmpty()) permissionLauncher.launch(permissions.toTypedArray())
    }

    fun startTrip() {
        val allowed = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!allowed) {
            requestPermissions()
            return
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, TelemetryForegroundService::class.java)
                .setAction(TelemetryForegroundService.ACTION_START),
        )
    }

    fun stopTrip() {
        startService(
            Intent(this, TelemetryForegroundService::class.java)
                .setAction(TelemetryForegroundService.ACTION_STOP),
        )
    }
}

private enum class Tab { HOME, DRIVE, TRIPS, REWARDS, PROFILE, DIAGNOSTICS, COMPATIBILITY, AUTH }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CrediSafeApp() {
    val activity = LocalContext.current as MainActivity
    val auth = remember { AuthManager(activity) }
    var tab by remember { mutableStateOf(if (auth.getUserId() == null) Tab.AUTH else Tab.HOME) }
    val telemetry by TripSession.state.collectAsState()

    if (tab == Tab.AUTH) {
        AuthScreen(auth) { tab = Tab.HOME }
        return
    }

    if (!activity.consentAccepted()) {
        Onboarding(onAccept = activity::acceptConsent)
        return
    }

    Scaffold(
        containerColor = Night,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandMark()
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("CrediSafe", color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Text("SAFE DRIVING • REAL REWARDS", color = GreenSoft, fontSize = 8.sp, letterSpacing = 1.sp)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Night),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Surface1) {
                Nav(tab == Tab.HOME, { tab = Tab.HOME }, Icons.Default.Home, "Home")
                Nav(tab == Tab.DRIVE, { tab = Tab.DRIVE }, Icons.Default.GpsFixed, "Drive")
                Nav(tab == Tab.TRIPS, { tab = Tab.TRIPS }, Icons.Default.Route, "Trips")
                Nav(tab == Tab.REWARDS, { tab = Tab.REWARDS }, Icons.Default.Redeem, "Rewards")
                Nav(tab == Tab.PROFILE, { tab = Tab.PROFILE }, Icons.Default.Person, "Profile")
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "tab_change"
            ) { targetTab ->
                when (targetTab) {
                    Tab.HOME -> HomeScreen(Modifier, telemetry, activity) { tab = Tab.DRIVE }
                    Tab.DRIVE -> DriveScreen(Modifier, telemetry, activity)
                    Tab.TRIPS -> TripsScreen(Modifier)
                    Tab.REWARDS -> RewardsScreen(Modifier)
                    Tab.PROFILE -> ProfileScreen(Modifier, { tab = Tab.DIAGNOSTICS }) { tab = Tab.COMPATIBILITY }
                    Tab.DIAGNOSTICS -> DiagnosticsScreen(Modifier, telemetry) { tab = Tab.PROFILE }
                    Tab.COMPATIBILITY -> CompatibilityScreen(Modifier) { tab = Tab.PROFILE }
                    Tab.AUTH -> Box {} // Handled above
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthScreen(auth: AuthManager, onAuthSuccess: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().background(Night).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = R.drawable.credisafe_icon),
            contentDescription = null,
            modifier = Modifier.size(100.dp)
        )
        Spacer(Modifier.height(32.dp))
        Text("Welcome to CrediSafe", color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Drive safe. Earn more.", color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(32.dp))

        TextField(
            value = email,
            onValueChange = { email = it; error = null },
            label = { Text("Email Address") },
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Surface1,
                unfocusedContainerColor = Surface1,
                focusedIndicatorColor = Green,
                unfocusedIndicatorColor = Border,
                cursorColor = Green,
                focusedTextColor = White,
                unfocusedTextColor = White
            ),
            shape = RoundedCornerShape(12.dp),
            leadingIcon = { Icon(Icons.Default.Email, null, tint = Muted) }
        )
        Spacer(Modifier.height(16.dp))
        TextField(
            value = password,
            onValueChange = { password = it; error = null },
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Surface1,
                unfocusedContainerColor = Surface1,
                focusedIndicatorColor = Green,
                unfocusedIndicatorColor = Border,
                cursorColor = Green,
                focusedTextColor = White,
                unfocusedTextColor = White
            ),
            shape = RoundedCornerShape(12.dp),
            leadingIcon = { Icon(Icons.Default.Lock, null, tint = Muted) }
        )

        if (error != null) {
            Spacer(Modifier.height(8.dp))
            Text(error!!, color = Error, fontSize = 12.sp)
        }

        Spacer(Modifier.height(32.dp))
        Button(
            onClick = {
                loading = true
                scope.launch {
                    val res = auth.login(email, password)
                    if (res.isSuccess) {
                        onAuthSuccess()
                    } else {
                        error = res.exceptionOrNull()?.message ?: "Authentication failed"
                    }
                    loading = false
                }
            },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Night),
            shape = RoundedCornerShape(17.dp),
            enabled = !loading && email.isNotEmpty() && password.isNotEmpty()
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(24.dp), color = Night)
            } else {
                Text("Login / Register", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Nav(
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
) {
    Column(
        modifier = Modifier
            .width(72.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (selected) Green else Muted,
            modifier = Modifier.size(21.dp),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            color = if (selected) Green else Muted,
            fontSize = 9.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun HomeScreen(modifier: Modifier, telemetry: LiveTelemetry, activity: MainActivity, onDrive: () -> Unit) {
    val db = remember { CrediSafeDb(activity) }
    var trips by remember { mutableStateOf(emptyList<TripRecord>()) }

    LaunchedEffect(Unit) {
        trips = db.listTrips()
    }

    val totalXp = trips.sumOf { it.xp ?: 0 }
    val totalPoints = trips.sumOf { it.rewardPoints ?: 0 }
    val completedTrips = trips.count { it.status == "COMPLETED" }
    val bestScore = trips.mapNotNull { it.safetyScore }.maxOrNull()

    LazyColumn(
        modifier.fillMaxSize().background(Night).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Spacer(Modifier.height(8.dp)) }
        item {
            Text("JOURNEY INTELLIGENCE", color = GreenSoft, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Text("Drive safe.", color = White, fontSize = 38.sp, fontWeight = FontWeight.Black)
            Text("Earn more.", color = Green, fontSize = 38.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(8.dp))
            Text(
                "Your results below are based only on trips actually recorded on this phone.",
                color = Muted,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
        }
        item { LiveHero(telemetry, bestScore ?: 0, totalXp) }
        item {
            LevelCard(totalXp)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
                DataStat("TRIPS", completedTrips.toString(), Modifier.weight(1f))
                DataStat("XP", totalXp.toString(), Modifier.weight(1f))
                DataStat("POINTS", totalPoints.toString(), Modifier.weight(1f))
            }
        }
        item {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Surface1),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("One transparent result", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    if (trips.isEmpty()) {
                        Text("No trip data yet. Complete a real journey to generate your first score, XP and reward points.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                    } else {
                        Text(
                            "Best recorded safety score: ${bestScore ?: 0}/100. " +
                                "Lifetime XP: $totalXp. Reward points: $totalPoints.",
                            color = Muted,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }
        }
        item {
            Button(
                onClick = {
                    onDrive()
                    if (!telemetry.active) {
                        activity.startTrip()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Night),
                shape = RoundedCornerShape(17.dp),
            ) {
                Icon(if (telemetry.active) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text(if (telemetry.active) "View active journey" else "Start your journey", fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.ArrowForward, null)
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun LiveHero(telemetry: LiveTelemetry, bestScore: Int, totalXp: Int) {
    val level = XpEngine.level(totalXp)
    Card(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(bestScore.coerceIn(0, 100), Modifier.size(120.dp))
            Spacer(Modifier.width(15.dp))
            Column(Modifier.weight(1f)) {
                Text(if (telemetry.active) "TRIP LIVE" else "CURRENT LEVEL", color = GreenSoft, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Text(
                    if (telemetry.active) (telemetry.safetyScore).toString() else level,
                    color = White,
                    fontSize = if (telemetry.active) 42.sp else 30.sp,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    if (telemetry.active) "Real-time safety estimate" else "Based on $totalXp lifetime XP",
                    color = Muted,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun LevelCard(totalXp: Int) {
    val progress = XpEngine.calculateProgress(totalXp)
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text(progress.current.uppercase(), color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                if (progress.next != null) {
                    Text("NEXT: ${progress.next.uppercase()}", color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(8.dp).background(Surface2, CircleShape)) {
                Box(Modifier.fillMaxWidth(progress.progress).fillMaxHeight().background(Gold, CircleShape))
            }
            if (progress.next != null) {
                Spacer(Modifier.height(6.dp))
                Text("${progress.remaining} XP remaining for ${progress.next} level", color = Muted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun DriveScreen(modifier: Modifier, telemetry: LiveTelemetry, activity: MainActivity) {
    Column(
        modifier.fillMaxSize().background(Night).verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (telemetry.active) "TRIP ACTIVE" else "READY TO DRIVE", color = GreenSoft, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Text(if (telemetry.active) "Measure the journey." else "Capture real data.", color = White, fontSize = 30.sp, fontWeight = FontWeight.Black)

        Card(shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = Surface1), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text("CURRENT SPEED", color = Muted, fontSize = 9.sp, letterSpacing = 1.2.sp)
                Text("%.1f".format(telemetry.speedKmh), color = White, fontSize = 50.sp, fontWeight = FontWeight.Black)
                Text("km/h", color = GreenSoft, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
                    DataStat("DISTANCE", "%.2f km".format(telemetry.distanceM / 1000.0), Modifier.weight(1f))
                    DataStat("DURATION", duration(telemetry.elapsedMs), Modifier.weight(1f))
                }
            }
        }

        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Surface1), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("JOURNEY INTELLIGENCE", color = White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    val streamColor = when (telemetry.streamStatus) {
                        com.credisafe.mobile.domain.StreamStatus.LIVE -> Green
                        com.credisafe.mobile.domain.StreamStatus.CONNECTING -> Warning
                        com.credisafe.mobile.domain.StreamStatus.RECONNECTING -> Warning
                        else -> Muted
                    }
                    if (telemetry.active) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(6.dp).background(streamColor, CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Text(telemetry.streamStatus.name, color = streamColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                DataLine("GPS quality", "${(telemetry.gpsQuality * 100).roundToInt()}%")
                DataLine("GPS accuracy", telemetry.gpsAccuracyM?.let { "%.0f m".format(it) } ?: "waiting")
                DataLine("Sensor samples", telemetry.sensorCount.toString())
                DataLine("Location samples", telemetry.locationCount.toString())
                            }
        }

        telemetry.latestEvent?.let { EventCard(it) }

        Button(
            onClick = if (telemetry.active) activity::stopTrip else activity::startTrip,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (telemetry.active) Error else Green,
                contentColor = if (telemetry.active) White else Night,
            ),
            shape = RoundedCornerShape(17.dp),
        ) {
            Icon(if (telemetry.active) Icons.Default.Stop else Icons.Default.PlayArrow, null)
            Spacer(Modifier.width(8.dp))
            Text(if (telemetry.active) "Stop journey" else "Start journey", fontWeight = FontWeight.Bold)
        }

        telemetry.lastError?.let { Text(it, color = Error, fontSize = 12.sp) }

        Text(
            "Pilot score only: calibrate against labelled real-world trips before treating it as validated safety intelligence.",
            color = Muted,
            fontSize = 10.sp,
            lineHeight = 15.sp,
        )
    }
}

@Composable
private fun TripsScreen(modifier: Modifier) {
    val context = LocalContext.current
    val db = remember { CrediSafeDb(context) }
    var trips by remember { mutableStateOf(emptyList<TripRecord>()) }
    var showExport by remember { mutableStateOf(false) }
    var selectedTrip by remember { mutableStateOf<TripRecord?>(null) }

    LaunchedEffect(Unit) {
        trips = db.listTrips()
    }

    Column(modifier.fillMaxSize().background(Night).padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("YOUR JOURNEYS", color = GreenSoft, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text("Trip history", color = White, fontSize = 30.sp, fontWeight = FontWeight.Black)
                Text("Only journeys recorded on this device appear here.", color = Muted, fontSize = 11.sp)
            }
            OutlinedButton(onClick = { showExport = true }) {
                Icon(Icons.Default.Download, null)
                Spacer(Modifier.width(4.dp))
                Text("Export")
            }
        }
        Spacer(Modifier.height(14.dp))

        if (trips.isEmpty()) {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Surface1), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("No trips yet", color = White, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("Start a real journey and its score, XP and reward points will appear here.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(trips, key = { it.id }) { TripRow(it) { selectedTrip = it } }
            }
        }
    }

    if (showExport) {
        ExportDialog(db) { showExport = false }
    }

    if (selectedTrip != null) {
        TripDetailDialog(selectedTrip!!, db) { selectedTrip = null }
    }
}

@Composable
private fun TripDetailDialog(trip: TripRecord, db: CrediSafeDb, onDismiss: () -> Unit) {
    var events by remember { mutableStateOf<List<DrivingEvent>>(emptyList()) }
    LaunchedEffect(trip.id) {
        events = db.listEvents(trip.id)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Journey Details", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    val statusText = if (trip.isAuthoritative) "SERVER CONFIRMED" else trip.syncStatus
                    val syncColor = when (trip.syncStatus) {
                        "SYNCED" -> Green
                        "FAILED" -> Error
                        "SYNCING" -> Warning
                        else -> Muted
                    }
                    Text(statusText, color = syncColor, fontSize = 8.sp, fontWeight = FontWeight.Black)
                }
                Text(SimpleDateFormat("dd MMM yyyy • HH:mm", Locale.getDefault()).format(Date(trip.startedAt)), color = Muted, fontSize = 11.sp)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DataStat("SCORE", (trip.safetyScore ?: 0).toString(), Modifier.weight(1f))
                    DataStat("XP", (trip.xp ?: 0).toString(), Modifier.weight(1f))
                    DataStat("POINTS", (trip.rewardPoints ?: 0).toString(), Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))
                DiagnosticSection("Performance Summary") {
                    DataLine("Distance", "%.2f km".format(trip.distanceM / 1000.0))
                    DataLine("Duration", duration(trip.durationMs))
                    DataLine("Avg Speed", "%.1f km/h".format(trip.avgSpeedKmh))
                    DataLine("Max Speed", "%.1f km/h".format(trip.maxSpeedKmh))
                    DataLine("GPS Confidence", "${(trip.gpsQuality * 100).roundToInt()}%")
                }
                if (events.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Safety Events", color = White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    events.forEach { event ->
                        Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Surface2), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (event.severity == com.credisafe.mobile.data.EventSeverity.HIGH) Icons.Default.Shield else Icons.Default.Settings,
                                        null,
                                        tint = if (event.severity == com.credisafe.mobile.data.EventSeverity.HIGH) Error else Warning,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(event.type.name.replace('_', ' '), color = White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                if (event.detail != null) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(event.detail, color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Night)) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun RewardsScreen(modifier: Modifier) {
    val context = LocalContext.current
    val db = remember { CrediSafeDb(context) }
    var trips by remember { mutableStateOf(emptyList<TripRecord>()) }

    LaunchedEffect(Unit) {
        trips = db.listTrips()
    }

    val points = trips.sumOf { it.rewardPoints ?: 0 }
    val hasData = trips.isNotEmpty()

    Column(
        modifier.fillMaxSize().background(Night).verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("REWARD PROGRESS", color = GreenSoft, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Text("Earn from real journeys.", color = White, fontSize = 30.sp, fontWeight = FontWeight.Black)
        Text(
            "No points are invented. This screen is derived from completed trips recorded on this phone.",
            color = Muted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )

        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Surface1), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text("Reward points", color = Muted, fontSize = 11.sp)
                Text(points.toString(), color = Green, fontSize = 38.sp, fontWeight = FontWeight.Black)
                Text(
                    if (hasData) "Earned from completed recorded trips." else "No completed trips yet.",
                    color = if (hasData) GreenSoft else Muted,
                    fontSize = 12.sp,
                )
            }
        }

        if (!hasData) {
            EmptyRewardCard("Complete your first real trip to start building reward progress.")
        } else {
            EmptyRewardCard("Partner redemption targets are intentionally hidden until real reward rules are connected to the backend.")
        }
    }
}

@Composable
private fun DiagnosticsScreen(modifier: Modifier, telemetry: LiveTelemetry, onBack: () -> Unit) {
    Column(
        modifier.fillMaxSize().background(Night).verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.ArrowForward,
                null,
                tint = Green,
                modifier = Modifier.size(20.dp).clickable(onClick = onBack).padding(2.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text("EXPERT DIAGNOSTICS", color = GreenSoft, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        }

        Text("Live Telemetry Math", color = White, fontSize = 24.sp, fontWeight = FontWeight.Black)

        DiagnosticSection("Genius Sensor Lab") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("GENUINE HZ", color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    Text("%.1f Hz".format(telemetry.sensorHz), color = if (telemetry.sensorHz >= 45) Green else Warning, fontSize = 16.sp, fontWeight = FontWeight.Black)
                }
                Column(Modifier.weight(1f)) {
                    Text("TIMING JITTER", color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    Text("%.2f ms".format(telemetry.sensorJitterMs), color = if (telemetry.sensorJitterMs < 5) GreenSoft else Error, fontSize = 16.sp, fontWeight = FontWeight.Black)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val health = when {
                        telemetry.sensorHz < 30 -> "Critical (OS Throttling)"
                        telemetry.sensorJitterMs > 10 -> "Poor (High Latency)"
                        telemetry.sensorHz >= 48 && telemetry.sensorJitterMs < 3 -> "Genuine / Elite"
                        else -> "Healthy"
                    }
                    Text("Quality: $health", color = White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("LATENCY", color = Muted, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                    Text("%.3f ms".format(telemetry.processLatencyMs), color = if (telemetry.processLatencyMs < 1.0) GreenSoft else Warning, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        DiagnosticSection("IMU Raw Feed (m/s² | rad/s)") {
            DiagnosticBar("Accel X", telemetry.rawAx, 20.0, Green)
            DiagnosticBar("Accel Y", telemetry.rawAy, 20.0, Green)
            DiagnosticBar("Accel Z", telemetry.rawAz, 20.0, Green)
            Spacer(Modifier.height(8.dp))
            DiagnosticBar("Gyro X", telemetry.rawGx, 5.0, GreenSoft)
            DiagnosticBar("Gyro Y", telemetry.rawGy, 5.0, GreenSoft)
            DiagnosticBar("Gyro Z", telemetry.rawGz, 5.0, GreenSoft)
        }

        DiagnosticSection("Processed World Frame") {
            DiagnosticBar("North", telemetry.worldNorth, 10.0, Gold)
            DiagnosticBar("East", telemetry.worldEast, 10.0, Gold)
            DiagnosticBar("Up", telemetry.worldUp, 10.0, Gold)
        }

        DiagnosticSection("Vehicle Alignment") {
            DiagnosticBar("Longitudinal", telemetry.longAcc, 10.0, White)
            DiagnosticBar("Lateral", telemetry.latAcc, 10.0, White)
            DiagnosticBar("Vertical", telemetry.verticalAcc, 10.0, White)
        }

        DiagnosticSection("GPS & Persistence") {
            DataLine("Quality Score", "%.2f".format(telemetry.gpsQuality))
            DataLine("Accuracy", "${telemetry.gpsAccuracyM ?: 0.0} m")
            DataLine("Sensor Samples", telemetry.sensorCount.toString())
            DataLine("Location Samples", telemetry.locationCount.toString())
        }

        DiagnosticSection("Network Connectivity") {
            val apiHost = remember { 
                try {
                    java.net.URL(com.credisafe.mobile.BuildConfig.CREDISAFE_API_BASE_URL).host
                } catch (e: Exception) {
                    "invalid-url"
                }
            }
            DataLine("API Host", apiHost)
            DataLine("Stream Status", telemetry.streamStatus.name)
        }
    }
}

@Composable
private fun CompatibilityScreen(modifier: Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    val compatibility = remember { CompatibilityChecker.check(context) }

    Column(
        modifier.fillMaxSize().background(Night).verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.ArrowForward,
                null,
                tint = Green,
                modifier = Modifier.size(20.dp).clickable(onClick = onBack).padding(2.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text("SYSTEM COMPATIBILITY", color = GreenSoft, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        }

        Text("Device Audit", color = White, fontSize = 24.sp, fontWeight = FontWeight.Black)

        compatibility.issues.forEach { issue ->
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Surface1),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (issue.level == IssueLevel.CRITICAL) Icons.Default.Shield else Icons.Default.Settings,
                            null,
                            tint = if (issue.level == IssueLevel.CRITICAL) Error else Warning
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(issue.label, color = White, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(issue.suggestion, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                    
                    if (issue.actionIntent != null) {
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                try {
                                    context.startActivity(Intent(issue.actionIntent))
                                } catch (_: Exception) {
                                    // Fallback for some intents
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Surface2)
                        ) {
                            Text("Resolve in Settings", color = GreenSoft, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        if (compatibility.issues.isEmpty()) {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Surface1), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Shield, null, tint = Green, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("System is Optimized", color = White, fontWeight = FontWeight.Bold)
                    Text("Your device hardware and settings are fully compatible with CrediSafe telemetry.", color = Muted, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun DiagnosticSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Surface1), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun DiagnosticBar(label: String, value: Double, range: Double, color: Color) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Muted, fontSize = 10.sp)
            Text("%.3f".format(value), color = White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).background(Border, CircleShape)) {
            val progress = ((value + range) / (range * 2)).coerceIn(0.0, 1.0).toFloat()
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(color, CircleShape))
        }
    }
}

@Composable
private fun ProfileScreen(modifier: Modifier, onDiagnostics: () -> Unit, onCompatibility: () -> Unit) {
    val context = LocalContext.current
    val db = remember { CrediSafeDb(context) }
    var trips by remember { mutableStateOf(emptyList<TripRecord>()) }
    val compatibility = remember { CompatibilityChecker.check(context) }

    LaunchedEffect(Unit) {
        trips = db.listTrips()
    }

    val xp = trips.sumOf { it.xp ?: 0 }
    val points = trips.sumOf { it.rewardPoints ?: 0 }

    Column(
        modifier.fillMaxSize().background(Night).verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("DRIVER PROFILE", color = GreenSoft, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Text("Your progress", color = White, fontSize = 30.sp, fontWeight = FontWeight.Black)

        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Surface1),
            modifier = Modifier.fillMaxWidth().clickable(onClick = onCompatibility)
        ) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Shield, 
                    null, 
                    tint = if (compatibility.issues.any { it.level == IssueLevel.CRITICAL }) Error else if (compatibility.issues.isNotEmpty()) Warning else Green
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("System Health", color = White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (compatibility.issues.isEmpty()) "Fully compatible" else "${compatibility.issues.size} issues detected",
                        color = Muted, 
                        fontSize = 11.sp
                    )
                }
                Icon(Icons.Default.ArrowForward, null, tint = Muted)
            }
        }

        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Surface1), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                ProfileValue("Completed trips", trips.count { it.status == "COMPLETED" }.toString())
                ProfileValue("Lifetime XP", xp.toString())
                ProfileValue("Reward points", points.toString())
                ProfileValue("Best score", trips.mapNotNull { it.safetyScore }.maxOrNull()?.toString() ?: "—")
            }
        }

        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Surface1),
            modifier = Modifier.fillMaxWidth().clickable(onClick = onDiagnostics)
        ) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Sensors, null, tint = Green)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Expert Diagnostics", color = White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("Real-time IMU and Math feed", color = Muted, fontSize = 11.sp)
                }
                Icon(Icons.Default.ArrowForward, null, tint = Muted)
            }
        }

        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Surface1), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text("No dummy profile data", color = White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text(
                    "The app does not display fake driver names, fake rankings, fake XP totals, or fake reward balances. Everything shown here comes from this device's recorded trip data.",
                    color = Muted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}

@Composable
private fun Onboarding(onAccept: () -> Unit) {
    val context = LocalContext.current
    val compatibility = remember { CompatibilityChecker.check(context) }
    
    Column(
        Modifier.fillMaxSize().background(Night).verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start
    ) {
        Image(
            painter = painterResource(id = R.drawable.credisafe_icon),
            contentDescription = null,
            modifier = Modifier.size(80.dp)
        )
        Spacer(Modifier.height(32.dp))
        Text("Drive safe.", color = White, fontSize = 48.sp, fontWeight = FontWeight.Black)
        Text("Earn more.", color = Green, fontSize = 48.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(16.dp))
        
        if (compatibility.issues.any { it.level == IssueLevel.CRITICAL }) {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF3A1A1D)), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("CRITICAL COMPATIBILITY ISSUE", color = Error, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(compatibility.issues.first { it.level == IssueLevel.CRITICAL }.suggestion, color = White, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        Text("To track your safety performance accurately, CrediSafe collects precise location and motion data even when the app is in the background or not in use while a journey is active.", color = Muted, fontSize = 15.sp, lineHeight = 22.sp)
        Spacer(Modifier.height(24.dp))
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Surface1), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Shield, null, tint = Gold, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("PROMINENT DISCLOSURE", color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                }
                Spacer(Modifier.height(10.dp))
                Text("Location and motion sensors are monitored continuously during a journey to detect events like harsh braking. This data is processed locally and uploaded only when the trip ends.", color = White, fontSize = 13.sp, lineHeight = 19.sp)
                Spacer(Modifier.height(10.dp))
                Text("You can stop tracking at any time via the 'Stop' button in the persistent notification.", color = Warning, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onAccept,
            enabled = compatibility.issues.none { it.level == IssueLevel.CRITICAL },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Night),
            shape = RoundedCornerShape(17.dp),
        ) {
            Text("Continue & grant permissions", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ScoreRing(score: Int, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawArc(Border, -90f, 360f, false, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
            if (score > 0) {
                drawArc(Green, -90f, (score / 100f) * 360f, false, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (score > 0) score.toString() else "—", color = White, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Text("SAFETY", color = Muted, fontSize = 8.sp, letterSpacing = 1.1.sp)
        }
    }
}

@Composable
private fun DataStat(label: String, value: String, modifier: Modifier) {
    val tint = if (label == "XP" || label == "POINTS") Gold else White
    Column(modifier.background(Surface2, RoundedCornerShape(14.dp)).padding(11.dp)) {
        Text(label, color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(3.dp))
        Text(value, color = tint, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DataLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TripRow(trip: TripRecord, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(22.dp), 
        colors = CardDefaults.cardColors(containerColor = Surface1), 
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        SimpleDateFormat("dd MMM yyyy • HH:mm", Locale.getDefault()).format(Date(trip.startedAt)),
                        color = White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(trip.status, color = GreenSoft, fontSize = 9.sp)
                        Spacer(Modifier.width(6.dp))
                        val statusText = if (trip.isAuthoritative) "SERVER CONFIRMED" else trip.syncStatus
                        val syncColor = when (trip.syncStatus) {
                            "SYNCED" -> Green
                            "FAILED" -> Error
                            "SYNCING" -> Warning
                            else -> Muted
                        }
                        Text(statusText, color = syncColor, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text(trip.safetyScore?.toString() ?: "—", color = Green, fontSize = 25.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(9.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Tag("%.2f km".format(trip.distanceM / 1000.0))
                Tag("${trip.xp ?: 0} XP", color = Gold)
                Tag("${trip.rewardPoints ?: 0} pts", color = Gold)
            }
        }
    }
}

@Composable
private fun EmptyRewardCard(message: String) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Surface1), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Icon(Icons.Default.Redeem, null, tint = Green, modifier = Modifier.size(25.dp))
            Spacer(Modifier.height(8.dp))
            Text(message, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun EventCard(event: DrivingEvent) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Surface1), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Latest safety event", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(6.dp))
            Text(event.type.name.replace('_', ' '), color = White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                "${event.severity.name} • ${"%.0f".format(event.confidence * 100)}% confidence",
                color = Warning,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun ProfileValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 12.sp)
        Text(value, color = White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Tag(text: String, color: Color = GreenSoft) {
    Text(
        text,
        color = color,
        fontSize = 9.sp,
        modifier = Modifier
            .background(Surface2, RoundedCornerShape(50.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        maxLines = 1,
        fontWeight = FontWeight.Bold,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun BrandMark() {
    Image(
        painter = painterResource(id = R.drawable.credisafe_icon),
        contentDescription = "CrediSafe Logo",
        modifier = Modifier.size(38.dp)
    )
}

@Composable
private fun ExportDialog(db: CrediSafeDb, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text("Export pilot JSON", color = White) },
        text = { Text("Export the actual telemetry stored on this phone as JSON.", color = Muted) },
        confirmButton = {
            Column {
                Button(onClick = { shareFile(context, "application/json", "credisafe_export.json", db.exportJson()); onDismiss() }) { Text("Share JSON") }
                OutlinedButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private fun shareFile(context: android.content.Context, mime: String, name: String, content: String) {
    val file = File(context.cacheDir, name)
    file.writeText(content)
    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share CrediSafe telemetry"))
}

private fun duration(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

@Composable
private fun CrediSafeTheme(content: @Composable () -> Unit) {
    val scheme = androidx.compose.material3.darkColorScheme(
        primary = Green,
        onPrimary = Night,
        primaryContainer = Color(0xFF143A24),
        onPrimaryContainer = GreenSoft,
        background = Night,
        onBackground = White,
        surface = Surface1,
        onSurface = White,
        surfaceVariant = Surface2,
        onSurfaceVariant = Muted,
        outline = Border,
        error = Error,
        errorContainer = Color(0xFF3A1A1D),
    )
    val context = LocalContext.current
    SideEffect {
        (context as? Activity)?.apply {
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
            window.statusBarColor = Night.toArgb()
            window.navigationBarColor = Surface1.toArgb()
        }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private fun Color.toArgb(): Int =
    android.graphics.Color.argb(
        (alpha * 255).roundToInt(),
        (red * 255).roundToInt(),
        (green * 255).roundToInt(),
        (blue * 255).roundToInt(),
    )
