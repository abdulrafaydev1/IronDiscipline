package com.irondiscipline.app.ui.overlay

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irondiscipline.app.domain.model.BlockEvaluationResult
import com.irondiscipline.app.domain.model.BlockReasonType
import com.irondiscipline.app.ui.theme.DarkBg
import com.irondiscipline.app.ui.theme.DarkCard
import com.irondiscipline.app.ui.theme.DarkSurface
import com.irondiscipline.app.ui.theme.NeonAmber
import com.irondiscipline.app.ui.theme.NeonCyan
import com.irondiscipline.app.ui.theme.NeonCrimson
import com.irondiscipline.app.ui.theme.NeonLime
import com.irondiscipline.app.ui.theme.TextMuted
import com.irondiscipline.app.ui.theme.TextPrimary
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.random.Random

private const val REQUIRED_PLEDGE_TEXT =
    "I am consciously pausing my discipline for an urgent task and will return to work in 5 minutes."

private const val COOLDOWN_TOTAL_SECONDS = 60

data class ArithmeticChallenge(
    val a: Int,
    val b: Int,
    val c: Int,
    val isAddition: Boolean
) {
    val expressionText: String
        get() = if (isAddition) "$a * $b + $c" else "$a * $b - $c"

    val expectedAnswer: Int
        get() = if (isAddition) (a * b) + c else (a * b) - c

    companion object {
        fun generateThree(): List<ArithmeticChallenge> {
            return List(3) {
                val a = Random.nextInt(13, 69)
                val b = Random.nextInt(11, 29)
                val c = Random.nextInt(14, 95)
                val isAdd = Random.nextBoolean()
                ArithmeticChallenge(a = a, b = b, c = c, isAddition = isAdd)
            }
        }
    }
}

