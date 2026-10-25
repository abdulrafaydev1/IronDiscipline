package com.irondiscipline.app.alarm

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
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
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irondiscipline.app.ui.theme.IronDisciplineTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class AlarmRingingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Turn screen on and show over lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        // Prevent bypass via back button
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Must solve math challenge or tap Dismiss/Snooze
            }
        })

        val alarmId = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID)
        val repository = AlarmRepository.getInstance(applicationContext)
        val alarm = if (alarmId != null) repository.getAlarmById(alarmId) else null

        setContent {
            IronDisciplineTheme {
                AlarmRingingScreen(
                    alarm = alarm,
                    onDismiss = {
                        if (alarmId != null) {
                            repository.handleAlarmDismissed(alarmId)
                        }
                        AlarmRingingService.stopRinging(this)
                        finish()
                    },
                    onSnooze = {
                        if (alarm != null) {
                            AlarmScheduler.getInstance(this).scheduleSnooze(alarm, alarm.snoozeMinutes)
                        }
                        AlarmRingingService.stopRinging(this)
                        finish()
                    }
                )
            }
        }
    }
}

data class MathProblem(val text: String, val answer: Int)

@Composable
fun AlarmRingingScreen(
    alarm: AlarmItem?,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit
) {
    var currentTimeString by remember { mutableStateOf(getFormattedCurrentTime()) }

    LaunchedEffect(Unit) {
        while (true) {
            currentTimeString = getFormattedCurrentTime()
            kotlinx.coroutines.delay(1000L)
        }
    }

    val requiresMath = alarm?.dismissChallenge in listOf("MATH_EASY", "MATH_HARD")
    val isHardMath = alarm?.dismissChallenge == "MATH_HARD"

    // Math Challenge State: must solve 3 problems
    var solvedCount by remember { mutableIntStateOf(0) }
    var currentProblem by remember {
        mutableStateOf(generateMathProblem(isHard = isHardMath))
    }
    var userAnswerText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val challengeCompleted = !requiresMath || (solvedCount >= 3)

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
                fontSize = 46.sp,
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

            // Math Brain Mission Section (if enabled)
            if (requiresMath && !challengeCompleted) {
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
                                text = "WAKE-UP BRAIN MISSION",
                                color = Color(0xFFFFB300),
                                fontWeight = FontWeight.Black,
                                fontSize = 13.sp,
                                letterSpacing = 1.0.sp
                            )
                        }

                        Text(
                            text = "Solve 3 math problems to unlock alarm dismissal ($solvedCount / 3)",
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
                            .padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${currentProblem.text} = ?",
                                color = Color.White,
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // User Answer Input
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
                                onDone = {
                                    val userVal = userAnswerText.toIntOrNull()
                                    if (userVal == currentProblem.answer) {
                                        val nextSolved = solvedCount + 1
                                        solvedCount = nextSolved
                                        userAnswerText = ""
                                        errorMessage = null
                                        if (nextSolved < 3) {
                                            currentProblem = generateMathProblem(isHard = isHardMath)
                                        }
                                    } else {
                                        errorMessage = "Incorrect answer. Try again!"
                                    }
                                }
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
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                val userVal = userAnswerText.toIntOrNull()
                                if (userVal == currentProblem.answer) {
                                    val nextSolved = solvedCount + 1
                                    solvedCount = nextSolved
                                    userAnswerText = ""
                                    errorMessage = null
                                    if (nextSolved < 3) {
                                        currentProblem = generateMathProblem(isHard = isHardMath)
                                    }
                                } else {
                                    errorMessage = "Incorrect answer. Try again!"
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFFB300),
                                contentColor = Color(0xFF06080E)
                            )
                        ) {
                            Text("Submit Answer", fontWeight = FontWeight.Black, fontSize = 13.sp)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            } else if (requiresMath) {
                // Mission Solved Indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF00E676),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "BRAIN MISSION SOLVED • YOU ARE AWAKE",
                        color = Color(0xFF00E676),
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp,
                        letterSpacing = 1.0.sp
                    )
                }
            }

            // Primary Dismiss Button
            Button(
                onClick = onDismiss,
                enabled = challengeCompleted,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF2E54),
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFF331620),
                    disabledContentColor = Color(0xFF884050)
                )
            ) {
                if (challengeCompleted) {
                    Text(
                        text = "DISMISS ALARM",
                        fontWeight = FontWeight.Black,
                        fontSize = 16.sp,
                        letterSpacing = 1.5.sp
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "LOCKED (SOLVE MATH MISSION)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // Snooze Button (if allowed by alarm settings)
            if (alarm?.snoozeEnabled == true) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onSnooze,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color(0xFF334460))
                ) {
                    Text(
                        text = "SNOOZE (${alarm.snoozeMinutes} MINUTES)",
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

private fun getFormattedCurrentTime(): String {
    val sdf = SimpleDateFormat("hh:mm:ss a", Locale.US)
    return sdf.format(Date()).uppercase(Locale.US)
}

private fun generateMathProblem(isHard: Boolean): MathProblem {
    return if (!isHard) {
        val a = Random.nextInt(12, 60)
        val b = Random.nextInt(11, 49)
        val isAdd = Random.nextBoolean()
        if (isAdd) {
            MathProblem("$a + $b", a + b)
        } else {
            val max = maxOf(a, b)
            val min = minOf(a, b)
            MathProblem("$max - $min", max - min)
        }
    } else {
        val a = Random.nextInt(7, 18)
        val b = Random.nextInt(6, 14)
        val c = Random.nextInt(15, 60)
        MathProblem("($a × $b) + $c", (a * b) + c)
    }
}
