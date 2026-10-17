package com.lamadriguera.app.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.lamadriguera.app.R

/**
 * Registro/login con teléfono + código OTP.
 * Teléfono -> enviar código -> campo OTP -> verificar. Estados de carga y error.
 */
@Composable
fun PhoneLoginScreen(
    state: PhoneLoginUiState,
    onPhoneChanged: (String) -> Unit,
    onCodeChanged: (String) -> Unit,
    onSendCode: () -> Unit,
    onResendCode: () -> Unit,
    onVerifyCode: () -> Unit,
    onAuthenticated: () -> Unit
) {
    LaunchedEffect(state.authenticated) {
        if (state.authenticated) onAuthenticated()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.auth_phone_title),
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.auth_phone_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = state.phoneInput,
            onValueChange = onPhoneChanged,
            label = { Text(stringResource(R.string.auth_phone_label)) },
            placeholder = { Text(stringResource(R.string.auth_phone_hint)) },
            singleLine = true,
            enabled = !state.isSubmitting,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onSendCode,
            enabled = !state.isSubmitting && !state.codeSent,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (state.isSubmitting && !state.codeSent) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Text(stringResource(R.string.auth_send_code))
            }
        }

        if (state.codeSent) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.auth_code_sent, state.phoneInput),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = state.codeInput,
                onValueChange = onCodeChanged,
                label = { Text(stringResource(R.string.auth_otp_label)) },
                placeholder = { Text(stringResource(R.string.auth_otp_hint)) },
                singleLine = true,
                enabled = !state.isSubmitting,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onVerifyCode,
                enabled = !state.isSubmitting,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(stringResource(R.string.auth_verify_code))
                }
            }
            TextButton(
                onClick = onResendCode,
                enabled = !state.isSubmitting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.auth_resend_code))
            }
        }

        if (state.errorMessage != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = state.errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.auth_phone_rule),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
