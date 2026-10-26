package com.irondiscipline.app.alarm

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import com.irondiscipline.app.util.PermissionHelper
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irondiscipline.app.BuildConfig
import com.irondiscipline.app.ui.components.jellyElastic
import com.irondiscipline.app.ui.theme.IronDisciplineTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen Alarm Ringing Activity enforcing strict Hard Math Challenge dismissal.
 * Renders directly over the lock screen with screen wake locks, persistent questions state across
 * screen wake/lock cycles, hardware volume suppression, and official lock-screen unlock handoff.
 */
class AlarmRingingActivity : ComponentActivity() {

    private var activeAlarmId: String? = null
    private var isAlarmDismissed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeActivityRef = java.lang.ref.WeakReference(this)
        isAlarmDismissed = false

        // 1. Wake screen and display directly over lock screen
        applyScreenAndLockFlags()

        // 2. Prevent bypass via Back button press
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Back button completely disabled until Math Hard is solved
            }
        })

        // 3. If Device Owner is active, restrict Power-Off menu via official Lock Task Mode
        PermissionHelper.enableLockTaskForAlarm(this)

        activeAlarmId = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID)
        Log.i("IronAlarmTrace", "[IronAlarmTrace:STAGE6_ACTIVITY] AlarmRingingActivity onCreate for Alarm ID=$activeAlarmId")
        ActiveAlarmSessionState.initSession(activeAlarmId)

        val repository = AlarmRepository.getInstance(applicationContext)
        val alarm = if (activeAlarmId != null) repository.getAlarmById(activeAlarmId!!) else null

        setContent {
            IronDisciplineTheme {
                AlarmRingingScreen(
                    alarm = alarm,
                    onDismissCompleted = {
                        completeAlarmAndDismiss(activeAlarmId, "MATH_SOLVED")
                    },
                    onDevTestDismiss = {
                        completeAlarmAndDismiss(activeAlarmId, "TEST_DISMISS")
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        activeActivityRef = java.lang.ref.WeakReference(this)
        applyScreenAndLockFlags()

        // Re-assert Lock Task Mode restriction if Device Owner is active
        PermissionHelper.enableLockTaskForAlarm(this)

        val incomingAlarmId = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID)
        val isNewTrigger = intent.getBooleanExtra("EXTRA_IS_NEW_TRIGGER", false)
        Log.i("IronAlarmTrace", "[IronAlarmTrace:STAGE6_ACTIVITY_RETRIGGER] onNewIntent incomingAlarmId=$incomingAlarmId, activeAlarmId=$activeAlarmId, isNewTrigger=$isNewTrigger")

        if (isNewTrigger || incomingAlarmId != activeAlarmId) {
            isAlarmDismissed = false
            activeAlarmId = incomingAlarmId
            ActiveAlarmSessionState.initSession(incomingAlarmId)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isAlarmDismissed && AlarmRingingService.isCurrentlyRinging) {
            PermissionHelper.enableLockTaskForAlarm(this)
        }
    }

    override fun onStop() {
        super.onStop()
        // If the alarm is still actively ringing and was pushed to background by phone unlock / home transition:
        if (!isAlarmDismissed && !isFinishing && AlarmRingingService.isCurrentlyRinging) {
            AlarmRingingService.bringAlarmScreenToFront(applicationContext)
        }
    }

    private fun applyScreenAndLockFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && !isAlarmDismissed && AlarmRingingService.isCurrentlyRinging) {
            // 1. Request closing any transient system dialog overlays
            try {
                @Suppress("DEPRECATION")
                sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
            } catch (_: Exception) {}

            // 2. Re-assert alarm window focus and keep screen awake
            AlarmWakeLockManager.acquireRingingWakeLocks(applicationContext)
            window.decorView.postDelayed({
                if (!isAlarmDismissed && AlarmRingingService.isCurrentlyRinging) {
                    AlarmRingingService.bringAlarmScreenToFront(applicationContext)
                }
            }, 80L)
        }
    }

    /**
     * Intercept physical key presses in dispatchKeyEvent to prevent volume muting,
     * back navigation, or power button bypass while alarm is ringing.
     */
    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        val keyCode = event?.keyCode
        if (AlarmRingingService.isCurrentlyRinging && !isAlarmDismissed) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_MUTE -> {
                    restoreMaxAlarmVolume()
                    return true
                }
                KeyEvent.KEYCODE_POWER,
                KeyEvent.KEYCODE_BACK -> {
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Intercept hardware volume down and mute keys to maintain 100% alarm volume.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (AlarmRingingService.isCurrentlyRinging && !isAlarmDismissed) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_MUTE -> {
                    restoreMaxAlarmVolume()
                    return true
                }
                KeyEvent.KEYCODE_POWER,
                KeyEvent.KEYCODE_BACK -> {
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun restoreMaxAlarmVolume() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.let {
                val maxVol = it.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                it.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
            }
        } catch (_: Exception) {}
    }

    /**
     * Terminate alarm ringing service, update repository, release wake locks,
     * and trigger official Android lock-screen unlock flow.
     */
    private fun completeAlarmAndDismiss(alarmId: String?, reason: String = "MATH_SOLVED") {
        Log.d("AlarmDiag", "[DISMISS_CLEANUP] Reason=$reason, alarmId=$alarmId")
        isAlarmDismissed = true

        // 1. Release Lock Task Mode immediately to restore standard power-off menu and navigation
        PermissionHelper.disableLockTaskForAlarm(this)

        // 2. Stop audio, vibration, and background foreground service
        AlarmRingingService.stopAndCleanup(applicationContext, alarmId)

        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && keyguardManager != null && keyguardManager.isKeyguardLocked) {
            keyguardManager.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    super.onDismissSucceeded()
                    finishAndRemoveTask()
                }

                override fun onDismissError() {
                    super.onDismissError()
                    finishAndRemoveTask()
                }

                override fun onDismissCancelled() {
                    super.onDismissCancelled()
                    finishAndRemoveTask()
                }
            })
        } else {
            finishAndRemoveTask()
        }
    }

    override fun onDestroy() {
        // Failsafe: Ensure Lock Task Mode is released so the device is never left restricted
        PermissionHelper.disableLockTaskForAlarm(this)
        if (activeActivityRef?.get() === this) {
            activeActivityRef = null
        }
        super.onDestroy()
    }

    companion object {
        private var activeActivityRef: java.lang.ref.WeakReference<AlarmRingingActivity>? = null

        fun releaseLockTaskIfActive() {
            activeActivityRef?.get()?.let { act ->
                try {
                    PermissionHelper.disableLockTaskForAlarm(act)
                } catch (_: Exception) {}
            }
        }
    }
}

