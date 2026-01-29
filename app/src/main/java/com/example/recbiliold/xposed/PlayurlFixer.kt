package com.example.recbiliold.xposed

import de.robv.android.xposed.XposedBridge
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.URI
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicReference

object PlayurlFixer {

    private const val APP_KEY_ANDROID = "1d8b6e7d45233436"

    private val interceptMap = WeakHashMap<Any, InterceptInfo>()

    private val aidOverrideMap = WeakHashMap<Any, String>()

    private val commentTotalCountCache: MutableMap<String, Long> = Collections.synchronizedMap(object : LinkedHashMap<String, Long>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > 400
        }
    })

    private val low32AidToFullAid: MutableMap<Long, String> = Collections.synchronizedMap(object : LinkedHashMap<Long, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?): Boolean {
            return size > 800
        }
    })

    private fun putLow32AidMapping(fullAid: String) {
        try {
            val a = fullAid.toLongOrNull() ?: return
            if (a <= 0L) return
            val low = a and 0xFFFF_FFFFL
            low32AidToFullAid[low] = a.toString()
        } catch (_: Throwable) {
        }
    }

    fun seedLow32AidMappingFromFullAid(fullAid: String?) {
        try {
            val s = fullAid?.trim()?.takeIf { it.isNotBlank() } ?: return
            putLow32AidMapping(s)
        } catch (_: Throwable) {
        }
    }

    private fun avToBv(aid: Long): String? {
        return try {
            if (aid <= 0L) return null
            val xorCode = 23442827791579L
            val maxAid = 1L shl 51
            val base = 58L
            val table = "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf"

            val bytes = CharArray(12)
            bytes[0] = 'B'
            bytes[1] = 'V'
            bytes[2] = '1'
            for (i in 3 until 12) bytes[i] = '0'

            var tmp = (maxAid or aid) xor xorCode
            var idx = 11
            while (tmp > 0 && idx >= 3) {
                val c = table[(tmp % base).toInt()]
                bytes[idx] = c
                tmp /= base
                idx -= 1
            }

            val t39 = bytes[3]
            bytes[3] = bytes[9]
            bytes[9] = t39

            val t47 = bytes[4]
            bytes[4] = bytes[7]
            bytes[7] = t47

            String(bytes)
        } catch (_: Throwable) {
            null
        }
    }

    fun setLastCommentContext(oid: String?, rpid: String?, type: String?) {
        try {
            val o = oid?.trim()?.takeIf { it.isNotBlank() }
            val r = rpid?.trim()?.takeIf { it.isNotBlank() }
            val t = type?.trim()?.takeIf { it.isNotBlank() }
            if (o == null && r == null && t == null) return
            lastCommentContextRef.set(CommentContext(o, r, t, System.currentTimeMillis()))
            // If oid looks like a positive 64-bit aid, also seed mapping.
            val n = o?.toLongOrNull()
            if (n != null && n > Int.MAX_VALUE.toLong()) {
                putLow32AidMapping(n.toString())
            }
        } catch (_: Throwable) {
        }
    }

    fun getLastCommentContextOid(): String? {
        return try { lastCommentContextRef.get()?.oid } catch (_: Throwable) { null }
    }

    fun getLastCommentContextRpid(): String? {
        return try { lastCommentContextRef.get()?.rpid } catch (_: Throwable) { null }
    }

    fun getLastCommentContextType(): String? {
        return try { lastCommentContextRef.get()?.type } catch (_: Throwable) { null }
    }

    fun putCommentTotalCount(oid: String?, type: String?, totalCount: Long?) {
        try {
            val o = oid?.trim()?.takeIf { it.isNotBlank() } ?: return
            val t = type?.trim()?.takeIf { it.isNotBlank() } ?: return
            val c = totalCount ?: return
            if (c <= 0L) return
            commentTotalCountCache["$t:$o"] = c
        } catch (_: Throwable) {
        }
    }

    fun getCommentTotalCount(oid: String?, type: String?): Long? {
        return try {
            val o = oid?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val t = type?.trim()?.takeIf { it.isNotBlank() } ?: return null
            commentTotalCountCache["$t:$o"]
        } catch (_: Throwable) {
            null
        }
    }

    @Volatile
    private var lastViewAidOrBvid: AidBvid? = null

    @Volatile
    private var lastClickedAid: String? = null

    @Volatile
    private var appContext: android.content.Context? = null

    private val lastCommentContextRef = AtomicReference<CommentContext?>()

    fun isVerboseNetworkEnabled(): Boolean {
        return try {
            val ctx = appContext ?: return false
            val mode = try {
                android.content.Context.MODE_PRIVATE or android.content.Context.MODE_MULTI_PROCESS
            } catch (_: Throwable) {
                android.content.Context.MODE_PRIVATE
            }
            val sp = ctx.getSharedPreferences("recbiliold_cache", mode)
            sp.getBoolean("verbose_network", false)
        } catch (_: Throwable) {
            false
        }
    }

    fun shouldLogNetworkUrl(url: String?): Boolean {
        return !url.isNullOrBlank()
    }

    fun setAppContext(ctx: android.content.Context?) {
        if (ctx == null) return
        appContext = ctx.applicationContext ?: ctx
    }

    fun setLastClickedAid(aid: String?) {
        val v = aid?.trim()?.takeIf { it.isNotBlank() } ?: return
        val n = v.toLongOrNull() ?: return
        if (n <= 0L) return
        lastClickedAid = n.toString()
        putLow32AidMapping(lastClickedAid!!)
        try {
            val ctx = appContext ?: return
            val mode = try {
                android.content.Context.MODE_PRIVATE or android.content.Context.MODE_MULTI_PROCESS
            } catch (_: Throwable) {
                android.content.Context.MODE_PRIVATE
            }
            val sp = ctx.getSharedPreferences("recbiliold_cache", mode)
            sp.edit().putString("last_clicked_aid", lastClickedAid).commit()
        } catch (_: Throwable) {
        }
    }

    fun getLastClickedAid(): String? {
        lastClickedAid?.let { return it }
        return try {
            val ctx = appContext ?: return null
            val mode = try {
                android.content.Context.MODE_PRIVATE or android.content.Context.MODE_MULTI_PROCESS
            } catch (_: Throwable) {
                android.content.Context.MODE_PRIVATE
            }
            val sp = ctx.getSharedPreferences("recbiliold_cache", mode)
            sp.getString("last_clicked_aid", null)?.also { lastClickedAid = it }
        } catch (_: Throwable) {
            null
        }
    }

    private fun putPersistedAidBvid(aid: String?, bvid: String?) {
        try {
            val ctx = appContext
            if (ctx == null) {
                XposedBridge.log("RecBiliOld: putPersistedAidBvid ctx=null")
                return
            }
            val mode = try {
                android.content.Context.MODE_PRIVATE or android.content.Context.MODE_MULTI_PROCESS
            } catch (_: Throwable) {
                android.content.Context.MODE_PRIVATE
            }
            val sp = ctx.getSharedPreferences("recbiliold_cache", mode)
            sp.edit()
                .putString("last_aid", aid)
                .putString("last_bvid", bvid)
                .commit()
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    private fun getPersistedAidBvid(): AidBvid? {
        return try {
            val ctx = appContext ?: return null
            val mode = try {
                android.content.Context.MODE_PRIVATE or android.content.Context.MODE_MULTI_PROCESS
            } catch (_: Throwable) {
                android.content.Context.MODE_PRIVATE
            }
            val sp = ctx.getSharedPreferences("recbiliold_cache", mode)
            val aid = sp.getString("last_aid", null)
            val bvid = sp.getString("last_bvid", null)
            if (aid.isNullOrBlank() && bvid.isNullOrBlank()) {
                return null
            } else {
                XposedBridge.log("RecBiliOld: read persisted aid/bvid aid=${aid ?: "<null>"} bvid=${bvid ?: "<null>"}")
                AidBvid(aid = aid, bvid = bvid)
            }
        } catch (t: Throwable) {
            XposedBridge.log(t)
            null
        }
    }

    private fun putPersistedCidMapping(cid: String, aid: String?, bvid: String?) {
        try {
            if (cid.isBlank()) return
            val ctx = appContext ?: return
            val mode = try {
                android.content.Context.MODE_PRIVATE or android.content.Context.MODE_MULTI_PROCESS
            } catch (_: Throwable) {
                android.content.Context.MODE_PRIVATE
            }
            val sp = ctx.getSharedPreferences("recbiliold_cache", mode)
            val v = "${aid ?: ""}|${bvid ?: ""}"
            sp.edit().putString("cid2_$cid", v).commit()
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    private fun getPersistedCidMapping(cid: String): AidBvid? {
        return try {
            if (cid.isBlank()) return null
            val ctx = appContext ?: return null
            val mode = try {
                android.content.Context.MODE_PRIVATE or android.content.Context.MODE_MULTI_PROCESS
            } catch (_: Throwable) {
                android.content.Context.MODE_PRIVATE
            }
            val sp = ctx.getSharedPreferences("recbiliold_cache", mode)
            val v = sp.getString("cid2_$cid", null) ?: return null
            val parts = v.split('|', limit = 2)
            val aid = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
            val bvid = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
            if (aid.isNullOrBlank() && bvid.isNullOrBlank()) return null
            else AidBvid(aid = aid, bvid = bvid)
        } catch (t: Throwable) {
            XposedBridge.log(t)
            null
        }
    }

    fun getPersistedAidBvidForJump(): Pair<String?, String?>? {
        val v = getPersistedAidBvid() ?: return null
        return v.aid to v.bvid
    }

    private val cidToAidBvid: MutableMap<String, AidBvid> = Collections.synchronizedMap(object : LinkedHashMap<String, AidBvid>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AidBvid>?): Boolean {
            return size > 500
        }
    })

    private val legacyCidOverrideToRealCid: MutableMap<String, String> = Collections.synchronizedMap(object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > 500
        }
    })

    private fun putPersistedLegacyCidOverride(legacyCid: String, realCid: String) {
        try {
            if (legacyCid.isBlank() || realCid.isBlank()) return
            val ctx = appContext ?: return
            val mode = try {
                android.content.Context.MODE_PRIVATE or android.content.Context.MODE_MULTI_PROCESS
            } catch (_: Throwable) {
                android.content.Context.MODE_PRIVATE
            }
            val sp = ctx.getSharedPreferences("recbiliold_cache", mode)
            sp.edit().putString("cidOverride_$legacyCid", realCid).commit()
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    private fun getPersistedLegacyCidOverride(legacyCid: String): String? {
        return try {
            if (legacyCid.isBlank()) return null
            val ctx = appContext ?: return null
            val mode = try {
                android.content.Context.MODE_PRIVATE or android.content.Context.MODE_MULTI_PROCESS
            } catch (_: Throwable) {
                android.content.Context.MODE_PRIVATE
            }
            val sp = ctx.getSharedPreferences("recbiliold_cache", mode)
            sp.getString("cidOverride_$legacyCid", null)
        } catch (t: Throwable) {
            XposedBridge.log(t)
            null
        }
    }

    fun resolveCidOverrideForDanmaku(cid: String): String? {
        if (cid.isBlank()) return null
        return legacyCidOverrideToRealCid[cid] ?: getPersistedLegacyCidOverride(cid)
    }

    private fun isViewApiUrl(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("/x/v2/view")
    }

    fun ingestVideoViewResponse(url: String, body: String) {
        try {
            if (!isViewApiUrl(url)) return
            val root = JSONObject(body)
            val data = root.optJSONObject("data") ?: return

            val aid = data.optLong("aid").takeIf { it > 0 }?.toString()
            val bvid = data.optString("bvid").takeIf { !it.isNullOrBlank() }

            val pages = data.optJSONArray("pages")
            if (pages != null) {
                for (i in 0 until pages.length()) {
                    val p = pages.optJSONObject(i) ?: continue
                    val cid = p.optLong("cid", 0L)
                    if (cid <= 0L) continue
                    val key = cid.toString()
                    val v = AidBvid(aid = aid, bvid = bvid)
                    cidToAidBvid[key] = v
                    putPersistedCidMapping(key, aid, bvid)
                }
            }

            val mainCid = data.optLong("cid").takeIf { it > 0 }?.toString()
            if (!mainCid.isNullOrBlank()) {
                cidToAidBvid[mainCid] = AidBvid(aid = aid, bvid = bvid)
            }

            if (!aid.isNullOrBlank() || !bvid.isNullOrBlank()) {
                lastViewAidOrBvid = AidBvid(aid = aid, bvid = bvid)
                putPersistedAidBvid(aid, bvid)
            }

            XposedBridge.log("RecBiliOld: ingest view ok aid=${aid ?: "<null>"} bvid=${bvid ?: "<null>"} pages=${pages?.length() ?: -1}")
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    fun ingestAidMappingsFromListJson(url: String, body: String) {
        try {
            if (body.isBlank()) return
            val t = body.trimStart()
            if (!t.startsWith("{")) return
            if (!t.contains("\"param\"") && !t.contains("bilibili://video/")) return

            val root = JSONObject(t)
            if (root.optInt("code", 0) != 0) return

            fun handleUri(s: String?) {
                if (s.isNullOrBlank()) return
                val m = Regex("(?i)bilibili://video/(\\d+)").find(s)
                val aid = m?.groupValues?.getOrNull(1) ?: return
                putLow32AidMapping(aid)
            }

            fun handleParam(s: String?) {
                if (s.isNullOrBlank()) return
                if (!s.all { it.isDigit() }) return
                putLow32AidMapping(s)
            }

            fun visit(obj: Any?) {
                when (obj) {
                    is JSONObject -> {
                        try {
                            handleParam(obj.optString("param", null))
                            handleUri(obj.optString("uri", null))
                        } catch (_: Throwable) {
                        }
                        val it = obj.keys()
                        while (it.hasNext()) {
                            val k = it.next()
                            visit(obj.opt(k))
                        }
                    }
                    is JSONArray -> {
                        for (i in 0 until obj.length()) {
                            visit(obj.opt(i))
                        }
                    }
                }
            }

            visit(root)
        } catch (_: Throwable) {
        }
    }

    private fun resolveCidToAidBvid(cid: String): AidBvid? {
        if (cid.isBlank()) return null

        cidToAidBvid[cid]?.let { return it }
        lastViewAidOrBvid?.let { return it }
        getPersistedCidMapping(cid)?.let {
            XposedBridge.log("RecBiliOld: resolve cid=$cid via persisted mapping aid=${it.aid ?: "<null>"} bvid=${it.bvid ?: "<null>"}")
            return it
        }
        getPersistedAidBvid()?.let { return it }
        return null
    }

    private fun extractDurlInfoFromWbiPlayurl(json: String): DurlInfo? {
        return try {
            val jo = JSONObject(json)
            val data = jo.optJSONObject("data") ?: return null
            val timelength = try {
                if (data.has("timelength")) data.optLong("timelength") else null
            } catch (_: Throwable) {
                null
            }

            val durl = data.optJSONArray("durl")
            if (durl != null && durl.length() > 0) {
                val first = durl.optJSONObject(0)
                val url = first?.optString("url")
                if (!url.isNullOrBlank()) {
                    val length = try {
                        if (first.has("length")) first.optLong("length") else null
                    } catch (_: Throwable) {
                        null
                    }
                    val size = try {
                        if (first.has("size")) first.optLong("size") else null
                    } catch (_: Throwable) {
                        null
                    }
                    return DurlInfo(url = url, timelength = timelength, length = length, size = size)
                }
            }

            val dash = data.optJSONObject("dash")
            val video = dash?.optJSONArray("video")
            if (video != null && video.length() > 0) {
                val first = video.optJSONObject(0)
                val baseUrl = first?.optString("baseUrl")
                if (!baseUrl.isNullOrBlank()) {
                    return DurlInfo(url = baseUrl, timelength = timelength, length = timelength, size = null)
                }
            }

            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun fetchAndCacheViewByAid(aid: String, headers: Map<String, String>) {
        try {
            val aidLong = aid.toLongOrNull() ?: return
            if (aidLong <= 0L) return

            // Prefer app view API (no WBI), as this is what the app itself uses.
            val url = "https://app.bilibili.com/x/v2/view?aid=${percentEncode(aid)}&from=7&plat=0"
            val h = LinkedHashMap(headers)
            h.putIfAbsent(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
            )
            XposedBridge.log("RecBiliOld: fetch view by aid -> $url")
            val (code, body) = httpGetWithCode(url, h)
            if (code !in 200..299 || body.isBlank()) {
                XposedBridge.log("RecBiliOld: fetch view by aid failed http=$code body=${body.take(200)}")
                return
            }
            ingestVideoViewResponse(url, body)
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    private fun fetchAndCachePagelistByBvid(bvid: String, aid: String?, headers: Map<String, String>): List<String> {
        try {
            if (bvid.isBlank()) return emptyList()
            val url = "https://api.bilibili.com/x/player/pagelist?bvid=${percentEncode(bvid)}"
            val h = LinkedHashMap(headers)
            h["User-Agent"] =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
            XposedBridge.log("RecBiliOld: fetch pagelist -> $url")
            val (code, body) = httpGetWithCode(url, h)
            if (code !in 200..299 || body.isBlank()) {
                XposedBridge.log("RecBiliOld: fetch pagelist failed http=$code body=${body.take(200)}")
                return emptyList()
            }
            XposedBridge.log("RecBiliOld: fetch pagelist resp http=$code body=${body.take(300)}")
            val jo = JSONObject(body)
            val data = jo.optJSONArray("data") ?: return emptyList()
            val cids = ArrayList<String>(data.length())
            for (i in 0 until data.length()) {
                val it = data.optJSONObject(i) ?: continue
                val cid = it.optLong("cid", 0L)
                if (cid <= 0L) continue
                val key = cid.toString()
                cids.add(key)
                val v = AidBvid(aid = aid, bvid = bvid)
                cidToAidBvid[key] = v
                putPersistedCidMapping(key, aid, bvid)
            }
            XposedBridge.log("RecBiliOld: fetch pagelist ok bvid=$bvid items=${data.length()}")
            return cids
        } catch (t: Throwable) {
            XposedBridge.log(t)
            return emptyList()
        }
    }

    fun ingestVideoViewRequest(url: String) {
        try {
            if (!isViewApiUrl(url)) return
            val query = try {
                URI(url).rawQuery.orEmpty()
            } catch (_: Throwable) {
                url.substringAfter('?', "")
            }
            val params = parseQuery(query)
            val aid = params["aid"]?.takeIf { it.isNotBlank() }
            val bvid = params["bvid"]?.takeIf { it.isNotBlank() }
            if (aid.isNullOrBlank() && bvid.isNullOrBlank()) {
                XposedBridge.log("RecBiliOld: ingest view url=$url has no aid/bvid params")
                return
            }

            val placeholder = Int.MAX_VALUE.toString()
            if (bvid.isNullOrBlank() && aid == placeholder) {
                XposedBridge.log("RecBiliOld: ingest view params aid=$aid (placeholder) ignored url=$url")
                return
            }

            lastViewAidOrBvid = AidBvid(aid = aid, bvid = bvid)
            XposedBridge.log("RecBiliOld: ingest view params aid=${aid ?: "<null>"} bvid=${bvid ?: "<null>"} url=$url")
            putPersistedAidBvid(aid = aid, bvid = bvid)

            try {
                val a = aid?.toLongOrNull()
                if (a != null && a > 0) {
                    val key = (a and 0xFFFF_FFFFL)
                    low32AidToFullAid[key] = a.toString()
                }
            } catch (_: Throwable) {
            }
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    fun resolveFullAidByLow32(oidOrLow32: String?): String? {
        if (oidOrLow32.isNullOrBlank()) return null
        return try {
            val n = oidOrLow32.toLongOrNull() ?: return null
            // Accept negative values (signed32 overflow). Always normalize to low32 key.
            val key = (n and 0xFFFF_FFFFL)
            if (key == 0L) return null
            low32AidToFullAid[key]
        } catch (_: Throwable) {
            null
        }
    }

    fun ingestOversizedAid(aid: String) {
        if (aid.isBlank()) return
        lastViewAidOrBvid = AidBvid(aid = aid, bvid = null)
        putPersistedAidBvid(aid = aid, bvid = null)
    }

    fun registerAidOverrideToken(token: Any, realAid: String) {
        aidOverrideMap[token] = realAid
    }

    fun consumeAidOverrideToken(token: Any): String? {
        return aidOverrideMap.remove(token)
    }

    fun getAidOverrideToken(token: Any): String? {
        return aidOverrideMap[token]
    }

    fun isLegacyPlayurlUrl(url: String): Boolean {
        return url.startsWith("https://interface.bilibili.com/playurl?", ignoreCase = true) ||
            url.startsWith("https://app.bilibili.com/playurl?", ignoreCase = true)
    }

    fun markIntercept(wrapper: Any, legacyUrl: String) {
        val existing = interceptMap[wrapper]
        if (existing != null) {
            if (!existing.legacyUrl.equals(legacyUrl, ignoreCase = false)) {
                // Wrapper objects can be reused across requests. Clear stale cached state to avoid replaying
                // the previous video's playurl response.
                interceptMap[wrapper] = InterceptInfo(legacyUrl = legacyUrl)
            }
            return
        }
        interceptMap[wrapper] = InterceptInfo(legacyUrl = legacyUrl)
    }

    fun onLegacyRequestHeader(wrapper: Any, key: String, value: String) {
        val info = interceptMap[wrapper] ?: return
        info.legacyHeaders[key] = value
    }

    fun shouldIntercept(wrapper: Any): Boolean {
        return interceptMap.containsKey(wrapper)
    }

    fun buildLegacyPlayurlResponseBytes(wrapper: Any): ByteArray {
        val info = interceptMap[wrapper] ?: return "".toByteArray(Charsets.UTF_8)
        info.responseBytes?.let { return it }

        val bytes = try {
            val legacyQuery = try {
                URI(info.legacyUrl).rawQuery.orEmpty()
            } catch (_: Throwable) {
                info.legacyUrl.substringAfter('?', "")
            }
            val legacyParams = parseQuery(legacyQuery)
            val cid = legacyParams["cid"]
            val qnStr = legacyParams["qn"] ?: legacyParams["quality"]
            val qn = qnStr?.toIntOrNull() ?: 2

            if (cid.isNullOrBlank()) {
                buildLegacyJson(durlUrl = null, qn = qn)
            } else {
                val passthroughHeaders = buildPassthroughHeaders(info)
                var cidForPlayurl = cid
                val cached = cidToAidBvid[cid]
                val (bvid, aid) = if (cached != null && (!cached.bvid.isNullOrBlank() || !cached.aid.isNullOrBlank())) {
                    cached.bvid to cached.aid
                } else {
                    val mapped = getPersistedCidMapping(cid)
                    if (mapped != null && (!mapped.bvid.isNullOrBlank() || !mapped.aid.isNullOrBlank())) {
                        XposedBridge.log(
                            "RecBiliOld: resolve cid=$cid via persisted mapping aid=${mapped.aid ?: "<null>"} bvid=${mapped.bvid ?: "<null>"}"
                        )
                        mapped.bvid to mapped.aid
                    } else {
                        // Don't pair CID with unrelated last-view video (will cause playurl -404).
                        // Instead, rebuild mappings via bvid pagelist (no WBI) using the most recent video identity.
                        val last = getPersistedAidBvid() ?: lastViewAidOrBvid
                        val lastAid = last?.aid
                        val lastBvid = last?.bvid
                        val derivedBvid = if (!lastBvid.isNullOrBlank()) {
                            lastBvid
                        } else {
                            val a = lastAid?.toLongOrNull()
                            if (a != null && a > 0L) avToBv(a) else null
                        }

                        var pagelistCids: List<String> = emptyList()
                        if (!derivedBvid.isNullOrBlank()) {
                            pagelistCids = fetchAndCachePagelistByBvid(derivedBvid, aid = lastAid, headers = passthroughHeaders)
                        } else if (!lastAid.isNullOrBlank()) {
                            // fallback: still try view-by-aid to populate mapping
                            fetchAndCacheViewByAid(lastAid, headers = passthroughHeaders)
                        }

                        val after = cidToAidBvid[cid] ?: getPersistedCidMapping(cid)
                        if (after != null && (!after.bvid.isNullOrBlank() || !after.aid.isNullOrBlank())) {
                            XposedBridge.log(
                                "RecBiliOld: cid=$cid mapping rebuilt -> aid=${after.aid ?: "<null>"} bvid=${after.bvid ?: "<null>"}"
                            )
                            after.bvid to after.aid
                        } else {
                            // Some builds appear to supply a different (legacy) cid while pagelist returns the real video cid.
                            // If pagelist returned any cid(s), prefer the first cid for playurl.
                            if (pagelistCids.isNotEmpty() && !derivedBvid.isNullOrBlank()) {
                                cidForPlayurl = pagelistCids[0]
                                legacyCidOverrideToRealCid[cid] = cidForPlayurl
                                putPersistedLegacyCidOverride(legacyCid = cid, realCid = cidForPlayurl)
                                XposedBridge.log(
                                    "RecBiliOld: use pagelist cid override legacyCid=$cid -> realCid=$cidForPlayurl bvid=$derivedBvid"
                                )
                                derivedBvid to lastAid
                            } else {
                                XposedBridge.log("RecBiliOld: cid=$cid no mapping after rebuild, skip bvid/aid")
                                null to null
                            }
                        }
                    }
                }

                if (bvid.isNullOrBlank() && aid.isNullOrBlank()) {
                    XposedBridge.log("RecBiliOld: cannot resolve cid mapping; legacy playurl is disabled cid=$cid")
                    buildLegacyJson(durlUrl = null, qn = qn)
                } else {
                    val newUrl = buildNewPlayurlUrl(cid = cidForPlayurl, qn = qn, bvid = bvid, aid = aid)
                    XposedBridge.log("RecBiliOld: new playurl -> $newUrl")

                    val (code, body) = httpGetWithCode(newUrl, headers = passthroughHeaders)
                    if (code !in 200..299) {
                        XposedBridge.log("RecBiliOld: new playurl http=$code body=${body.take(300)}")
                    }

                    val durlInfo = extractDurlInfoFromWbiPlayurl(body)
                    val durlUrl = durlInfo?.url
                    XposedBridge.log("RecBiliOld: extracted url=${durlUrl ?: "<null>"}")
                    if (durlUrl.isNullOrBlank()) {
                        XposedBridge.log("RecBiliOld: extracted url null; legacy playurl is disabled cid=$cid")
                        buildLegacyJson(durlUrl = null, qn = qn)
                    } else {
                        lastExtractedPlayableUrlRef.set(durlUrl)
                        buildLegacyJson(
                            durlUrl = durlUrl,
                            qn = qn,
                            timelength = durlInfo?.timelength,
                            length = durlInfo?.length,
                            size = durlInfo?.size,
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log(t)
            buildLegacyJson(durlUrl = null, qn = 2)
        }

        try {
            XposedBridge.log("RecBiliOld: served legacy playurl json prefix=${String(bytes, Charsets.UTF_8).take(220)}")
        } catch (_: Throwable) {
        }

        info.responseBytes = bytes
        return bytes
    }

    fun getLastExtractedPlayableUrl(): String? {
        return lastExtractedPlayableUrlRef.get()?.takeIf { it.isNotBlank() }
    }

    private fun buildLegacyJson(
        durlUrl: String?,
        qn: Int,
        timelength: Long? = null,
        length: Long? = null,
        size: Long? = null,
    ): ByteArray {
        val ok = !durlUrl.isNullOrBlank()
        val quality = if (qn > 0) qn else 2

        val tl = timelength?.takeIf { it > 0 } ?: 0L
        val dl = length?.takeIf { it > 0 } ?: 0L
        val ds = size?.takeIf { it > 0 } ?: 0L

        val durlArr = JSONArray()
        if (ok) {
            val durlItem = JSONObject()
            durlItem.put("order", 1)
            durlItem.put("length", dl)
            durlItem.put("size", ds)
            durlItem.put("url", durlUrl)
            durlItem.put("backup_url", JSONArray())
            durlArr.put(durlItem)
        }

        // Classic legacy playurl schema (what many clients actually parse)
        val legacy = JSONObject()
        legacy.put("quality", quality)
        legacy.put("format", "mp4")
        legacy.put("timelength", tl)
        legacy.put("accept_format", "mp4")
        legacy.put("accept_description", JSONArray().put("unknown"))
        legacy.put("accept_quality", JSONArray().put(quality))
        legacy.put("video_codecid", 7)
        legacy.put("seek_param", "start")
        legacy.put("seek_type", "second")
        legacy.put("durl", durlArr)

        val supportFormats = JSONArray()
        val sf = JSONObject()
        sf.put("quality", quality)
        sf.put("format", "mp4")
        sf.put("new_description", "unknown")
        sf.put("display_desc", "unknown")
        sf.put("superscript", "")
        supportFormats.put(sf)
        legacy.put("support_formats", supportFormats)

        // Also include wrapper schema to satisfy newer parsers that expect code/message/ttl/data
        legacy.put("code", if (ok) 0 else -404)
        legacy.put("message", if (ok) "0" else "not found")
        legacy.put("ttl", 1)
        val data = JSONObject()
        data.put("from", "local")
        data.put("result", if (ok) "suee" else "error")
        data.put("quality", quality)
        data.put("format", "mp4")
        data.put("timelength", tl)
        data.put("accept_format", "mp4")
        data.put("accept_description", JSONArray().put("unknown"))
        data.put("accept_quality", JSONArray().put(quality))
        data.put("video_codecid", 7)
        data.put("seek_param", "start")
        data.put("seek_type", "second")
        data.put("durl", durlArr)
        data.put("support_formats", supportFormats)
        legacy.put("data", data)

        return legacy.toString().toByteArray(Charsets.UTF_8)
    }

    private val lastExtractedPlayableUrlRef = AtomicReference<String?>(null)

    private fun buildNewPlayurlUrl(
        cid: String,
        qn: Int,
        bvid: String?,
        aid: String?,
    ): String {
        val base = "https://api.bilibili.com/x/player/wbi/playurl"

        val params = linkedMapOf(
            "cid" to cid,
            "fnval" to "0",
            "fnver" to "0",
            "fourk" to "0",
            "otype" to "json",
        )
        val aidLong = aid?.toLongOrNull()
        val bvidFixed = if (!bvid.isNullOrBlank()) {
            bvid
        } else if (aidLong != null && aidLong > 0) {
            avToBv(aidLong)
        } else {
            null
        }
        if (!bvidFixed.isNullOrBlank()) {
            params["bvid"] = bvidFixed
        } else if (!aid.isNullOrBlank()) {
            params["avid"] = aid
        }
        if (qn > 0) {
            params["qn"] = qn.toString()
        }

        val signedParams = PlayurlFixerSigning.signWbiParams(params)
        return base + "?" + toQueryString(signedParams)
    }

    fun signWbiParams(params: Map<String, String>): Map<String, String> {
        return PlayurlFixerSigning.signWbiParams(params)
    }

    fun fetchCommentWbiMain(
        oid: String,
        type: String,
        mode: String,
        ps: String?,
        nohot: String?,
    ): Pair<Int, String> {
        return PlayurlFixerCommentApi.fetchCommentWbiMain(oid, type, mode, ps, nohot)
    }

    fun fetchReplyLegacyMainListCloneAndResign(
        originalUrl: String,
        oid: String,
        pn: String?,
        ps: String?,
        sort: String?,
        nohot: String?,
    ): Pair<Int, String> {
        return PlayurlFixerCommentApi.fetchReplyLegacyMainListCloneAndResign(originalUrl, oid, pn, ps, sort, nohot)
    }

    fun fetchReplyLegacyMainList(
        oid: String,
        type: String,
        pn: String?,
        ps: String?,
        sort: String?,
        nohot: String?,
    ): Pair<Int, String> {
        return PlayurlFixerCommentApi.fetchReplyLegacyMainList(oid, type, pn, ps, sort, nohot)
    }

    fun fetchReplyReplyLegacy(
        oid: String,
        type: String,
        root: String,
        pn: String?,
        ps: String?,
    ): Pair<Int, String> {
        return PlayurlFixerCommentApi.fetchReplyReplyLegacy(oid, type, root, pn, ps)
    }

    private fun extractPlayableUrlFromWbiPlayurl(json: String): String? {
        val jo = JSONObject(json)
        val data = jo.optJSONObject("data") ?: return null

        val durl = data.optJSONArray("durl")
        if (durl != null && durl.length() > 0) {
            val first = durl.optJSONObject(0)
            val url = first?.optString("url")
            if (!url.isNullOrBlank()) return url
        }

        val dash = data.optJSONObject("dash")
        val video = dash?.optJSONArray("video")
        if (video != null && video.length() > 0) {
            val first = video.optJSONObject(0)
            val baseUrl = first?.optString("baseUrl")
            if (!baseUrl.isNullOrBlank()) return baseUrl
        }

        return null
    }

    private fun buildPassthroughHeaders(info: InterceptInfo): Map<String, String> {
        val out = LinkedHashMap<String, String>()

        val legacyUa = info.legacyHeaders.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
        val legacyCookie = info.legacyHeaders.entries.firstOrNull { it.key.equals("Cookie", ignoreCase = true) }?.value

        out["User-Agent"] = legacyUa?.takeIf { it.isNotBlank() } ?: "Mozilla/5.0"
        out["Referer"] = "https://www.bilibili.com/"
        if (!legacyCookie.isNullOrBlank()) {
            out["Cookie"] = legacyCookie
        }

        return out
    }

    private fun httpGetWithCode(url: String, headers: Map<String, String>): Pair<Int, String> {
        return PlayurlFixerHttp.httpGetWithCode(url, headers)
    }

    private fun parseQuery(query: String): Map<String, String> {
        return PlayurlFixerUtil.parseQuery(query)
    }

    private fun toQueryString(params: Map<String, String>): String {
        return PlayurlFixerUtil.toQueryString(params)
    }

    private fun percentEncode(s: String): String {
        return PlayurlFixerUtil.percentEncode(s)
    }

    private fun md5Hex(s: String): String {
        return PlayurlFixerUtil.md5Hex(s)
    }
}
