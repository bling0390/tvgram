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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Top-of-screen toast-style overlay for a transient status message.
 *
 * Pure component — takes [visible] (animates show/hide) and [message]
 * (the text to display). Knows nothing about MainViewModel or auth
 * state; the caller drives the lifecycle.
 *
 * Animation:
 *   enter = fadeIn (300ms) + slideInVertically from -fullHeight (300ms)
 *   exit  = fadeOut (250ms) + slideOutVertically to -fullHeight (250ms)
 *   easing = FastOutSlowIn on the slides (Material standard motion)
 *
 * Visual:
 *   - 25% black full-screen dim
 *   - Top-anchored banner, screen-center horizontally
 *   - 50% screen width, 12dp rounded corners, ~90% opaque dark surface
 *   - 28dp white CircularProgressIndicator + 18sp white label
 *
 * Composition pattern in AppRoot (always rendered, AnimatedVisibility
 * hides when not visible so there's no layout cost):
 * ```
 *   Message(
 *       visible = showSignOutBanner,
 *       message = stringResource(R.string.signing_out),
 *   )
 * ```
 *
 * Sibling overlay components planned for this layer:
 *   - Drawer.kt → side-anchored panel (left/right)
 *   - Dialog.kt → centered modal card
 * Message stays the simple top toast variant — distinct shape, position,
 * and animation from the others.
 */
@Composable
fun Message(
    visible: Boolean,
    message: String,
    modifier: Modifier = Modifier,
    showProgress: Boolean = true,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(300)) + slideInVertically(
            animationSpec = tween(300, easing = FastOutSlowInEasing),
            initialOffsetY = { fullHeight -> -fullHeight },
        ),
        exit = fadeOut(animationSpec = tween(250)) + slideOutVertically(
            animationSpec = tween(250, easing = FastOutSlowInEasing),
            targetOffsetY = { fullHeight -> -fullHeight },
        ),
        modifier = modifier,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Full-screen dim layer so the underlying screen stays visible
            // but de-emphasized while the banner is up.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.25f)),
            )

            // Top banner, screen-center horizontally (Alignment.TopCenter
            // inside the full-screen Box).
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
                    if (showProgress) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 3.dp,
                            trackColor = Color.White.copy(alpha = 0.3f),
                        )
                    }
                    Text(
                        text = message,
                        color = Color.White,
                        fontSize = 18.sp,
                    )
                }
            }
        }
    }
}
