package cc.kafuu.bilidownload.common.network.model

import com.google.gson.annotations.SerializedName

data class BiliQrCodeData(
    // 扫码跳转链接
    val url: String,
    // QrCode Key
    @SerializedName("qrcode_key")
    val qrcodeKey: String
)

data class BiliQrCodePollData(
    // 登录跳转url，未登录为空
    val url: String,
    // 刷新token，未登录为空
    @SerializedName("refresh_token")
    val refreshToken: String,
    // 登录时间戳（毫秒）
    val timestamp: Long,
    // 扫码状态码 0：扫码登录成功 86038：二维码已失效 86090：二维码已扫码未确认 86101：未扫码
    val code: Int,
    // 扫码状态描述
    val message: String
)