package com.glookogluroo.bridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

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

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Cloud account", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Sign in to sync via AWS. Scheduled sync runs in the cloud even when this phone is off.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isBusy,
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
                    TextButton(onClick = { passwordVisible = !passwordVisible }) {
                        Text(if (passwordVisible) "Hide" else "Show")
                    }
                },
            )
            if (showConfirm) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Confirmation code") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isBusy,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onSignIn(email, password) },
                    enabled = !isBusy && email.isNotBlank() && password.isNotBlank(),
                ) {
                    Text("Sign in")
                }
                Button(
                    onClick = {
                        showConfirm = false
                        onSignUp(email, password)
                        showConfirm = true
                    },
                    enabled = !isBusy && email.isNotBlank() && password.isNotBlank(),
                ) {
                    Text("Sign up")
                }
                if (showConfirm) {
                    Button(
                        onClick = { onConfirm(email, code) },
                        enabled = !isBusy && code.isNotBlank(),
                    ) {
                        Text("Confirm")
                    }
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
