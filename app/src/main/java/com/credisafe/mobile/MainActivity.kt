package com.credisafe.mobile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.credisafe.mobile.data.AuthManager
import com.credisafe.mobile.data.CrediSafeDb
import com.credisafe.mobile.data.DrivingEvent
import com.credisafe.mobile.data.TripRecord
import com.credisafe.mobile.data.Vehicle
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
        if (android.os.Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions += Manifest.permission.ACTIVITY_RECOGNITION
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        if (permissions.isNotEmpty()) permissionLauncher.launch(permissions.toTypedArray())
    }

    fun startTrip(userId: String?, vehicleId: String?) {
        val allowed = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!allowed) {
            requestPermissions()
            return
        }

        // Mobility permission was added after the original pilot onboarding. Existing
        // users are prompted at trip start if they have not granted it yet;
        // recording can still continue in degraded mode if they decline.
        if (android.os.Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions()
        }

        val intent = Intent(this, TelemetryForegroundService::class.java).apply {
            action = TelemetryForegroundService.ACTION_START
            putExtra(TelemetryForegroundService.EXTRA_USER_ID, userId)
            putExtra(TelemetryForegroundService.EXTRA_VEHICLE_ID, vehicleId)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    fun stopTrip() {
        startService(
            Intent(this, TelemetryForegroundService::class.java)
                .setAction(TelemetryForegroundService.ACTION_STOP),
        )
    }
}

private enum class Tab { HOME, DRIVE, TRIPS, REWARDS, PROFILE, DIAGNOSTICS, COMPATIBILITY, AUTH, REGISTER }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CrediSafeApp() {
    val context = LocalContext.current
    val activity = context as MainActivity
    val auth = remember { AuthManager(context) }
    var tab by remember { mutableStateOf(if (auth.getUserId() == null) Tab.AUTH else Tab.HOME) }
    val telemetry by TripSession.state.collectAsState()

    // Smooth navigation back logic
    BackHandler(enabled = tab != Tab.HOME && tab != Tab.AUTH && tab != Tab.REGISTER) {
        when (tab) {
            Tab.DIAGNOSTICS, Tab.COMPATIBILITY -> tab = Tab.PROFILE
            else -> tab = Tab.HOME
        }
    }

    if (tab == Tab.AUTH) {
        AuthScreen(auth, onRegister = { tab = Tab.REGISTER }) { tab = Tab.HOME }
        return
    }

    if (tab == Tab.REGISTER) {
        RegisterScreen(auth, onLogin = { tab = Tab.AUTH }) { tab = Tab.HOME }
        return
    }

    if (!activity.consentAccepted()) {
        Onboarding(onAccept = activity::acceptConsent)
        return
    }

