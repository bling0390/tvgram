@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package tv.telegram.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.telegram.td.AuthState
import tv.telegram.ui.Language
import tv.telegram.ui.MainViewModel
import tv.telegram.ui.SettingsRepository
import tv.telegram.ui.ThemeMode
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * SettingsScreen — the "Settings" pane of the v0.8.0 HomeScreen.
 *
 * 5 rows (top to bottom):
 *   1. 账户信息 (Account info)        — shows current TG user id + name (read-only)
 *   2. 语言切换 (Language)            — cycle: English → ... (v0.8.0 only English; UI is a stub)
 *   3. 主题切换 (Theme)              — cycle: Dark (v0.8.0 only Dark; UI is a stub)
 *   4. 关于我们 (About Tvgram)        — version, build, repo link (read-only dialog)
 *   5. 退出登录 (Sign out)           — calls viewModel.logout() with a confirm step
 *
 * D-pad: Up/Down moves between rows, OK activates the row's action.
 * The Sign out row requires a second OK to confirm (no accidental logouts).
 */
@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val theme by viewModel.themeMode.collectAsStateWithLifecycle()
    val lang by viewModel.language.collectAsStateWithLifecycle()
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val accountInfo = remember(authState) {
        when (authState) {
            is AuthState.Ready -> "Account ID: (loaded)"
            is AuthState.WaitQrCode -> "Account ID: not signed in"
            else -> "Account ID: initializing\u2026"
        }
    }

    var showAbout by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
    ) {
        Text(
            "Settings",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(24.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SettingsRow(
                    title = "Account info",
                    value = accountInfo,
                    onClick = { /* read-only */ },
                    fr = firstFocus,
                )
            }
            item {
                SettingsRow(
                    title = "Language",
                    value = lang.name,
                    onClick = {
                        // v0.8.0: only English. Cycle is a no-op for now; logging
                        // is left in so a future language lands in SharedPreferences.
                        viewModel.setLanguage(Language.English)
                    },
                )
            }
            item {
                SettingsRow(
                    title = "Theme",
                    value = theme.name,
                    onClick = { viewModel.setTheme(ThemeMode.Dark) },
                )
            }
            item {
                SettingsRow(
                    title = "About Tvgram",
                    value = "v0.8.0-debug",
                    onClick = { showAbout = true },
                )
            }
            item {
                SettingsRow(
                    title = "Sign out",
                    value = if (showLogoutConfirm) "Press OK again to confirm" else "Sign out of this Telegram account",
                    onClick = {
                        if (showLogoutConfirm) {
                            viewModel.logout()
                            showLogoutConfirm = false
                        } else {
                            showLogoutConfirm = true
                        }
                    },
                    danger = true,
                )
            }
        }
    }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }
}

@Composable
private fun SettingsRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    fr: FocusRequester? = null,
    danger: Boolean = false,
) {
    Card(
        onClick = onClick,
        scale = CardDefaults.scale(focusedScale = 1.02f),
        colors = CardDefaults.colors(
            containerColor = Color(0xFF1F1F1F),
            focusedContainerColor = if (danger) Color(0xFF7E2A2A) else MaterialTheme.colorScheme.secondary,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .let { if (fr != null) it.focusRequester(fr) else it },
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                color = if (danger) Color(0xFFEF5350) else Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                value,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(0.6f).height(280.dp),
            colors = CardDefaults.colors(containerColor = Color(0xFF1E1E1E)),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "About Tvgram",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Tvgram v0.8.0-debug",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 16.sp,
                )
                Text(
                    "A non-official Telegram client for Android TV, focused on browsing images and videos in your chats.",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                )
                Text(
                    "github.com/bling0390/tvgram",
                    color = MaterialTheme.colorScheme.secondary,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "Press OK to close",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                )
            }
        }
    }
}