@Composable
fun BlockOverlayView(
    evaluationResult: BlockEvaluationResult,
    onBackToWorkClicked: () -> Unit,
    onEmergencyUnlockCompleted: (String) -> Unit
) {
    // Consume hardware back button presses so overlay cannot be dismissed via Back
    BackHandler(enabled = true) {
        // Intentionally blocked: user must choose "Back to Work"
    }

    var isFrictionFlowActive by remember(evaluationResult.targetKey) { mutableStateOf(false) }
    var cooldownSecondsLeft by remember(evaluationResult.targetKey) {
        mutableIntStateOf(COOLDOWN_TOTAL_SECONDS)
    }

    var equations by remember(evaluationResult.targetKey) {
        mutableStateOf(ArithmeticChallenge.generateThree())
    }
    var answer1 by remember(evaluationResult.targetKey) { mutableStateOf("") }
    var answer2 by remember(evaluationResult.targetKey) { mutableStateOf("") }
    var answer3 by remember(evaluationResult.targetKey) { mutableStateOf("") }
    var mathErrorText by remember(evaluationResult.targetKey) { mutableStateOf<String?>(null) }
    var mathSolved by remember(evaluationResult.targetKey) { mutableStateOf(false) }

    var pledgeInput by remember(evaluationResult.targetKey) { mutableStateOf("") }
    var pledgeWarning by remember(evaluationResult.targetKey) { mutableStateOf<String?>(null) }

    // 60-Second Cooldown Timer when Friction Flow is active
    LaunchedEffect(isFrictionFlowActive, cooldownSecondsLeft) {
        if (isFrictionFlowActive && cooldownSecondsLeft > 0) {
            delay(1000L)
            cooldownSecondsLeft -= 1
        }
    }

    val deepWorkHoursFormatted = remember(evaluationResult.todayDeepWorkMinutes) {
        val hours = evaluationResult.todayDeepWorkMinutes / 60.0
        String.format(Locale.US, "%.1f hrs (%d mins)", hours, evaluationResult.todayDeepWorkMinutes)
    }

    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF06080D),
                        Color(0xFF0C121E),
                        Color(0xFF06080D)
                    )
                )
            )
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Shield Icon Header
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .background(NeonCrimson.copy(alpha = 0.14f))
                    .border(2.dp, NeonCrimson, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (evaluationResult.reasonType == BlockReasonType.STRICT_MODE_SETTINGS_GUARD) {
                        Icons.Default.Lock
                    } else {
                        Icons.Default.Shield
                    },
                    contentDescription = "Blocked Shield",
                    tint = NeonCrimson,
                    modifier = Modifier.size(44.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Status Badge
            Text(
                text = "IRON DISCIPLINE INTERCEPTION",
                color = NeonCrimson,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 2.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = evaluationResult.displayTitle.ifBlank { "Restricted Distraction" },
                color = TextPrimary,
                fontSize = 26.dp.value.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = evaluationResult.reasonDetail,
                color = NeonAmber,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(NeonAmber.copy(alpha = 0.12f))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Bold Customizable Quote Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, NeonCyan.copy(alpha = 0.45f), RoundedCornerShape(18.dp)),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = DarkCard)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "\"${evaluationResult.motivationalQuote}\"",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontStyle = FontStyle.Italic,
                        textAlign = TextAlign.Center,
                        lineHeight = 26.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Discipline Streak & Deep Work Stats Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, NeonLime.copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocalFireDepartment,
                            contentDescription = null,
                            tint = NeonLime,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${evaluationResult.currentStreakDays} DAYS",
                            color = NeonLime,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "Discipline Streak",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }

                Card(
                    modifier = Modifier
                        .weight(1f)
                        .border(1.dp, NeonCyan.copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkSurface)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = NeonCyan,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = deepWorkHoursFormatted,
                            color = NeonCyan,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Today's Deep Work",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Primary Action: Back to Work
            Button(
                onClick = onBackToWorkClicked,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonCyan,
                    contentColor = DarkBg
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "BACK TO WORK",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
            }

            // Secondary Action: Emergency Unlock (5 Mins) + Hard Friction Challenge
            if (evaluationResult.canUseEmergencyOverride && !isFrictionFlowActive) {
                Spacer(modifier = Modifier.height(14.dp))
                OutlinedButton(
                    onClick = {
                        isFrictionFlowActive = true
                        cooldownSecondsLeft = COOLDOWN_TOTAL_SECONDS
                        equations = ArithmeticChallenge.generateThree()
                        answer1 = ""
                        answer2 = ""
                        answer3 = ""
                        mathSolved = false
                        pledgeInput = ""
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMuted)
                ) {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = null,
                        tint = NeonAmber,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Emergency Unlock (5 Mins) • Max 1/Day",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Hard Friction Challenge Panel
            AnimatedVisibility(
                visible = isFrictionFlowActive,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp)
                        .border(1.dp, NeonAmber.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = DarkCard)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp)
                    ) {
                        Text(
                            text = "HARD FRICTION EMERGENCY CHALLENGE",
                            color = NeonAmber,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Complete all 3 verification steps to unlock ${evaluationResult.displayTitle} for 5 minutes (1/1 daily override).",
                            color = TextMuted,
                            fontSize = 12.sp
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // STEP 1: Mandatory 60-Second Cooldown
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = if (cooldownSecondsLeft == 0) {
                                    Icons.Default.CheckCircle
                                } else {
                                    Icons.Default.Timer
                                },
                                contentDescription = null,
                                tint = if (cooldownSecondsLeft == 0) NeonLime else NeonAmber,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (cooldownSecondsLeft > 0) {
                                    "Step 1: Mandatory Cooldown (${cooldownSecondsLeft}s remaining)"
                                } else {
                                    "Step 1: 60s Cooldown Completed"
                                },
                                color = if (cooldownSecondsLeft == 0) NeonLime else TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        LinearProgressIndicator(
                            progress = {
                                (COOLDOWN_TOTAL_SECONDS - cooldownSecondsLeft).toFloat() / COOLDOWN_TOTAL_SECONDS.toFloat()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = if (cooldownSecondsLeft == 0) NeonLime else NeonAmber,
                            trackColor = DarkSurface
                        )

                        // STEP 2: 3 Multi-Digit Arithmetic Equations (unlocked after 60s cooldown)
                        if (cooldownSecondsLeft == 0) {
                            Spacer(modifier = Modifier.height(20.dp))
                            Text(
                                text = if (mathSolved) {
                                    "Step 2: 3 Arithmetic Equations Solved ✓"
                                } else {
                                    "Step 2: Solve All 3 Arithmetic Equations"
                                },
                                color = if (mathSolved) NeonLime else TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            if (!mathSolved) {
                                EquationInputRow(
                                    index = 1,
                                    expression = equations[0].expressionText,
                                    value = answer1,
                                    onValueChange = { answer1 = it }
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                EquationInputRow(
                                    index = 2,
                                    expression = equations[1].expressionText,
                                    value = answer2,
                                    onValueChange = { answer2 = it }
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                EquationInputRow(
                                    index = 3,
                                    expression = equations[2].expressionText,
                                    value = answer3,
                                    onValueChange = { answer3 = it }
                                )

                                if (mathErrorText != null) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = mathErrorText!!,
                                        color = NeonCrimson,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = {
                                        val a1 = answer1.trim().toIntOrNull()
                                        val a2 = answer2.trim().toIntOrNull()
                                        val a3 = answer3.trim().toIntOrNull()
                                        if (a1 == equations[0].expectedAnswer &&
                                            a2 == equations[1].expectedAnswer &&
                                            a3 == equations[2].expectedAnswer
                                        ) {
                                            mathSolved = true
                                            mathErrorText = null
                                        } else {
                                            mathErrorText =
                                                "Incorrect answer(s). Equations regenerated—calculate carefully."
                                            equations = ArithmeticChallenge.generateThree()
                                            answer1 = ""
                                            answer2 = ""
                                            answer3 = ""
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = NeonAmber,
                                        contentColor = DarkBg
                                    )
                                ) {
                                    Text(
                                        text = "Verify Equations",
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        // STEP 3: Exact Pledge Typing (unlocked after Step 1 & Step 2)
                        if (cooldownSecondsLeft == 0 && mathSolved) {
                            Spacer(modifier = Modifier.height(20.dp))
                            Text(
                                text = "Step 3: Type the Exact Discipline Pledge",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = REQUIRED_PLEDGE_TEXT,
                                color = NeonCyan,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(DarkSurface)
                                    .padding(10.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = pledgeInput,
                                onValueChange = { newText ->
                                    // Anti-paste protection: reject sudden jumps > 2 characters
                                    if (newText.length - pledgeInput.length > 2) {
                                        pledgeWarning =
                                            "Copy-pasting is disabled. Type each character manually."
                                    } else {
                                        pledgeWarning = null
                                        pledgeInput = newText
                                    }
                                },
                                label = {
                                    Text(
                                        "Type pledge (${pledgeInput.length}/${REQUIRED_PLEDGE_TEXT.length} chars)"
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = NeonCyan,
                                    unfocusedBorderColor = TextMuted,
                                    focusedTextColor = TextPrimary,
                                    unfocusedTextColor = TextPrimary
                                )
                            )

                            if (pledgeWarning != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = pledgeWarning!!,
                                    color = NeonCrimson,
                                    fontSize = 12.sp
                                )
                            }

                            val isPledgeExactMatch = pledgeInput == REQUIRED_PLEDGE_TEXT

                            Spacer(modifier = Modifier.height(14.dp))
                            Button(
                                onClick = {
                                    if (isPledgeExactMatch) {
                                        onEmergencyUnlockCompleted(evaluationResult.targetKey)
                                    }
                                },
                                enabled = isPledgeExactMatch,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = NeonCrimson,
                                    contentColor = TextPrimary,
                                    disabledContainerColor = DarkSurface,
                                    disabledContentColor = TextMuted
                                )
                            ) {
                                Text(
                                    text = "CONFIRM 5-MINUTE EMERGENCY UNLOCK",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EquationInputRow(
    index: Int,
    expression: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "#$index:  $expression =",
            color = NeonCyan,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = value,
            onValueChange = { input ->
                if (input.length <= 7 && input.all { it.isDigit() || it == '-' }) {
                    onValueChange(input)
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            placeholder = { Text("?") },
            modifier = Modifier.width(120.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NeonAmber,
                unfocusedBorderColor = TextMuted,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            )
        )
    }
}
