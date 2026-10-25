package com.irondiscipline.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

private val ColorDialogBackground = Color(0xFF141B29)
private val ColorCrimsonNeon = Color(0xFFFF2E54)
private val ColorTextPrimary = Color(0xFFFFFFFF)
private val ColorTextSecondary = Color(0xFF8A99AD)
private val ColorSkyBlue = Color(0xFF38BDF8)
private val ColorCardBorder = Color(0xFF26334D)

/**
 * Obsidian Dark-Luxury PIN Dialog for authorized app deletion & protection disarm.
 */
@Composable
fun UninstallPinDialog(
    onDismiss: () -> Unit,
    onVerifyPin: (String) -> Boolean,
    onConfirmUninstall: () -> Unit
) {
    var enteredPin by remember { mutableStateOf("") }
    var hasError by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    BorderStroke(1.5.dp, ColorCrimsonNeon),
                    RoundedCornerShape(22.dp)
                ),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = ColorDialogBackground)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Title
                Text(
                    text = "🔐 Enter Security PIN to Delete App",
                    color = ColorTextPrimary,
                    fontWeight = FontWeight.Black,
                    fontSize = 17.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Helper Subtitle (Testing note)
                Text(
                    text = "Enter your 4-digit PIN to disarm protection and uninstall IronDiscipline (Default Test PIN: 1234).",
                    color = ColorTextSecondary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 17.sp
                )

                Spacer(modifier = Modifier.height(18.dp))

                // PIN Input Field
                OutlinedTextField(
                    value = enteredPin,
                    onValueChange = {
                        if (it.length <= 10) {
                            enteredPin = it
                            hasError = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(
                        color = ColorTextPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center
                    ),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (onVerifyPin(enteredPin)) {
                                onConfirmUninstall()
                            } else {
                                hasError = true
                            }
                        }
                    ),
                    singleLine = true,
                    placeholder = {
                        Text(
                            text = "••••",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            color = ColorTextSecondary.copy(alpha = 0.5f),
                            fontSize = 24.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    },
                    isError = hasError,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF0C1019),
                        unfocusedContainerColor = Color(0xFF0C1019),
                        focusedBorderColor = ColorCrimsonNeon,
                        unfocusedBorderColor = ColorCardBorder,
                        errorBorderColor = ColorCrimsonNeon,
                        cursorColor = ColorCrimsonNeon
                    )
                )

                // Error Message
                if (hasError) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "❌ Incorrect PIN! App cannot be deleted.",
                        color = ColorCrimsonNeon,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(22.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .jellyElastic(baseCornerRadius = 12.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, ColorCardBorder),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = ColorTextSecondary
                        )
                    ) {
                        Text(
                            text = "Cancel",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }

                    Button(
                        onClick = {
                            if (onVerifyPin(enteredPin)) {
                                onConfirmUninstall()
                            } else {
                                hasError = true
                            }
                        },
                        modifier = Modifier
                            .weight(1.3f)
                            .height(46.dp)
                            .jellyElastic(baseCornerRadius = 12.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ColorCrimsonNeon,
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            text = "Unlock & Delete App",
                            fontWeight = FontWeight.Black,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

/**
 * Obsidian Dark-Luxury PIN Dialog for updating the Security PIN.
 */
@Composable
fun ChangeSecurityPinDialog(
    onDismiss: () -> Unit,
    onVerifyCurrentPin: (String) -> Boolean,
    onSaveNewPin: (String, String) -> Boolean
) {
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    BorderStroke(1.2.dp, ColorSkyBlue.copy(alpha = 0.6f)),
                    RoundedCornerShape(22.dp)
                ),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = ColorDialogBackground)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "🔐 Change Security PIN",
                    color = ColorTextPrimary,
                    fontWeight = FontWeight.Black,
                    fontSize = 17.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Enter current PIN (Default: 1234) and set a new 4+ digit security PIN.",
                    color = ColorTextSecondary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Current PIN
                OutlinedTextField(
                    value = currentPin,
                    onValueChange = { currentPin = it; errorMessage = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Current PIN", fontSize = 11.sp) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF0C1019),
                        unfocusedContainerColor = Color(0xFF0C1019),
                        focusedBorderColor = ColorSkyBlue,
                        unfocusedBorderColor = ColorCardBorder,
                        focusedTextColor = ColorTextPrimary,
                        unfocusedTextColor = ColorTextPrimary
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                // New PIN
                OutlinedTextField(
                    value = newPin,
                    onValueChange = { newPin = it; errorMessage = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("New PIN (min 4 digits)", fontSize = 11.sp) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF0C1019),
                        unfocusedContainerColor = Color(0xFF0C1019),
                        focusedBorderColor = ColorSkyBlue,
                        unfocusedBorderColor = ColorCardBorder,
                        focusedTextColor = ColorTextPrimary,
                        unfocusedTextColor = ColorTextPrimary
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Confirm PIN
                OutlinedTextField(
                    value = confirmPin,
                    onValueChange = { confirmPin = it; errorMessage = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Confirm New PIN", fontSize = 11.sp) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF0C1019),
                        unfocusedContainerColor = Color(0xFF0C1019),
                        focusedBorderColor = ColorSkyBlue,
                        unfocusedBorderColor = ColorCardBorder,
                        focusedTextColor = ColorTextPrimary,
                        unfocusedTextColor = ColorTextPrimary
                    )
                )

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage!!,
                        color = ColorCrimsonNeon,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .jellyElastic(baseCornerRadius = 12.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, ColorCardBorder)
                    ) {
                        Text("Cancel", color = ColorTextSecondary, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            if (!onVerifyCurrentPin(currentPin)) {
                                errorMessage = "❌ Current PIN is incorrect."
                                return@Button
                            }
                            if (newPin.trim().length < 4) {
                                errorMessage = "❌ New PIN must be at least 4 digits."
                                return@Button
                            }
                            if (newPin != confirmPin) {
                                errorMessage = "❌ New PIN and confirmation do not match."
                                return@Button
                            }
                            val success = onSaveNewPin(currentPin, newPin)
                            if (success) {
                                onDismiss()
                            } else {
                                errorMessage = "❌ Failed to update PIN."
                            }
                        },
                        modifier = Modifier
                            .weight(1.2f)
                            .height(44.dp)
                            .jellyElastic(baseCornerRadius = 12.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ColorSkyBlue,
                            contentColor = Color(0xFF06080E)
                        )
                    ) {
                        Text("Save New PIN", fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    }
}
