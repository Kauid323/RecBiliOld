package com.example.recbiliold.xposed

internal object PlayurlFixerCommentApi {

    fun fetchCommentWbiMain(
        oid: String,
        type: String,
        mode: String,
        ps: String?,
        nohot: String?,
    ): Pair<Int, String> {
        val baseParams = LinkedHashMap<String, String>()
        baseParams["oid"] = oid
        baseParams["type"] = type
        baseParams["mode"] = mode
        if (!ps.isNullOrBlank()) baseParams["ps"] = ps
        if (!nohot.isNullOrBlank()) baseParams["nohot"] = nohot

        val signed = PlayurlFixerSigning.signWbiParams(baseParams)
        val url = "https://api.bilibili.com/x/v2/reply/wbi/main?" + signed.entries.joinToString("&") { (k, v) ->
            PlayurlFixerUtil.percentEncode(k) + "=" + PlayurlFixerUtil.percentEncode(v)
        }

        val headers = linkedMapOf(
            "Accept" to "application/json",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36",
            "Referer" to "https://www.bilibili.com/",
            "Origin" to "https://www.bilibili.com",
        )

        return PlayurlFixerHttp.httpGetWithCode(url, headers)
    }

    fun fetchReplyLegacyMainListCloneAndResign(
        originalUrl: String,
        oid: String,
        pn: String?,
        ps: String?,
        sort: String?,
        nohot: String?,
    ): Pair<Int, String> {
        val base = originalUrl.substringBefore('?', originalUrl)
        val query0 = originalUrl.substringAfter('?', "")
        val qp = LinkedHashMap<String, String>()
        if (query0.isNotBlank()) {
            for (p in query0.split('&')) {
                if (p.isBlank()) continue
                val idx = p.indexOf('=')
                val k = if (idx >= 0) p.substring(0, idx) else p
                val v = if (idx >= 0) p.substring(idx + 1) else ""
                if (k.isBlank()) continue
                qp[k] = v
            }
        }

        qp["oid"] = oid
        if (!pn.isNullOrBlank()) qp["pn"] = pn
        if (!ps.isNullOrBlank()) qp["ps"] = ps
        if (!sort.isNullOrBlank()) qp["sort"] = sort
        if (!nohot.isNullOrBlank()) qp["nohot"] = nohot

        // Re-sign with appsec, ensure we are using the expected appkey.
        qp["appkey"] = qp["appkey"]?.takeIf { it.isNotBlank() } ?: "1d8b6e7d45233436"
        qp.remove("sign")
        val signed = PlayurlFixerSigning.signAppParams(qp)

        val url = (if (base.isBlank()) "https://api.bilibili.com/x/v2/reply" else base) + "?" + signed.entries.joinToString("&") { (k, v) ->
            "$k=$v"
        }

        val headers = linkedMapOf(
            "Accept" to "application/json",
            // Use a desktop UA is okay here; auth is via app params/sign.
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36",
            "Referer" to "https://www.bilibili.com/",
            "Origin" to "https://www.bilibili.com",
        )

        return PlayurlFixerHttp.httpGetWithCode(url, headers)
    }

    fun fetchReplyLegacyMainList(
        oid: String,
        type: String,
        pn: String?,
        ps: String?,
        sort: String?,
        nohot: String?,
    ): Pair<Int, String> {
        val qp = LinkedHashMap<String, String>()
        qp["oid"] = oid
        qp["type"] = type
        if (!pn.isNullOrBlank()) qp["pn"] = pn
        if (!ps.isNullOrBlank()) qp["ps"] = ps
        if (!sort.isNullOrBlank()) qp["sort"] = sort
        if (!nohot.isNullOrBlank()) qp["nohot"] = nohot

        val url = "https://api.bilibili.com/x/v2/reply?" + qp.entries.joinToString("&") { (k, v) ->
            PlayurlFixerUtil.percentEncode(k) + "=" + PlayurlFixerUtil.percentEncode(v)
        }

        val headers = linkedMapOf(
            "Accept" to "application/json",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36",
            "Referer" to "https://www.bilibili.com/",
            "Origin" to "https://www.bilibili.com",
        )

        return PlayurlFixerHttp.httpGetWithCode(url, headers)
    }

    fun fetchReplyReplyLegacy(
        oid: String,
        type: String,
        root: String,
        pn: String?,
        ps: String?,
    ): Pair<Int, String> {
        val qp = LinkedHashMap<String, String>()
        qp["oid"] = oid
        qp["type"] = type
        qp["root"] = root
        if (!pn.isNullOrBlank()) qp["pn"] = pn
        if (!ps.isNullOrBlank()) qp["ps"] = ps
        qp["sort"] = "1"

        val url = "https://api.bilibili.com/x/v2/reply/reply?" + qp.entries.joinToString("&") { (k, v) ->
            PlayurlFixerUtil.percentEncode(k) + "=" + PlayurlFixerUtil.percentEncode(v)
        }

        val headers = linkedMapOf(
            "Accept" to "application/json",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36",
            "Referer" to "https://www.bilibili.com/",
            "Origin" to "https://www.bilibili.com",
        )

        return PlayurlFixerHttp.httpGetWithCode(url, headers)
    }
}
