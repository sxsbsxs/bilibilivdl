package cc.kafuu.bilidownload.common.network.service

import cc.kafuu.bilidownload.common.network.model.BiliQrCodeData
import cc.kafuu.bilidownload.common.network.model.BiliQrCodePollData
import cc.kafuu.bilidownload.common.network.model.BiliRespond
import com.google.gson.JsonObject
import retrofit2.Call
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query


interface BiliPassportService {
    @FormUrlEncoded
    @POST("login/exit/v2")
    fun requestLogout(@Field("biliCSRF") biliCSRF: String): Call<BiliRespond<JsonObject>>

    @GET("x/passport-login/web/qrcode/generate")
    fun generateQrCode(): Call<BiliRespond<BiliQrCodeData>>

    @GET("x/passport-login/web/qrcode/poll")
    fun pollQrCode(
        @Query("qrcode_key") key: String
    ): Call<BiliRespond<BiliQrCodePollData>>
}
