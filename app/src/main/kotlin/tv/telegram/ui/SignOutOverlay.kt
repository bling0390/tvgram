package tv.telegram.ui

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.telegram.R

/**
 * Top-level sign-out overlay. Rendered by [AppRoot] above whatever
 * screen is currently shown.
 *
 * Why a separate top-level component (and not inlined into SettingsScreen):
 *   - When inlined into SettingsScreen, the banner's Alignment.TopCenter
 *     was relative to SettingsScreen's own Box bounds — which is inside
 *     HomeScreen, which itself has padding/safe-area insets. So the
 *     "center" was off-screen-center.
 *   - At the AppRoot level, the banner's Box parent is the full-screen
 *     fillMaxSize() root, so TopCenter really is screen-center.
 *   - As a bonus: lives above any screen transition, so the slide-out
 *     tween can complete cleanly even while AppRoot is swapping screens.
 *
 * Driven by [MainViewModel.showSignOutBanner], which is decoupled from
 * [MainViewModel.signingOut] with a 350ms gap so the exit tween (250ms)
 * finishes before AppRoot swaps SettingsScreen out for QrLoginScreen.
 *
 * The "signing out…" string comes from R.string.signing_out (3 locales).
 */
@Composable
fun SignOutOverlay(viewModel: MainViewModel) {
    val showSignOutBanner by viewModel.showSignOutBanner.collectAsStateWithLifecycle()

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
            // Full-screen dim layer (alpha 0.25) — sits over the underlying
            // screen to provide spatial context for the banner without
            // fully blocking the page.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.25f)),
            )

            // Top-anchored banner. Alignment.TopCenter inside the full-screen
            // Box above gives true screen-center horizontal alignment.
            // 50% width (per design tweak) leaves 25% empty on each side.
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