    Scaffold(
        containerColor = Night,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BrandMark()
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("CrediSafe", color = White, fontSize = 19.sp, fontWeight = FontWeight.Black)
                                Text("INTELLIGENT TELEMATICS", color = GreenSoft, fontSize = 8.sp, letterSpacing = 1.2.sp)
                            }
                        }
                    },
                    actions = {
                        if (telemetry.active) {
                            Row(
                                modifier = Modifier.padding(end = 12.dp).background(Surface2, RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.size(6.dp).background(Green, CircleShape))
                                Spacer(Modifier.width(6.dp))
                                Text("LIVE", color = White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Night),
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, Border, Color.Transparent))))
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Surface1, tonalElevation = 0.dp) {
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
                transitionSpec = {
                    fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                },
                label = "tab_change"
            ) { targetTab ->
                when (targetTab) {
                    Tab.HOME -> HomeScreen(Modifier, telemetry, activity) { tab = Tab.DRIVE }
                    Tab.DRIVE -> DriveScreen(Modifier, telemetry, activity, auth)
                    Tab.TRIPS -> TripsScreen(Modifier, telemetry)
                    Tab.REWARDS -> RewardsScreen(Modifier, telemetry)
                    Tab.PROFILE -> ProfileScreen(Modifier, telemetry, auth, { tab = Tab.DIAGNOSTICS }, { tab = Tab.COMPATIBILITY }) {
                        tab = Tab.AUTH
                    }
                    Tab.DIAGNOSTICS -> DiagnosticsScreen(Modifier, telemetry) { tab = Tab.PROFILE }
                    Tab.COMPATIBILITY -> CompatibilityScreen(Modifier) { tab = Tab.PROFILE }
                    Tab.AUTH, Tab.REGISTER -> Box {}
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthScreen(auth: AuthManager, onRegister: () -> Unit, onAuthSuccess: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().background(Night).padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = R.drawable.credisafe_icon),
            contentDescription = null,
            modifier = Modifier.size(100.dp).shadow(20.dp, CircleShape, spotColor = Green)
        )
        Spacer(Modifier.height(32.dp))
        Text("Welcome to CrediSafe", color = White, fontSize = 28.sp, fontWeight = FontWeight.Black)
        Text("Drive safe. Earn more.", color = GreenSoft, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(40.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it.trim().lowercase(); error = null },
            label = { Text("Email Address") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Green,
                unfocusedBorderColor = Surface3,
                cursorColor = Green,
                focusedLabelColor = Green,
                unfocusedLabelColor = Muted,
                focusedTextColor = White,
                unfocusedTextColor = White
            ),
            shape = RoundedCornerShape(16.dp),
            leadingIcon = { Icon(Icons.Default.Email, null, tint = Muted) }
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; error = null },
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Green,
                unfocusedBorderColor = Surface3,
                cursorColor = Green,
                focusedLabelColor = Green,
                unfocusedLabelColor = Muted,
                focusedTextColor = White,
                unfocusedTextColor = White
            ),
            shape = RoundedCornerShape(16.dp),
            leadingIcon = { Icon(Icons.Default.Lock, null, tint = Muted) },
            trailingIcon = {
                val icon = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(icon, null, tint = Muted)
                }
            }
        )

        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error!!, color = Error, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }

        Spacer(Modifier.height(32.dp))
        Button(
            onClick = {
                if (email.isEmpty() || password.isEmpty()) {
                    error = "Please enter both email and password"
                    return@Button
                }
                loading = true
                scope.launch {
                    val res = auth.login(email, password)
                    if (res.isSuccess) {
                        onAuthSuccess()
                    } else {
                        val e = res.exceptionOrNull()
                        error = when {
                            e?.message?.contains("401") == true -> "Incorrect email or password"
                            e?.message?.contains("timeout") == true -> "Connection timed out. Render may be waking up. Please try again."
                            else -> e?.message ?: "Unable to connect to CrediSafe server"
                        }
                    }
                    loading = false
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Night),
            shape = RoundedCornerShape(18.dp),
            enabled = !loading
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(24.dp), color = Night, strokeWidth = 2.dp)
            } else {
                Text("Login", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onRegister) {
            Text("New to CrediSafe? Create account", color = GreenSoft, fontSize = 14.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RegisterScreen(auth: AuthManager, onLogin: () -> Unit, onAuthSuccess: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().background(Night).padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Create Account", color = White, fontSize = 28.sp, fontWeight = FontWeight.Black)
        Text("Start building your driving profile.", color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(40.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it; error = null },
            label = { Text("Full Name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Green, unfocusedBorderColor = Surface3,
                cursorColor = Green, focusedLabelColor = Green, unfocusedLabelColor = Muted,
                focusedTextColor = White, unfocusedTextColor = White
            ),
            shape = RoundedCornerShape(16.dp),
            leadingIcon = { Icon(Icons.Default.Person, null, tint = Muted) }
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = email,
            onValueChange = { email = it.trim().lowercase(); error = null },
            label = { Text("Email Address") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Green, unfocusedBorderColor = Surface3,
                cursorColor = Green, focusedLabelColor = Green, unfocusedLabelColor = Muted,
                focusedTextColor = White, unfocusedTextColor = White
            ),
            shape = RoundedCornerShape(16.dp),
            leadingIcon = { Icon(Icons.Default.Email, null, tint = Muted) }
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; error = null },
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Green, unfocusedBorderColor = Surface3,
                cursorColor = Green, focusedLabelColor = Green, unfocusedLabelColor = Muted,
                focusedTextColor = White, unfocusedTextColor = White
            ),
            shape = RoundedCornerShape(16.dp),
            leadingIcon = { Icon(Icons.Default.Lock, null, tint = Muted) }
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = confirmPassword,
            onValueChange = { confirmPassword = it; error = null },
            label = { Text("Confirm Password") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Green, unfocusedBorderColor = Surface3,
                cursorColor = Green, focusedLabelColor = Green, unfocusedLabelColor = Muted,
                focusedTextColor = White, unfocusedTextColor = White
            ),
            shape = RoundedCornerShape(16.dp),
            leadingIcon = { Icon(Icons.Default.Lock, null, tint = Muted) }
        )

        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(error!!, color = Error, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }

        Spacer(Modifier.height(32.dp))
        Button(
            onClick = {
                if (name.isEmpty() || email.isEmpty() || password.isEmpty()) {
                    error = "All fields are required"
                    return@Button
                }
                if (password != confirmPassword) {
                    error = "Passwords do not match"
                    return@Button
                }
                loading = true
                scope.launch {
                    val res = auth.login(email, password, name)
                    if (res.isSuccess) {
                        onAuthSuccess()
                    } else {
                        error = res.exceptionOrNull()?.message ?: "Registration failed"
                    }
                    loading = false
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Night),
            shape = RoundedCornerShape(18.dp),
            enabled = !loading
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(24.dp), color = Night, strokeWidth = 2.dp)
            } else {
                Text("Create Account", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onLogin) {
            Text("Already have an account? Login", color = GreenSoft, fontSize = 14.sp)
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

    LaunchedEffect(telemetry.active) {
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
                    Text("Intelligent Insight", color = Gold, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                    Spacer(Modifier.height(4.dp))
                    Text("One transparent result", color = White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    if (trips.isEmpty()) {
                        Text("No trip data yet. Complete a real journey to generate your first score, XP and reward points.", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                    } else {
                        val smoothBraking = trips.count { (it.safetyScore ?: 0) > 90 }
                        val insight = if (smoothBraking > 0) {
                            "You've had $smoothBraking high-score journeys. Keep building a verified safe-driving history."
                        } else {
                            "Best recorded safety score: ${bestScore ?: 0}/100. Lifetime XP: $totalXp."
                        }
                        Text(insight, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
            }
        }
        item {
            Button(
                onClick = {
                    onDrive()
                    if (!telemetry.active) {
                        // Vehicle ID is handled in DriveScreen start logic
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
        item {
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Text("POWERED BY CREDISAFE INTELLIGENCE", color = Muted.copy(alpha = 0.4f), fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
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
private fun DriveScreen(modifier: Modifier, telemetry: LiveTelemetry, activity: MainActivity, auth: AuthManager) {
    val context = LocalContext.current
    val db = remember { CrediSafeDb(context) }
    var selectedVehicleId by remember { mutableStateOf(auth.getSelectedVehicleId()) }
    var vehicles by remember { mutableStateOf(emptyList<Vehicle>()) }

    LaunchedEffect(Unit) {
        vehicles = db.listVehicles(auth.getUserId() ?: "")
        if (selectedVehicleId == null && vehicles.isNotEmpty()) {
            selectedVehicleId = vehicles.first().id
            auth.setSelectedVehicleId(selectedVehicleId)
        }
    }

    Column(
        modifier.fillMaxSize().background(Night).verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (telemetry.active) "TRIP ACTIVE" else "READY TO DRIVE", color = GreenSoft, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Text(if (telemetry.active) "Measure the journey." else "Capture real data.", color = White, fontSize = 30.sp, fontWeight = FontWeight.Black)

        if (!telemetry.active) {
            VehicleSelectionCard(vehicles, selectedVehicleId) {
                selectedVehicleId = it.id
                auth.setSelectedVehicleId(it.id)
            }
        }

        if (telemetry.active && telemetry.route.isNotEmpty()) {
            Card(shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = Surface1), modifier = Modifier.fillMaxWidth().height(200.dp)) {
                OpenMobilityMap(
                    route = telemetry.route,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

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
                DataLine(
                    "Mobility",
                    "${telemetry.transportMode.name.replace('_', ' ')} (${telemetry.mobility.confidence}%)",
                )
                DataLine(
                    "Road zone",
                    telemetry.roadContext.zoneType.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() },
                )
                DataLine("Road", telemetry.roadContext.roadName ?: "Resolving / unavailable")
                DataLine(
                    "Trusted speed limit",
                    telemetry.roadContext.speedLimitKmh
                        ?.takeIf { telemetry.roadContext.speedLimitTrusted }
                        ?.let { "${it.roundToInt()} km/h" }
                        ?: "Not verified",
                )
                DataLine(
                    "Context confidence",
                    "${(telemetry.roadContext.confidence * 100).roundToInt()}%",
                )
                DataLine("Sensor samples", telemetry.sensorCount.toString())
                DataLine("Location samples", telemetry.locationCount.toString())
                            }
        }

        telemetry.latestEvent?.let { EventCard(it) }

        Button(
            onClick = {
                if (telemetry.active) {
                    activity.stopTrip()
                } else {
                    activity.startTrip(auth.getUserId(), selectedVehicleId)
                }
            },
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
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun VehicleSelectionCard(vehicles: List<Vehicle>, selectedId: String?, onSelect: (Vehicle) -> Unit) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Surface1), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Vehicle Profile", color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            if (vehicles.isEmpty()) {
                Text("No vehicles registered. Using generic profile.", color = Muted, fontSize = 12.sp)
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(vehicles) { vehicle ->
                        val selected = vehicle.id == selectedId
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selected) Green.copy(alpha = 0.1f) else Surface2)
                                .border(1.dp, if (selected) Green else Color.Transparent, RoundedCornerShape(12.dp))
                                .clickable { onSelect(vehicle) }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Column {
                                Text(vehicle.make, color = if (selected) Green else White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text(vehicle.model, color = if (selected) GreenSoft else Muted, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TripsScreen(modifier: Modifier, telemetry: LiveTelemetry) {
    val context = LocalContext.current
    val db = remember { CrediSafeDb(context) }
    var trips by remember { mutableStateOf(emptyList<TripRecord>()) }
    var showExport by remember { mutableStateOf(false) }
    var selectedTrip by remember { mutableStateOf<TripRecord?>(null) }
    var subTab by remember { mutableIntStateOf(0) } // 0: History, 1: Leaderboard

    LaunchedEffect(telemetry.active) {
        trips = db.listTrips()
    }

    Column(modifier.fillMaxSize().background(Night).padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("YOUR JOURNEYS", color = GreenSoft, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Text(if (subTab == 0) "Trip history" else "City Rank", color = White, fontSize = 30.sp, fontWeight = FontWeight.Black)
            }
            OutlinedButton(onClick = { showExport = true }) {
                Icon(Icons.Default.Download, null)
                Spacer(Modifier.width(4.dp))
                Text("Export")
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxWidth().background(Surface1, RoundedCornerShape(12.dp)).padding(4.dp)) {
            val historyWeight by animateFloatAsState(if (subTab == 0) 1.2f else 0.8f)
            val rankWeight by animateFloatAsState(if (subTab == 1) 1.2f else 0.8f)

            TabButton("History", subTab == 0, Modifier.weight(historyWeight)) { subTab = 0 }
            TabButton("Leaderboard", subTab == 1, Modifier.weight(rankWeight)) { subTab = 1 }
        }

        Spacer(Modifier.height(16.dp))

        if (subTab == 0) {
            if (trips.isEmpty()) {
                Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Surface1), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Route, null, tint = Muted, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("No trips yet", color = White, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Start a real journey and its score, XP and reward points will appear here.",
                            color = Muted,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(20.dp))
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(trips, key = { it.id }) { TripRow(it) { selectedTrip = it } }
                    item { Spacer(Modifier.height(20.dp)) }
                }
            }
        } else {
            LeaderboardScreen()
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
private fun TabButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(36.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) Surface3 else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) White else Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LeaderboardScreen() {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Stars, null, tint = Gold, modifier = Modifier.size(30.dp))
            Spacer(Modifier.height(10.dp))
            Text("Leaderboard", color = White, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(6.dp))
            Text(
                "No fabricated rankings. Real city/community rankings will appear only when the server provides verified driver data.",
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun TripDetailDialog(trip: TripRecord, db: CrediSafeDb, onDismiss: () -> Unit) {
    val clipboardManager = LocalClipboardManager.current
    var events by remember { mutableStateOf<List<DrivingEvent>>(emptyList()) }
    LaunchedEffect(trip.id) {
        events = db.listEvents(trip.id)
    }

    val (statusLabel, statusColor) = when {
        trip.status == "REJECTED" -> "INELIGIBLE" to Error
        trip.isAuthoritative && trip.status == "VALIDATED" -> "SERVER CONFIRMED" to Green
        trip.syncStatus == "SYNCING" -> "SYNCING VERIFICATION" to Warning
        trip.syncStatus == "FAILED" -> "SYNC FAILED (RETRYING)" to Warning
        else -> "ESTIMATED PREVIEW" to Gold
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Journey Details", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(statusLabel, color = statusColor, fontSize = 8.sp, fontWeight = FontWeight.Black)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy • HH:mm", Locale.getDefault()) }
                    Text(dateFormat.format(Date(trip.startedAt)), color = Muted, fontSize = 11.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "ID: ${trip.id.take(8)}...",
                        color = Muted,
                        fontSize = 10.sp,
                        modifier = Modifier.clickable {
                            clipboardManager.setText(AnnotatedString(trip.id))
                        }
                    )
                    Icon(Icons.Default.ContentCopy, null, tint = Muted, modifier = Modifier.size(10.dp).padding(start = 2.dp))
                }
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
                    DataLine("Classification", trip.tripClassification)
                    DataLine("Mobility", "${trip.mobilityMode.lowercase().replace('_', ' ')} (${trip.mobilityConfidence}%)")
                    DataLine("Road zone", trip.roadZoneType.replace('_', ' '))
                    DataLine("Road", trip.roadName ?: "Unavailable")
                    DataLine(
                        "Trusted speed limit",
                        trip.roadSpeedLimitKmh?.let { "${it.roundToInt()} km/h" } ?: "Not verified",
                    )
                }
                if (!trip.eligibilityReason.isNull_or_blank_safe()) {
                    Spacer(Modifier.height(12.dp))
                    DiagnosticSection("Eligibility & Decision") {
                        Text(trip.eligibilityReason!!, color = if (trip.status == "REJECTED") Error else Muted, fontSize = 11.sp, lineHeight = 16.sp)
                    }
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

private fun String?.isNull_or_blank_safe(): Boolean = this == null || this.isBlank()

@Composable
private fun RewardsScreen(modifier: Modifier, telemetry: LiveTelemetry) {
    val context = LocalContext.current
    val db = remember { CrediSafeDb(context) }
    var trips by remember { mutableStateOf(emptyList<TripRecord>()) }

    LaunchedEffect(telemetry.active) {
        trips = db.listTrips()
    }

    val points = trips.sumOf { it.rewardPoints ?: 0 }
    val totalXp = trips.sumOf { it.xp ?: 0 }
    val levelInfo = XpEngine.calculateLevelInfo(totalXp)

    Column(
        modifier.fillMaxSize().background(Night).verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("REWARD PROGRESSION", color = GreenSoft, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Text("Earn from real journeys.", color = White, fontSize = 30.sp, fontWeight = FontWeight.Black)

        Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = Surface1), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Available Reward Points", color = Muted, fontSize = 11.sp)
                        Text(points.toString(), color = Green, fontSize = 42.sp, fontWeight = FontWeight.Black)
                    }
                    Icon(Icons.Default.Redeem, null, tint = Green, modifier = Modifier.size(48.dp).alpha(0.2f))
                }
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(6.dp).background(Surface2, CircleShape)) {
                    Box(Modifier.fillMaxWidth(levelInfo.progressPercent).fillMaxHeight().background(Green, CircleShape))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Level ${levelInfo.currentLevel} • ${levelInfo.xpRemaining} XP remaining to unlock Level ${levelInfo.currentLevel + 1}",
                    color = Muted,
                    fontSize = 11.sp,
                )
            }
        }

        Text("PARTNER PERKS & PROGRESSION", color = GreenSoft, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)

        PerkCard("Fuel Partner Credit", "Level progression benefit • Subject to partner verification", "Level 2 Unlock", Icons.Default.DirectionsCar)
        PerkCard("Toll & FASTag Benefit", "Level progression benefit • Subject to partner verification", "Level 3 Unlock", Icons.Default.Route)
        PerkCard("Insurance Mobility Shield", "Level progression benefit • Subject to partner verification", "Level 5 Unlock", Icons.Default.Shield)

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun PerkCard(title: String, desc: String, cost: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Surface1), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(Surface2, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Green, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(desc, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
            }
            Text(cost, color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Black)
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
                Icons.Default.ArrowBack,
                null,
                tint = Green,
                modifier = Modifier.size(20.dp).clickable(onClick = onBack)
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

        DiagnosticSection("GPS, Road & Mobility") {
            DataLine("GPS Quality", "%.2f".format(telemetry.gpsQuality))
            DataLine("Accuracy", "${telemetry.gpsAccuracyM ?: 0.0} m")
            DataLine("Activity", "${telemetry.mobility.activity.name} (${telemetry.mobility.confidence}%)")
            DataLine("Transport Mode", telemetry.transportMode.name)
            DataLine("Road Zone", telemetry.roadContext.zoneType.name)
            DataLine("Road Matched", telemetry.roadContext.roadMatched.toString())
            DataLine("Road Confidence", "%.2f".format(telemetry.roadContext.confidence))
            DataLine("Sensor Samples", telemetry.sensorCount.toString())
            DataLine("Location Samples", telemetry.locationCount.toString())
        }

        DiagnosticSection("Network Connectivity") {
            val context = LocalContext.current
            val apiHost = remember {
                try {
                    java.net.URL(com.credisafe.mobile.BuildConfig.CREDISAFE_API_BASE_URL).host
                } catch (e: Exception) {
                    "invalid-url"
                }
            }
            var backendHealth by remember { mutableStateOf("Checking...") }
            val syncManager = remember { com.credisafe.mobile.data.TripSyncManager(context) }

            LaunchedEffect(Unit) {
                backendHealth = if (syncManager.checkHealth()) "Connected" else "Unavailable"
            }

            DataLine("API Host", apiHost)
            DataLine("Backend Health", backendHealth)
            DataLine("Stream Status", telemetry.streamStatus.name)
        }
        Spacer(Modifier.height(20.dp))
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
                Icons.Default.ArrowBack,
                null,
                tint = Green,
                modifier = Modifier.size(20.dp).clickable(onClick = onBack)
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
                    Text("Your device hardware and settings are fully compatible with CrediSafe telemetry.", color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
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
private fun ProfileScreen(
    modifier: Modifier,
    telemetry: LiveTelemetry,
    auth: AuthManager,
    onDiagnostics: () -> Unit,
    onCompatibility: () -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val db = remember { CrediSafeDb(context) }
    var trips by remember { mutableStateOf(emptyList<TripRecord>()) }
    var vehicles by remember { mutableStateOf(emptyList<Vehicle>()) }
    val compatibility = remember { CompatibilityChecker.check(context) }

    LaunchedEffect(telemetry.active) {
        trips = db.listTrips()
        vehicles = db.listVehicles(auth.getUserId() ?: "")
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

        VehiclesProfileSection(vehicles, auth.getUserId() ?: "", db)

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

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = {
                auth.logout()
                onLogout()
            },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Error),
            border = BorderStroke(1.dp, Surface3),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Default.Logout, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Logout Session", fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("v${BuildConfig.VERSION_NAME} • ${BuildConfig.DISTRIBUTION_CHANNEL.uppercase()}", color = Muted.copy(alpha = 0.45f), fontSize = 9.sp, fontWeight = FontWeight.Bold)
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

        Text("While a journey is active, CrediSafe uses precise location, motion sensors and mobility recognition to understand whether the recording is a driving trip. Tracking continues through the visible foreground-service notification when you leave the app.", color = Muted, fontSize = 15.sp, lineHeight = 22.sp)
        Spacer(Modifier.height(24.dp))
        Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Surface1), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Shield, null, tint = Gold, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("PROMINENT DISCLOSURE", color = Gold, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                }
                Spacer(Modifier.height(10.dp))
                Text("Location, motion sensors and Android activity recognition are used during a journey to detect driving events and filter walking/running/cycling or possible non-driving travel. Telemetry is processed local-first and eligible trips sync after completion.", color = White, fontSize = 13.sp, lineHeight = 19.sp)
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
    val animatedScore by animateFloatAsState(targetValue = score.toFloat(), label = "score_anim")
    val scoreColor = when {
        score >= 90 -> Green
        score >= 70 -> Warning
        score > 0 -> Error
        else -> Muted
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawArc(Border, -90f, 360f, false, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
            if (animatedScore > 0) {
                drawArc(scoreColor, -90f, (animatedScore / 100f) * 360f, false, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
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
    val score = trip.safetyScore ?: 0
    val scoreColor = when {
        score >= 90 -> Green
        score >= 70 -> Warning
        score > 0 -> Error
        else -> Muted
    }

    val infiniteTransition = rememberInfiniteTransition(label = "sync_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_anim"
    )

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy • HH:mm", Locale.getDefault()) }
                    Text(
                        dateFormat.format(Date(trip.startedAt)),
                        color = White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val tripStateColor = when (trip.tripClassification) {
                            "ELIGIBLE" -> GreenSoft
                            "SUSPICIOUS" -> Warning
                            else -> Muted
                        }
                        Text(
                            if (trip.tripClassification == "ELIGIBLE") trip.status else trip.tripClassification,
                            color = tripStateColor,
                            fontSize = 9.sp,
                        )
                        Spacer(Modifier.width(6.dp))
                        val statusText = if (trip.isAuthoritative) "SERVER CONFIRMED" else trip.syncStatus
                        val syncColor = when (trip.syncStatus) {
                            "SYNCED" -> Green
                            "FAILED" -> Error
                            "SYNCING" -> Warning
                            else -> Muted
                        }
                        Text(
                            statusText,
                            color = syncColor,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = if (trip.syncStatus == "SYNCING") Modifier.alpha(alpha) else Modifier
                        )
                    }
                }
                Text(if (score > 0) score.toString() else "—", color = scoreColor, fontSize = 25.sp, fontWeight = FontWeight.Black)
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
private fun VehiclesProfileSection(vehicles: List<Vehicle>, userId: String, db: CrediSafeDb) {
    var showAddDialog by remember { mutableStateOf(false) }
    var currentVehicles by remember(vehicles) { mutableStateOf(vehicles) }

    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Surface1), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("My Vehicles", color = White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = { showAddDialog = true }, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Add, null, tint = Green, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            if (currentVehicles.isEmpty()) {
                Text("No vehicles registered yet.", color = Muted, fontSize = 12.sp)
            } else {
                currentVehicles.forEach { vehicle ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(32.dp).background(Surface2, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.DirectionsCar, null, tint = GreenSoft, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(vehicle.make, color = White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(vehicle.model, color = Muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddVehicleDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { make, model ->
                db.insertVehicle(userId, make, model)
                currentVehicles = db.listVehicles(userId)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun AddVehicleDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var make by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        title = { Text("Add Vehicle", color = White, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = make,
                    onValueChange = { make = it },
                    label = { Text("Make (e.g. Tesla)") },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Green, unfocusedBorderColor = Surface3, focusedTextColor = White, unfocusedTextColor = White)
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model (e.g. Model 3)") },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Green, unfocusedBorderColor = Surface3, focusedTextColor = White, unfocusedTextColor = White)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (make.isNotBlank() && model.isNotBlank()) onAdd(make, model) },
                colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Night),
                enabled = make.isNotBlank() && model.isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Muted) }
        }
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
    val scheme = darkColorScheme(
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
