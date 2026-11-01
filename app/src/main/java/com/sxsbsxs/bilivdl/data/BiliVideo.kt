package com.sxsbsxs.bilivdl.data

data class BiliVideo(
    val title: String,
    val cover: String,
    val author: String,
    val play: String,
    val duration: String,
    val bvid: String,
    val aid: Long,
    val description: String,
    val danmaku: Long? = null,
    val likes: Long? = null,
    val favorites: Long? = null,
    val coins: Long? = null,
    val shares: Long? = null,
    val comments: Long? = null,
    val category: String = "",
    val pubdate: Long? = null
) : java.io.Serializable