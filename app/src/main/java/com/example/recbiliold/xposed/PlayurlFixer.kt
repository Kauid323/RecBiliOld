package com.example.recbiliold.xposed

import de.robv.android.xposed.XposedBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicReference

object PlayurlFixer {
    private data class InterceptInfo(
        val legacyUrl: String,
        val legacyHeaders: MutableMap<String, String> = LinkedHashMap(),
        var responseBytes: ByteArray? = null,
    )

    private data class DurlInfo(
        val url: String,
        val timelength: Long?,
        val length: Long?,
        val size: Long?,
    )

    private val interceptMap = WeakHashMap<Any, InterceptInfo>()

    private val aidOverrideMap = WeakHashMap<Any, String>()

    private data class AidBvid(val aid: String?, val bvid: String?)

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

    @Volatile
    private var lastViewAidOrBvid: AidBvid? = null

    @Volatile
    private var appContext: android.content.Context? = null

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
        } catch (t: Throwable) {
            XposedBridge.log(t)
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

        val (imgKey, subKey) = getWbiKeys()
        val signedParams = encWbi(params, imgKey, subKey)

        return base + "?" + toQueryString(signedParams)
    }

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

        val navJson = httpGet(
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
            "${percentEncode(k)}=${percentEncode(v)}"
        }

        val wRid = md5Hex(queryToSign + mixinKey)

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

    private fun httpGet(url: String, headers: Map<String, String>): String {
        return httpGetWithCode(url, headers).second
    }

    private fun httpGetWithCode(url: String, headers: Map<String, String>): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 8000
            readTimeout = 10000
            // Ensure UA exists (some endpoints respond differently without it)
            if (!headers.keys.any { it.equals("User-Agent", ignoreCase = true) }) {
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
                )
            }
            for ((k, v) in headers) {
                setRequestProperty(k, v)
            }
        }

        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val sb = StringBuilder()
            if (stream != null) {
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { br ->
                    var line: String?
                    while (true) {
                        line = br.readLine() ?: break
                        sb.append(line)
                    }
                }
            }
            return code to sb.toString()
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGetBytesWithCode(url: String, headers: Map<String, String>): Pair<Int, ByteArray> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 8000
            readTimeout = 10000
            if (!headers.keys.any { it.equals("User-Agent", ignoreCase = true) }) {
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
                )
            }
            for ((k, v) in headers) {
                setRequestProperty(k, v)
            }
        }

        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.readBytes() ?: ByteArray(0)
            return code to bytes
        } finally {
            conn.disconnect()
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        val pairs = query.split('&')
        for (p in pairs) {
            if (p.isBlank()) continue
            val idx = p.indexOf('=')
            if (idx < 0) {
                out[p] = ""
            } else {
                val k = p.substring(0, idx)
                val v = p.substring(idx + 1)
                out[k] = v
            }
        }
        return out
    }

    private fun toQueryString(params: Map<String, String>): String {
        return params.entries.joinToString("&") { (k, v) ->
            "${percentEncode(k)}=${percentEncode(v)}"
        }
    }

    private fun percentEncode(s: String): String {
        val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
        val sb = StringBuilder()
        for (ch in s) {
            if (unreserved.indexOf(ch) >= 0) {
                sb.append(ch)
            } else {
                val bytes = ch.toString().toByteArray(Charsets.UTF_8)
                for (b in bytes) {
                    sb.append('%')
                    sb.append(((b.toInt() shr 4) and 0xF).toString(16).uppercase())
                    sb.append((b.toInt() and 0xF).toString(16).uppercase())
                }
            }
        }
        return sb.toString()
    }

    private fun md5Hex(s: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}
