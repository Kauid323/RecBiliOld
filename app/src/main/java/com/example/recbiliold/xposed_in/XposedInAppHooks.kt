package com.example.recbiliold.xposed_in

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.example.recbiliold.xposed.PlayurlFixer
import com.example.recbiliold.xposed.PlayurlFixerSigning
import com.example.recbiliold.xposed.XposedSharedState
import java.util.concurrent.atomic.AtomicBoolean
import java.lang.reflect.Proxy
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import android.os.Handler
import android.os.Looper
import dalvik.system.DexFile

internal object XposedInAppHooks {

    private val dexNetScanOnce = AtomicBoolean(false)

    private fun logChunked(title: String, text: String, chunkSize: Int = 3000) {
        try {
            XposedBridge.log(title)
        } catch (_: Throwable) {
        }
        if (text.isEmpty()) return
        val safeChunk = if (chunkSize <= 0) 3000 else chunkSize
        var i = 0
        var idx = 0
        val total = (text.length + safeChunk - 1) / safeChunk
        while (idx < text.length) {
            val end = kotlin.math.min(idx + safeChunk, text.length)
            val part = text.substring(idx, end)
            try {
                XposedBridge.log("$title [${i + 1}/$total]\n$part")
            } catch (_: Throwable) {
            }
            i++
            idx = end
        }
    }

    private fun scanAndLogNetworkClasses(ctx: android.content.Context) {
        if (!dexNetScanOnce.compareAndSet(false, true)) return

        val ai = try { ctx.applicationInfo } catch (_: Throwable) { null } ?: return
        val paths = ArrayList<String>()
        try {
            ai.sourceDir?.let { if (it.isNotBlank()) paths.add(it) }
        } catch (_: Throwable) {
        }
        try {
            ai.splitSourceDirs?.forEach { p -> if (!p.isNullOrBlank()) paths.add(p) }
        } catch (_: Throwable) {
        }
        if (paths.isEmpty()) return

        val keywords = arrayOf(
            "okhttp", "retrofit", "volley", "cronet", "httpurlconnection", "urlconnection",
            "httpclient", "netty", "grpc", "websocket",
            "bl.elp", "bl.elo", "bl.elr", "p000bl.elp", "p000bl.elo", "p000bl.elr",
            "interceptor", "call", "request", "response", "httpurl"
        )

        val hits = ArrayList<String>(2048)
        for (p in paths.distinct()) {
            try {
                val df = DexFile(p)
                val it = df.entries()
                while (it.hasMoreElements()) {
                    val name = it.nextElement()
                    val ln = name.lowercase()
                    var ok = false
                    for (k in keywords) {
                        if (ln.contains(k)) {
                            ok = true
                            break
                        }
                    }
                    if (ok) {
                        hits.add(name)
                        if (hits.size >= 4000) break
                    }
                }
                try { df.close() } catch (_: Throwable) {
                }
            } catch (t: Throwable) {
                XposedBridge.log(t)
            }
        }

        hits.sort()
        val text = buildString {
            append("paths=\n")
            for (p in paths.distinct()) append(p).append('\n')
            append("\nclasses(count=").append(hits.size).append(")=\n")
            for (c in hits) append(c).append('\n')
        }
        logChunked("RecBiliOld: [net-scan]", text)
    }

    fun hookOversizedAvidInvalidFixIfPresent(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.processName != lpparam.packageName) {
            XposedBridge.log(
                "RecBiliOld: skip com.bilibili.app.in hooks in non-main process=${lpparam.processName}"
            )
            return
        }

        val cl = lpparam.classLoader

        fun isIntLikeReturn(t: Class<*>?): Boolean {
            if (t == null) return false
            return t == Int::class.javaPrimitiveType || t == Int::class.java || t == java.lang.Integer::class.java
        }

        fun toPositiveAid31(fullAid: Long): Int {
            // VideoDetailsActivity only checks `<= 0`. If we return a negative Int (signed32), it still fails.
            // Use 31-bit positive mapping to guarantee `>0`.
            val v = (fullAid and 0x7FFF_FFFFL).toInt()
            return if (v == 0) 1 else v
        }

        fun ingestFullAid(fullAid: Long) {
            if (fullAid <= 0L) return
            try { PlayurlFixer.ingestOversizedAid(fullAid.toString()) } catch (_: Throwable) {
            }
            try { XposedSharedState.oversizedAidAtomicRef.set(fullAid) } catch (_: Throwable) {
            }
        }

        fun ingestFullCid(fullCid: Long) {
            if (fullCid <= 0L) return
            try { XposedSharedState.fullCidAtomicRef.set(fullCid) } catch (_: Throwable) {
            }
        }

