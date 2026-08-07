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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.google.zxing.EncodeHintType
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

    // Cache the most recent WaitQrCode link so the brief LoggingIn
    // state after the user scans can keep rendering the same QrContent
    // layout — no layout swap, no spinner, no "Connecting…" flash. The
    // AppRoot-level Message("Signing in…") overlay is the only visible
    // signal that login is in progress.
    var lastQrLink by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(authState) {
        val st = authState  // local val — can't smart-cast delegated property
        if (st is AuthState.WaitQrCode) {
            lastQrLink = st.link
        }
    }

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
            AuthState.WaitTdlibParams -> StatusMessage(
                title = stringResource(R.string.app_name),
                subtitle = "Connecting to Telegram…",
            )

            // LoggingIn: keep showing the QR page using the last cached
            // link. No spinner in the QR area, no layout swap — only the
            // AppRoot-level Message("Signing in…") overlay appears. The
            // user has already scanned; the QR code on screen is now
            // visually stale but harmless (they're not going to scan it
            // again).
            AuthState.LoggingIn -> QrContent(
                title = stringResource(R.string.login_title),
                subtitle = stringResource(R.string.login_subtitle),
                qrLink = lastQrLink ?: "",
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
    alreadyLoggedIn: Boolean = false,
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
            // Per design tweak: no third "waiting" line below the
            // numbered steps — once the user scans, AppRoot renders the
            // Message("Signing in…") overlay instead, so the in-screen
            // hint is redundant.
        }

        // Right half: QR. Display the 360×360 bitmap at its native 360dp so
        // the QR modules + built-in quiet zone keep their 1:1 ratio
        // (no DPI scaling artifacts, no extra 16dp white padding around it).
        // Sized to 75% of the original 480dp per design tweak — gives the
        // copy column on the left more breathing room without leaving the
        // QR too small for reliable phone scanning.
        if (qrBitmap != null) {
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = "Telegram login QR code",
                modifier = Modifier.size(360.dp),
                contentScale = ContentScale.Fit,
            )
        } else {
            Box(
                modifier = Modifier.size(360.dp).background(Color.White),
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

/** Encode a string as a 360×360 QR code Bitmap. Null on encode failure. */
private fun encodeQr(content: String): Bitmap? = try {
    // Generate at 360×360 (the display size, after the 480→360dp shrink)
    // so the bitmap is 1:1 with the Image's dp size. Quiet zone is
    // explicitly overridden to 2 modules (zxing default is 4) — packed
    // tighter visually but BELOW the ISO/IEC 18004 minimum of 4 modules,
    // so some scanners may reject. The module itself also scales up
    // (multiple goes from 6 to 7 px since 360 / (45+4) = 7), which
    // partially compensates for the narrower border.
    val hints = mapOf<EncodeHintType, Any>(
        EncodeHintType.MARGIN to 2,
    )
    val matrix: BitMatrix = MultiFormatWriter().encode(
        content, BarcodeFormat.QR_CODE, 360, 360, hints
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
