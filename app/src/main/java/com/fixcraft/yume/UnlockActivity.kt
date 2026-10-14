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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.fixcraft.yume.ui.theme.mainActionColors
import com.fixcraft.yume.vpn.YumeVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

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
                            val reconnect = intent.getBooleanExtra(YumeVpnService.EXTRA_RECONNECT, false)
                            val success = withContext(Dispatchers.IO) {
                                // The key derivation and the kit index read stay off the main thread.
                                AppVaultManager.unlockWithPassword(this@UnlockActivity, password).isSuccess
                                    .also { unlocked ->
                                        if (unlocked && reconnect) YumeVpnService.reconnectAfterUnlock(this@UnlockActivity)
                                    }
                            }
                            if (success) openMain()
                            success
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
    onUnlock: suspend (String) -> Boolean,
) {
    val strings = appStrings()
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { password = "" } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
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
                    enabled = !busy,
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
                    onClick = {
                        if (!busy && password.isNotEmpty()) {
                            busy = true
                            val submitted = password
                            password = ""
                            scope.launch {
                                try {
                                    if (!onUnlock(submitted)) error = strings.failedToUnlockAppData
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    error = strings.failedToUnlockAppData
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = mainActionColors(),
                ) {
                    Text(strings.unlockButton)
                }
            }
        }
    }
}