        fun fixUrlQueryIfNeeded(urlStr: String): String? {
            val base = urlStr.substringBefore('?')
            val u = base.lowercase()
            val isView = u.contains("/x/intl/view") || u.contains("/x/v2/view")
            val isDm = u.contains("/x/v2/dm/view") || u.contains("/x/v2/dm/list.so")
            val isReply = u.contains("/x/v2/reply")
            if (!isView && !isDm && !isReply) return null

            val fullAid = try { XposedSharedState.oversizedAidAtomicRef.get() } catch (_: Throwable) { null } ?: 0L
            val fullCid = try { XposedSharedState.fullCidAtomicRef.get() } catch (_: Throwable) { null } ?: 0L
            if (fullAid <= 0L && fullCid <= 0L) return null

            val query = try { URI(urlStr).rawQuery.orEmpty() } catch (_: Throwable) { urlStr.substringAfter('?', "") }
            if (query.isBlank()) return null

            val paramsEnc = LinkedHashMap<String, String>()
            try {
                for (pair in query.split('&')) {
                    if (pair.isBlank()) continue
                    val idx = pair.indexOf('=')
                    if (idx <= 0) continue
                    val k = pair.substring(0, idx)
                    val v = pair.substring(idx + 1)
                    paramsEnc[k] = v
                }
            } catch (_: Throwable) {
            }

            val decoded = LinkedHashMap<String, String>()
            try {
                for ((k, vEnc) in paramsEnc) {
                    val v = try { URLDecoder.decode(vEnc, "UTF-8") } catch (_: Throwable) { vEnc }
                    decoded[k] = v
                }
            } catch (_: Throwable) {
            }

            var didRewrite = false

            if (isReply) {
                val type = decoded["type"]
                if (type == "1" && fullAid > 0L) {
                    val oid = decoded["oid"]
                    if (!oid.isNullOrBlank() && oid != fullAid.toString()) {
                        decoded["oid"] = fullAid.toString()
                        didRewrite = true
                    }
                }
            } else {
                if (fullAid > 0L) {
                    val aid = decoded["aid"]
                    if (!aid.isNullOrBlank() && aid != fullAid.toString()) {
                        decoded["aid"] = fullAid.toString()
                        didRewrite = true
                    }
                }
                if (isDm && fullCid > 0L) {
                    val oid = decoded["oid"]
                    if (!oid.isNullOrBlank() && oid != fullCid.toString()) {
                        decoded["oid"] = fullCid.toString()
                        didRewrite = true
                    }
                }
            }

            if (!didRewrite) return null

            if (decoded.containsKey("sign")) {
                val signed = try { PlayurlFixerSigning.signAppParams(decoded) } catch (_: Throwable) { null }
                if (signed != null) {
                    decoded.clear()
                    decoded.putAll(signed)
                }
            } else if (decoded.containsKey("w_rid") || decoded.containsKey("wts")) {
                try {
                    val baseParams = decoded.toMutableMap()
                    baseParams.remove("w_rid")
                    baseParams.remove("wts")
                    val signed = PlayurlFixerSigning.signWbiParams(baseParams)
                    decoded.clear()
                    decoded.putAll(signed)
                } catch (_: Throwable) {
                }
            }

            val newQuery = try {
                decoded.entries.joinToString("&") { (k, v) ->
                    URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
                }
            } catch (_: Throwable) {
                return null
            }

            if (newQuery.isBlank()) return null
            return base + "?" + newQuery
        }

        fun setConnectionUrlBestEffort(conn: Any, fixedUrl: String) {
            val urlObj = try { URL(fixedUrl) } catch (_: Throwable) { null } ?: return
            try {
                val methods = conn.javaClass.methods
                val m = methods.firstOrNull { it.name == "setURL" && it.parameterTypes.size == 1 && it.parameterTypes[0] == URL::class.java }
                if (m != null) {
                    m.isAccessible = true
                    m.invoke(conn, urlObj)
                    return
                }
            } catch (_: Throwable) {
            }

            try {
                var c: Class<*>? = conn.javaClass
                while (c != null && c != Any::class.java) {
                    for (f in c.declaredFields) {
                        try {
                            if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                            if (f.type != URL::class.java) continue
                            f.isAccessible = true
                            f.set(conn, urlObj)
                            return
                        } catch (_: Throwable) {
                        }
                    }
                    c = c.superclass
                }
            } catch (_: Throwable) {
            }
        }

