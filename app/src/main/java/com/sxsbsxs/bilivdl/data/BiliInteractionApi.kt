package com.sxsbsxs.bilivdl.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class VideoComment(val user:String,val text:String,val likes:Int)

object BiliInteractionApi {
    private fun request(url:String,cookie:String=""):String {
        val c=(URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout=15000; readTimeout=18000
            setRequestProperty("User-Agent",BiliHeaders.UA); setRequestProperty("Referer","https://www.bilibili.com/")
            setRequestProperty("Accept","application/json, text/plain, */*")
            if(cookie.isNotBlank()) setRequestProperty("Cookie",cookie)
        }
        return try {
            val stream=if(c.responseCode in 200..299)c.inputStream else c.errorStream
            stream?.bufferedReader()?.use{it.readText()}.orEmpty()
        } finally { c.disconnect() }
    }
    fun comments(aid:Long,cookie:String=""):List<VideoComment> {
        if(aid<=0) error("视频编号无效，无法加载评论")
        val unique=linkedMapOf<String,VideoComment>()
        for(page in 1..5) {
            val root=JSONObject(request("https://api.bilibili.com/x/v2/reply?type=1&oid=$aid&pn=$page&ps=20&sort=0&nohot=1",cookie))
            if(root.optInt("code")!=0) {
                val code=root.optInt("code")
                if(page==1) error(if(code == -352) "评论接口被 B站风控拦截（-352），请登录后重试" else root.optString("message","评论接口暂不可用"))
                break
            }
            val data=root.optJSONObject("data")?:break
            val arr=data.optJSONArray("replies")?:break
            if(arr.length()==0) break
            for(i in 0 until arr.length()) {
                val item=arr.optJSONObject(i)?:continue
                val member=item.optJSONObject("member"); val content=item.optJSONObject("content")
                val key=item.optString("rpid","$page-$i")
                unique[key]=VideoComment(member?.optString("uname")?:"用户",content?.optString("message")?:"",item.optInt("like"))
            }
            if(unique.size>=100||arr.length()<20) break
        }
        return unique.values.toList()
    }
    fun danmaku(cid:Long,cookie:String=""):List<String> {
        val xml=request("https://api.bilibili.com/x/v1/dm/list.so?oid=$cid",cookie)
        if(xml.isBlank()) error("弹幕接口没有返回内容")
        return Regex("<d\\s+p=\"[^\"]+\">(.*?)</d>",setOf(RegexOption.DOT_MATCHES_ALL)).findAll(xml).take(100)
            .map{it.groupValues[1].replace("&amp;","&").replace("&lt;","<").replace("&gt;",">")}.toList()
    }
}
