package com.irondiscipline.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irondiscipline.app.ui.components.jellyElastic

private val ColorObsidianDeep = Color(0xFF06080E)
private val ColorObsidianSlate = Color(0xFF0D121D)
private val ColorElevatedSlate = Color(0xFF141B29)
private val ColorCyanGlow = Color(0xFF38BDF8)
private val ColorCrimsonNeon = Color(0xFFFF2E54)
private val ColorTextPrimary = Color(0xFFFFFFFF)
private val ColorTextSecondary = Color(0xFF8A99AD)

@Composable
fun AppPasswordGateScreen(
    onUnlockSuccess: () -> Unit,
    onVerifyPassword: (String) -> Boolean,
    onExitToHome: () -> Unit
) {
    // Intercept hardware and gesture back presses: exit to Home, never bypass gate
    BackHandler {
        onExitToHome()
    }

    var passwordInput by remember { mutableStateOf("") }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var isErrorVisible by remember { mutableStateOf(false) }

    fun attemptUnlock() {
        if (onVerifyPassword(passwordInput)) {
            isErrorVisible = false
            onUnlockSuccess()
        } else {
            isErrorVisible = true
            passwordInput = ""
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(ColorObsidianDeep, ColorObsidianSlate)
                )
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 2.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(ColorCyanGlow, Color(0xFF0284C7))
                    ),
                    shape = RoundedCornerShape(24.dp)
                ),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = ColorElevatedSlate)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Glowing Shield Lock Badge
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E293B))
                        .border(1.5.dp, ColorCyanGlow, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Security Shield Lock",
                        tint = ColorCyanGlow,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "IRON DISCIPLINE LOCKED",
                    color = ColorTextPrimary,
                    fontWeight = FontWeight.Black,
                    fontSize = 20.sp,
                    letterSpacing = 1.2.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Apna Master Password enter karein app open karne ke liye",
                    color = ColorTextSecondary,
                    fontWeight = FontWeight.Normal,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )

                Spacer(modifier = Modifier.height(24.dp))

                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = {
                        passwordInput = it
                        if (isErrorVisible) isErrorVisible = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    placeholder = {
                        Text(
                            text = "Enter Master Password",
                            color = Color(0xFF64748B),
                            fontSize = 14.sp
                        )
                    },
                    singleLine = true,
                    visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { attemptUnlock() }
                    ),
                    trailingIcon = {
                        IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                            Icon(
                                imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (isPasswordVisible) "Hide password" else "Show password",
                                tint = ColorTextSecondary
                            )
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF0B0F17),
                        unfocusedContainerColor = Color(0xFF0B0F17),
                        focusedBorderColor = ColorCyanGlow,
                        unfocusedBorderColor = Color(0xFF26334D),
                        focusedTextColor = ColorTextPrimary,
                        unfocusedTextColor = ColorTextPrimary,
                        cursorColor = ColorCyanGlow
                    )
                )

                AnimatedVisibility(
                    visible = isErrorVisible,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Column {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "❌ Galat Password! Sahi password enter karein.",
                            color = ColorCrimsonNeon,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Primary Unlock Button
                Button(
                    onClick = { attemptUnlock() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .jellyElastic(baseCornerRadius = 14.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ColorCyanGlow,
                        contentColor = ColorObsidianDeep
                    )
                ) {
                    Text(
                        text = "🔓 Unlock IronDiscipline",
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp,
                        letterSpacing = 0.5.sp
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Exit to Home Screen Button
                OutlinedButton(
                    onClick = onExitToHome,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .jellyElastic(baseCornerRadius = 14.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color(0xFF26334D)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = ColorTextSecondary
                    )
                ) {
                    Text(
                        text = "🏠 Wapas Home Screen",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}
