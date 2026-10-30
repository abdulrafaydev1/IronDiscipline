package com.irondiscipline.app.alarm

import android.app.Activity
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
import androidx.core.content.ContextCompat
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    private var isFollowUpAlarm = false
    private var followUpCode = ""
    private var isTenSecondCodeVisible = false
    private var preWakeCountdownMs = 0L

    private fun extractFollowUpDetails(intent: Intent?) {
        val prefs = getSharedPreferences("iron_discipline_prefs", Context.MODE_PRIVATE)
        isFollowUpAlarm = intent?.getBooleanExtra("EXTRA_IS_FOLLOWUP_ALARM", false) == true ||
            intent?.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID) == "FOLLOWUP_5MIN_ALARM" ||
            intent?.getStringExtra("EXTRA_ALARM_ID") == "FOLLOWUP_5MIN_ALARM" ||
            prefs.getBoolean("is_current_ring_followup", false)

        followUpCode = intent?.getStringExtra("EXTRA_FOLLOWUP_CODE")
            ?: prefs.getString("followup_alarm_code", "") ?: ""
    }

    private fun ensureAlarmServiceRunning(intent: Intent?) {
        if (!AlarmRingingService.isCurrentlyRinging) {
            val alarmId = intent?.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID)
                ?: intent?.getStringExtra("EXTRA_ALARM_ID")
                ?: activeAlarmId
            val alarmLabel = intent?.getStringExtra(AlarmScheduler.EXTRA_ALARM_LABEL)
                ?: intent?.getStringExtra("EXTRA_ALARM_LABEL")
            val soundIndex = if (intent?.hasExtra(AlarmScheduler.EXTRA_SOUND_INDEX) == true) {
                intent.getIntExtra(AlarmScheduler.EXTRA_SOUND_INDEX, 0)
            } else {
                intent?.getIntExtra("EXTRA_SOUND_INDEX", 0) ?: 0
            }
            val deleteAfter = if (intent?.hasExtra(AlarmScheduler.EXTRA_DELETE_AFTER_RINGING) == true) {
                intent.getBooleanExtra(AlarmScheduler.EXTRA_DELETE_AFTER_RINGING, false)
            } else {
                intent?.getBooleanExtra("EXTRA_DELETE_AFTER_RINGING", false) ?: false
            }

            val serviceIntent = Intent(applicationContext, AlarmRingingService::class.java).apply {
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                putExtra("EXTRA_ALARM_ID", alarmId)
                putExtra(AlarmScheduler.EXTRA_ALARM_LABEL, alarmLabel)
                putExtra("EXTRA_ALARM_LABEL", alarmLabel)
                putExtra(AlarmScheduler.EXTRA_SOUND_INDEX, soundIndex)
                putExtra("EXTRA_SOUND_INDEX", soundIndex)
                putExtra(AlarmScheduler.EXTRA_DELETE_AFTER_RINGING, deleteAfter)
                putExtra("EXTRA_DELETE_AFTER_RINGING", deleteAfter)
                putExtra("EXTRA_IS_NEW_TRIGGER", true)
                putExtra("EXTRA_IS_FOLLOWUP_ALARM", isFollowUpAlarm)
                putExtra("EXTRA_FOLLOWUP_CODE", followUpCode)
            }
            try {
                ContextCompat.startForegroundService(applicationContext, serviceIntent)
                Log.i("IronAlarmTrace", "[IronAlarmTrace:STAGE6_ACTIVITY] ContextCompat.startForegroundService initiated from AlarmRingingActivity for alarmId=$alarmId")
            } catch (e: Exception) {
                Log.e("IronAlarmTrace", "[IronAlarmTrace:STAGE6_ACTIVITY_ERROR] Failed to start AlarmRingingService from Activity: ${e.message}", e)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeActivityRef = java.lang.ref.WeakReference(this)
        isAlarmDismissed = false

        // 1. Acquire WakeLocks immediately so CPU and Screen turn ON without delay
        AlarmWakeLockManager.acquireRingingWakeLocks(applicationContext)

        // 2. Wake screen and display directly over lock screen with FLAG_SECURE
        applyScreenAndLockFlags()

        // 3. Prevent bypass via Back button press
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Back button completely disabled until Math Hard or Follow-Up is solved
            }
        })

        // 4. If Device Owner is active, restrict Power-Off menu via official Lock Task Mode
        PermissionHelper.enableLockTaskForAlarm(this)

        activeAlarmId = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID)
            ?: intent.getStringExtra("EXTRA_ALARM_ID")
        extractFollowUpDetails(intent)
        preWakeCountdownMs = intent.getLongExtra("EXTRA_PRE_WAKE_COUNTDOWN_MS", 0L)

        Log.i("IronAlarmTrace", "[IronAlarmTrace:STAGE6_ACTIVITY] AlarmRingingActivity onCreate for Alarm ID=$activeAlarmId, isFollowUp=$isFollowUpAlarm, code=$followUpCode, preWakeMs=$preWakeCountdownMs")
        ActiveAlarmSessionState.initSession(activeAlarmId)

        // Ensure AlarmRingingService is active and ringing audio at 100% volume
        ensureAlarmServiceRunning(intent)

        val repository = AlarmRepository.getInstance(applicationContext)
        val alarm = if (activeAlarmId != null) repository.getAlarmById(activeAlarmId!!) else null

        setContent {
            IronDisciplineTheme {
                AlarmRingingScreen(
                    alarm = alarm,
                    isFollowUpAlarm = isFollowUpAlarm,
                    savedFollowUpCode = followUpCode,
                    initialPreWakeMs = preWakeCountdownMs,
                    onCodeScreenVisibleChange = { isVisible ->
                        isTenSecondCodeVisible = isVisible
                    },
                    onDismissCompleted = {
                        completeAlarmAndDismiss(activeAlarmId, if (isFollowUpAlarm) "FOLLOWUP_SOLVED" else "MATH_SOLVED")
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        activeActivityRef = java.lang.ref.WeakReference(this)
        AlarmWakeLockManager.acquireRingingWakeLocks(applicationContext)
        applyScreenAndLockFlags()

        // Re-assert Lock Task Mode restriction if Device Owner is active
        PermissionHelper.enableLockTaskForAlarm(this)

        val incomingAlarmId = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID)
            ?: intent.getStringExtra("EXTRA_ALARM_ID")
        val isNewTrigger = intent.getBooleanExtra("EXTRA_IS_NEW_TRIGGER", false)
        extractFollowUpDetails(intent)
        preWakeCountdownMs = intent.getLongExtra("EXTRA_PRE_WAKE_COUNTDOWN_MS", 0L)

        Log.i("IronAlarmTrace", "[IronAlarmTrace:STAGE6_ACTIVITY_RETRIGGER] onNewIntent incomingAlarmId=$incomingAlarmId, activeAlarmId=$activeAlarmId, isNewTrigger=$isNewTrigger, isFollowUp=$isFollowUpAlarm, preWakeMs=$preWakeCountdownMs")

        if (isNewTrigger || incomingAlarmId != activeAlarmId) {
            isAlarmDismissed = false
            activeAlarmId = incomingAlarmId
            ActiveAlarmSessionState.initSession(incomingAlarmId)
        }

        // Ensure ringing service is running
        ensureAlarmServiceRunning(intent)
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
            try {
                val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                km?.requestDismissKeyguard(this, null)
            } catch (_: Exception) {}
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SECURE
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
     * screenshot capture, back navigation, or power button bypass while alarm is ringing
     * or while the 10-second 5-digit code is visible.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val isRinging = AlarmRingingService.isCurrentlyRinging
        val isCodeVisible = isTenSecondCodeVisible
        if ((isRinging || isCodeVisible) && !isAlarmDismissed) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_MUTE -> {
                    if (isRinging) {
                        restoreMaxAlarmVolume()
                    }
                    return true
                }
                KeyEvent.KEYCODE_POWER,
                KeyEvent.KEYCODE_SYSRQ,
                KeyEvent.KEYCODE_BACK -> {
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Intercept hardware volume down and mute keys to maintain 100% alarm volume,
     * and block screenshot combos while code is shown.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val isRinging = AlarmRingingService.isCurrentlyRinging
        val isCodeVisible = isTenSecondCodeVisible
        if ((isRinging || isCodeVisible) && !isAlarmDismissed) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN,
                KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_VOLUME_MUTE -> {
                    if (isRinging) {
                        restoreMaxAlarmVolume()
                    }
                    return true
                }
                KeyEvent.KEYCODE_POWER,
                KeyEvent.KEYCODE_SYSRQ,
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
        AlarmRingingService.stopAlarm(applicationContext, alarmId)

        // 3. Clear follow-up alarm state if this was a follow-up alarm
        if (isFollowUpAlarm || reason.startsWith("FOLLOWUP")) {
            AlarmScheduler.clearFollowUpAlarm(applicationContext)
        }

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
        isTenSecondCodeVisible = false
        super.onDestroy()
    }

    companion object {
        const val TOTAL_MATH_QUESTIONS = 5
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
    isFollowUpAlarm: Boolean,
    savedFollowUpCode: String,
    initialPreWakeMs: Long = 0L,
    onCodeScreenVisibleChange: (Boolean) -> Unit = {},
    onDismissCompleted: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var currentTimeString by remember { mutableStateOf(getFormattedCurrentTime(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            currentTimeString = getFormattedCurrentTime(context)
            kotlinx.coroutines.delay(1000L)
        }
    }

    // Stage 2: 10-Second Code Display State (Initial Alarm Only)
    var showTenSecondCodeScreen by remember { mutableStateOf(false) }
    var generatedMemoryCode by remember { mutableStateOf("") }
    var countdownSeconds by remember { mutableIntStateOf(10) }

    // Stage 3: Follow-Up Dismissal Tabs State (0 = Math, 1 = Code)
    var selectedFollowUpTab by remember { mutableIntStateOf(0) }
    var enteredFollowUpCode by remember { mutableStateOf("") }
    var codeErrorMessage by remember { mutableStateOf<String?>(null) }

    // Read persistent math questions and solved count from ActiveAlarmSessionState
    val solvedCount = ActiveAlarmSessionState.solvedCount
    val currentProblem = ActiveAlarmSessionState.currentProblem ?: ActiveAlarmSessionState.generateHardMathProblem()
    var userAnswerText by remember { mutableStateOf("") }
    var mathErrorMessage by remember { mutableStateOf<String?>(null) }

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

    // 10-Second Countdown & Auto Home Redirect Handler (Stage 2)
    LaunchedEffect(showTenSecondCodeScreen) {
        onCodeScreenVisibleChange(showTenSecondCodeScreen)
        if (showTenSecondCodeScreen) {
            for (sec in 10 downTo 1) {
                countdownSeconds = sec
                kotlinx.coroutines.delay(1000L)
            }
            countdownSeconds = 0

            // Release Lock Task Mode immediately before leaving
            activity?.let { PermissionHelper.disableLockTaskForAlarm(it) }

            // Navigate to phone Home Screen
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(homeIntent)
            activity?.finishAndRemoveTask()
        }
    }

    fun handleMathSolved() {
        if (isFollowUpAlarm) {
            // Stage 3 Option 1: 5 Math Questions solved on Follow-Up Alarm -> Complete Cycle
            AlarmScheduler.clearFollowUpAlarm(context)
            AlarmRingingService.stopAlarm(context)
            onDismissCompleted()
        } else {
            // Stage 1 -> Stage 2: Initial Alarm Math completed:
            // 1. Immediately stop alarm audio and vibration
            AlarmRingingService.stopAlarm(context)

            // 2. Generate random 5-digit numeric code (10000..99999)
            val randomCode = (10000..99999).random().toString()
            generatedMemoryCode = randomCode

            // 3. Immediately schedule 5-Minute Follow-Up Alarm (holding PARTIAL_WAKE_LOCK)
            val soundIdx = alarm?.soundIndex ?: 0
            val labelText = alarm?.label ?: "5-Min Wake-Up Verification"
            AlarmScheduler.scheduleFiveMinuteFollowUpAlarm(context, randomCode, soundIdx, labelText)

            // 4. Transition screen to 10-second memorization view
            showTenSecondCodeScreen = true
        }
    }

    fun evaluateMathAnswer() {
        val userVal = userAnswerText.trim().toIntOrNull()
        val problem = ActiveAlarmSessionState.currentProblem ?: return
        if (userVal == problem.answer) {
            val allDone = ActiveAlarmSessionState.onQuestionSolved()
            userAnswerText = ""
            mathErrorMessage = null
            if (allDone) {
                handleMathSolved()
            }
        } else {
            mathErrorMessage = "❌ Galat Jawab! Dobara solve karein."
            userAnswerText = ""
        }
    }

    fun verifyFollowUpCode() {
        val prefs = context.getSharedPreferences("iron_discipline_prefs", Context.MODE_PRIVATE)
        val expectedCode = savedFollowUpCode.ifBlank {
            prefs.getString("followup_alarm_code", "") ?: ""
        }.trim()

        if (enteredFollowUpCode.trim().isNotEmpty() && enteredFollowUpCode.trim() == expectedCode) {
            codeErrorMessage = null
            AlarmScheduler.clearFollowUpAlarm(context)
            AlarmRingingService.stopAlarm(context)
            onDismissCompleted()
        } else {
            codeErrorMessage = "❌ Galat Code! Sahi 5-digit code dalein ya Math Questions solve karein."
            enteredFollowUpCode = ""
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = if (showTenSecondCodeScreen) {
                        listOf(Color(0xFF030A14), Color(0xFF0A1828))
                    } else {
                        listOf(Color(0xFF06080E), Color(0xFF14080E))
                    }
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
                    .alpha(if (showTenSecondCodeScreen) 1f else pulseAlpha)
                    .background(if (showTenSecondCodeScreen) Color(0x3300E5FF) else Color(0x33FF2E54))
                    .border(2.dp, if (showTenSecondCodeScreen) Color(0xFF00E5FF) else Color(0xFFFF2E54), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (showTenSecondCodeScreen) Icons.Default.Lock else Icons.Default.Alarm,
                    contentDescription = null,
                    tint = if (showTenSecondCodeScreen) Color(0xFF00E5FF) else Color(0xFFFF2E54),
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
                text = if (isFollowUpAlarm) {
                    "5-MIN WAKE-UP FOLLOW-UP"
                } else {
                    alarm?.label ?: "HARD DISCIPLINE ALARM"
                },
                color = if (showTenSecondCodeScreen) Color(0xFF00E5FF) else Color(0xFFFFB300),
                fontSize = 17.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.2.sp
            )

            // Sound Name Badge
            Text(
                text = if (showTenSecondCodeScreen) "STATUS: ALARM MUTED — MEMORIZE CODE" else "SOUND: ${alarm?.soundDisplayName ?: "Hard Synthesizer"}",
                color = Color(0xFF8A99AD),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp)
            )

            // Pre-Wake Status Badge
            if (initialPreWakeMs > 0 && !showTenSecondCodeScreen) {
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x3300E5FF))
                        .border(1.dp, Color(0xFF00E5FF), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "⚡ Waking Screen... Alarm Ringing in 5s",
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (showTenSecondCodeScreen) {
                // =========================================================================
                // STAGE 2: 10-Second 5-Digit Code Memorization View (Obsidian Dark-Luxury Vault)
                // =========================================================================
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(2.dp, Color(0xFF00E5FF), RoundedCornerShape(24.dp)),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0C131D))
                ) {
                    Column(
                        modifier = Modifier.padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Badge: "✅ ALARM STOPPED — MEMORIZE THIS CODE"
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF003822))
                                .border(1.dp, Color(0xFF00E676), RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "✅ ALARM STOPPED — MEMORIZE THIS CODE",
                                color = Color(0xFF00E676),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.8.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = "MEMORIZE THIS 5-DIGIT CODE",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.2.sp,
                            textAlign = TextAlign.Center
                        )

                        Text(
                            text = "Yeh 5-digit code yaad kar lein! 5 minute baad dobara alarm bajega.",
                            color = Color(0xFF8A99AD),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(top = 6.dp, bottom = 18.dp)
                        )

                        // Giant Glowing 5-Digit Code Box (44sp monospace bold Cyan/Emerald text)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xFF040810))
                                .border(1.5.dp, Color(0xFF00E5FF), RoundedCornerShape(16.dp))
                                .padding(vertical = 20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            val spacedCode = generatedMemoryCode.chunked(1).joinToString("   ")
                            Text(
                                text = spacedCode.ifBlank { "0   0   0   0   0" },
                                color = Color(0xFF00E5FF),
                                fontSize = 42.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 2.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // Live Countdown Indicator
                        Text(
                            text = "Closing to Home Screen in ${countdownSeconds}s...",
                            color = Color(0xFFFFB300),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Countdown Progress Bar
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(Color(0xFF1E2838))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fraction = (countdownSeconds / 10f).coerceIn(0f, 1f))
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(0xFFFFB300))
                            )
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        Button(
                            onClick = {
                                activity?.let { PermissionHelper.disableLockTaskForAlarm(it) }
                                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                                    addCategory(Intent.CATEGORY_HOME)
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(homeIntent)
                                activity?.finishAndRemoveTask()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .jellyElastic(baseCornerRadius = 12.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF00E5FF),
                                contentColor = Color(0xFF040810)
                            )
                        ) {
                            Text(
                                text = "CLOSE TO HOME SCREEN NOW (${countdownSeconds}s)",
                                fontWeight = FontWeight.Black,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            } else {
                // =========================================================================
                // ACTIVE RINGING CHALLENGE VIEW
                // Initial Alarm -> 5 Math Questions
                // Follow-Up Alarm -> 2 Dismissal Options (Math OR 5-Digit Code)
                // =========================================================================

                if (isFollowUpAlarm) {
                    // Header badge for follow-up
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF33141E))
                            .border(1.dp, Color(0xFFFF2E54), RoundedCornerShape(10.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "🚨 5-MINUTE FOLLOW-UP VERIFICATION",
                            color = Color(0xFFFF4D6D),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 2-Option Mode Switcher Tabs (Stage 3)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Option 1 Tab: "🧮 Solve 5 Math Questions"
                        Button(
                            onClick = { selectedFollowUpTab = 0 },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .jellyElastic(baseCornerRadius = 10.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selectedFollowUpTab == 0) Color(0xFFFFB300) else Color(0xFF141923),
                                contentColor = if (selectedFollowUpTab == 0) Color(0xFF06080E) else Color(0xFF8A99AD)
                            ),
                            border = if (selectedFollowUpTab == 0) null else BorderStroke(1.dp, Color(0xFF26334D))
                        ) {
                            Text(
                                text = "🧮 Math (5)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black
                            )
                        }

                        // Option 2 Tab: "🔑 Enter 5-Digit Code"
                        Button(
                            onClick = { selectedFollowUpTab = 1 },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .jellyElastic(baseCornerRadius = 10.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selectedFollowUpTab == 1) Color(0xFF00E5FF) else Color(0xFF141923),
                                contentColor = if (selectedFollowUpTab == 1) Color(0xFF06080E) else Color(0xFF8A99AD)
                            ),
                            border = if (selectedFollowUpTab == 1) null else BorderStroke(1.dp, Color(0xFF26334D))
                        ) {
                            Text(
                                text = "🔑 5-Digit Code",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                }

                if (isFollowUpAlarm && selectedFollowUpTab == 1) {
                    // Option 2: Enter 5-Digit Code Challenge Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.5.dp, Color(0xFF00E5FF), RoundedCornerShape(20.dp)),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF141923))
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = Color(0xFF00E5FF),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "ENTER 5-DIGIT MEMORY CODE",
                                    color = Color(0xFF00E5FF),
                                    fontWeight = FontWeight.Black,
                                    fontSize = 13.sp,
                                    letterSpacing = 1.0.sp
                                )
                            }

                            Text(
                                text = "Enter the 5-digit code you memorized 5 minutes ago to stop the alarm.",
                                color = Color(0xFF8A99AD),
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
                            )

                            // 5-Digit Code Input Field
                            OutlinedTextField(
                                value = enteredFollowUpCode,
                                onValueChange = {
                                    if (it.length <= 5 && it.all { ch -> ch.isDigit() }) {
                                        enteredFollowUpCode = it
                                        codeErrorMessage = null
                                    }
                                },
                                placeholder = { Text("5-Digit Code", color = Color(0xFF5E6D82)) },
                                singleLine = true,
                                textStyle = TextStyle(
                                    color = Color(0xFF00E5FF),
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Black,
                                    fontFamily = FontFamily.Monospace,
                                    textAlign = TextAlign.Center,
                                    letterSpacing = 6.sp
                                ),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = { verifyFollowUpCode() }
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFF00E5FF),
                                    unfocusedBorderColor = Color(0xFF26334D)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            if (codeErrorMessage != null) {
                                Text(
                                    text = codeErrorMessage!!,
                                    color = Color(0xFFFF2E54),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Button(
                                onClick = { verifyFollowUpCode() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .jellyElastic(baseCornerRadius = 12.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF00E5FF),
                                    contentColor = Color(0xFF06080E)
                                )
                            ) {
                                Text(
                                    text = "🔓 UNLOCK & STOP ALARM",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                } else {
                    // Option 1 / Initial Alarm: 5 Math Hard Questions Card
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
                                    text = if (isFollowUpAlarm) "OPTION 1: MATH HARD MISSION" else "MANDATORY MATH HARD MISSION",
                                    color = Color(0xFFFFB300),
                                    fontWeight = FontWeight.Black,
                                    fontSize = 13.sp,
                                    letterSpacing = 1.0.sp
                                )
                            }

                            Text(
                                text = "Solve 5 Math Hard Questions to Stop Alarm",
                                color = Color(0xFFFFB300),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 4.dp)
                            )

                            val currentQuestion = (solvedCount + 1).coerceAtMost(AlarmRingingActivity.TOTAL_MATH_QUESTIONS)
                            Text(
                                text = "Question $currentQuestion of ${AlarmRingingActivity.TOTAL_MATH_QUESTIONS} • $solvedCount / ${AlarmRingingActivity.TOTAL_MATH_QUESTIONS} Solved",
                                color = Color(0xFF8A99AD),
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
                            )

                            // 5-Step Progress Indicator Dots / Bars: loop (1..5)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                for (step in 1..AlarmRingingActivity.TOTAL_MATH_QUESTIONS) {
                                    val isCompleted = step <= solvedCount
                                    val isCurrent = step == solvedCount + 1
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(
                                                when {
                                                    isCompleted -> Color(0xFF00E676)
                                                    isCurrent -> Color(0xFFFFB300)
                                                    else -> Color(0xFF26334D)
                                                }
                                            )
                                    )
                                }
                            }

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
                                    mathErrorMessage = null
                                },
                                placeholder = { Text("Enter answer", color = Color(0xFF5E6D82)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = { evaluateMathAnswer() }
                                ),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFFFFB300),
                                    unfocusedBorderColor = Color(0xFF26334D)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )

                            if (mathErrorMessage != null) {
                                Text(
                                    text = mathErrorMessage!!,
                                    color = Color(0xFFFF2E54),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            Button(
                                onClick = { evaluateMathAnswer() },
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
                                    text = if (solvedCount == AlarmRingingActivity.TOTAL_MATH_QUESTIONS - 1) {
                                        if (isFollowUpAlarm) "SOLVE & STOP FOLLOW-UP ALARM (5/5)" else "SOLVE & REVEAL CODE (5/5)"
                                    } else {
                                        "SUBMIT ANSWER (${solvedCount + 1}/${AlarmRingingActivity.TOTAL_MATH_QUESTIONS})"
                                    },
                                    fontWeight = FontWeight.Black,
                                    fontSize = 13.sp
                                )
                            }
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
                            text = if (isFollowUpAlarm) {
                                "DISMISS LOCKED: SOLVE MATH OR ENTER 5-DIGIT CODE"
                            } else {
                                "STANDARD DISMISS LOCKED (SOLVE ALL 5 EQUATIONS)"
                            },
                            color = Color(0xFFFF7B93),
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            letterSpacing = 0.5.sp
                        )
                    }
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
