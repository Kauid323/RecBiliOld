package com.example.recbiliold.xposed

import org.json.JSONObject
import java.net.URLEncoder

internal object PlayurlFixerSigning {

    private const val APP_KEY_ANDROID = "1d8b6e7d45233436"
    private const val APP_SEC_ANDROID = "560c52ccd288fed045859ed18bffd973"

    @Volatile
    private var wbiKeyCache: Pair<String, String>? = null

    @Volatile
    private var wbiKeyCacheTsMs: Long = 0L

    private fun getWbiKeys(): Pair<String, String> {
        val now = System.currentTimeMillis()
        val cached = wbiKeyCache
        if (cached != null && now - wbiKeyCacheTsMs < 10 * 60 * 1000L) {
            return cached
        }

        val navJson = PlayurlFixerHttp.httpGet(
            "https://api.bilibili.com/x/web-interface/nav",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0",
                "Referer" to "https://www.bilibili.com",
            )
        )
        val jo = JSONObject(navJson)
        val data = jo.optJSONObject("data") ?: JSONObject()
        val wbiImg = data.optJSONObject("wbi_img") ?: JSONObject()
        val imgUrl = wbiImg.optString("img_url", "")
        val subUrl = wbiImg.optString("sub_url", "")

        val imgKey = imgUrl.substringAfterLast('/').substringBefore('.')
        val subKey = subUrl.substringAfterLast('/').substringBefore('.')

        if (imgKey.isBlank() || subKey.isBlank()) {
            throw IllegalStateException("missing wbi keys")
        }
        val out = imgKey to subKey
        wbiKeyCache = out
        wbiKeyCacheTsMs = now
        return out
    }

    fun signWbiParams(params: Map<String, String>): Map<String, String> {
        val (imgKey, subKey) = getWbiKeys()
        return encWbi(params, imgKey, subKey)
    }

    fun signAppParams(params: Map<String, String>): Map<String, String> {
        val mutable = params.toMutableMap()
        val appkey = mutable["appkey"]?.takeIf { it.isNotBlank() } ?: APP_KEY_ANDROID
        mutable["appkey"] = appkey
        mutable.remove("sign")

        val sorted = java.util.TreeMap<String, String>()
        for ((k, v) in mutable) {
            sorted[k] = v
        }

        val query = sorted.entries.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
        }
        val sign = PlayurlFixerUtil.md5Hex(query + APP_SEC_ANDROID)

        val out = sorted.toMutableMap()
        out["sign"] = sign
        return out
    }

    private fun encWbi(
        params: Map<String, String>,
        imgKey: String,
        subKey: String,
    ): Map<String, String> {
        val mixinKey = genMixinKey(imgKey + subKey)
        val wts = (System.currentTimeMillis() / 1000L).toString()

        val mutable = params.toMutableMap()
        mutable["wts"] = wts

        val filtered = mutable.mapValues { (_, v) ->
            v.filterNot { ch -> ch == '!' || ch == '\'' || ch == '(' || ch == ')' || ch == '*' }
        }

        val sorted = filtered.toSortedMap()
        val queryToSign = sorted.entries.joinToString("&") { (k, v) ->
            "${PlayurlFixerUtil.percentEncode(k)}=${PlayurlFixerUtil.percentEncode(v)}"
        }

        val wRid = PlayurlFixerUtil.md5Hex(queryToSign + mixinKey)

        val out = filtered.toMutableMap()
        out["w_rid"] = wRid
        out["wts"] = wts
        return out
    }

    private fun genMixinKey(rawWbiKey: String): String {
        val tab = intArrayOf(
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
            61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
            36, 20, 34, 44, 52
        )
        val sb = StringBuilder()
        for (i in tab) {
            if (i in rawWbiKey.indices) {
                sb.append(rawWbiKey[i])
            }
        }
        return if (sb.length > 32) sb.substring(0, 32) else sb.toString()
    }
}