        fun hookUrlConnectionFallbackIfPresent() {
            val installed = AtomicBoolean(false)

            fun tryInstallOnce(reason: String) {
                if (installed.get()) return

                val okConnClazz = XposedHelpers.findClassIfExists("com.bilibili.lib.okhttp.huc.OkHttpURLConnection", cl)
                val cronetConnClazz = XposedHelpers.findClassIfExists("org.chromium.net.urlconnection.CronetHttpURLConnection", cl)
                if (okConnClazz == null && cronetConnClazz == null) {
                    if (reason == "init") {
                        XposedBridge.log("RecBiliOld: com.bilibili.app.in URLConnection fallback: target classes not loaded yet")
                    }
                    return
                }

                fun hookConnClazz(connClazz: Class<*>, tag: String) {
                    val connectM = try { connClazz.getMethod("connect") } catch (_: Throwable) { null }
                    if (connectM != null) {
                        XposedBridge.hookMethod(
                            connectM,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    try {
                                        val conn = param.thisObject ?: return
                                        val url = try { XposedHelpers.callMethod(conn, "getURL")?.toString() } catch (_: Throwable) { null }
                                        if (!url.isNullOrBlank()) {
                                            val fixed = fixUrlQueryIfNeeded(url)
                                            if (!fixed.isNullOrBlank() && fixed != url) {
                                                setConnectionUrlBestEffort(conn, fixed)
                                                XposedBridge.log("RecBiliOld: [urlconn][$tag] rewrite -> $fixed")
                                            } else {
                                                XposedBridge.log("RecBiliOld: [urlconn][$tag] -> $url")
                                            }
                                        }
                                    } catch (t: Throwable) {
                                        XposedBridge.log(t)
                                    }
                                }
                            }
                        )
                    }

                    val inputStreamM = try { connClazz.getMethod("getInputStream") } catch (_: Throwable) { null }
                    if (inputStreamM != null) {
                        XposedBridge.hookMethod(
                            inputStreamM,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    try {
                                        val conn = param.thisObject ?: return
                                        val url = try { XposedHelpers.callMethod(conn, "getURL")?.toString() } catch (_: Throwable) { null }
                                        if (!url.isNullOrBlank()) {
                                            val fixed = fixUrlQueryIfNeeded(url)
                                            if (!fixed.isNullOrBlank() && fixed != url) {
                                                setConnectionUrlBestEffort(conn, fixed)
                                                XposedBridge.log("RecBiliOld: [urlconn][$tag] rewrite(getInputStream) -> $fixed")
                                            } else {
                                                XposedBridge.log("RecBiliOld: [urlconn][$tag] -> $url")
                                            }
                                        }
                                    } catch (t: Throwable) {
                                        XposedBridge.log(t)
                                    }
                                }
                            }
                        )
                    }

                    val outputStreamM = try { connClazz.getMethod("getOutputStream") } catch (_: Throwable) { null }
                    if (outputStreamM != null) {
                        XposedBridge.hookMethod(
                            outputStreamM,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    try {
                                        val conn = param.thisObject ?: return
                                        val url = try { XposedHelpers.callMethod(conn, "getURL")?.toString() } catch (_: Throwable) { null }
                                        if (!url.isNullOrBlank()) {
                                            val fixed = fixUrlQueryIfNeeded(url)
                                            if (!fixed.isNullOrBlank() && fixed != url) {
                                                setConnectionUrlBestEffort(conn, fixed)
                                                XposedBridge.log("RecBiliOld: [urlconn][$tag] rewrite(getOutputStream) -> $fixed")
                                            } else {
                                                XposedBridge.log("RecBiliOld: [urlconn][$tag] -> $url")
                                            }
                                        }
                                    } catch (t: Throwable) {
                                        XposedBridge.log(t)
                                    }
                                }
                            }
                        )
                    }

                    val responseCodeM = try { connClazz.getMethod("getResponseCode") } catch (_: Throwable) { null }
                    if (responseCodeM != null) {
                        XposedBridge.hookMethod(
                            responseCodeM,
                            object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    try {
                                        val conn = param.thisObject ?: return
                                        val url = try { XposedHelpers.callMethod(conn, "getURL")?.toString() } catch (_: Throwable) { null }
                                        val code = param.result?.toString() ?: "?"
                                        if (!url.isNullOrBlank()) {
                                            XposedBridge.log("RecBiliOld: [urlconn][$tag] <- http=$code $url")
                                        }
                                    } catch (_: Throwable) {
                                    }
                                }
                            }
                        )
                    }
                }

                try {
                    if (okConnClazz != null) {
                        hookConnClazz(okConnClazz, "bili-huc")
                        XposedBridge.log("RecBiliOld: com.bilibili.app.in URLConnection fallback hook installed: ${okConnClazz.name}")
                    }
                } catch (t: Throwable) {
                    XposedBridge.log(t)
                }

                try {
                    if (cronetConnClazz != null) {
                        hookConnClazz(cronetConnClazz, "cronet")
                        XposedBridge.log("RecBiliOld: com.bilibili.app.in URLConnection fallback hook installed: ${cronetConnClazz.name}")
                    }
                } catch (t: Throwable) {
                    XposedBridge.log(t)
                }

                installed.set(true)
            }

            tryInstallOnce("init")

            try {
                val h = Handler(Looper.getMainLooper())
                for (i in 1..12) {
                    h.postDelayed(
                        {
                            try {
                                tryInstallOnce("retry#$i")
                            } catch (_: Throwable) {
                            }
                        },
                        (i * 1000L)
                    )
                }
            } catch (_: Throwable) {
            }
        }

        try {
            XposedHelpers.findAndHookMethod(
                android.app.Application::class.java,
                "attach",
                android.content.Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ctx = param.args.getOrNull(0) as? android.content.Context ?: return
                        val pn = try { ctx.packageName } catch (_: Throwable) { null } ?: return
                        if (pn != "com.bilibili.app.in") return
                        try {
                            Handler(Looper.getMainLooper()).post {
                                try { scanAndLogNetworkClasses(ctx) } catch (t: Throwable) { XposedBridge.log(t) }
                            }
                        } catch (_: Throwable) {
                            try { scanAndLogNetworkClasses(ctx) } catch (t: Throwable) { XposedBridge.log(t) }
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }

        try {
            hookUrlConnectionFallbackIfPresent()
        } catch (_: Throwable) {
        }

        fun tryExtractFullAidFromIntent(intent: android.content.Intent?): Long? {
            if (intent == null) return null

            fun fromUriString(s: String?): Long? {
                if (s.isNullOrBlank()) return null
                if (!s.startsWith("bilibili://", ignoreCase = true)) return null
                if (!s.contains("bilibili://video/", ignoreCase = true)) return null
                val u = try { android.net.Uri.parse(s) } catch (_: Throwable) { null } ?: return null
                val seg = try { u.pathSegments } catch (_: Throwable) { emptyList() }
                val last = seg.lastOrNull() ?: return null
                return last.toLongOrNull()
            }

            val dataStr = try { intent.dataString } catch (_: Throwable) { null }
            fromUriString(dataStr)?.let { return it }

            val extras = try { intent.extras } catch (_: Throwable) { null }
            val candidates = arrayOf("route_uri_actual", "route_uri_original", "uri", "route_uri")
            for (k in candidates) {
                val s = try { extras?.get(k) as? String } catch (_: Throwable) { null }
                fromUriString(s)?.let { return it }
            }
            return null
        }

        fun tryExtractFullCidFromIntent(intent: android.content.Intent?): Long? {
            if (intent == null) return null

            fun fromUriString(s: String?): Long? {
                if (s.isNullOrBlank()) return null
                // bilibili://video/<aid>?cid=<cid>
                val m = Regex("(?i)(?:[?&])cid=(\\d+)").find(s)
                return m?.groupValues?.getOrNull(1)?.toLongOrNull()
            }

            try {
                fromUriString(intent.dataString)?.let { return it }
            } catch (_: Throwable) {
            }

            try {
                val extras = intent.extras
                if (extras != null) {
                    val keys = try { extras.keySet()?.toList().orEmpty() } catch (_: Throwable) { emptyList() }
                    for (k in keys) {
                        val lk = k.lowercase()
                        if (lk != "cid") continue
                        val v = try { extras.get(k) } catch (_: Throwable) { null }
                        val n = when (v) {
                            is Long -> v
                            is Int -> v.toLong()
                            is String -> v.toLongOrNull()
                            else -> null
                        }
                        if (n != null && n > 0L) return n
                    }
                }
            } catch (_: Throwable) {
            }

            return null
        }

        fun collectFullAidFromModel(modelObj: Any, fullAidOverride: Long? = null): Long? {
            if (fullAidOverride != null && fullAidOverride > 0L) return fullAidOverride
            var best: Long? = null

            try {
                for (f in modelObj.javaClass.declaredFields) {
                    try {
                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                        if (f.type != String::class.java) continue
                        f.isAccessible = true
                        val v = f.get(modelObj) as? String ?: continue
                        val s = v.trim()
                        if (s.isEmpty()) continue
                        if (s.any { it < '0' || it > '9' }) continue
                        val n = s.toLongOrNull() ?: continue
                        if (n <= Int.MAX_VALUE.toLong()) continue
                        if (best == null || n > best) best = n
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }

            return best
        }

        fun hookOkHttpViewApisAidRewriteIfPresent() {
            val installed = AtomicBoolean(false)

            fun tryInstallOnce(reason: String) {
                if (installed.get()) return

                val interceptorClazz = XposedHelpers.findClassIfExists("okhttp3.Interceptor", cl)
                val builderClazz =
                    XposedHelpers.findClassIfExists("okhttp3.OkHttpClient\$Builder", cl)
                        ?: XposedHelpers.findClassIfExists("okhttp3.OkHttpClient.Builder", cl)

                if (interceptorClazz == null || builderClazz == null) {
                    if (reason != "loadClass") {
                        XposedBridge.log(
                            "RecBiliOld: com.bilibili.app.in okhttp classes not ready yet (interceptor=${interceptorClazz != null} builder=${builderClazz != null}), will wait via loadClass"
                        )
                    }
                    return
                }

                fun isTargetUrl(url: String): Boolean {
                    val u = url.lowercase()
                    return u.contains("/x/intl/view") ||
                        u.contains("/x/v2/view") ||
                        u.contains("/x/v2/dm/view") ||
                        u.contains("/x/v2/reply")
                }

                val interceptorProxy = Proxy.newProxyInstance(
                    cl,
                    arrayOf(interceptorClazz)
                ) { _, method, args ->
                    if (method.name != "intercept" || args.isNullOrEmpty()) {
                        return@newProxyInstance if (args == null) method.invoke(this) else method.invoke(this, *args)
                    }

                    val chain = args[0]
                    val request = try { XposedHelpers.callMethod(chain, "request") } catch (_: Throwable) { null }
                        ?: return@newProxyInstance XposedHelpers.callMethod(chain, "proceed", null)

                    val urlObj = try { XposedHelpers.callMethod(request, "url") } catch (_: Throwable) { null }
                    val urlStr = try { urlObj?.toString() } catch (_: Throwable) { null }
                    if (urlStr.isNullOrBlank() || !isTargetUrl(urlStr)) {
                        return@newProxyInstance XposedHelpers.callMethod(chain, "proceed", request)
                    }

                    val realAid = try { XposedSharedState.oversizedAidAtomicRef.get() } catch (_: Throwable) { null } ?: 0L
                    if (realAid <= Int.MAX_VALUE.toLong()) {
                        return@newProxyInstance XposedHelpers.callMethod(chain, "proceed", request)
                    }

                    val query = try { URI(urlStr).rawQuery.orEmpty() } catch (_: Throwable) { urlStr.substringAfter('?', "") }
                    if (query.isBlank()) {
                        return@newProxyInstance XposedHelpers.callMethod(chain, "proceed", request)
                    }

                    val paramsEnc = LinkedHashMap<String, String>()
                    try {
                        for (pair in query.split('&')) {
                            if (pair.isBlank()) continue
                            val idx = pair.indexOf('=')
                            if (idx <= 0) continue
                            val k = pair.substring(0, idx)
                            val v = pair.substring(idx + 1)
                            paramsEnc[k] = v
                        }
                    } catch (_: Throwable) {
                    }

                    val decoded = LinkedHashMap<String, String>()
                    try {
                        for ((k, vEnc) in paramsEnc) {
                            val v = try { URLDecoder.decode(vEnc, "UTF-8") } catch (_: Throwable) { vEnc }
                            decoded[k] = v
                        }
                    } catch (_: Throwable) {
                    }

                    val base = urlStr.substringBefore('?')
                    var didRewrite = false
                    var oldVal: String? = null
                    var paramName: String? = null

                    if (base.contains("/x/v2/reply", ignoreCase = true)) {
                        val type = decoded["type"]
                        val oid = decoded["oid"]
                        if (type == "1" && !oid.isNullOrBlank() && oid != realAid.toString()) {
                            oldVal = oid
                            paramName = "oid"
                            decoded["oid"] = realAid.toString()
                            didRewrite = true
                        }
                    } else {
                        val aid = decoded["aid"]
                        if (!aid.isNullOrBlank() && aid != realAid.toString()) {
                            oldVal = aid
                            paramName = "aid"
                            decoded["aid"] = realAid.toString()
                            didRewrite = true
                        }
                    }

                    if (!didRewrite) {
                        return@newProxyInstance XposedHelpers.callMethod(chain, "proceed", request)
                    }

                    if (decoded.containsKey("sign")) {
                        val signed = try { PlayurlFixerSigning.signAppParams(decoded) } catch (_: Throwable) { null }
                        if (signed != null) {
                            decoded.clear()
                            decoded.putAll(signed)
                        }
                    } else if (decoded.containsKey("w_rid") || decoded.containsKey("wts")) {
                        try {
                            val baseParams = decoded.toMutableMap()
                            baseParams.remove("w_rid")
                            baseParams.remove("wts")
                            val signed = PlayurlFixerSigning.signWbiParams(baseParams)
                            decoded.clear()
                            decoded.putAll(signed)
                        } catch (_: Throwable) {
                        }
                    }

                    val newQuery = try {
                        decoded.entries.joinToString("&") { (k, v) ->
                            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
                        }
                    } catch (_: Throwable) {
                        null
                    }
                    if (newQuery.isNullOrBlank()) {
                        return@newProxyInstance XposedHelpers.callMethod(chain, "proceed", request)
                    }

                    val fixedUrl = base + "?" + newQuery
                    val proceedReq = try {
                        val b = XposedHelpers.callMethod(request, "newBuilder")
                        XposedHelpers.callMethod(b, "url", fixedUrl)
                        XposedHelpers.callMethod(b, "build")
                    } catch (_: Throwable) {
                        request
                    }

                    XposedBridge.log(
                        "RecBiliOld: com.bilibili.app.in rewrite ${base.substringAfterLast('/')} ${paramName ?: "<p>"} ${oldVal ?: "?"} -> $realAid"
                    )
                    XposedHelpers.callMethod(chain, "proceed", proceedReq)
                }

                try {
                    XposedHelpers.findAndHookMethod(
                        builderClazz,
                        "build",
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                try {
                                    val flag = XposedHelpers.getAdditionalInstanceField(param.thisObject, "recbilioldViewApiAidRewrite")
                                    if (flag != null) return
                                    XposedHelpers.callMethod(param.thisObject, "addNetworkInterceptor", interceptorProxy)
                                    XposedHelpers.setAdditionalInstanceField(param.thisObject, "recbilioldViewApiAidRewrite", true)
                                    XposedBridge.log("RecBiliOld: com.bilibili.app.in okhttp view/reply aid/oid rewrite interceptor installed")
                                    installed.set(true)
                                } catch (_: Throwable) {
                                    try {
                                        XposedHelpers.callMethod(param.thisObject, "addInterceptor", interceptorProxy)
                                        XposedHelpers.setAdditionalInstanceField(param.thisObject, "recbilioldViewApiAidRewrite", true)
                                        XposedBridge.log("RecBiliOld: com.bilibili.app.in okhttp view/reply aid/oid rewrite interceptor installed (app)")
                                        installed.set(true)
                                    } catch (_: Throwable) {
                                    }
                                }
                            }
                        }
                    )
                } catch (_: Throwable) {
                }
            }

            // Try now.
            tryInstallOnce("init")

            // If not loaded yet, retry later on main thread (avoid loadClass hook recursion/ANR).
            try {
                val h = Handler(Looper.getMainLooper())
                for (i in 1..12) {
                    h.postDelayed(
                        {
                            try {
                                tryInstallOnce("retry#$i")
                            } catch (_: Throwable) {
                            }
                        },
                        (i * 1000L)
                    )
                }
            } catch (_: Throwable) {
            }
        }

        // Key point for com.bilibili.app.in:
        // UgcVideoModel keeps avid in a String field, then converts to Int.
        // Oversized avid may become <=0 and VideoDetailsActivity will finish() with "Invalid avid".
        // Strategy:
        // - Keep Intent/data/extras ORIGINAL.
        // - Do NOT mutate UgcVideoModel avid/String fields.
        // - Only cache the full (oversized) avid, and bypass the `<=0` check by hooking the int getter return value.
        try {
            val modelClazz = XposedHelpers.findClassIfExists("tv.danmaku.bili.ui.video.viewmodel.UgcVideoModel", cl)
                ?: XposedHelpers.findClassIfExists("tv.danmaku.bili.p046ui.video.viewmodel.UgcVideoModel", cl)

            if (modelClazz != null) {
                val m = XposedHelpers.findMethodExactIfExists(modelClazz, "m105688a", android.app.Activity::class.java)
                if (m != null) {
                    XposedBridge.hookMethod(
                        m,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                try {
                                    val avidStr = try { XposedHelpers.getObjectField(param.thisObject, "f99720h") as? String } catch (_: Throwable) { null }
                                    val full = avidStr?.toLongOrNull() ?: return
                                    if (full <= Int.MAX_VALUE.toLong()) return
                                    ingestFullAid(full)
                                    XposedBridge.log("RecBiliOld: com.bilibili.app.in cached full avid from UgcVideoModel=$full (no mutation)")
                                } catch (t: Throwable) {
                                    XposedBridge.log(t)
                                }
                            }
                        }
                    )
                    XposedBridge.log("RecBiliOld: com.bilibili.app.in hook installed: UgcVideoModel.m105688a(Activity)")
                } else {
                    // Fallback for different builds: find any instance method (Activity)->void.
                    val cand = try {
                        modelClazz.declaredMethods.firstOrNull { mm ->
                            !java.lang.reflect.Modifier.isStatic(mm.modifiers) &&
                                mm.parameterTypes.size == 1 &&
                                mm.parameterTypes[0] == android.app.Activity::class.java &&
                                mm.returnType == Void.TYPE
                        }
                    } catch (_: Throwable) {
                        null
                    }

                    if (cand != null) {
                        try { cand.isAccessible = true } catch (_: Throwable) {
                        }
                        XposedBridge.hookMethod(
                            cand,
                            object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    try {
                                        // Prefer the stable field if present, otherwise scan all strings.
                                        val avidStr = try { XposedHelpers.getObjectField(param.thisObject, "f99720h") as? String } catch (_: Throwable) { null }
                                        val full = avidStr?.toLongOrNull()
                                        if (full != null && full > Int.MAX_VALUE.toLong()) {
                                            ingestFullAid(full)
                                            XposedBridge.log("RecBiliOld: com.bilibili.app.in cached full avid from UgcVideoModel.f99720h=$full (no mutation)")
                                            return
                                        }

                                        val scanned = collectFullAidFromModel(param.thisObject)
                                        if (scanned != null && scanned > Int.MAX_VALUE.toLong()) {
                                            ingestFullAid(scanned)
                                            XposedBridge.log("RecBiliOld: com.bilibili.app.in cached full avid from UgcVideoModel (scan)=$scanned (no mutation)")
                                        }
                                    } catch (t: Throwable) {
                                        XposedBridge.log(t)
                                    }
                                }
                            }
                        )
                        XposedBridge.log(
                            "RecBiliOld: com.bilibili.app.in hook installed: UgcVideoModel.<fallback>(Activity)->void name=${cand.name}"
                        )
                    } else {
                        XposedBridge.log("RecBiliOld: com.bilibili.app.in skip hook: UgcVideoModel parse(Activity)->void not found")
                    }
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }

        // Earlier cache: ensure we have fullAid before any avid getter fallback runs.
        try {
            val vdaClazz = XposedHelpers.findClassIfExists("tv.danmaku.bili.ui.video.VideoDetailsActivity", cl)
                ?: XposedHelpers.findClassIfExists("tv.danmaku.bili.p046ui.video.VideoDetailsActivity", cl)

            if (vdaClazz != null) {
                // Hard fallback: m104544c(Intent) runs before the `avid<=0` check in onCreate.
                // Patch the model right after m104544c returns.
                val m = XposedHelpers.findMethodExactIfExists(vdaClazz, "m104544c", android.content.Intent::class.java)
                if (m != null) {
                    XposedBridge.hookMethod(
                        m,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                try {
                                    val act = param.thisObject ?: return
                                    // Try to locate UgcVideoModel instance from VideoDetailsActivity fields (e.g. f98771ah)
                                    val modelObj = run {
                                        try {
                                            val f = act.javaClass.declaredFields.firstOrNull { ff ->
                                                !java.lang.reflect.Modifier.isStatic(ff.modifiers) &&
                                                    ff.type.name.contains("UgcVideoModel")
                                            } ?: return@run null
                                            f.isAccessible = true
                                            f.get(act)
                                        } catch (_: Throwable) {
                                            null
                                        }
                                    } ?: return

                                    val fullFromIntent = tryExtractFullAidFromIntent(
                                        (param.args.getOrNull(0) as? android.content.Intent)
                                    )

                                    val full = collectFullAidFromModel(modelObj, fullAidOverride = fullFromIntent)
                                    if (full != null && full > Int.MAX_VALUE.toLong()) {
                                        ingestFullAid(full)
                                        XposedBridge.log("RecBiliOld: com.bilibili.app.in cached full avid after m104544c=$full (no mutation)")
                                    }
                                } catch (t: Throwable) {
                                    XposedBridge.log(t)
                                }
                            }
                        }
                    )
                    XposedBridge.log("RecBiliOld: com.bilibili.app.in hook installed: VideoDetailsActivity.m104544c(Intent) hard-fallback")
                } else {
                    // Fallback for different builds: find any instance method (Intent)->void.
                    val cand = try {
                        vdaClazz.declaredMethods.firstOrNull { mm ->
                            !java.lang.reflect.Modifier.isStatic(mm.modifiers) &&
                                mm.parameterTypes.size == 1 &&
                                mm.parameterTypes[0] == android.content.Intent::class.java &&
                                mm.returnType == Void.TYPE
                        }
                    } catch (_: Throwable) {
                        null
                    }

                    if (cand != null) {
                        try { cand.isAccessible = true } catch (_: Throwable) {
                        }
                        XposedBridge.hookMethod(
                            cand,
                            object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    try {
                                        val act = param.thisObject ?: return
                                        val modelObj = run {
                                            try {
                                                val f = act.javaClass.declaredFields.firstOrNull { ff ->
                                                    !java.lang.reflect.Modifier.isStatic(ff.modifiers) &&
                                                        ff.type.name.contains("UgcVideoModel")
                                                } ?: return@run null
                                                f.isAccessible = true
                                                f.get(act)
                                            } catch (_: Throwable) {
                                                null
                                            }
                                        } ?: return

                                        val fullFromIntent = tryExtractFullAidFromIntent(param.args.getOrNull(0) as? android.content.Intent)
                                        val full = collectFullAidFromModel(modelObj, fullAidOverride = fullFromIntent)
                                        if (full != null && full > Int.MAX_VALUE.toLong()) {
                                            ingestFullAid(full)
                                            XposedBridge.log("RecBiliOld: com.bilibili.app.in cached full avid after VideoDetailsActivity.<fallback>(Intent)->void full=$full (no mutation)")
                                        }
                                    } catch (t: Throwable) {
                                        XposedBridge.log(t)
                                    }
                                }
                            }
                        )
                        XposedBridge.log(
                            "RecBiliOld: com.bilibili.app.in hook installed: VideoDetailsActivity.<fallback>(Intent)->void name=${cand.name}"
                        )
                    } else {
                        XposedBridge.log("RecBiliOld: com.bilibili.app.in skip hook: VideoDetailsActivity parse(Intent)->void not found")
                    }
                }

                XposedHelpers.findAndHookMethod(
                    vdaClazz,
                    "onCreate",
                    android.os.Bundle::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val act = param.thisObject as? android.app.Activity ?: return
                            val full = tryExtractFullAidFromIntent(act.intent) ?: return
                            if (full <= Int.MAX_VALUE.toLong()) return
                            ingestFullAid(full)

                            val cid = tryExtractFullCidFromIntent(act.intent)
                            if (cid != null && cid > 0L) ingestFullCid(cid)
                        }
                    }
                )
                XposedBridge.log("RecBiliOld: com.bilibili.app.in hook installed: VideoDetailsActivity.onCreate(Bundle) pre-cache")
            }
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }

        // Optional fallback: if parse still returns <=0 (e.g. field name differs), keep page alive.
        // Return a positive 32-bit value derived from cached full aid.
        try {
            val modelClazz = XposedHelpers.findClassIfExists("tv.danmaku.bili.ui.video.viewmodel.UgcVideoModel", cl)
                ?: XposedHelpers.findClassIfExists("tv.danmaku.bili.p046ui.video.viewmodel.UgcVideoModel", cl)

            if (modelClazz != null) {
                val m = XposedHelpers.findMethodExactIfExists(modelClazz, "m105683C")
                if (m != null) {
                    XposedBridge.hookMethod(
                        m,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val r = (param.result as? Number)?.toLong() ?: return
                                if (r > 0L) return
                                val full = try { XposedSharedState.oversizedAidAtomicRef.get() } catch (_: Throwable) { null } ?: return
                                if (full <= 0L) return
                                val aid31 = toPositiveAid31(full)
                                if (aid31 <= 0) return
                                param.result = aid31
                            }
                        }
                    )
                    XposedBridge.log("RecBiliOld: com.bilibili.app.in hook installed: UgcVideoModel.m105683C() avid fallback")
                } else {
                    // Fallback for different builds: find any instance method ()->int/Integer.
                    val cand = try {
                        modelClazz.declaredMethods.firstOrNull { mm ->
                            !java.lang.reflect.Modifier.isStatic(mm.modifiers) &&
                                mm.parameterTypes.isEmpty() &&
                                isIntLikeReturn(mm.returnType)
                        }
                    } catch (_: Throwable) {
                        null
                    }

                    if (cand != null) {
                        try { cand.isAccessible = true } catch (_: Throwable) {
                        }
                        XposedBridge.hookMethod(
                            cand,
                            object : XC_MethodHook() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    val r = (param.result as? Number)?.toLong() ?: return
                                    if (r > 0L) return
                                    val full = try { XposedSharedState.oversizedAidAtomicRef.get() } catch (_: Throwable) { null } ?: return
                                    if (full <= 0L) return
                                    val aid31 = toPositiveAid31(full)
                                    if (aid31 <= 0) return
                                    param.result = aid31
                                }
                            }
                        )
                        XposedBridge.log(
                            "RecBiliOld: com.bilibili.app.in hook installed: UgcVideoModel.<fallback>()->int name=${cand.name}"
                        )
                    } else {
                        XposedBridge.log("RecBiliOld: com.bilibili.app.in skip hook: UgcVideoModel getter()->int not found")
                    }
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }

        // Optional: suppress toast "Invalid avid" to reduce annoyance if some path still triggers it.
        try {
            val dryClazz = XposedHelpers.findClassIfExists("log.dry", cl)
            if (dryClazz != null) {
                XposedHelpers.findAndHookMethod(
                    dryClazz,
                    "m18279b",
                    android.content.Context::class.java,
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val msg = param.args.getOrNull(1) as? String ?: return
                            if (msg == "Invalid avid") {
                                param.result = null
                                param.setResult(null)
                            }
                        }
                    }
                )
                XposedBridge.log("RecBiliOld: com.bilibili.app.in hook installed: suppress dry.m18279b(Invalid avid)")
            }
        } catch (_: Throwable) {
        }

        // Ensure view API requests use full aid (long) so the correct video loads.
        try {
            hookOkHttpViewApisAidRewriteIfPresent()
        } catch (_: Throwable) {
        }
    }
}
