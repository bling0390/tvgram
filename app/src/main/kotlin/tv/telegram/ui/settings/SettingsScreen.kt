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
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.telegram.BuildConfig
import tv.telegram.R
import tv.telegram.td.AuthState
import tv.telegram.td.TdUser
import tv.telegram.ui.Language
import tv.telegram.ui.MainViewModel
import tv.telegram.ui.ThemeMode
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * SettingsScreen — v0.9.0 with real account info, real sign-out, light
 * theme, and 3-locale picker.
 *
 * 5 rows:
 *   1. Account info     — shows TDLib getMe (display name / id / phone)
 *   2. Language          — cycle en / 简体中文 / 繁體中文
 *   3. Theme             — cycle Dark / Light / System
 *   4. About Tvgram      — version + repo dialog
 *   5. Sign out          — two-step confirm; calls realSignOut() which
 *                          stops the TDLib process + wipes on-disk state
 *                          + restarts the client.
 */
@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val ctx = LocalContext.current
    val theme by viewModel.themeMode.collectAsStateWithLifecycle()
    val lang by viewModel.language.collectAsStateWithLifecycle()
    val user by viewModel.currentUser.collectAsStateWithLifecycle()
    val authState by viewModel.authState.collectAsStateWithLifecycle()

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
            text = stringResource(R.string.settings_title),
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(24.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SettingsRow(
                    title = stringResource(R.string.settings_account),
                    value = accountValue(authState, user),
                    onClick = { /* read-only */ },
                    fr = firstFocus,
                )
            }
            item {
                SettingsRow(
                    title = stringResource(R.string.settings_language),
                    value = languageLabel(lang),
                    onClick = { viewModel.setLanguage(lang.next()) },
                )
            }
            item {
                SettingsRow(
                    title = stringResource(R.string.settings_theme),
                    value = themeLabel(theme),
                    onClick = { viewModel.setTheme(theme.next()) },
                )
            }
            item {
                SettingsRow(
                    title = stringResource(R.string.settings_about),
                    value = stringResource(R.string.settings_about_value, BuildConfig.VERSION_NAME),
                    onClick = { showAbout = true },
                )
            }
            item {
                SettingsRow(
                    title = stringResource(R.string.settings_signout),
                    value = stringResource(
                        if (showLogoutConfirm) R.string.settings_signout_confirm
                        else R.string.settings_signout_value
                    ),
                    onClick = {
                        if (showLogoutConfirm) {
                            viewModel.realSignOut()
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

    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
}

@Composable
private fun accountValue(authState: AuthState, user: TdUser?): String =
    when {
        user != null -> {
            val name = user.displayName
            when {
                user.phoneNumber.isNotBlank() -> stringResource(
                    R.string.settings_account_value_signed_in_with_phone,
                    name, user.id, user.phoneNumber,
                )
                else -> stringResource(
                    R.string.settings_account_value_signed_in_with_id,
                    name, user.id,
                )
            }
        }
        authState is AuthState.WaitQrCode -> stringResource(R.string.settings_account_value_not_signed_in)
        else -> stringResource(R.string.settings_account_value_loading)
    }

@Composable
private fun languageLabel(lang: Language): String = when (lang) {
    Language.English -> stringResource(R.string.settings_language_english)
    Language.SimplifiedChinese -> stringResource(R.string.settings_language_simplified_chinese)
    Language.TraditionalChinese -> stringResource(R.string.settings_language_traditional_chinese)
}

@Composable
private fun themeLabel(theme: ThemeMode): String = when (theme) {
    ThemeMode.Dark -> stringResource(R.string.settings_theme_dark)
    ThemeMode.Light -> stringResource(R.string.settings_theme_light)
    ThemeMode.System -> stringResource(R.string.settings_theme_system)
}

private fun Language.next(): Language = when (this) {
    Language.English -> Language.SimplifiedChinese
    Language.SimplifiedChinese -> Language.TraditionalChinese
    Language.TraditionalChinese -> Language.English
}

private fun ThemeMode.next(): ThemeMode = when (this) {
    ThemeMode.Dark -> ThemeMode.Light
    ThemeMode.Light -> ThemeMode.System
    ThemeMode.System -> ThemeMode.Dark
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
            modifier = Modifier.fillMaxWidth(0.6f).height(300.dp),
            colors = CardDefaults.colors(containerColor = Color(0xFF1E1E1E)),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.about_title),
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 16.sp,
                )
                Text(
                    stringResource(R.string.about_body),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
                )
                Text(
                    stringResource(R.string.about_repo),
                    color = MaterialTheme.colorScheme.secondary,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.about_close_hint),
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                )
            }
        }
    }
}
