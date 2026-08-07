@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package tv.telegram.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton

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
    val signingOut by viewModel.signingOut.collectAsStateWithLifecycle()
    val showSignOutBanner by viewModel.showSignOutBanner.collectAsStateWithLifecycle()

    var showAbout by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(signingOut) { if (!signingOut) firstFocus.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Settings list + about dialog stay rendered during sign-out —
        // only a translucent mask is layered on top so the user can still
        // see which page they clicked. AppRoot guards this state in
        // MainActivity so the screen is not yanked to QrLoginScreen until
        // TDLib reaches WaitQrCode.
        Column(
            modifier = Modifier
                .fillMaxSize()
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
                        value = stringResource(R.string.settings_signout_value),
                        onClick = { showLogoutConfirm = true },
                        danger = true,
                    )
                }
            }
        }

        if (showAbout) AboutDialog(onDismiss = { showAbout = false })

        // Top-most layer: full-screen 0.25-alpha dim + a top-anchored
        // banner with spinner + label. Both fade and slide together,
        // driven by [showSignOutBanner] (not [signingOut]) so the
        // slide-out tween has time to finish before AppRoot swaps the
        // screen out — see MainViewModel._showSignOutBanner for the
        // timing rationale.
        AnimatedVisibility(
            visible = showSignOutBanner,
            enter = fadeIn(animationSpec = tween(300)) + slideInVertically(
                animationSpec = tween(300, easing = FastOutSlowInEasing),
                initialOffsetY = { fullHeight -> -fullHeight },
            ),
            exit = fadeOut(animationSpec = tween(250)) + slideOutVertically(
                animationSpec = tween(250, easing = FastOutSlowInEasing),
                targetOffsetY = { fullHeight -> -fullHeight },
            ),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Dim layer
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.25f)),
                )

                // Top banner — "bottom overlay" template from the Android
                // TV design guide, but anchored to TopCenter so the banner
                // has somewhere to slide IN from above (and OUT to, on exit).
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth(0.5f)
                        .padding(horizontal = 48.dp, vertical = 32.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xE61E1E1E))
                        .padding(horizontal = 28.dp, vertical = 18.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 3.dp,
                            trackColor = Color.White.copy(alpha = 0.3f),
                        )
                        Text(
                            text = stringResource(R.string.signing_out),
                            color = Color.White,
                            fontSize = 18.sp,
                        )
                    }
                }
            }
        }
    }

    // Confirmation dialog lives outside the if/else so it can never be
    // blocked by the overlay branch. (In practice signingOut becomes
    // true only after the user has already dismissed this dialog.)
    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text(stringResource(R.string.settings_signout_dialog_title)) },
            text = { Text(stringResource(R.string.settings_signout_dialog_text)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.realSignOut()
                    showLogoutConfirm = false
                }) {
                    Text(stringResource(R.string.settings_signout_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) {
                    Text(stringResource(R.string.settings_signout_dialog_cancel))
                }
            },
        )
    }
}

/**
 * Full-screen loading overlay shown on SettingsScreen while
 * [MainViewModel.signingOut] is true. The actual data wipe and TDLib
 * restart happens inside [MainViewModel.realSignOut] — this just
 * gives the user feedback on the page they just clicked.
 *
 * Moved to an inlined AnimatedVisibility block in [SettingsScreen] —
 * the slide-in / slide-out tween needs to be driven by
 * [MainViewModel.showSignOutBanner] (not [MainViewModel.signingOut])
 * so the exit animation completes before AppRoot swaps the screen.
 */


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
                    stringResource(R.string.app_full_name),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp,
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