@Composable
fun AlarmRingingScreen(
    alarm: AlarmItem?,
    onDismissCompleted: () -> Unit,
    onDevTestDismiss: () -> Unit
) {
    val context = LocalContext.current
    var currentTimeString by remember { mutableStateOf(getFormattedCurrentTime(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            currentTimeString = getFormattedCurrentTime(context)
            kotlinx.coroutines.delay(1000L)
        }
    }

    // Dev test dismiss condition
    val isTestOrDebugMode = remember {
        val prefs = context.getSharedPreferences("iron_discipline_prefs", Context.MODE_PRIVATE)
        BuildConfig.DEBUG ||
            prefs.getBoolean("is_test_mode", false) ||
            prefs.getBoolean("enable_dev_test_dismiss", true)
    }

    // Read persistent math questions and solved count from ActiveAlarmSessionState
    val solvedCount = ActiveAlarmSessionState.solvedCount
    val currentProblem = ActiveAlarmSessionState.currentProblem ?: ActiveAlarmSessionState.generateHardMathProblem()
    var userAnswerText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Pulsing animation for warning ring
    val infiniteTransition = rememberInfiniteTransition(label = "AlarmRingingPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseAlpha"
    )

    val scrollState = rememberScrollState()

    fun evaluateAnswer() {
        val userVal = userAnswerText.trim().toIntOrNull()
        val problem = ActiveAlarmSessionState.currentProblem ?: return
        if (userVal == problem.answer) {
            val allDone = ActiveAlarmSessionState.onQuestionSolved()
            userAnswerText = ""
            errorMessage = null
            if (allDone) {
                // Completed all 3 equations -> Dismiss immediately & trigger unlock
                onDismissCompleted()
            }
        } else {
            errorMessage = "❌ Galat Jawab! Dobara solve karein."
            userAnswerText = ""
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF06080E), Color(0xFF14080E))
                )
            )
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Pulsing Alarm Icon
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .alpha(pulseAlpha)
                    .background(Color(0x33FF2E54))
                    .border(2.dp, Color(0xFFFF2E54), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Alarm,
                    contentDescription = null,
                    tint = Color(0xFFFF2E54),
                    modifier = Modifier.size(42.dp)
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Time Readout
            Text(
                text = currentTimeString,
                color = Color.White,
                fontSize = 44.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp
            )

            // Alarm Title
            Text(
                text = alarm?.label ?: "HARD DISCIPLINE ALARM",
                color = Color(0xFFFFB300),
                fontSize = 17.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.2.sp
            )

            // Sound Name Badge
            Text(
                text = "SOUND: ${alarm?.soundDisplayName ?: "Hard Synthesizer"}",
                color = Color(0xFF8A99AD),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Math Hard Brain Mission Card (Mandatory Dismiss Option)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.5.dp, Color(0xFFFFB300), RoundedCornerShape(20.dp)),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141923))
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Psychology,
                            contentDescription = null,
                            tint = Color(0xFFFFB300),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "MANDATORY MATH HARD MISSION",
                            color = Color(0xFFFFB300),
                            fontWeight = FontWeight.Black,
                            fontSize = 13.sp,
                            letterSpacing = 1.0.sp
                        )
                    }

                    Text(
                        text = "Solve 3 multi-digit equations to unlock dismissal (${solvedCount + 1} of 3)",
                        color = Color(0xFF8A99AD),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )

                    // Math Question Box
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF090D15))
                            .border(1.dp, Color(0xFF26334D), RoundedCornerShape(12.dp))
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${currentProblem.text} = ?",
                            color = Color.White,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // User Answer Input Field
                    OutlinedTextField(
                        value = userAnswerText,
                        onValueChange = {
                            userAnswerText = it
                            errorMessage = null
                        },
                        placeholder = { Text("Enter answer", color = Color(0xFF5E6D82)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { evaluateAnswer() }
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFFFB300),
                            unfocusedBorderColor = Color(0xFF26334D)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (errorMessage != null) {
                        Text(
                            text = errorMessage!!,
                            color = Color(0xFFFF2E54),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = { evaluateAnswer() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .jellyElastic(baseCornerRadius = 12.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFFB300),
                            contentColor = Color(0xFF06080E)
                        )
                    ) {
                        Text(
                            text = if (solvedCount == 2) "SOLVE & DISMISS ALARM" else "SUBMIT ANSWER (${solvedCount + 1}/3)",
                            fontWeight = FontWeight.Black,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Locked Dismiss Indicator
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF1E1417))
                    .border(1.dp, Color(0xFF4A1A24), RoundedCornerShape(14.dp))
                    .padding(vertical = 12.dp, horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = Color(0xFFFF2E54),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "STANDARD DISMISS LOCKED (SOLVE ALL 3 EQUATIONS)",
                        color = Color(0xFFFF7B93),
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 0.5.sp
                    )
                }
            }

            // Developer / Debug Test Dismiss Button
            if (isTestOrDebugMode) {
                Spacer(modifier = Modifier.height(14.dp))
                OutlinedButton(
                    onClick = onDevTestDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .jellyElastic(baseCornerRadius = 14.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color(0xFFFF9100)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF9100))
                ) {
                    Text(
                        text = "🛠️ TEST DISMISS (Stop Alarm Immediately)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

private fun getFormattedCurrentTime(context: Context): String {
    val is24 = android.text.format.DateFormat.is24HourFormat(context)
    val pattern = if (is24) "HH:mm:ss" else "hh:mm:ss a"
    val sdf = SimpleDateFormat(pattern, Locale.US)
    return sdf.format(Date()).uppercase(Locale.US)
}
