@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package tv.telegram.ui.login

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import tv.telegram.R
import tv.telegram.td.AuthState
import tv.telegram.ui.MainViewModel
import androidx.tv.material3.Text
import androidx.tv.material3.MaterialTheme

/**
 * QrLoginScreen — entry screen.
 *
 * Renders the QR code that TDLib hands us via
 * [AuthState.WaitQrCode]. The QR encodes a `tg://login?token=...` URL;
 * the user scans it with the Telegram app on their phone, then
 * TDLib transitions to [AuthState.Ready] and we move to home.
 */
@Composable
fun QrLoginScreen(viewModel: MainViewModel) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val signingOut by viewModel.signingOut.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        // While sign-out is in flight, suppress the intermediate states
        // (Closed / WaitTdlibParams / WaitEncryptionKey / LoggingIn)
        // and show a single "Signing out..." message. As soon as TDLib
        // emits WaitQrCode the flag clears and the QR renders directly.
        if (signingOut && authState !is AuthState.WaitQrCode && authState !is AuthState.Error) {
            StatusMessage(
                title = stringResource(R.string.app_name),
                subtitle = "Signing out…",
            )
        } else when (val s = authState) {
            AuthState.Idle,
            AuthState.WaitTdlibParams,
            AuthState.LoggingIn -> StatusMessage(
                title = stringResource(R.string.app_name),
                subtitle = "Connecting to Telegram…",
            )

            AuthState.WaitEncryptionKey -> StatusMessage(
                title = stringResource(R.string.app_name),
                subtitle = "Unlocking local database…",
            )

            is AuthState.WaitQrCode -> QrContent(
                title = stringResource(R.string.login_title),
                subtitle = stringResource(R.string.login_subtitle),
                qrLink = s.link,
                alreadyLoggedIn = s.alreadyLoggedIn,
            )

            AuthState.Ready -> StatusMessage(
                title = stringResource(R.string.app_name),
                subtitle = "Signed in. Loading chats…",
            )

            is AuthState.Error -> StatusMessage(
                title = stringResource(R.string.app_name),
                subtitle = stringResource(R.string.login_failed, s.message),
            )

            AuthState.Closed -> StatusMessage(
                title = stringResource(R.string.app_name),
                subtitle = "Disconnected from Telegram. Restart the app to sign in again.",
            )
        }
    }
}

@Composable
private fun QrContent(
    title: String,
    subtitle: String,
    qrLink: String,
    alreadyLoggedIn: Boolean,
) {
    val qrBitmap = remember(qrLink) { encodeQr(qrLink) }

    // Side-by-side layout: title/subtitle on the left, QR on the right.
    // Fills the 16:9 TV screen horizontally so there's no big black void
    // around the QR (which is what the previous Column layout produced).
    Row(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left half: copy
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text  = title,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 48.sp,
                style   = MaterialTheme.typography.displayLarge,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text  = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 18.sp,
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text  = if (alreadyLoggedIn)
                            stringResource(R.string.login_waiting)
                        else
                            "Waiting for you to scan…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 16.sp,
            )
        }

        // Right half: QR. Display the 480×480 bitmap at its native 480dp so
        // the QR modules + built-in quiet zone keep their 1:1 ratio
        // (no DPI scaling artifacts, no extra 16dp white padding around it).
        if (qrBitmap != null) {
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = "Telegram login QR code",
                modifier = Modifier.size(480.dp),
                contentScale = ContentScale.Fit,
            )
        } else {
            Box(
                modifier = Modifier.size(480.dp).background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Text("[ QR ]", color = Color.Black, fontSize = 28.sp)
            }
        }
    }
}

@Composable
private fun StatusMessage(title: String, subtitle: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 56.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 22.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(720.dp),
        )
    }
}

/** Encode a string as a 320×320 QR code Bitmap. Null on encode failure. */
private fun encodeQr(content: String): Bitmap? = try {
    // Generate at 480×480 (the display size) so the bitmap is 1:1 with
    // the Image's dp size and the QR's built-in quiet zone stays in the
    // correct physical proportion. Previous version used 320×320 + a
    // 1.5x display scale, which inflated the quiet zone to ~27dp of
    // white border on every side.
    val matrix: BitMatrix = MultiFormatWriter().encode(
        content, BarcodeFormat.QR_CODE, 480, 480
    )
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h)
    for (y in 0 until h) {
        val offset = y * w
        for (x in 0 until w) {
            pixels[offset + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, w, 0, 0, w, h)
    }
} catch (e: Throwable) {
    null
}
