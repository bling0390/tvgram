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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import tv.telegram.R
import tv.telegram.td.AuthState
import tv.telegram.ui.MainViewModel

/**
 * QR login screen. Renders the QR code (and its copy) whenever a
 * WaitQrCode link is available. While no link is available yet
 * (initializing, waiting for a fresh link after sign-out, etc.) the QR
 * area shows a loading indicator. Errors are appended under the left
 * column text instead of replacing the whole page.
 */
@Composable
fun QrCodeScreen(viewModel: MainViewModel) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()

    val qrLink = (authState as? AuthState.WaitQrCode)?.link
    val error = when (val st = authState) {
        is AuthState.Error -> st.message
        AuthState.Closed -> "Disconnected from Telegram."
        else -> null
    }

    QrContent(
        title = stringResource(R.string.login_title),
        subtitle = stringResource(R.string.login_subtitle),
        qrLink = qrLink,
        error = error,
    )
}

@Composable
private fun QrContent(
    title: String,
    subtitle: String,
    qrLink: String?,
    error: String?,
) {
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
                text = title,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 48.sp,
                style = MaterialTheme.typography.displayLarge,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 18.sp,
            )
            if (error != null) {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 16.sp,
                )
            }
        }

        Box(
            modifier = Modifier.size(360.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (qrLink.isNullOrEmpty()) {
                CircularProgressIndicator()
            } else {
                val qrBitmap = remember(qrLink) { encodeQr(qrLink) }
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "Telegram login QR code",
                        modifier = Modifier.size(360.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Log.w(TAG, "encodeQr returned null for linkLen=${qrLink.length}")
                    Box(
                        modifier = Modifier.size(360.dp).background(Color.White),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("[ QR ]", color = Color.Black, fontSize = 28.sp)
                    }
                }
            }
        }
    }
}

private fun encodeQr(content: String): Bitmap? = try {
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
    Log.w(TAG, "encodeQr failed (type=${e.javaClass.simpleName}: ${e.message})")
    null
}

private const val TAG = "QrCodeScreen"
