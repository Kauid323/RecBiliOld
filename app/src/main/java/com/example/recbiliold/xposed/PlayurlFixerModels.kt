package com.example.recbiliold.xposed

internal data class InterceptInfo(
    val legacyUrl: String,
    val legacyHeaders: MutableMap<String, String> = LinkedHashMap(),
    var responseBytes: ByteArray? = null,
)

internal data class CommentContext(
    val oid: String?,
    val rpid: String?,
    val type: String?,
    val ts: Long,
)

internal data class DurlInfo(
    val url: String,
    val timelength: Long?,
    val length: Long?,
    val size: Long?,
)

internal data class AidBvid(val aid: String?, val bvid: String?)
