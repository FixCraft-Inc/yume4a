package com.fixcraft.yume

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fixcraft.yume.data.AppLanguage
import com.fixcraft.yume.data.AppVaultManager
import com.fixcraft.yume.i18n.ProvideAppStrings
import com.fixcraft.yume.i18n.appStrings
import com.fixcraft.yume.ui.theme.YumeVPNTheme

class UnlockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (AppVaultManager.ensureReady(this)) {
            openMain()
            return
        }
        enableEdgeToEdge()
        setContent {
            YumeVPNTheme {
                ProvideAppStrings(context = LocalContext.current, language = AppLanguage.SystemDefault) {
                    UnlockScreen(
                        onUnlock = { password ->
                            val result = AppVaultManager.unlockWithPassword(this, password)
                            result.onSuccess { openMain() }
                            result.exceptionOrNull()?.message
                        },
                    )
                }
            }
        }
    }

    private fun openMain() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        finish()
    }
}

@Composable
private fun UnlockScreen(
    onUnlock: (String) -> String?,
) {
    val strings = appStrings()
    var password by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = strings.unlockAppDataTitle,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = strings.unlockAppDataDetail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(strings.passwordLabel) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                if (!error.isNullOrBlank()) {
                    Text(
                        text = error.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(
                    onClick = { error = onUnlock(password) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(strings.unlockButton)
                }
            }
        }
    }
}
