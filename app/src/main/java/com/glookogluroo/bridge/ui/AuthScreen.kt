package com.glookogluroo.bridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

@Composable
fun AuthScreen(
    isBusy: Boolean,
    message: String?,
    error: String?,
    onSignIn: (email: String, password: String) -> Unit,
    onSignUp: (email: String, password: String) -> Unit,
    onConfirm: (email: String, code: String) -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var showConfirm by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.relayColors.borderSubtle,
        cursorColor = MaterialTheme.colorScheme.primary,
    )
    val fieldShape = RoundedCornerShape(RelayTokens.RadiusField)

    Column(verticalArrangement = Arrangement.spacedBy(RelayTokens.Space3)) {
        RelayRoute()
        Text(
            text = "Sign in to keep automatic relay running in the cloud, even when this phone is off.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.relayColors.textMuted,
        )
        RelaySafetyNote()

        RelayCard {
            RelaySectionLabel("Cloud account")
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isBusy,
                shape = fieldShape,
                colors = fieldColors,
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isBusy,
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    RelayTextButton(
                        text = if (passwordVisible) "Hide" else "Show",
                        onClick = { passwordVisible = !passwordVisible },
                    )
                },
                shape = fieldShape,
                colors = fieldColors,
            )
            if (showConfirm) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Confirmation code") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isBusy,
                    shape = fieldShape,
                    colors = fieldColors,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(RelayTokens.Space2)) {
                RelayPrimaryButton(
                    text = "Sign in",
                    onClick = { onSignIn(email, password) },
                    enabled = !isBusy && email.isNotBlank() && password.isNotBlank(),
                )
                RelaySecondaryButton(
                    text = "Sign up",
                    onClick = {
                        showConfirm = false
                        onSignUp(email, password)
                        showConfirm = true
                    },
                    enabled = !isBusy && email.isNotBlank() && password.isNotBlank(),
                )
                if (showConfirm) {
                    RelayPrimaryButton(
                        text = "Confirm",
                        onClick = { onConfirm(email, code) },
                        enabled = !isBusy && code.isNotBlank(),
                    )
                }
            }
            message?.let {
                RelayBanner(message = it, tone = RelayBannerTone.Success)
            }
            error?.let {
                RelayBanner(message = it, tone = RelayBannerTone.Error)
            }
        }
    }
}
