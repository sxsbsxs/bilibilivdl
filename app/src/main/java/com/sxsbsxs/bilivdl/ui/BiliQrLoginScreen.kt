package com.sxsbsxs.bilivdl.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * QR login screen adapted from the Compose QR layout in KafuuNeko/BiliDownload.
 * Upstream source and GPL-3.0 notice are retained in upstream/BiliDownload/.
 */
@Composable
fun BiliQrLoginScreen(
    qrBitmap: Bitmap?,
    statusMessage: String,
    loading: Boolean,
    onRefresh: () -> Unit,
    onBack: () -> Unit
) {
    val brand = Color(0xFFFB7299)
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = brand,
            onPrimary = Color.White,
            background = Color(0xFFF6F7F9),
            surface = Color.White,
            onSurface = Color(0xFF25262B),
            secondaryContainer = Color(0xFFFFF0F4)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "‹",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 34.sp,
                    modifier = Modifier
                        .clickable(onClick = onBack)
                        .padding(end = 18.dp)
                )
                Text(
                    text = "扫码登录",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "哔哩哔哩",
                    color = brand,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "使用 B站 APP 扫描二维码，确认后即可登录",
                    color = Color(0xFF858B96),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(26.dp))

                Surface(
                    modifier = Modifier.size(264.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White,
                    shadowElevation = 3.dp
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(enabled = qrBitmap != null, onClick = onRefresh)
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (qrBitmap != null) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "B站登录二维码",
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            CircularProgressIndicator(color = brand)
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                Text(
                    text = statusMessage,
                    color = Color(0xFF34363C),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = onRefresh,
                    enabled = !loading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = brand,
                        disabledContainerColor = brand.copy(alpha = 0.45f)
                    )
                ) {
                    Text(if (loading) "正在获取二维码…" else "刷新二维码", fontSize = 14.sp)
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "请勿将登录二维码转发给他人",
                    color = Color(0xFF9296A0),
                    fontSize = 12.sp
                )
            }
        }
    }
}
