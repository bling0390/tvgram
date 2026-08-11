@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package tv.telegram.ui.login

import android.graphics.Bitmap
import android.util.Log
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

private const val TAG = "QrLoginScreen"

/** One-line label for logcat — avoids leaking token material. */
private fun AuthState.label(): String = when (this) {
    AuthState.Idle -> "Idle"
    AuthState.WaitTdlibParams -> "WaitTdlibParams"
    AuthState.WaitEncryptionKey -> "WaitEncryptionKey"
    is AuthState.WaitQrCode -> "WaitQrCode(linkLen=${link.length}, alreadyLoggedIn=$alreadyLoggedIn)"
    AuthState.LoggingIn -> "LoggingIn"
    AuthState.Ready -> "Ready"
    is AuthState.Error -> "Error(\"$message\")"
    AuthState.Closed -> "Closed"
}

/**
 * QrLoginScreen. Renders the QR from [AuthState.WaitQrCode] (encoded
 * as `tg://login?token=...`); on [AuthState.Ready] the app moves to home.
 */
@Composable
fun QrLoginScreen(viewModel: MainViewModel) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    val signingOut by viewModel.signingOut.collectAsStateWithLifecycle()

    // Cache the most recent WaitQrCode link so the brief LoggingIn
    // state after the user scans can keep rendering the same QrContent
    // layout — no layout swap, no spinner, no "Connecting…" flash.
    var lastQrLink by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(authState) {
        val st = authState  // local val — can't smart-cast delegated property
        val prevLen = lastQrLink?.length  // capture for log diff
        when (st) {
            is AuthState.WaitQrCode -> {
                lastQrLink = st.link
                Log.d(TAG, "[effect] authState=${st.label()} → lastQrLink set (prevLen=${prevLen ?: "null"}, newLen=${st.link.length})")
            }
            // Clear at WaitTdlibParams, not Closed: WaitTdlibParams is
            // the first state of every fresh bootstrap, so it's the
            // only unambiguous "a new WaitQrCode is coming" signal.
            // Closed races with the brief WaitQrCode-after-Close window
            // TDLib sometimes emits — clearing there would wipe an
            // in-flight new QR before the user can scan it.
            AuthState.WaitTdlibParams -> {
                lastQrLink = null
                Log.d(TAG, "[effect] authState=${st.label()} → lastQrLink cleared (prevLen=${prevLen ?: "null"}, reason=bootstrap-start)")
            }
            else -> {
                Log.d(TAG, "[effect] authState=${st.label()} → lastQrLink unchanged (len=${prevLen ?: "null"})")
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        // During sign-out: keep the QR layout (WaitingQrCode / Error render
        // the right message below). For every other transient state, drop
        // the QR content and show a single "Signing out…" message.
        if (signingOut && authState !is AuthState.WaitQrCode && authState !is AuthState.Error) {
            Log.d(TAG, "[render] signingOut=true authState=${authState.label()} → StatusMessage('Signing out…')")
            StatusMessage(
                title = stringResource(R.string.app_name),
                subtitle = "Signing out…",
            )
        } else when (val s = authState) {
            is AuthState.Error -> {
                Log.d(TAG, "[render] branch=Error authState=${s.label()} → StatusMessage('Login failed: ${s.message}')")
                StatusMessage(
                    title = stringResource(R.string.app_name),
                    subtitle = stringResource(R.string.login_failed, s.message),
                )
            }
            AuthState.Closed -> {
                Log.d(TAG, "[render] branch=Closed → StatusMessage('Disconnected')")
                StatusMessage(
                    title = stringResource(R.string.app_name),
                    subtitle = "Disconnected from Telegram. Restart the app to sign in again.",
                )
            }

            // Default: QR layout for every transient state. Avoids
            // layout swaps when TDLib re-emits WaitTdlibParams or
            // WaitEncryptionKey between WaitQrCode and Ready (which would
            // otherwise flash "Connecting to Telegram…" / "Unlocking…").
            is AuthState.WaitQrCode -> {
                Log.d(TAG, "[render] branch=WaitQrCode linkLen=${s.link.length} alreadyLoggedIn=${s.alreadyLoggedIn} cachedLastQrLinkLen=${lastQrLink?.length ?: "null"} → QrContent")
                QrContent(
                    title = stringResource(R.string.login_title),
                    subtitle = stringResource(R.string.login_subtitle),
                    qrLink = s.link,
                    alreadyLoggedIn = s.alreadyLoggedIn,
                )
            }
            else -> {
                val effective = lastQrLink ?: ""
                Log.d(TAG, "[render] branch=else authState=${s.label()} effectiveQrLinkLen=${effective.length} lastQrLinkLen=${lastQrLink?.length ?: "null"} → QrContent")
                QrContent(
                    title = stringResource(R.string.login_title),
                    subtitle = stringResource(R.string.login_subtitle),
                    qrLink = effective,
                )
            }
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

    // Side-by-side: copy on the left, QR on the right. Fills the 16:9
    // TV screen — no big black void around the QR.
    Row(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
        }

        // Display 360×360 at native 360dp so the QR modules + quiet zone
        // keep their 1:1 ratio (no DPI scaling artifacts).
        if (qrBitmap != null) {
            Image(
                bitmap = qrBitmap.asImageBitmap(),
                contentDescription = "Telegram login QR code",
                modifier = Modifier.size(360.dp),
                contentScale = ContentScale.Fit,
            )
        } else {
            Log.w(TAG, "[QrContent] rendering [ QR ] placeholder (encodeQr returned null for qrLinkLen=${qrLink.length}, empty=${qrLink.isEmpty()})")
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
    // Quiet zone overridden to 2 modules (zxing default 4) — packed
    // tighter visually but BELOW the ISO/IEC 18004 minimum of 4, so
    // some scanners may reject. Module size scales from 6 to 7 px
    // (360 / (45+4) = 7), which partially compensates.
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
    Log.w(TAG, "[encodeQr] failed (contentLen=${content.length}, empty=${content.isEmpty()}, type=${e.javaClass.simpleName}: ${e.message})")
    null
}
