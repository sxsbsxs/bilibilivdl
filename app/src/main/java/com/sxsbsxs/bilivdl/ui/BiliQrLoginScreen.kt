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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
 * QR login presentation adapted from KafuuNeko/BiliDownload's QrLoginLayout.
 * The upstream GPL-3.0 source is retained under upstream/BiliDownload/.
 */
@Composable
fun BiliQrLoginScreen(
    qrBitmap: Bitmap?,
    qrUrl: String?,
    statusMessage: String,
    loading: Boolean,
    scanned: Boolean,
    onRefresh: () -> Unit,
    onOpenQrUrl: () -> Unit,
    onBack: () -> Unit
) {
    val brand = Color(0xFFFB7299)
    val ink = Color(0xFF25262B)
    val muted = Color(0xFF858B96)
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = brand,
            onPrimary = Color.White,
            background = Color(0xFFF6F7F9),
            surface = Color.White,
            onSurface = ink,
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
                    color = ink,
                    fontSize = 34.sp,
                    modifier = Modifier
                        .clickable(onClick = onBack)
                        .padding(end = 18.dp)
                )
                Text(
                    text = "扫码登录",
                    color = ink,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "哔哩哔哩",
                    color = brand,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    text = "使用哔哩哔哩 APP 扫码，安全授权登录",
                    color = muted,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(22.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFFFFF0F4)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.size(34.dp),
                            shape = CircleShape,
                            color = brand
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("B", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Column(modifier = Modifier.padding(start = 11.dp)) {
                            Text(
                                text = if (scanned) "已检测到扫码" else "打开 B站 APP",
                                color = ink,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (scanned) "在手机上选择账号并确认授权" else "点击首页右上角扫一扫，扫描下方二维码",
                                color = muted,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                Spacer(Modifier.height(22.dp))
                Surface(
                    modifier = Modifier.size(256.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = Color.White,
                    shadowElevation = 3.dp
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(enabled = qrBitmap != null && !loading, onClick = onRefresh)
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (qrBitmap != null) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "B站登录二维码",
                                modifier = Modifier.fillMaxSize()
                            )
                            if (scanned) {
                                Surface(
                                    modifier = Modifier.fillMaxSize(),
                                    color = Color.White.copy(alpha = 0.94f),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text("✓", color = brand, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                                        Text("已扫码", color = ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                        Text("请在 B站 APP 中确认", color = muted, fontSize = 12.sp)
                                    }
                                }
                            }
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = brand)
                                Spacer(Modifier.height(12.dp))
                                Text("正在获取二维码…", color = muted, fontSize = 12.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(17.dp))
                Text(
                    text = statusMessage,
                    color = ink,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                if (qrUrl != null) {
                    Spacer(Modifier.height(7.dp))
                    Text(
                        text = "无法扫码？点此打开登录链接",
                        color = brand,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable(onClick = onOpenQrUrl)
                    )
                }
                Spacer(Modifier.height(15.dp))
                Button(
                    onClick = onRefresh,
                    enabled = !loading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = brand,
                        disabledContainerColor = brand.copy(alpha = 0.42f)
                    )
                ) {
                    Text(if (loading) "正在获取二维码…" else "重新获取二维码", fontSize = 14.sp)
                }
                Spacer(Modifier.height(13.dp))
                Text(
                    text = "登录成功后将安全保存账号凭据；请勿将二维码转发给他人",
                    color = muted,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
