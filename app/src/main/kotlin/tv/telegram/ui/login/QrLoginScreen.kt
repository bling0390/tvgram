@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package tv.telegram.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.NonInteractiveSurfaceDefaults

/**
 * QrLoginScreen — entry screen.
 *
 * MVP step 1: a static placeholder verifying Compose for TV renders.
 *   - Big TV-typography title centered
 *   - "Fake QR" placeholder (white square on dark surface)
 *   - Subtitle telling the user what to do
 *
 * MVP step 2: real QR generated from TdApi.getLoginUrl + TdAuth state.
 */
@Composable
fun QrLoginScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(48.dp),
        ) {
            Text(
                text  = "Telegram TV",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 56.sp,
                style   = MaterialTheme.typography.displayLarge,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text  = "Open Telegram on your phone, then scan this QR code",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 22.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(720.dp),
            )
            Spacer(Modifier.height(48.dp))

            // Fake QR placeholder — replaced with a real ZXing bitmap in step 2
            Card(
                onClick = { /* no-op until TDLib is wired */ },
                colors  = CardDefaults.colors(
                    containerColor = Color.White,
                ),
                scale   = CardDefaults.scale(focusedScale = 1.05f),
                modifier = Modifier.size(360.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text  = "[ QR ]",
                        color = Color.Black,
                        fontSize = 32.sp,
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
            Text(
                text  = "Waiting for confirmation…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 20.sp,
            )
        }
    }
}
