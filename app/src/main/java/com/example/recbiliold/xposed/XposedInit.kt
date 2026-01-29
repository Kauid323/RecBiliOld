package com.example.recbiliold.xposed

import com.alibaba.fastjson.JSONObject
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.Collections
import java.util.WeakHashMap
import java.net.URLDecoder

private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.36"
private const val DESKTOP_REFERER = "https://www.bilibili.com"

class XposedInit : IXposedHookLoadPackage {

    private fun getMyProcessName(): String? {
        return try {
            val at = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null),
                "currentActivityThread"
            )
            XposedHelpers.callMethod(at, "getProcessName") as? String
        } catch (_: Throwable) {
            try {
                XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentProcessName"
                ) as? String
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun hookCommentActivityIntentCaptureIfPresent(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHooksCommentIntent.hookCommentActivityIntentCaptureIfPresent(lpparam)
    }

    private val bundledRespBodyCache = WeakHashMap<Any, String>()
    private val bundledIngestGuard = object : ThreadLocal<Boolean>() {
        override fun initialValue(): Boolean = false
    }
    private val hookedMethods = java.util.HashSet<java.lang.reflect.Method>()

    private fun isBangumiGetSourceEpisode0(url: String): Boolean {
        if (!url.contains("bangumi.bilibili.com/api/get_source", ignoreCase = true)) return false
        return Regex("(?i)(?:[?&])episode_id=0(?:&|$)").containsMatchIn(url)
    }

    private fun buildBundledElrJsonResponse(cl: ClassLoader, reqObj: Any, jsonBody: String): Any? {
        return try {
            XposedBridge.log("RecBiliOld: [buildSyntheticResp] Step 1: Finding elr builder class...")
            val elrBuilderClazz =
                XposedHelpers.findClassIfExists("p000bl.elr\$C2964a", cl)
                    ?: XposedHelpers.findClassIfExists("p000bl.elr\$a", cl)
                    ?: XposedHelpers.findClassIfExists("bl.elr\$C2964a", cl)
                    ?: XposedHelpers.findClassIfExists("bl.elr\$a", cl)
                    ?: run {
                        XposedBridge.log("RecBiliOld: [buildSyntheticResp] FAILED: elr builder class not found")
                        return null
                    }
            XposedBridge.log("RecBiliOld: [buildSyntheticResp] Found elr builder: ${elrBuilderClazz.name}")
            
            XposedBridge.log("RecBiliOld: [buildSyntheticResp] Step 2: Finding els (body) class...")
            val elsClazz =
                XposedHelpers.findClassIfExists("p000bl.els", cl)
                    ?: XposedHelpers.findClassIfExists("bl.els", cl)
                    ?: run {
                        XposedBridge.log("RecBiliOld: [buildSyntheticResp] FAILED: els class not found")
                        return null
                    }
            XposedBridge.log("RecBiliOld: [buildSyntheticResp] Found els: ${elsClazz.name}")
            
            XposedBridge.log("RecBiliOld: [buildSyntheticResp] Step 3: Finding ell (MediaType) class...")
            val ellClazz =
                XposedHelpers.findClassIfExists("p000bl.ell", cl)
                    ?: XposedHelpers.findClassIfExists("bl.ell", cl)
                    ?: run {
                        XposedBridge.log("RecBiliOld: [buildSyntheticResp] FAILED: ell class not found")
                        return null
                    }
            XposedBridge.log("RecBiliOld: [buildSyntheticResp] Found ell: ${ellClazz.name}")

            XposedBridge.log("RecBiliOld: [buildSyntheticResp] Step 4: Finding ell.parse method...")
            val ellParse = ellClazz.declaredMethods.firstOrNull { m ->
                Modifier.isStatic(m.modifiers) && m.parameterTypes.size == 1 && m.parameterTypes[0] == String::class.java
            } ?: run {
                XposedBridge.log("RecBiliOld: [buildSyntheticResp] FAILED: ell.parse method not found")
                return null
            }
            ellParse.isAccessible = true
            val mediaType = ellParse.invoke(null, "application/json; charset=utf-8")
            XposedBridge.log("RecBiliOld: [buildSyntheticResp] MediaType created: ${mediaType?.javaClass?.name}")

            XposedBridge.log("RecBiliOld: [buildSyntheticResp] Step 5: Finding els.create method...")
            val elsCreate = elsClazz.declaredMethods.firstOrNull { m ->
                Modifier.isStatic(m.modifiers) && m.parameterTypes.size == 2 &&
                    m.parameterTypes[1] == String::class.java
            } ?: run {
                XposedBridge.log("RecBiliOld: [buildSyntheticResp] FAILED: els.create method not found")
                XposedBridge.log("RecBiliOld: [buildSyntheticResp] Available static methods in els:")
                elsClazz.declaredMethods.filter { Modifier.isStatic(it.modifiers) }.forEach {
                    XposedBridge.log("RecBiliOld: [buildSyntheticResp]   - ${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }})")
                }
                return null
            }
            XposedBridge.log("RecBiliOld: [buildSyntheticResp] Found els.create method: ${elsCreate.name}")
            elsCreate.isAccessible = true
            val bodyObj = elsCreate.invoke(null, mediaType, jsonBody)
            XposedBridge.log("RecBiliOld: [buildSyntheticResp] Body object created: ${bodyObj?.javaClass?.name}")

            XposedBridge.log("RecBiliOld: [buildSyntheticResp] Step 6: Creating builder instance...")
            val builder = elrBuilderClazz.getDeclaredConstructor().newInstance()
            XposedBridge.log("RecBiliOld: [buildSyntheticResp] Builder instance created")

            XposedBridge.log("RecBiliOld: [buildSyntheticResp] Step 7: Finding request setter method...")
            val requestClazz = XposedHelpers.findClassIfExists("p000bl.elp", cl)
                ?: XposedHelpers.findClassIfExists("bl.elp", cl)
            val requestSetterMethod = elrBuilderClazz.declaredMethods.firstOrNull { m ->
                m.parameterTypes.size == 1 && requestClazz != null && m.parameterTypes[0] == requestClazz &&
                    m.returnType == elrBuilderClazz
            } ?: run {
                XposedBridge.log("RecBiliOld: [buildSyntheticResp] FAILED: request setter not found")
                return null
            }
            requestSetterMethod.isAccessible = true
            requestSetterMethod.invoke(builder, reqObj)
            XposedBridge.log("RecBiliOld: [buildSyntheticResp] Request set successfully: ${requestSetterMethod.name}")

            try {
                val protocolEnum =
                    XposedHelpers.findClassIfExists("okhttp3.Protocol", cl)
                        ?: XposedHelpers.findClassIfExists("okhttp3.Protocol", reqObj.javaClass.classLoader)
                if (protocolEnum != null && protocolEnum.isEnum) {
                    val pick = try {
                        java.lang.Enum.valueOf(protocolEnum as Class<out Enum<*>>, "HTTP_1_1")
                    } catch (_: Throwable) {
                        try {
                            java.lang.Enum.valueOf(protocolEnum as Class<out Enum<*>>, "HTTP_2")
                        } catch (_: Throwable) {
                            (protocolEnum.enumConstants?.firstOrNull() as? Enum<*>)
                        }
                    }
                    if (pick != null) {
                        val protocolSetterMethod = elrBuilderClazz.declaredMethods.firstOrNull { m ->
                            m.parameterTypes.size == 1 && m.parameterTypes[0] == protocolEnum &&
                                m.returnType == elrBuilderClazz
                        }
                        if (protocolSetterMethod != null) {
                            protocolSetterMethod.isAccessible = true
                            protocolSetterMethod.invoke(builder, pick)
                        }
                    }
                }
            } catch (_: Throwable) {
            }

            XposedBridge.log("RecBiliOld: [buildSyntheticResp] Step 8: Setting code/message/headers/body...")
            
            // code(int)
            val codeSetterMethod = elrBuilderClazz.declaredMethods.firstOrNull { m ->
                m.parameterTypes.size == 1 && (m.parameterTypes[0] == Int::class.javaPrimitiveType || m.parameterTypes[0] == Int::class.java) &&
                    m.returnType == elrBuilderClazz
            }
            if (codeSetterMethod != null) {
                codeSetterMethod.isAccessible = true
                codeSetterMethod.invoke(builder, 200)
            }
            
            // message(String)
            val messageSetterMethod = elrBuilderClazz.declaredMethods.firstOrNull { m ->
                m.parameterTypes.size == 1 && m.parameterTypes[0] == String::class.java &&
                    m.returnType == elrBuilderClazz
            }
            if (messageSetterMethod != null) {
                messageSetterMethod.isAccessible = true
                messageSetterMethod.invoke(builder, "OK")
            }
            
            // header(String, String)
            try {
                val headerSetterMethod = elrBuilderClazz.declaredMethods.firstOrNull { m ->
                    m.parameterTypes.size == 2 && m.parameterTypes[0] == String::class.java && 
                        m.parameterTypes[1] == String::class.java && m.returnType == elrBuilderClazz
                }
                if (headerSetterMethod != null) {
                    headerSetterMethod.isAccessible = true
                    headerSetterMethod.invoke(builder, "Content-Type", "application/json; charset=utf-8")
                }
            } catch (_: Throwable) {
            }
            
            // body(els)
            val bodySetterMethod = elrBuilderClazz.declaredMethods.firstOrNull { m ->
                m.parameterTypes.size == 1 && (m.parameterTypes[0] == elsClazz || m.parameterTypes[0].name.endsWith(".els")) &&
                    m.returnType == elrBuilderClazz
            }
            if (bodySetterMethod != null) {
                bodySetterMethod.isAccessible = true
                bodySetterMethod.invoke(builder, bodyObj)
            }

            XposedBridge.log("RecBiliOld: [buildSyntheticResp] Step 9: Building response...")
            val buildMethod = elrBuilderClazz.declaredMethods.firstOrNull { m ->
                m.parameterTypes.isEmpty() && m.returnType.name.endsWith(".elr")
            } ?: run {
                XposedBridge.log("RecBiliOld: [buildSyntheticResp] FAILED: build() method not found")
                return null
            }
            buildMethod.isAccessible = true
            val result = buildMethod.invoke(builder)
            XposedBridge.log("RecBiliOld: [buildSyntheticResp] SUCCESS: Response built: ${result?.javaClass?.name}")
            result
        } catch (t: Throwable) {
            XposedBridge.log("RecBiliOld: [buildSyntheticResp] EXCEPTION during build:")
            XposedBridge.log(t)
            null
        }
    }

    private fun hookExoPlayerDataSourceIfPresent(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        var installedCount = 0

        fun tryHook(clazzName: String) {
            val dsClazz = XposedHelpers.findClassIfExists(clazzName, cl) ?: return
            try {
                val dataSpecClazz = if (clazzName.startsWith("androidx.media3.")) {
                    XposedHelpers.findClassIfExists("androidx.media3.datasource.DataSpec", cl)
                        ?: XposedHelpers.findClassIfExists("androidx.media3.datasource.DataSpec", dsClazz.classLoader)
                } else {
                    XposedHelpers.findClassIfExists("com.google.android.exoplayer2.upstream.DataSpec", cl)
                        ?: XposedHelpers.findClassIfExists("com.google.android.exoplayer2.upstream.DataSpec", dsClazz.classLoader)
                } ?: return

                XposedHelpers.findAndHookMethod(
                    dsClazz,
                    "open",
                    dataSpecClazz,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val spec = param.args.getOrNull(0) ?: return
                                val uriObj = try {
                                    XposedHelpers.getObjectField(spec, "uri")
                                } catch (_: Throwable) {
                                    try {
                                        XposedHelpers.callMethod(spec, "uri")
                                    } catch (_: Throwable) {
                                        null
                                    }
                                }
                                val uriStr = uriObj?.toString().orEmpty()
                                if (uriStr.isBlank()) return
                                if (!uriStr.contains("upgcxcode", ignoreCase = true) && !uriStr.contains("bilivideo.com", ignoreCase = true)) return
                                if (!exoOpenLoggedOnce.add(uriStr)) return

                                val pos = try {
                                    (XposedHelpers.getLongField(spec, "position") as Long)
                                } catch (_: Throwable) {
                                    try {
                                        XposedHelpers.getObjectField(spec, "position") as? Long
                                    } catch (_: Throwable) {
                                        null
                                    }
                                }
                                val len = try {
                                    (XposedHelpers.getLongField(spec, "length") as Long)
                                } catch (_: Throwable) {
                                    try {
                                        XposedHelpers.getObjectField(spec, "length") as? Long
                                    } catch (_: Throwable) {
                                        null
                                    }
                                }

                                XposedBridge.log(
                                    "RecBiliOld: [exo-open] ds=${dsClazz.name} uri=${uriStr.take(220)}" +
                                        " pos=${pos ?: "<null>"} len=${len ?: "<null>"}"
                                )
                            } catch (t: Throwable) {
                                XposedBridge.log(t)
                            }
                        }
                    }
                )
                installedCount += 1
                XposedBridge.log("RecBiliOld: exoplayer DataSource.open hook installed for $clazzName")
            } catch (t: Throwable) {
                XposedBridge.log(t)
            }
        }

        val pn = getMyProcessName() ?: "<unknown>"
        val pid = try { android.os.Process.myPid() } catch (_: Throwable) { -1 }
        XposedBridge.log("RecBiliOld: exoplayer datasource hook scanning... process=$pn pid=$pid")

        tryHook("com.google.android.exoplayer2.upstream.DefaultHttpDataSource")
        tryHook("com.google.android.exoplayer2.upstream.DefaultDataSource")
        tryHook("com.google.android.exoplayer2.upstream.StatsDataSource")
        tryHook("com.google.android.exoplayer2.upstream.PriorityDataSource")
        tryHook("com.google.android.exoplayer2.upstream.TeeDataSource")
        tryHook("com.google.android.exoplayer2.upstream.cache.CacheDataSource")
        tryHook("com.google.android.exoplayer2.upstream.cache.CacheDataSourceImpl")
        tryHook("com.google.android.exoplayer2.ext.okhttp.OkHttpDataSource")
        tryHook("com.google.android.exoplayer2.ext.cronet.CronetDataSource")

        tryHook("androidx.media3.datasource.DefaultHttpDataSource")
        tryHook("androidx.media3.datasource.DefaultDataSource")
        tryHook("androidx.media3.datasource.StatsDataSource")
        tryHook("androidx.media3.datasource.PriorityDataSource")
        tryHook("androidx.media3.datasource.TeeDataSource")
        tryHook("androidx.media3.datasource.cache.CacheDataSource")
        tryHook("androidx.media3.datasource.cache.CacheDataSourceImpl")
        tryHook("androidx.media3.datasource.okhttp.OkHttpDataSource")
        tryHook("androidx.media3.datasource.cronet.CronetDataSource")

        if (installedCount <= 0) {
            XposedBridge.log("RecBiliOld: exoplayer datasource hook installedCount=0 (no known DataSource classes found)")
        } else {
            XposedBridge.log("RecBiliOld: exoplayer datasource hook installedCount=$installedCount")
        }
    }

    private fun hookIjkPlayerSetDataSourceIfPresent(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        var installedCount = 0

        fun shouldLogMediaUrl(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            return url.contains("upgcxcode", ignoreCase = true) || url.contains("bilivideo.com", ignoreCase = true)
        }

        fun logOnce(tag: String, url: String, extra: String? = null) {
            if (!ijkSetDataSourceLoggedOnce.add("$tag|$url")) return
            XposedBridge.log(
                "RecBiliOld: [ijk-ds] via=$tag url=${url.take(220)}" +
                    (if (!extra.isNullOrBlank()) " extra=${extra.take(800)}" else "")
            )
        }

        fun ensureIjkHeadersForUrl(mpObj: Any?, url: String?) {
            if (mpObj == null) return
            if (!shouldLogMediaUrl(url)) return
            val u = url ?: return
            try {
                val hdr = "User-Agent: $DESKTOP_UA\r\nReferer: $DESKTOP_REFERER\r\n"
                if (ijkHeaderInjectedOnce.add(u)) {
                    XposedBridge.log("RecBiliOld: [ijk-hdr] preset headers for url=${u.take(220)}")
                }

                val targetPlayer: Any? = when {
                    mpObj.javaClass.name.contains("IjkMediaPlayer") -> mpObj
                    mpObj.javaClass.name.contains("MediaPlayerProxy") -> {
                        try {
                            XposedHelpers.callMethod(mpObj, "getInternalMediaPlayer")
                        } catch (t: Throwable) {
                            null
                        }
                    }
                    else -> null
                }

                if (targetPlayer != null && targetPlayer.javaClass.name.contains("IjkMediaPlayer")) {
                    XposedHelpers.callMethod(targetPlayer, "setOption", 1, "headers", hdr)
                }
            } catch (t: Throwable) {
                XposedBridge.log(t)
            }
        }

        fun hookIjkSetOptionHeadersIfPresent() {
            val ijkClazz = XposedHelpers.findClassIfExists("tv.danmaku.ijk.media.player.IjkMediaPlayer", cl) ?: return
            try {
                XposedHelpers.findAndHookMethod(
                    ijkClazz,
                    "setOption",
                    Int::class.javaPrimitiveType,
                    String::class.java,
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val name = param.args.getOrNull(1) as? String ?: return
                                if (!name.equals("headers", ignoreCase = true)) return
                                val old = param.args.getOrNull(2) as? String

                                val uaLine = "User-Agent: $DESKTOP_UA"
                                val refLine = "Referer: $DESKTOP_REFERER"

                                val hasUa = old?.contains("User-Agent:", ignoreCase = true) == true
                                val hasRef = old?.contains("Referer:", ignoreCase = true) == true

                                if (hasUa && hasRef) return

                                val sb = StringBuilder()
                                if (!old.isNullOrEmpty()) sb.append(old)
                                if (!old.isNullOrEmpty() && !old.endsWith("\r\n")) sb.append("\r\n")
                                if (!hasUa) sb.append(uaLine).append("\r\n")
                                if (!hasRef) sb.append(refLine).append("\r\n")
                                val newHdr = sb.toString()
                                param.args[2] = newHdr

                                if (ijkHeaderInjectedOnce.add("opt|" + newHdr.hashCode().toString())) {
                                    XposedBridge.log("RecBiliOld: [ijk-hdr] patched setOption(headers) addUa=${!hasUa} addRef=${!hasRef}")
                                }
                            } catch (t: Throwable) {
                                XposedBridge.log(t)
                            }
                        }
                    }
                )
                installedCount += 1
                XposedBridge.log("RecBiliOld: ijk setOption(headers) hook installed for tv.danmaku.ijk.media.player.IjkMediaPlayer")
            } catch (t: Throwable) {
                XposedBridge.log(t)
            }
        }

        fun hookString(clazzName: String) {
            val c = XposedHelpers.findClassIfExists(clazzName, cl) ?: return
            try {
                XposedHelpers.findAndHookMethod(
                    c,
                    "setDataSource",
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val url = param.args.getOrNull(0) as? String
                                if (!shouldLogMediaUrl(url)) return
                                logOnce(clazzName + ".setDataSource(String)", url!!)
                                ensureIjkHeadersForUrl(param.thisObject, url)
                            } catch (t: Throwable) {
                                XposedBridge.log(t)
                            }
                        }
                    }
                )
                installedCount += 1
                XposedBridge.log("RecBiliOld: ijk setDataSource(String) hook installed for $clazzName")
            } catch (_: Throwable) {
            }
        }

        fun hookContextUri(clazzName: String) {
            val c = XposedHelpers.findClassIfExists(clazzName, cl) ?: return
            try {
                XposedHelpers.findAndHookMethod(
                    c,
                    "setDataSource",
                    android.content.Context::class.java,
                    android.net.Uri::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val uri = param.args.getOrNull(1) as? android.net.Uri
                                val url = uri?.toString()
                                if (!shouldLogMediaUrl(url)) return
                                logOnce(clazzName + ".setDataSource(Context,Uri)", url!!)
                                ensureIjkHeadersForUrl(param.thisObject, url)
                            } catch (t: Throwable) {
                                XposedBridge.log(t)
                            }
                        }
                    }
                )
                installedCount += 1
                XposedBridge.log("RecBiliOld: ijk setDataSource(Context,Uri) hook installed for $clazzName")
            } catch (_: Throwable) {
            }
        }

        fun hookContextUriHeaders(clazzName: String) {
            val c = XposedHelpers.findClassIfExists(clazzName, cl) ?: return
            try {
                XposedHelpers.findAndHookMethod(
                    c,
                    "setDataSource",
                    android.content.Context::class.java,
                    android.net.Uri::class.java,
                    Map::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val uri = param.args.getOrNull(1) as? android.net.Uri
                                val url = uri?.toString()
                                if (!shouldLogMediaUrl(url)) return
                                val hdr = param.args.getOrNull(2) as? Map<*, *>
                                val hdrKeys = try { hdr?.keys?.joinToString(prefix = "[", postfix = "]") } catch (_: Throwable) { null }
                                logOnce(clazzName + ".setDataSource(Context,Uri,Map)", url!!, hdrKeys)
                                ensureIjkHeadersForUrl(param.thisObject, url)
                            } catch (t: Throwable) {
                                XposedBridge.log(t)
                            }
                        }
                    }
                )
                installedCount += 1
                XposedBridge.log("RecBiliOld: ijk setDataSource(Context,Uri,Map) hook installed for $clazzName")
            } catch (_: Throwable) {
            }
        }

        val pn = getMyProcessName() ?: "<unknown>"
        val pid = try { android.os.Process.myPid() } catch (_: Throwable) { -1 }
        XposedBridge.log("RecBiliOld: ijk/mediaplayer setDataSource hook scanning... process=$pn pid=$pid")

        hookIjkSetOptionHeadersIfPresent()

        hookString("tv.danmaku.ijk.media.player.IjkMediaPlayer")
        hookContextUri("tv.danmaku.ijk.media.player.IjkMediaPlayer")
        hookContextUriHeaders("tv.danmaku.ijk.media.player.IjkMediaPlayer")

        hookString("tv.danmaku.ijk.media.player.MediaPlayerProxy")
        hookContextUri("tv.danmaku.ijk.media.player.MediaPlayerProxy")
        hookContextUriHeaders("tv.danmaku.ijk.media.player.MediaPlayerProxy")

        hookString("tv.danmaku.ijk.media.player.AndroidMediaPlayer")
        hookContextUri("tv.danmaku.ijk.media.player.AndroidMediaPlayer")
        hookContextUriHeaders("tv.danmaku.ijk.media.player.AndroidMediaPlayer")

        if (installedCount <= 0) {
            XposedBridge.log("RecBiliOld: ijk setDataSource hook installedCount=0 (no ijk player classes found)")
        } else {
            XposedBridge.log("RecBiliOld: ijk setDataSource hook installedCount=$installedCount")
        }

        try {
            XposedHelpers.findAndHookMethod(
                android.media.MediaPlayer::class.java,
                "setDataSource",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val url = param.args.getOrNull(0) as? String
                            if (!shouldLogMediaUrl(url)) return
                            logOnce("android.media.MediaPlayer.setDataSource(String)", url!!)
                        } catch (t: Throwable) {
                            XposedBridge.log(t)
                        }
                    }
                }
            )
            XposedBridge.log("RecBiliOld: android.media.MediaPlayer setDataSource(String) hook installed")
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }

        try {
            XposedHelpers.findAndHookMethod(
                android.media.MediaPlayer::class.java,
                "setDataSource",
                android.content.Context::class.java,
                android.net.Uri::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val uri = param.args.getOrNull(1) as? android.net.Uri
                            val url = uri?.toString()
                            if (!shouldLogMediaUrl(url)) return
                            logOnce("android.media.MediaPlayer.setDataSource(Context,Uri)", url!!)
                        } catch (t: Throwable) {
                            XposedBridge.log(t)
                        }
                    }
                }
            )
            XposedBridge.log("RecBiliOld: android.media.MediaPlayer setDataSource(Context,Uri) hook installed")
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }

        try {
            XposedHelpers.findAndHookMethod(
                android.media.MediaPlayer::class.java,
                "setDataSource",
                android.content.Context::class.java,
                android.net.Uri::class.java,
                Map::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val uri = param.args.getOrNull(1) as? android.net.Uri
                            val url = uri?.toString()
                            if (!shouldLogMediaUrl(url)) return
                            val hdr = param.args.getOrNull(2) as? Map<*, *>
                            val hdrKeys = try { hdr?.keys?.joinToString(prefix = "[", postfix = "]") } catch (_: Throwable) { null }
                            logOnce("android.media.MediaPlayer.setDataSource(Context,Uri,Map)", url!!, hdrKeys)
                        } catch (t: Throwable) {
                            XposedBridge.log(t)
                        }
                    }
                }
            )
            XposedBridge.log("RecBiliOld: android.media.MediaPlayer setDataSource(Context,Uri,Map) hook installed")
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    private fun forceNormalVideoPath(detailObj: Any?) {
        if (detailObj == null) return
        try {
            val candidates = arrayOf(
                "mBangumiInfo",
                "bangumiInfo",
                "bangumi_info",
                "mBangumi",
                "bangumi",
                "mMovie",
                "movie",
                "movieInfo",
                "mMovieInfo"
            )
            for (n in candidates) {
                try {
                    XposedHelpers.setObjectField(detailObj, n, null)
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun hookForceNormalVideoPathIfPresent(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader

        val biliVideoDetailClazz =
            XposedHelpers.findClassIfExists("tv.danmaku.bili.ui.video.api.BiliVideoDetail", cl)
                ?: return

        try {
            val fwdClazz =
                XposedHelpers.findClassIfExists("p000bl.fwd", cl)
                    ?: XposedHelpers.findClassIfExists("bl.fwd", cl)

            if (fwdClazz != null) {
                val methods = fwdClazz.declaredMethods.filter { m ->
                    m.name == "m30409a" && m.parameterTypes.any { it == biliVideoDetailClazz }
                }
                for (m in methods) {
                    XposedBridge.hookMethod(
                        m,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                try {
                                    for (i in param.args.indices) {
                                        val a = param.args[i]
                                        if (a != null && a.javaClass == biliVideoDetailClazz) {
                                            forceNormalVideoPath(a)
                                        }
                                    }
                                } catch (_: Throwable) {
                                }
                            }
                        }
                    )
                }
                if (methods.isNotEmpty()) {
                    XposedBridge.log("RecBiliOld: force normal video path hook installed fwd=${fwdClazz.name} count=${methods.size}")
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }

        try {
            val actClazz =
                XposedHelpers.findClassIfExists("tv.danmaku.bili.p046ui.video.VideoDetailsActivity", cl)
                    ?: return

            val m = actClazz.declaredMethods.firstOrNull { it.name == "m50055am" && it.parameterTypes.isEmpty() }
            if (m != null) {
                XposedBridge.hookMethod(
                    m,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val detail = try {
                                    XposedHelpers.getObjectField(param.thisObject, "f47480Z")
                                } catch (_: Throwable) {
                                    null
                                }
                                if (detail != null && detail.javaClass == biliVideoDetailClazz) {
                                    forceNormalVideoPath(detail)
                                }
                            } catch (_: Throwable) {
                            }
                        }
                    }
                )
                XposedBridge.log("RecBiliOld: force normal video path hook installed VideoDetailsActivity.m50055am")
            }
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    private val bundledIngestGlobalGuard = AtomicBoolean(false)

    private val bundledUrlFailOnce = ConcurrentHashMap.newKeySet<String>()

    private val bundledHeaderInjectedOnce = ConcurrentHashMap.newKeySet<String>()

    private val uposReqLoggedOnce = ConcurrentHashMap.newKeySet<String>()

    private val uposRespLoggedOnce = ConcurrentHashMap.newKeySet<String>()

    private val exoOpenLoggedOnce = ConcurrentHashMap.newKeySet<String>()

    private val ijkSetDataSourceLoggedOnce = ConcurrentHashMap.newKeySet<String>()

    private val ijkHeaderInjectedOnce = ConcurrentHashMap.newKeySet<String>()

    private val contextLoggedOnce = AtomicBoolean(false)

    private val cardClickLoggedOnce = ConcurrentHashMap.newKeySet<String>()

    private fun ensureAppContext(ctx: android.content.Context?) {
        if (ctx == null) return
        val appCtx = ctx.applicationContext ?: ctx
        PlayurlFixer.setAppContext(appCtx)
        XposedBridge.log(
            "RecBiliOld: app context set proc=${android.os.Process.myPid()} ctx=${appCtx.javaClass.name} callerPid=${android.os.Binder.getCallingPid()}"
        )
    }

    private fun extractUrlFromRequestLike(obj: Any?): String? {
        if (obj == null) return null
        // IMPORTANT: never call arbitrary toString() / methods on unknown objects.
        // Only accept safe types to avoid triggering our own hooked methods.
        return try {
            when (obj) {
                is String -> obj
                is CharSequence -> obj.toString()
                else -> {
                    val n = obj.javaClass.name
                    if (n == "android.net.Uri" || n.endsWith(".Uri") || n == "java.net.URL" || n.contains("HttpUrl", ignoreCase = true)) {
                        obj.toString()
                    } else {
                        null
                    }
                }
            }?.takeIf { it.startsWith("http", ignoreCase = true) }
        } catch (_: Throwable) {
            null
        }
    }

    private fun hookBundledDesktopHeadersIfPresent(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        val builderClazz =
            XposedHelpers.findClassIfExists("p000bl.elp\$a", cl)
                ?: XposedHelpers.findClassIfExists("bl.elp\$a", cl)
                ?: XposedHelpers.findClassIfExists("p000bl.elp\$C2960a", cl)
                ?: XposedHelpers.findClassIfExists("bl.elp\$C2960a", cl)
                ?: return

        val elpClazz =
            XposedHelpers.findClassIfExists("p000bl.elp", cl)
                ?: XposedHelpers.findClassIfExists("bl.elp", cl)

        try {
            val buildMethod = builderClazz.declaredMethods.firstOrNull { m ->
                m.parameterTypes.isEmpty() &&
                    (elpClazz == null || m.returnType == elpClazz || m.returnType.name.endsWith(".elp"))
            }

            if (buildMethod == null) {
                XposedBridge.log("RecBiliOld: bundled desktop header hook failed: build method not found builder=${builderClazz.name}")
                return
            }

            XposedBridge.hookMethod(
                buildMethod,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val hit = try {
                                val re = Regex("(?i)(upgcxcode|bilivideo\\.com)")
                                var found: String? = null
                                for (f in builderClazz.declaredFields) {
                                    try {
                                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                                        f.isAccessible = true
                                        val v = f.get(param.thisObject) ?: continue
                                        val s = v.toString()
                                        if (s.isNotBlank() && re.containsMatchIn(s)) {
                                            found = s
                                            break
                                        }
                                    } catch (_: Throwable) {
                                    }
                                }
                                found
                            } catch (_: Throwable) {
                                null
                            }

                            if (hit == null) return

                            // Force desktop headers to avoid 403 on UPOS resources.
                            val addHeaderMethod = builderClazz.declaredMethods.firstOrNull { m ->
                                m.parameterTypes.size == 2 &&
                                    m.parameterTypes[0] == String::class.java &&
                                    m.parameterTypes[1] == String::class.java &&
                                    (m.returnType == Void.TYPE || m.returnType == builderClazz)
                            }

                            if (addHeaderMethod == null) {
                                XposedBridge.log("RecBiliOld: inject desktop headers failed: header method not found (builder-scan) hit=${hit.take(200)}")
                                return
                            }

                            addHeaderMethod.isAccessible = true
                            addHeaderMethod.invoke(param.thisObject, "User-Agent", DESKTOP_UA)
                            addHeaderMethod.invoke(param.thisObject, "Referer", DESKTOP_REFERER)
                            XposedBridge.log("RecBiliOld: inject desktop headers ok (builder-scan) hit=${hit.take(200)}")
                        } catch (t: Throwable) {
                            XposedBridge.log(t)
                        }
                    }
                }
            )

            XposedBridge.log(
                "RecBiliOld: bundled desktop header hook installed builder=${builderClazz.name} build=${buildMethod.name}"
            )
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    private fun hookOkHttpDesktopHeadersIfPresent(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        val interceptorClazz = XposedHelpers.findClassIfExists("okhttp3.Interceptor", cl) ?: return
        val builderClazz = XposedHelpers.findClassIfExists("okhttp3.OkHttpClient\$Builder", cl)
            ?: XposedHelpers.findClassIfExists("okhttp3.OkHttpClient.Builder", cl)
            ?: return

        val headerInterceptor = java.lang.reflect.Proxy.newProxyInstance(
            cl,
            arrayOf(interceptorClazz)
        ) { _, method, args ->
            if (method.name != "intercept" || args.isNullOrEmpty()) {
                return@newProxyInstance if (args == null) method.invoke(this) else method.invoke(this, *args)
            }
            val chain = args[0]
            val request = try {
                XposedHelpers.callMethod(chain, "request")
            } catch (_: Throwable) {
                null
            } ?: return@newProxyInstance XposedHelpers.callMethod(chain, "proceed", null)

            val urlObj = try {
                XposedHelpers.callMethod(request, "url")
            } catch (_: Throwable) {
                null
            }
            var urlStr = urlObj?.toString().orEmpty()
            val isPassportOauth2Login = urlStr.contains("passport.bilibili.com/api/oauth2/login", ignoreCase = true)
            val isLegacyCommentList = urlStr.contains("api.bilibili.com/x/v2/reply", ignoreCase = true)
            val needsHeaders = Regex("(?i)(upgcxcode|bilivideo\\.com)").containsMatchIn(urlStr)

            // Danmaku CID can overflow into negative when the app treats long cid as int.
            // If we have a legacyCid->realCid mapping from playurl interception, rewrite danmaku xml to the real cid.
            var danmakuRewritten = false
            val danmakuCid = Regex("https?://comment\\.bilibili\\.com/(-?\\d+)\\.xml", RegexOption.IGNORE_CASE)
                .find(urlStr)
                ?.groupValues
                ?.getOrNull(1)
            if (!danmakuCid.isNullOrBlank()) {
                val real = PlayurlFixer.resolveCidOverrideForDanmaku(danmakuCid)
                if (!real.isNullOrBlank() && real != danmakuCid) {
                    val newUrlStr = urlStr.replace(
                        Regex("(?i)(https?://comment\\.bilibili\\.com/)(-?\\d+)(\\.xml)"),
                        "$1$real$3"
                    )
                    if (newUrlStr != urlStr) {
                        urlStr = newUrlStr
                        danmakuRewritten = true
                    }
                }
            }

            val proceedRequest = if (needsHeaders || isPassportOauth2Login || isLegacyCommentList || danmakuRewritten) {
                try {
                    val builder = XposedHelpers.callMethod(request, "newBuilder")
                    if (urlStr != (urlObj?.toString().orEmpty())) {
                        try {
                            XposedHelpers.callMethod(builder, "url", urlStr)
                            XposedBridge.log("RecBiliOld: rewrite danmaku cid -> $urlStr")
                        } catch (_: Throwable) {
                        }
                    }
                    if (needsHeaders) {
                        XposedHelpers.callMethod(builder, "header", "User-Agent", DESKTOP_UA)
                        XposedHelpers.callMethod(builder, "header", "Referer", DESKTOP_REFERER)
                    }
                    if (isPassportOauth2Login) {
                        try {
                            val oldUrl = XposedHelpers.callMethod(request, "url")
                            val newUrlBuilder = XposedHelpers.callMethod(oldUrl, "newBuilder")
                            XposedHelpers.callMethod(newUrlBuilder, "encodedPath", "/x/passport-login/oauth2/login")
                            val newUrl = XposedHelpers.callMethod(newUrlBuilder, "build")
                            XposedHelpers.callMethod(builder, "url", newUrl)
                            XposedHelpers.callMethod(builder, "header", "env", "prod")
                            XposedHelpers.callMethod(builder, "header", "app-key", "android64")
                            XposedBridge.log("RecBiliOld: rewrite login url /api/oauth2/login -> /x/passport-login/oauth2/login")
                        } catch (t: Throwable) {
                            XposedBridge.log(t)
                        }
                    }
                    if (isLegacyCommentList) {
                        try {
                            val oldUrl = XposedHelpers.callMethod(request, "url")
                            val oid = try { XposedHelpers.callMethod(oldUrl, "queryParameter", "oid") as? String } catch (_: Throwable) { null }
                            val pn = try { XposedHelpers.callMethod(oldUrl, "queryParameter", "pn") as? String } catch (_: Throwable) { null }
                            val type = try { XposedHelpers.callMethod(oldUrl, "queryParameter", "type") as? String } catch (_: Throwable) { null }
                            val ps = try { XposedHelpers.callMethod(oldUrl, "queryParameter", "ps") as? String } catch (_: Throwable) { null }
                            val sort = try { XposedHelpers.callMethod(oldUrl, "queryParameter", "sort") as? String } catch (_: Throwable) { null }
                            val nohot = try { XposedHelpers.callMethod(oldUrl, "queryParameter", "nohot") as? String } catch (_: Throwable) { null }

                            if (!oid.isNullOrBlank() && !type.isNullOrBlank()) {
                                val mode = when (sort) {
                                    "0", null -> "2" // time
                                    else -> "3" // hot-like
                                }
                                val baseParams = LinkedHashMap<String, String>()
                                baseParams["oid"] = oid
                                baseParams["type"] = type
                                baseParams["mode"] = mode
                                if (!ps.isNullOrBlank()) baseParams["ps"] = ps
                                if (!nohot.isNullOrBlank()) baseParams["nohot"] = nohot

                                val signed = try {
                                    PlayurlFixer.signWbiParams(baseParams)
                                } catch (t: Throwable) {
                                    XposedBridge.log(t)
                                    null
                                }

                                if (signed != null) {
                                    val cl = request.javaClass.classLoader
                                    val httpUrlBuilderClazz = XposedHelpers.findClassIfExists("okhttp3.HttpUrl\$Builder", cl)
                                    val newUrlBuilder = if (httpUrlBuilderClazz != null) {
                                        try {
                                            XposedHelpers.newInstance(httpUrlBuilderClazz)
                                        } catch (_: Throwable) {
                                            null
                                        }
                                    } else {
                                        null
                                    }

                                    if (newUrlBuilder != null) {
                                        val scheme = try { XposedHelpers.callMethod(oldUrl, "scheme") as? String } catch (_: Throwable) { null } ?: "https"
                                        val host = try { XposedHelpers.callMethod(oldUrl, "host") as? String } catch (_: Throwable) { null } ?: "api.bilibili.com"
                                        val port = try { (XposedHelpers.callMethod(oldUrl, "port") as? Int) } catch (_: Throwable) { null } ?: 443

                                        try { XposedHelpers.callMethod(newUrlBuilder, "scheme", scheme) } catch (_: Throwable) {}
                                        try { XposedHelpers.callMethod(newUrlBuilder, "host", host) } catch (_: Throwable) {}
                                        try { XposedHelpers.callMethod(newUrlBuilder, "port", port) } catch (_: Throwable) {}
                                        try { XposedHelpers.callMethod(newUrlBuilder, "encodedPath", "/x/v2/reply/wbi/main") } catch (_: Throwable) {}

                                        for ((k, v) in signed) {
                                            try {
                                                XposedHelpers.callMethod(newUrlBuilder, "addQueryParameter", k, v)
                                            } catch (_: Throwable) {
                                            }
                                        }

                                        val newUrl = XposedHelpers.callMethod(newUrlBuilder, "build")
                                        XposedHelpers.callMethod(builder, "url", newUrl)
                                        XposedBridge.log("RecBiliOld: rewrite comment url /x/v2/reply -> /x/v2/reply/wbi/main oid=$oid type=$type mode=$mode")
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log(t)
                        }
                    }
                    XposedHelpers.callMethod(builder, "build")
                } catch (_: Throwable) {
                    request
                }
            } else {
                request
            }

            try {
                XposedHelpers.callMethod(chain, "proceed", proceedRequest)
            } catch (t: Throwable) {
                XposedBridge.log(t)
                throw t
            }
        }

        try {
            XposedHelpers.findAndHookMethod(
                builderClazz,
                "build",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val flag = XposedHelpers.getAdditionalInstanceField(param.thisObject, "recbilioldDesktopHeader")
                            if (flag == null) {
                                XposedHelpers.callMethod(param.thisObject, "addNetworkInterceptor", headerInterceptor)
                                XposedHelpers.setAdditionalInstanceField(param.thisObject, "recbilioldDesktopHeader", true)
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    private fun extractRequestFromFields(owner: Any): Any? {
        try {
            for (f in owner.javaClass.declaredFields) {
                try {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    f.isAccessible = true
                    val v = f.get(owner) ?: continue
                    if (v.javaClass.isPrimitive) continue
                    // do not call methods on v here; only field/toString-based extraction
                    val url = extractUrlFromRequestLike(v)
                    if (url != null) {
                        return v
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
        return null
    }

    private val bundledElrDeepDumpOnce = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private fun deepDumpBundledElrOnce(url: String, responseObj: Any) {
        try {
            if (!bundledElrDeepDumpOnce.add(url)) return

            val sb = StringBuilder()
            sb.append("RecBiliOld: [bundled-elr-dump] url=").append(url)
            sb.append(" class=").append(responseObj.javaClass.name)

            // fields
            for (f in responseObj.javaClass.declaredFields) {
                try {
                    if (Modifier.isStatic(f.modifiers)) continue
                    f.isAccessible = true
                    val v = f.get(responseObj)
                    sb.append(" | f:").append(f.name).append(":").append(f.type.name)
                    if (v != null) {
                        sb.append("=")
                        when (v) {
                            is String -> sb.append(v.take(200))
                            is CharSequence -> sb.append(v.toString().take(200))
                            is ByteArray -> sb.append("bytes(len=").append(v.size).append(")")
                            else -> sb.append(v.javaClass.name)
                        }
                    }
                } catch (_: Throwable) {
                }
            }

            // methods: probe only a few 0-arg non-void to avoid heavy side-effects
            var probed = 0
            for (m in responseObj.javaClass.declaredMethods) {
                try {
                    if (probed >= 12) break
                    if (Modifier.isStatic(m.modifiers)) continue
                    if (m.parameterTypes.isNotEmpty()) continue
                    if (m.returnType == java.lang.Void.TYPE) continue

                    m.isAccessible = true
                    val r = m.invoke(responseObj)
                    sb.append(" | m:").append(m.name).append("->").append(m.returnType.name)
                    if (r != null) sb.append("=").append(r.javaClass.name)
                    probed++
                } catch (_: Throwable) {
                }
            }

            XposedBridge.log(sb.toString())
        } catch (_: Throwable) {
        }
    }

    private fun bruteForceExtractBundledElrJson(responseObj: Any): String? {
        fun looksLikeJson(s: String?): Boolean {
            if (s.isNullOrBlank()) return false
            val t = s.trimStart()
            return t.startsWith("{") || t.startsWith("[")
        }

        try {
            // 1) direct field scan of elr
            tryExtractJsonBodyFromFields(responseObj)?.let { if (looksLikeJson(it)) return it }

            // 2) probe 0-arg non-void methods and attempt to extract from returned objects
            for (m in responseObj.javaClass.declaredMethods) {
                try {
                    if (Modifier.isStatic(m.modifiers)) continue
                    if (m.parameterTypes.isNotEmpty()) continue
                    if (m.returnType == java.lang.Void.TYPE) continue
                    m.isAccessible = true
                    val r = m.invoke(responseObj) ?: continue
                    when (r) {
                        is String -> if (looksLikeJson(r)) return r
                        is CharSequence -> {
                            val s = r.toString()
                            if (looksLikeJson(s)) return s
                        }
                        is ByteArray -> {
                            val s = try { String(r, Charsets.UTF_8) } catch (_: Throwable) { null }
                            if (looksLikeJson(s)) return s
                        }
                        else -> {
                            val s = tryExtractJsonBodyFromFields(r)
                            if (looksLikeJson(s)) return s
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
        return null
    }

    private fun tryExtractBundledElrBodyString(responseObj: Any): String? {
        try {
            val cls = responseObj.javaClass

            fun tryReadUtf8FromOkioLike(o: Any?): String? {
                if (o == null) return null
                val cn = o.javaClass.name
                // Many builds return an implementation of p000bl.eog (BufferedSource).
                // The runtime class name may not literally be "p000bl.eog", so rely on signature.
                val sFromCharset = try {
                    val m = o.javaClass.declaredMethods.firstOrNull { mm ->
                        mm.parameterTypes.size == 1 &&
                            mm.parameterTypes[0] == java.nio.charset.Charset::class.java &&
                            mm.returnType == String::class.java
                    }
                    if (m != null) {
                        m.isAccessible = true
                        m.invoke(o, Charsets.UTF_8) as? String
                    } else null
                } catch (_: Throwable) {
                    null
                }
                if (!sFromCharset.isNullOrBlank()) {
                    val t = sFromCharset.trimStart()
                    if (t.startsWith("{") || t.startsWith("[")) return sFromCharset
                }
                if (cn == "okio.ByteString" || cn.endsWith(".ByteString")) {
                    val s = try {
                        val m = o.javaClass.declaredMethods.firstOrNull { mm ->
                            mm.parameterTypes.isEmpty() && mm.returnType == String::class.java
                        }
                        if (m != null) {
                            m.isAccessible = true
                            m.invoke(o) as? String
                        } else null
                    } catch (_: Throwable) {
                        null
                    }
                    if (!s.isNullOrBlank()) return s
                }
                if (cn == "okio.Buffer" || cn.endsWith(".Buffer")) {
                    val s = try {
                        val mClone = o.javaClass.declaredMethods.firstOrNull { mm ->
                            mm.parameterTypes.isEmpty() && mm.name == "clone"
                        }
                        val cloned = if (mClone != null) {
                            mClone.isAccessible = true
                            mClone.invoke(o)
                        } else {
                            o
                        }
                        val mRead = cloned.javaClass.declaredMethods.firstOrNull { mm ->
                            mm.parameterTypes.isEmpty() && mm.returnType == String::class.java && mm.name.contains("read", ignoreCase = true)
                        }
                        if (mRead != null) {
                            mRead.isAccessible = true
                            mRead.invoke(cloned) as? String
                        } else null
                    } catch (_: Throwable) {
                        null
                    }
                    if (!s.isNullOrBlank()) return s
                }
                return null
            }

            fun tryReadUtf8FromFields(o: Any?): String? {
                if (o == null) return null
                try {
                    for (f in o.javaClass.declaredFields) {
                        try {
                            if (Modifier.isStatic(f.modifiers)) continue
                            f.isAccessible = true
                            val v = f.get(o) ?: continue
                            when (v) {
                                is String -> if (v.isNotBlank()) return v
                                is CharSequence -> {
                                    val s = v.toString()
                                    if (s.isNotBlank()) return s
                                }
                                is ByteArray -> {
                                    if (v.isEmpty()) continue
                                    val s = try {
                                        String(v, Charsets.UTF_8)
                                    } catch (_: Throwable) {
                                        null
                                    }
                                    if (!s.isNullOrBlank()) return s
                                }
                                else -> {
                                    tryReadUtf8FromOkioLike(v)?.let { return it }
                                }
                            }
                        } catch (_: Throwable) {
                        }
                    }
                } catch (_: Throwable) {
                }
                return null
            }

            val bodyObj = try {
                cls.declaredMethods.firstOrNull { m ->
                    m.parameterTypes.isEmpty() &&
                        m.returnType != java.lang.Void.TYPE &&
                        (m.returnType.name == "p000bl.els" || m.returnType.name.endsWith(".els"))
                }?.let { m ->
                    m.isAccessible = true
                    m.invoke(responseObj)
                }
            } catch (_: Throwable) {
                null
            } ?: run {
                try {
                    // Common field name in bl.elr is f19662g (ResponseBody)
                    val f = cls.getDeclaredField("f19662g")
                    f.isAccessible = true
                    f.get(responseObj)
                } catch (_: Throwable) {
                    null
                }
            } ?: run {
                try {
                    cls.declaredFields.firstOrNull { f ->
                        if (Modifier.isStatic(f.modifiers)) return@firstOrNull false
                        val tn = f.type.name
                        tn == "p000bl.els" || tn.endsWith(".els")
                    }?.let { f ->
                        f.isAccessible = true
                        f.get(responseObj)
                    }
                } catch (_: Throwable) {
                    null
                }
            }

            if (bodyObj != null) {
                val s = try {
                    bodyObj.javaClass.declaredMethods.firstOrNull { m ->
                        m.parameterTypes.isEmpty() && m.returnType == String::class.java
                    }?.let { m ->
                        m.isAccessible = true
                        m.invoke(bodyObj) as? String
                    }
                } catch (_: Throwable) {
                    null
                }
                if (!s.isNullOrBlank()) return s

                val b = try {
                    bodyObj.javaClass.declaredMethods.firstOrNull { m ->
                        m.parameterTypes.isEmpty() && m.returnType == ByteArray::class.java
                    }?.let { m ->
                        m.isAccessible = true
                        m.invoke(bodyObj) as? ByteArray
                    }
                } catch (_: Throwable) {
                    null
                }
                if (b != null && b.isNotEmpty()) {
                    val s2 = try { String(b, Charsets.UTF_8) } catch (_: Throwable) { null }
                    if (!s2.isNullOrBlank()) return s2
                }

                // OkHttp-like ResponseBody often exposes a source() / bufferedSource() which can be read.
                // We avoid relying on method names by just finding a 0-arg, non-void method and probing the returned object.
                val srcObj = try {
                    // Prefer returning p000bl.eog (BufferedSource) as seen in bl.emv.
                    val preferred = bodyObj.javaClass.declaredMethods.firstOrNull { m ->
                        m.parameterTypes.isEmpty() &&
                            m.returnType != java.lang.Void.TYPE &&
                            (m.returnType.name == "p000bl.eog" || m.returnType.name.endsWith(".eog"))
                    } ?: bodyObj.javaClass.declaredMethods.firstOrNull { m ->
                        m.parameterTypes.isEmpty() && m.returnType != java.lang.Void.TYPE &&
                            m.returnType != String::class.java && m.returnType != ByteArray::class.java
                    }
                    preferred?.let { m ->
                        m.isAccessible = true
                        m.invoke(bodyObj)
                    }
                } catch (_: Throwable) {
                    null
                }

                tryReadUtf8FromOkioLike(srcObj)?.let { return it }
                tryReadUtf8FromFields(bodyObj)?.let { return it }
            }
        } catch (_: Throwable) {
        }
        return null
    }

    private fun isViewUrl(url: String): Boolean {
        return url.contains("/x/v2/view", ignoreCase = true)
    }

    private fun tryStripBangumiFieldsFromViewJson(body: String): String? {
        try {
            if (body.isBlank()) return null
            // Fast-path: avoid JSON parse if no relevant keys.
            val lower = body.lowercase()
            if (
                !lower.contains("bangumi") &&
                !lower.contains("movie") &&
                !lower.contains("season") &&
                !lower.contains("argue_msg") &&
                !lower.contains("rejectpage") &&
                !lower.contains("playtoast")
            ) return null

            val root = JSONObject.parseObject(body) ?: return null
            val data = root.getJSONObject("data") ?: return null

            var changed = false
            if (data.containsKey("bangumi_info")) {
                data.remove("bangumi_info")
                changed = true
            }
            if (data.containsKey("bangumi")) {
                data.remove("bangumi")
                changed = true
            }
            if (data.containsKey("movie")) {
                data.remove("movie")
                changed = true
            }
            if (data.containsKey("season")) {
                data.remove("season")
                changed = true
            }

            // Some videos return a "version too low" argue_msg and the app may block playback before requesting streams.
            if (data.containsKey("argue_msg")) {
                data.remove("argue_msg")
                changed = true
            }
            if (data.containsKey("RejectPage")) {
                data.remove("RejectPage")
                changed = true
            }
            if (data.containsKey("PlayToast")) {
                data.remove("PlayToast")
                changed = true
            }

            if (!changed) return null
            return root.toJSONString()
        } catch (_: Throwable) {
            return null
        }
    }

    private fun tryPatchOkHttpResponseBodyIfComment(responseObj: Any): Any? {
        try {
            val req = XposedHelpers.callMethod(responseObj, "request") ?: return null
            val urlObj = XposedHelpers.callMethod(req, "url") ?: return null
            val url = urlObj.toString()
            if (!url.contains("/x/v2/reply/wbi/main", ignoreCase = true)) return null

            val peeked = XposedHelpers.callMethod(responseObj, "peekBody", 1024L * 1024L) ?: return null
            val bodyStr = XposedHelpers.callMethod(peeked, "string") as? String ?: return null
            if (bodyStr.isBlank()) return null

            val patched = run {
                try {
                    val root = JSONObject.parseObject(bodyStr) ?: return@run null
                    val data = root.getJSONObject("data") ?: return@run null

                    // Only patch if this looks like the wbi/main shape.
                    if (!data.containsKey("cursor")) return@run null

                    val cursor = data.getJSONObject("cursor")

                    val pageObj = JSONObject()
                    pageObj["num"] = 1
                    pageObj["size"] = run {
                        // Try parse ps from request URL; fall back to 20.
                        try {
                            val query = try {
                                java.net.URI(url).rawQuery.orEmpty()
                            } catch (_: Throwable) {
                                url.substringAfter('?', "")
                            }
                            val ps = query.split('&')
                                .firstOrNull { it.startsWith("ps=", ignoreCase = true) }
                                ?.substringAfter('=')
                                ?.toIntOrNull()
                            ps ?: 20
                        } catch (_: Throwable) {
                            20
                        }
                    }
                    val allCount = try { cursor?.getLongValue("all_count") } catch (_: Throwable) { 0L }
                    pageObj["count"] = allCount
                    pageObj["acount"] = allCount

                    // Legacy /x/v2/reply expects `page`, and `top` to be either null or a comment object.
                    // wbi/main returns `top` as an object wrapper; set it to null to avoid model mismatch.
                    data["page"] = pageObj
                    data["top"] = null

                    // Keep existing fields that are still compatible: replies/hots/notice/upper/config/control/folder...
                    // Remove `cursor` to prevent older parsers from choking on unexpected structures.
                    data.remove("cursor")

                    root.toJSONString()
                } catch (_: Throwable) {
                    null
                }
            } ?: return null

            val newBody = run {
                val body = XposedHelpers.callMethod(responseObj, "body")
                val mediaType = try { XposedHelpers.callMethod(body, "contentType") } catch (_: Throwable) { null }

                // okhttp3.ResponseBody.create(MediaType, String)
                try {
                    val rbClazz = XposedHelpers.findClass("okhttp3.ResponseBody", responseObj.javaClass.classLoader)
                    val create = rbClazz.declaredMethods.firstOrNull { m ->
                        Modifier.isStatic(m.modifiers) && m.name == "create" && m.parameterTypes.size == 2 &&
                            m.parameterTypes[0].name == "okhttp3.MediaType" && m.parameterTypes[1] == String::class.java
                    }
                    if (create != null) {
                        create.isAccessible = true
                        return@run create.invoke(null, mediaType, patched)
                    }
                } catch (_: Throwable) {
                }

                // okhttp3.ResponseBody.Companion.create(String, MediaType?) (OkHttp 4)
                try {
                    val rbClazz = XposedHelpers.findClass("okhttp3.ResponseBody", responseObj.javaClass.classLoader)
                    val companion = rbClazz.declaredFields.firstOrNull { it.name == "Companion" }?.let { f ->
                        f.isAccessible = true
                        f.get(null)
                    }
                    if (companion != null) {
                        val create = companion.javaClass.declaredMethods.firstOrNull { m ->
                            m.name == "create" && m.parameterTypes.size == 2 &&
                                m.parameterTypes[0] == String::class.java && m.parameterTypes[1].name == "okhttp3.MediaType"
                        }
                        if (create != null) {
                            create.isAccessible = true
                            return@run create.invoke(companion, patched, mediaType)
                        }
                    }
                } catch (_: Throwable) {
                }

                null
            } ?: return null

            val newResp = run {
                val nb = XposedHelpers.callMethod(responseObj, "newBuilder") ?: return@run null
                XposedHelpers.callMethod(nb, "body", newBody)
                XposedHelpers.callMethod(nb, "build")
            } ?: return null

            XposedBridge.log("RecBiliOld: patch comment response wbi/main -> legacy reply shape url=$url")
            return newResp
        } catch (t: Throwable) {
            XposedBridge.log(t)
            return null
        }
    }

    private fun tryPatchOkHttpResponseBodyIfView(responseObj: Any): Any? {
        try {
            val req = XposedHelpers.callMethod(responseObj, "request") ?: return null
            val urlObj = XposedHelpers.callMethod(req, "url") ?: return null
            val url = urlObj.toString()
            if (!isViewUrl(url)) return null

            val peeked = XposedHelpers.callMethod(responseObj, "peekBody", 1024L * 1024L) ?: return null
            val bodyStr = XposedHelpers.callMethod(peeked, "string") as? String ?: return null
            val patched = tryStripBangumiFieldsFromViewJson(bodyStr) ?: return null

            val newBody = run {
                val body = XposedHelpers.callMethod(responseObj, "body")
                val mediaType = try { XposedHelpers.callMethod(body, "contentType") } catch (_: Throwable) { null }

                // okhttp3.ResponseBody.create(MediaType, String)
                try {
                    val rbClazz = XposedHelpers.findClass("okhttp3.ResponseBody", responseObj.javaClass.classLoader)
                    val create = rbClazz.declaredMethods.firstOrNull { m ->
                        Modifier.isStatic(m.modifiers) && m.name == "create" && m.parameterTypes.size == 2 &&
                            m.parameterTypes[0].name == "okhttp3.MediaType" && m.parameterTypes[1] == String::class.java
                    }
                    if (create != null) {
                        create.isAccessible = true
                        return@run create.invoke(null, mediaType, patched)
                    }
                } catch (_: Throwable) {
                }

                // okhttp3.ResponseBody.Companion.create(String, MediaType?) (OkHttp 4)
                try {
                    val rbClazz = XposedHelpers.findClass("okhttp3.ResponseBody", responseObj.javaClass.classLoader)
                    val companion = rbClazz.declaredFields.firstOrNull { it.name == "Companion" }?.let { f ->
                        f.isAccessible = true
                        f.get(null)
                    }
                    if (companion != null) {
                        val create = companion.javaClass.declaredMethods.firstOrNull { m ->
                            m.name == "create" && m.parameterTypes.size == 2 &&
                                m.parameterTypes[0] == String::class.java && m.parameterTypes[1].name == "okhttp3.MediaType"
                        }
                        if (create != null) {
                            create.isAccessible = true
                            return@run create.invoke(companion, patched, mediaType)
                        }
                    }
                } catch (_: Throwable) {
                }

                null
            } ?: return null

            val newResp = run {
                val nb = XposedHelpers.callMethod(responseObj, "newBuilder") ?: return@run null
                XposedHelpers.callMethod(nb, "body", newBody)
                XposedHelpers.callMethod(nb, "build")
            } ?: return null

            XposedBridge.log("RecBiliOld: strip view bangumi/movie fields (okhttp) url=$url")
            return newResp
        } catch (t: Throwable) {
            XposedBridge.log(t)
            return null
        }
    }

    private fun tryExtractJsonBodyFromFields(obj: Any?): String? {
        if (obj == null) return null

        fun looksLikeJson(s: String?): Boolean {
            if (s.isNullOrBlank()) return false
            val t = s.trimStart()
            return t.startsWith("{") || t.startsWith("[")
        }

        fun extractFromOne(o: Any): String? {
            // scan fields only, avoid invoking unknown methods
            for (f in o.javaClass.declaredFields) {
                try {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    f.isAccessible = true
                    val v = f.get(o) ?: continue
                    when (v) {
                        is String -> if (looksLikeJson(v)) return v
                        is CharSequence -> {
                            val s = v.toString()
                            if (looksLikeJson(s)) return s
                        }
                        is ByteArray -> {
                            if (v.isEmpty()) continue
                            val s = try {
                                String(v, Charsets.UTF_8)
                            } catch (_: Throwable) {
                                null
                            }
                            if (looksLikeJson(s)) return s
                        }
                        else -> {
                            // Known safe body containers
                            val cn = v.javaClass.name
                            if (cn == "okio.ByteString" || cn.endsWith(".ByteString")) {
                                val s = try {
                                    val m = v.javaClass.getDeclaredMethod("utf8")
                                    m.isAccessible = true
                                    (m.invoke(v) as? String)
                                } catch (_: Throwable) {
                                    null
                                }
                                if (looksLikeJson(s)) return s
                            }
                            if (cn == "okio.Buffer" || cn.endsWith(".Buffer")) {
                                val s = try {
                                    val mClone = v.javaClass.getDeclaredMethod("clone")
                                    mClone.isAccessible = true
                                    val cloned = mClone.invoke(v)
                                    val mRead = cloned.javaClass.getDeclaredMethod("readUtf8")
                                    mRead.isAccessible = true
                                    (mRead.invoke(cloned) as? String)
                                } catch (_: Throwable) {
                                    null
                                }
                                if (looksLikeJson(s)) return s
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
            }
            return null
        }

        // depth 0
        extractFromOne(obj)?.let { return it }

        try {
            if (obj.javaClass.name == "p000bl.elr" || obj.javaClass.name.endsWith(".elr")) {
                val s = tryExtractBundledElrBodyString(obj)
                if (s != null) {
                    try {
                        patchBundledResponseBody(obj, s)
                    } catch (_: Throwable) {
                    }
                }
                if (looksLikeJson(s)) return s
            }
        } catch (_: Throwable) {
        }

        // depth 1
        for (f in obj.javaClass.declaredFields) {
            try {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                f.isAccessible = true
                val v = f.get(obj) ?: continue
                if (v is String || v is CharSequence || v is ByteArray) continue
                extractFromOne(v)?.let { return it }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun extractUrlFromObjectFields(root: Any): String? {
        // fast path for bundled okhttp types (avoid field heuristics)
        try {
            val cn = root.javaClass.name
            if (cn == "p000bl.elp" || cn.endsWith(".elp")) {
                val mUrl = root.javaClass.getDeclaredMethod("a")
                mUrl.isAccessible = true
                val httpUrl = mUrl.invoke(root)
                val s = httpUrl?.toString()
                if (!s.isNullOrBlank() && s.startsWith("http", ignoreCase = true)) return s
            }
            if (cn == "p000bl.elr" || cn.endsWith(".elr")) {
                val mReq = root.javaClass.getDeclaredMethod("a")
                mReq.isAccessible = true
                val req = mReq.invoke(root)
                if (req != null) {
                    val mUrl = req.javaClass.getDeclaredMethod("a")
                    mUrl.isAccessible = true
                    val httpUrl = mUrl.invoke(req)
                    val s = httpUrl?.toString()
                    if (!s.isNullOrBlank() && s.startsWith("http", ignoreCase = true)) return s
                }
            }
        } catch (_: Throwable) {
        }

        fun tryValue(v: Any?): String? {
            if (v == null) return null
            val s = when (v) {
                is String -> v
                is CharSequence -> v.toString()
                else -> {
                    val n = v.javaClass.name
                    if (n == "android.net.Uri" || n.endsWith(".Uri") || n == "java.net.URL" || n.contains("HttpUrl", ignoreCase = true)) {
                        v.toString()
                    } else null
                }
            } ?: return null
            return s.takeIf { it.startsWith("http", ignoreCase = true) }
        }

        fun scanFields(obj: Any): String? {
            for (f in obj.javaClass.declaredFields) {
                try {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                    f.isAccessible = true
                    val v = f.get(obj) ?: continue
                    tryValue(v)?.let { return it }
                } catch (_: Throwable) {
                }
            }
            return null
        }

        // depth 0
        tryValue(root)?.let { return it }
        // depth 1
        scanFields(root)?.let { return it }
        // depth 2 (one level nested, still field-only)
        for (f in root.javaClass.declaredFields) {
            try {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                f.isAccessible = true
                val v = f.get(root) ?: continue
                if (v is String || v is CharSequence) continue
                scanFields(v)?.let { return it }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "tv.danmaku.bili" && lpparam.packageName != "com.bilibili.app.blue") return

        val pn = getMyProcessName() ?: lpparam.processName ?: "<unknown>"
        val pid = try { android.os.Process.myPid() } catch (_: Throwable) { -1 }
        XposedBridge.log("RecBiliOld: loaded ${lpparam.packageName} process=$pn pid=$pid")

        try {
            XposedHelpers.findAndHookMethod(
                android.app.Application::class.java,
                "attach",
                android.content.Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            ensureAppContext(param.args[0] as? android.content.Context)
                        } catch (t: Throwable) {
                            XposedBridge.log(t)
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }

        try {
            XposedHelpers.findAndHookMethod(
                android.content.ContextWrapper::class.java,
                "attachBaseContext",
                android.content.Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            ensureAppContext(param.args[0] as? android.content.Context)
                        } catch (t: Throwable) {
                            XposedBridge.log(t)
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }

        try {
            XposedBridge.log("RecBiliOld: starting hooks for ${lpparam.packageName}")
            hookEpisodeParamsResolver(lpparam)
            hookLuaHttpIfPresent(lpparam)
            hookOversizedAvidIfPresent(lpparam)
            hookForceNormalVideoPathIfPresent(lpparam)
            hookCommentActivityIntentCaptureIfPresent(lpparam)
            hookOkHttpDesktopHeadersIfPresent(lpparam)
            hookBundledDesktopHeadersIfPresent(lpparam)
            hookOkHttpViewResponseIfPresent(lpparam)
            hookOkHttpVerboseIfPresent(lpparam)
            hookOkHttpRealCallVerboseIfPresent(lpparam)
            hookExoPlayerDataSourceIfPresent(lpparam)
            hookIjkPlayerSetDataSourceIfPresent(lpparam)
            hookBundledOkHttpViewResponseIfPresent(lpparam)
            hookVolleyViewResponseIfPresent(lpparam)
            hookFeedJumpAvidClampFixIfPresent(lpparam)
            hookFatUriVideoOpenFixIfPresent(lpparam)
            hookFatVideoBangumiSanitizerIfPresent(lpparam)
            hookVideoDetailsIntentSanitizerIfPresent(lpparam)
            hookRankingListAvidFixIfPresent(lpparam)
            hookBundledViewAidPlaceholderRewriteIfPresent(lpparam)
            hookBundledHttpUrlGetterAidRewriteIfPresent(lpparam)
            hookBundledViewAidBuildTimeRewriteIfPresent(lpparam)
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    private fun hookFatVideoBangumiSanitizerIfPresent(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        val fatClazz = XposedHelpers.findClassIfExists("p000bl.fat", cl)
            ?: XposedHelpers.findClassIfExists("bl.fat", cl)
            ?: return

        val startMethod = fatClazz.declaredMethods.firstOrNull { m ->
            m.name == "m25088a" &&
                m.parameterTypes.size == 2 &&
                android.content.Context::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                android.content.Intent::class.java.isAssignableFrom(m.parameterTypes[1])
        } ?: return

        startMethod.isAccessible = true

        try {
            XposedBridge.hookMethod(
                startMethod,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val ctx = param.args.getOrNull(0) as? android.content.Context ?: return
                            val intent = param.args.getOrNull(1) as? android.content.Intent ?: return

                            val cn = try { intent.component?.className } catch (_: Throwable) { null }
                            if (cn.isNullOrBlank()) return

                            val isVideoDetails = cn.endsWith("VideoDetailsActivity")
                            if (!isVideoDetails) return

                            val dataStr = try { intent.data?.toString() } catch (_: Throwable) { null }
                            val extras = try { intent.extras } catch (_: Throwable) { null }
                            val keys = try { extras?.keySet()?.toList().orEmpty() } catch (_: Throwable) { emptyList() }

                            val st = try { Throwable().stackTrace } catch (_: Throwable) { null }
                            val fromUpSpace = st?.any {
                                val n = it.className
                                n == "p000bl.fbq" || n == "bl.fbq" || n.contains("AuthorSpace", ignoreCase = true)
                            } == true

                            XposedBridge.log(
                                "RecBiliOld: fat.m25088a -> $cn" +
                                    " fromUpSpace=$fromUpSpace" +
                                    " data=${dataStr ?: "<null>"}" +
                                    " extrasKeys=${keys.joinToString(prefix = "[", postfix = "]") }"
                            )

                            try {
                                XposedHooksVideoDetails.tryFixVideoDetailsAvidZero(intent)
                            } catch (t: Throwable) {
                                XposedBridge.log(t)
                            }

                            val removed = XposedHooksVideoDetails.sanitizeVideoDetailsIntent(intent, preferAggressive = fromUpSpace)
                            if (removed.isNotEmpty()) {
                                XposedBridge.log("RecBiliOld: fat.m25088a sanitized VideoDetailsActivity removed=${removed.joinToString(prefix = "[", postfix = "]")}")
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log(t)
                        }
                    }
                }
            )
            XposedBridge.log("RecBiliOld: fat VideoDetailsActivity intent sanitizer hook installed")
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    private fun hookVideoDetailsIntentSanitizerIfPresent(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHooksVideoDetails.hookVideoDetailsIntentSanitizerIfPresent(lpparam)
    }

    private fun hookBundledViewAidBuildTimeRewriteIfPresent(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        val builderClazz =
            XposedHelpers.findClassIfExists("p000bl.elp\$a", cl)
                ?: XposedHelpers.findClassIfExists("bl.elp\$a", cl)
                ?: XposedHelpers.findClassIfExists("p000bl.elp\$C2960a", cl)
                ?: XposedHelpers.findClassIfExists("bl.elp\$C2960a", cl)
                ?: return

        val httpUrlClazz = XposedHelpers.findClassIfExists("okhttp3.HttpUrl", cl) ?: return
        val parseMethod = httpUrlClazz.declaredMethods.firstOrNull { m ->
            java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                m.parameterTypes.size == 1 &&
                m.parameterTypes[0] == String::class.java &&
                m.returnType == httpUrlClazz
        } ?: return

        builderClazz.declaredMethods.forEach { m ->
            XposedBridge.log("RecBiliOld: elp builder method=${m.name} params=${m.parameterTypes.map { it.simpleName }}")
        }

        val setUrlHttp = builderClazz.declaredMethods.firstOrNull { m ->
            m.parameterTypes.size == 1 && m.parameterTypes[0] == httpUrlClazz
        } ?: return

        parseMethod.isAccessible = true
        setUrlHttp.isAccessible = true

        try {
            XposedBridge.hookMethod(
                setUrlHttp,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val urlObj = param.args.getOrNull(0) ?: return
                        val urlStr = try { urlObj.toString() } catch (_: Throwable) { return }

                        try {
                            if (isViewUrl(urlStr)) {
                                val m = Regex("(?i)(?:[?&])aid=(-?\\d+)(?:&|$)").find(urlStr)
                                val aidRaw = m?.groupValues?.getOrNull(1)
                                val aidLong = aidRaw?.toLongOrNull()

                                // 1) Restore truncated aid from low32->fullAid mapping (rank/feed list source)
                                val restored = try { PlayurlFixer.resolveFullAidByLow32(aidRaw) } catch (_: Throwable) { null }
                                if (!restored.isNullOrBlank() && restored != aidRaw) {
                                    val fixedUrl = urlStr.replace(
                                        Regex("(?i)([?&]aid=)(-?\\d+)(?=&|$)"),
                                        "${'$'}1$restored"
                                    )
                                    if (fixedUrl != urlStr) {
                                        val newHttpUrl = try { parseMethod.invoke(null, fixedUrl) } catch (_: Throwable) { null }
                                        if (newHttpUrl != null) {
                                            param.args[0] = newHttpUrl
                                            XposedBridge.log("RecBiliOld: bundled restore view aid $aidRaw -> $restored")
                                            return
                                        }
                                    }
                                }

                                // 2) Negative overflow: convert to unsigned32.
                                if (aidLong != null && aidLong < 0) {
                                    val fixedAid = (aidLong and 0xFFFF_FFFFL)
                                    val fixedUrl = urlStr.replace(
                                        Regex("(?i)([?&]aid=)(-?\\d+)(?=&|$)"),
                                        "${'$'}1$fixedAid"
                                    )
                                    if (fixedUrl != urlStr) {
                                        val newHttpUrl = try {
                                            parseMethod.invoke(null, fixedUrl)
                                        } catch (_: Throwable) {
                                            null
                                        }
                                        if (newHttpUrl != null) {
                                            param.args[0] = newHttpUrl
                                            XposedBridge.log("RecBiliOld: bundled rewrite view aid $aidRaw -> $fixedAid")
                                            return
                                        }
                                    }
                                }
                            }
                        } catch (_: Throwable) {
                        }

                        // Rewrite danmaku cid (bundled stack): comment.bilibili.com/<cid>.xml
                        try {
                            val m = Regex("(?i)https?://comment\\.bilibili\\.com/(-?\\d+)\\.xml").find(urlStr)
                            val legacyCid = m?.groupValues?.getOrNull(1)
                            if (!legacyCid.isNullOrBlank()) {
                                val realCid = PlayurlFixer.resolveCidOverrideForDanmaku(legacyCid)
                                if (!realCid.isNullOrBlank() && realCid != legacyCid) {
                                    val fixed = urlStr.replace(
                                        Regex("(?i)(https?://comment\\.bilibili\\.com/)(-?\\d+)(\\.xml)"),
                                        "$1$realCid$3"
                                    )
                                    if (fixed != urlStr) {
                                        val newHttpUrl = try {
                                            parseMethod.invoke(null, fixed)
                                        } catch (_: Throwable) {
                                            null
                                        }
                                        if (newHttpUrl != null) {
                                            param.args[0] = newHttpUrl
                                            XposedBridge.log("RecBiliOld: bundled rewrite danmaku cid $legacyCid -> $realCid")
                                            return
                                        }
                                    }
                                }
                            }
                        } catch (_: Throwable) {
                        }

                        val realAid = XposedSharedState.oversizedAidAtomicRef.get() ?: return
                        if (realAid <= 0L) return
                        if (!urlStr.contains("/x/v2/view", ignoreCase = true)) return

                        val placeholder = Int.MAX_VALUE.toString()
                        if (!Regex("(?i)(?:[?&])aid=$placeholder(?:&|$)").containsMatchIn(urlStr)) return

                        val fixed = urlStr.replace(
                            Regex("(?i)([?&])aid=$placeholder(?=&|$)"),
                            "$1aid=${realAid}"
                        )
                        if (fixed == urlStr) return

                        val newHttpUrl = try {
                            parseMethod.invoke(null, fixed)
                        } catch (_: Throwable) {
                            null
                        } ?: return

                        param.args[0] = newHttpUrl
                        XposedBridge.log("RecBiliOld: build-time rewrite x/v2/view aid placeholder -> realAid=$realAid")
                    }
                }
            )
            XposedBridge.log("RecBiliOld: bundled build-time view aid rewrite hook installed")
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    private fun hookBundledHttpUrlGetterAidRewriteIfPresent(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        val elpClazz =
            XposedHelpers.findClassIfExists("p000bl.elp", cl)
                ?: XposedHelpers.findClassIfExists("bl.elp", cl)
                ?: return

        val httpUrlClazz = XposedHelpers.findClassIfExists("okhttp3.HttpUrl", cl) ?: return

        val parseMethod = httpUrlClazz.declaredMethods.firstOrNull { m ->
            java.lang.reflect.Modifier.isStatic(m.modifiers) &&
                m.parameterTypes.size == 1 &&
                m.parameterTypes[0] == String::class.java &&
                m.returnType == httpUrlClazz
        } ?: return

        val getUrlMethod = elpClazz.declaredMethods.firstOrNull { m ->
            m.name == "m22166a" && m.parameterTypes.isEmpty() && m.returnType == httpUrlClazz
        } ?: return

        parseMethod.isAccessible = true
        getUrlMethod.isAccessible = true

        try {
            XposedBridge.hookMethod(
                getUrlMethod,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val realAid = XposedSharedState.oversizedAidAtomicRef.get() ?: return
                        if (realAid <= 0L) return

                        val urlObj = param.result ?: return
                        val urlStr = try { urlObj.toString() } catch (_: Throwable) { return }

                        val placeholder = Int.MAX_VALUE.toString()
                        if (!urlStr.contains(placeholder)) return
                        if (!urlStr.contains("/x/v2/view", ignoreCase = true)) return

                        val fixed = urlStr.replace(
                            Regex("(?i)([?&])aid=$placeholder(?=&|$)"),
                            "$1aid=${realAid}"
                        )
                        if (fixed == urlStr) return

                        val newHttpUrl = try {
                            parseMethod.invoke(null, fixed)
                        } catch (_: Throwable) {
                            null
                        } ?: return

                        param.result = newHttpUrl

                        // best-effort: update request field too (for later reads)
                        try {
                            XposedHelpers.setObjectField(param.thisObject, "f19637a", newHttpUrl)
                        } catch (_: Throwable) {
                        }

                        XposedBridge.log("RecBiliOld: force replace aid placeholder in HttpUrl getter realAid=$realAid")
                    }
                }
            )
            XposedBridge.log("RecBiliOld: bundled HttpUrl getter aid rewrite hook installed")
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    private fun hookBundledViewAidPlaceholderRewriteIfPresent(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        val builderClazz =
            XposedHelpers.findClassIfExists("p000bl.elp\$a", cl)
                ?: XposedHelpers.findClassIfExists("bl.elp\$a", cl)
                ?: XposedHelpers.findClassIfExists("p000bl.elp\$C2960a", cl)
                ?: XposedHelpers.findClassIfExists("bl.elp\$C2960a", cl)
                ?: return

        // elp.C2960a#m22180a(String url)
        val setUrlString = builderClazz.declaredMethods.firstOrNull { m ->
            m.name == "m22180a" && m.parameterTypes.size == 1 && m.parameterTypes[0] == String::class.java
        } ?: return

        try {
            XposedBridge.hookMethod(
                setUrlString,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val realAid = XposedSharedState.oversizedAidAtomicRef.get() ?: return
                        if (realAid <= 0L) return

                        val url = param.args.getOrNull(0) as? String ?: return
                        if (!url.contains("/x/v2/view", ignoreCase = true)) return

                        val placeholder = Int.MAX_VALUE.toString()
                        // Only rewrite when aid is the placeholder.
                        if (!Regex("(?i)(?:[?&])aid=$placeholder(?:&|$)").containsMatchIn(url)) return

                        val fixed = url.replace(
                            Regex("(?i)([?&])aid=$placeholder(?=&|$)"),
                            "$1aid=${realAid}"
                        )

                        if (fixed != url) {
                            param.args[0] = fixed
                            XposedBridge.log("RecBiliOld: rewrite x/v2/view aid placeholder -> realAid=$realAid")
                        }
                    }
                }
            )
            XposedBridge.log("RecBiliOld: bundled view aid placeholder rewrite hook installed")
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    private fun hookFatUriVideoOpenFixIfPresent(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        val fatClazz = XposedHelpers.findClassIfExists("p000bl.fat", cl)
            ?: XposedHelpers.findClassIfExists("bl.fat", cl)
            ?: return

        val openAvidMethod = fatClazz.declaredMethods.firstOrNull { m ->
            m.name == "m25082a" &&
                m.parameterTypes.size == 2 &&
                android.content.Context::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                (m.parameterTypes[1] == Int::class.javaPrimitiveType || m.parameterTypes[1] == Int::class.java)
        } ?: return

        val openUriMethod = fatClazz.declaredMethods.firstOrNull { m ->
            m.name == "m25090a" &&
                m.parameterTypes.size == 2 &&
                android.content.Context::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                m.parameterTypes[1].name == "android.net.Uri"
        } ?: return

        openAvidMethod.isAccessible = true
        openUriMethod.isAccessible = true

        try {
            XposedBridge.hookMethod(
                openUriMethod,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val ctx = param.args.getOrNull(0) as? android.content.Context ?: return
                        val uriObj = param.args.getOrNull(1) ?: return
                        val uriStr = try { uriObj.toString() } catch (_: Throwable) { return }

                        val aidLong = XposedHookUtils.extractAidFromUriStringLong(uriStr) ?: return
                        if (aidLong <= 0L) return

                        try {
                            PlayurlFixer.setLastClickedAid(aidLong.toString())
                        } catch (_: Throwable) {
                        }

                        if (aidLong <= Int.MAX_VALUE.toLong()) {
                            val aid = aidLong.toInt()
                            val key = "fatUriAvid=$aid"
                            if (cardClickLoggedOnce.add(key)) {
                                XposedBridge.log("RecBiliOld: fat.m25090a reroute to avid=$aid uri=${uriStr.take(200)}")
                            }

                            try {
                                openAvidMethod.invoke(null, ctx, aid)
                                param.result = null
                            } catch (t: Throwable) {
                                XposedBridge.log(t)
                            }
                            return
                        }

                        // Oversized aid: seed for VideoDetailsActivity oversized hook.
                        XposedSharedState.oversizedAidAtomicRef.set(aidLong)
                        PlayurlFixer.ingestOversizedAid(aidLong.toString())
                        val key = "fatUriOversizedAvid=$aidLong"
                        if (cardClickLoggedOnce.add(key)) {
                            XposedBridge.log("RecBiliOld: fat.m25090a oversized aid=$aidLong (keep uri route) uri=${uriStr.take(200)}")
                        }
                    }
                }
            )
            XposedBridge.log("RecBiliOld: fat uri->avid hook installed")
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }
    private fun hookOkHttpVerboseIfPresent(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHooksOkHttpVerbose.hookOkHttpVerboseIfPresent(lpparam)
    }

    private fun isVerboseNetworkEnabledForce(): Boolean {
        return true
    }

    private fun hookOkHttpRealCallVerboseIfPresent(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHooksOkHttpVerbose.hookOkHttpRealCallVerboseIfPresent(lpparam)
    }

    private fun findClassAny(cl: ClassLoader, vararg names: String): Class<*>? {
        for (n in names) {
            val c = XposedHelpers.findClassIfExists(n, cl)
            if (c != null) return c
        }
        return null
    }

    private fun hookVolleyViewResponseIfPresent(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        val tracerClazz = findClassAny(cl, "bl.euj", "p000bl.euj") ?: return
        val requestClazz = XposedHelpers.findClassIfExists("com.android.volley.Request", cl) ?: return
        val networkRespClazz = XposedHelpers.findClassIfExists("com.android.volley.NetworkResponse", cl) ?: return
        val volleyErrClazz = XposedHelpers.findClassIfExists("com.android.volley.VolleyError", cl) ?: return

        try {
            XposedHelpers.findAndHookMethod(
                tracerClazz,
                "endNetwork",
                requestClazz,
                networkRespClazz,
                volleyErrClazz,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val req = param.args.getOrNull(0) ?: return
                            val url = XposedHelpers.callMethod(req, "m39470h") as? String ?: return
                            if (!url.contains("/x/v2/view", ignoreCase = true)) return

                            val netResp = param.args.getOrNull(1) ?: return
                            val data = XposedHelpers.getObjectField(netResp, "data") as? ByteArray ?: return
                            if (data.isEmpty()) return
                            val bodyStr = try {
                                String(data, Charsets.UTF_8)
                            } catch (_: Throwable) {
                                return
                            }
                            if (bodyStr.isBlank()) return

                            val patched = tryStripBangumiFieldsFromViewJson(bodyStr)
                            if (!patched.isNullOrBlank()) {
                                try {
                                    XposedHelpers.setObjectField(netResp, "data", patched.toByteArray(Charsets.UTF_8))
                                    XposedBridge.log("RecBiliOld: strip view bangumi/movie fields (volley) url=$url")
                                } catch (t: Throwable) {
                                    XposedBridge.log(t)
                                }
                            }

                            XposedBridge.log("RecBiliOld: volley view resp url=$url bytes=${data.size}")
                            PlayurlFixer.ingestVideoViewRequest(url)
                            PlayurlFixer.ingestVideoViewResponse(url, patched ?: bodyStr)
                        } catch (t: Throwable) {
                            XposedBridge.log(t)
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    private fun hookFeedJumpAvidClampFixIfPresent(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        val fatClazz = findClassAny(cl, "bl.fat", "p000bl.fat") ?: return

        val uriJumpMethod = fatClazz.declaredMethods.firstOrNull { m ->
            Modifier.isStatic(m.modifiers) && m.parameterTypes.size == 2 &&
                m.parameterTypes[0] == android.content.Context::class.java &&
                m.parameterTypes[1] == android.net.Uri::class.java
        }

        fun redirectIfNeeded(ctx: Any?, avid: Int): Boolean {
            if (ctx !is android.content.Context) return false
            if (avid != Int.MAX_VALUE) return false

            val clickedAid = try { PlayurlFixer.getLastClickedAid() } catch (_: Throwable) { null }
            val target = if (!clickedAid.isNullOrBlank()) {
                "bilibili://video/$clickedAid"
            } else {
                val persisted = PlayurlFixer.getPersistedAidBvidForJump() ?: return false
                val aid = persisted.first
                val bvid = persisted.second
                if (!bvid.isNullOrBlank()) {
                    "bilibili://video/$bvid"
                } else if (!aid.isNullOrBlank()) {
                    "bilibili://video/$aid"
                } else {
                    return false
                }
            }

            try {
                XposedBridge.log("RecBiliOld: redirect int avid=2147483647 jump -> $target")
                val uri = android.net.Uri.parse(target)
                if (uriJumpMethod != null) {
                    uriJumpMethod.isAccessible = true
                    uriJumpMethod.invoke(null, ctx, uri)
                } else {
                    // fallback: direct startActivity
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                }
                return true
            } catch (t: Throwable) {
                XposedBridge.log(t)
                return false
            }
        }

        try {
            for (m in fatClazz.declaredMethods) {
                if (!Modifier.isStatic(m.modifiers)) continue
                val p = m.parameterTypes
                if (p.size == 2 && p[0] == android.content.Context::class.java && p[1] == Int::class.javaPrimitiveType) {
                    XposedBridge.hookMethod(
                        m,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val ctx = param.args.getOrNull(0)
                                val avid = (param.args.getOrNull(1) as? Int) ?: return
                                if (redirectIfNeeded(ctx, avid)) {
                                    param.result = null
                                }
                            }
                        }
                    )
                } else if (p.size == 3 && p[0] == android.content.Context::class.java && p[1] == Int::class.javaPrimitiveType && p[2] == Int::class.javaPrimitiveType) {
                    XposedBridge.hookMethod(
                        m,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val ctx = param.args.getOrNull(0)
                                val avid = (param.args.getOrNull(1) as? Int) ?: return
                                if (redirectIfNeeded(ctx, avid)) {
                                    param.result = null
                                }
                            }
                        }
                    )
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    private fun hookBundledOkHttpViewResponseIfPresent(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHooksBundledOkHttp.hookBundledOkHttpViewResponseIfPresent(
            lpparam,
            findClassAny = { cl, names -> findClassAny(cl, *names) },
            hookBundledOkHttpWithClasses = { cl, callClazz, callbackClazz ->
                hookBundledOkHttpWithClasses(cl, callClazz, callbackClazz)
            },
            hookBundledOkHttpWhenLoaded = { cl ->
                hookBundledOkHttpWhenLoaded(cl)
            }
        )
    }

    private fun hookBundledOkHttpWhenLoaded(cl: ClassLoader) {
        XposedHooksBundledOkHttp.hookBundledOkHttpWhenLoaded(
            cl,
            findClassAny = { cc, names -> findClassAny(cc, *names) },
            hookBundledOkHttpWithClasses = { cc, callClazz, callbackClazz ->
                hookBundledOkHttpWithClasses(cc, callClazz, callbackClazz)
            }
        )
    }

    private fun hookBundledOkHttpWithClasses(cl: ClassLoader, callClazz: Class<*>, callbackClazz: Class<*>) {
        XposedHooksBundledOkHttp.hookBundledOkHttpWithClasses(
            cl,
            callClazz,
            callbackClazz,
            hookedMethods,
            isBangumiGetSourceEpisode0 = { url -> isBangumiGetSourceEpisode0(url) },
            buildBundledElrJsonResponse = { ccl, req, json -> buildBundledElrJsonResponse(ccl, req, json) },
            ingestBundledRequest = { callObj -> ingestBundledRequest(callObj) },
            ingestBundledResponse = { respObj -> ingestBundledResponse(respObj) }
        )
    }

    private fun ingestBundledRequest(callObj: Any) {
        if (bundledIngestGuard.get() == true) return
        if (!bundledIngestGlobalGuard.compareAndSet(false, true)) return
        bundledIngestGuard.set(true)
        try {
            val req0 = try {
                XposedHelpers.callMethod(callObj, "mo21912a")
            } catch (_: Throwable) {
                null
            }
            val url0 = try {
                if (req0 != null) {
                    val httpUrl = XposedHelpers.callMethod(req0, "m22166a")
                    httpUrl?.toString()
                } else {
                    null
                }
            } catch (_: Throwable) {
                null
            }

            try {
                if (!url0.isNullOrBlank() && isBangumiGetSourceEpisode0(url0)) {
                    if (bundledUrlFailOnce.add("get_source:episode_id=0")) {
                        XposedBridge.log("RecBiliOld: bangumi get_source episode_id=0 seen at request stage url=$url0")
                        XposedBridge.log("RecBiliOld: callObj class=${callObj.javaClass.name} req0 class=${req0?.javaClass?.name}")
                        XposedBridge.log(Throwable("get_source episode_id=0 request stacktrace"))
                    }
                } else if (!url0.isNullOrBlank() && url0.contains("bangumi.bilibili.com/api/get_source", ignoreCase = true)) {
                    if (bundledUrlFailOnce.add("get_source:not_episode_0")) {
                        XposedBridge.log("RecBiliOld: bangumi get_source NOT episode_id=0 seen at request stage url=$url0")
                        XposedBridge.log("RecBiliOld: callObj class=${callObj.javaClass.name} req0 class=${req0?.javaClass?.name}")
                    }
                }
            } catch (_: Throwable) {
            }

            // Log request stage for stream URLs. If this never appears but playurl did,
            // the player likely didn't start streaming or is using another stack.
            try {
                if (!url0.isNullOrBlank() && (url0.contains("upgcxcode", ignoreCase = true) || url0.contains("bilivideo.com", ignoreCase = true))) {
                    if (uposReqLoggedOnce.add(url0)) {
                        val method = tryExtractBundledRequestMethod(req0)
                        val headersDump = tryDumpBundledRequestHeaders(req0)
                        XposedBridge.log(
                            "RecBiliOld: [upos-req] method=${method ?: "<null>"} url=${url0.take(220)}" +
                                (if (!headersDump.isNullOrBlank()) " headers=\n$headersDump" else "")
                        )
                    }
                }
            } catch (_: Throwable) {
            }

            val req = try {
                XposedHelpers.callMethod(callObj, "a")
            } catch (_: Throwable) {
                null
            }

            val reqCandidate = (req ?: req0) ?: return
            val urlCandidate = extractUrlFromRequestLike(reqCandidate) ?: url0
            val url = urlCandidate ?: return

            try {
                if (url.contains("/x/v2/reply", ignoreCase = true)) {
                    if (bundledUrlFailOnce.add("bundled-reply-req:" + url.take(160))) {
                        XposedBridge.log("RecBiliOld: [bundled-reply-req] url=${url.take(220)}")
                    }
                }
            } catch (_: Throwable) {
            }

            try {
                maybeInjectDesktopHeadersForBundledCall(callObj, reqCandidate, url)
            } catch (t: Throwable) {
                XposedBridge.log(t)
            }

            try {
                maybeRewriteCommentForBundledCall(callObj, reqCandidate, url)
            } catch (t: Throwable) {
                XposedBridge.log(t)
            }

            if (isViewUrl(url)) {
                XposedBridge.log("RecBiliOld: seen view request url=$url")
            }
            PlayurlFixer.ingestVideoViewRequest(url)
        } catch (t: Throwable) {
            XposedBridge.log(t)
        } finally {
            bundledIngestGuard.set(false)
            bundledIngestGlobalGuard.set(false)
        }
    }

    private fun patchBundledResponseBody(responseObj: Any, body: String): Boolean {
        val bytes = body.toByteArray(Charsets.UTF_8)
        var patched = false

        // Hard path: bundled okhttp Response = bl.elr, body field = f19662g (els)
        try {
            if (responseObj.javaClass.name == "p000bl.elr" || responseObj.javaClass.name.endsWith(".elr")) {
                val oldBody = try {
                    XposedHelpers.getObjectField(responseObj, "f19662g")
                } catch (_: Throwable) {
                    try {
                        responseObj.javaClass.declaredFields.firstOrNull { f ->
                            if (Modifier.isStatic(f.modifiers)) return@firstOrNull false
                            val tn = f.type.name
                            tn == "p000bl.els" || tn.endsWith(".els")
                        }?.let { f ->
                            f.isAccessible = true
                            f.get(responseObj)
                        }
                    } catch (_: Throwable) {
                        null
                    }
                }

                var mediaType: Any? = try {
                    XposedHelpers.callMethod(oldBody, "mo3375a")
                } catch (_: Throwable) {
                    null
                }

                if (mediaType == null) {
                    mediaType = try {
                        val ellClazz =
                            XposedHelpers.findClassIfExists("p000bl.ell", responseObj.javaClass.classLoader)
                                ?: XposedHelpers.findClassIfExists("bl.ell", responseObj.javaClass.classLoader)
                        val parse = ellClazz?.declaredMethods?.firstOrNull { m ->
                            Modifier.isStatic(m.modifiers) && m.parameterTypes.size == 1 && m.parameterTypes[0] == String::class.java
                        }
                        if (parse != null) {
                            parse.isAccessible = true
                            parse.invoke(null, "application/json; charset=utf-8")
                        } else null
                    } catch (_: Throwable) {
                        null
                    }
                }

                val newBody = try {
                    val cl = responseObj.javaClass.classLoader
                    val elsClazz =
                        XposedHelpers.findClassIfExists("p000bl.els", cl)
                            ?: XposedHelpers.findClassIfExists("bl.els", cl)

                    if (elsClazz == null) {
                        null
                    } else {
                        val create = elsClazz.declaredMethods.firstOrNull { m ->
                            if (!Modifier.isStatic(m.modifiers)) return@firstOrNull false
                            val p = m.parameterTypes
                            p.size == 2 &&
                                p[1] == String::class.java &&
                                (m.returnType == elsClazz || m.returnType.name.endsWith(".els"))
                        }
                        if (create != null) {
                            create.isAccessible = true
                            create.invoke(null, mediaType, body)
                        } else {
                            null
                        }
                    }
                } catch (_: Throwable) {
                    null
                }

                if (newBody != null) {
                    try {
                        var setOk = false
                        try {
                            val f = responseObj.javaClass.getDeclaredField("f19662g")
                            f.isAccessible = true
                            f.set(responseObj, newBody)
                            setOk = true
                        } catch (_: Throwable) {
                        }

                        if (!setOk) {
                            for (f in responseObj.javaClass.declaredFields) {
                                try {
                                    if (Modifier.isStatic(f.modifiers)) continue
                                    val tn = f.type.name
                                    if (tn != "p000bl.els" && !tn.endsWith(".els")) continue
                                    f.isAccessible = true
                                    f.set(responseObj, newBody)
                                    setOk = true
                                    break
                                } catch (_: Throwable) {
                                }
                            }
                        }

                        if (setOk) patched = true
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (_: Throwable) {
        }

        // Fallback: patch common byte[] holder fields if present
        try {
            val f = responseObj.javaClass.getDeclaredField("data")
            f.isAccessible = true
            f.set(responseObj, bytes)
            patched = true
        } catch (_: Throwable) {
        }

        try {
            for (f in responseObj.javaClass.declaredFields) {
                try {
                    if (Modifier.isStatic(f.modifiers)) continue
                    if (f.type != ByteArray::class.java) continue
                    f.isAccessible = true
                    val old = f.get(responseObj) as? ByteArray ?: continue
                    val s = try { String(old, Charsets.UTF_8) } catch (_: Throwable) { null }
                    if (s != null) {
                        val t = s.trimStart()
                        if (t.startsWith("{") || t.startsWith("[")) {
                            f.set(responseObj, bytes)
                            patched = true
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }

        return patched
    }

    private fun maybeRewriteCommentForBundledCall(callObj: Any, reqObj: Any, url: String) {
        if (!url.contains("/x/v2/reply", ignoreCase = true)) return

        XposedBridge.log("RecBiliOld: seen legacy comment url=$url")

        // Request-stage rewrite for nested replies: /x/v2/reply/reply
        // Goal: avoid sending negative/truncated oid at all.
        try {
            if (url.contains("/x/v2/reply/reply", ignoreCase = true)) {
                val oidRaw0 = parseQueryParam(url, "oid")
                val type0 = parseQueryParam(url, "type")
                val root0 = parseQueryParam(url, "root")
                val cachedRoot = try { PlayurlFixer.getLastCommentContextRpid() } catch (_: Throwable) { null }
                val rootFixed0 = run {
                    val r0 = root0?.takeIf { it.isNotBlank() } ?: cachedRoot?.takeIf { it.isNotBlank() }
                    if (r0.isNullOrBlank()) return@run null
                    val rn = r0.toLongOrNull()
                    if (rn != null && rn < 0) (rn and 0xFFFF_FFFFL).toString() else r0
                }

                if (!oidRaw0.isNullOrBlank() && !type0.isNullOrBlank()) {
                    val oidFixed = run {
                        try {
                            val normalizedForLookup = run {
                                val n0 = oidRaw0.toLongOrNull()
                                if (n0 != null && n0 < 0) (n0 and 0xFFFF_FFFFL).toString() else oidRaw0
                            }
                            val restored = PlayurlFixer.resolveFullAidByLow32(normalizedForLookup)
                            if (!restored.isNullOrBlank() && restored != oidRaw0) {
                                return@run restored
                            }
                        } catch (_: Throwable) {
                        }

                        // Prefer cached CommentActivity oid only when it is a full 64-bit aid.
                        try {
                            val cached = PlayurlFixer.getLastCommentContextOid()
                            val c = cached?.toLongOrNull()
                            if (c != null && c > Int.MAX_VALUE.toLong()) {
                                return@run c.toString()
                            }
                        } catch (_: Throwable) {
                        }

                        val n = oidRaw0.toLongOrNull()
                        if (n != null && n < 0) (n and 0xFFFF_FFFFL).toString() else oidRaw0
                    }

                    if (oidFixed != oidRaw0) {
                        val base = url.substringBefore('?', url)
                        val query0 = url.substringAfter('?', "")
                        val qp = LinkedHashMap<String, String>()
                        if (query0.isNotBlank()) {
                            for (p in query0.split('&')) {
                                if (p.isBlank()) continue
                                val idx = p.indexOf('=')
                                val k = if (idx >= 0) p.substring(0, idx) else p
                                val v = if (idx >= 0) p.substring(idx + 1) else ""
                                qp[k] = v
                            }
                        }
                        qp["oid"] = oidFixed
                        if (!rootFixed0.isNullOrBlank()) {
                            qp["root"] = rootFixed0
                        }
                        val newUrl = base + "?" + qp.entries.joinToString("&") { (k, v) -> "$k=$v" }

                        val builderObj = try { XposedHelpers.callMethod(reqObj, "m22172f") } catch (_: Throwable) { null } ?: return
                        val builderClazz = builderObj.javaClass
                        val setUrlMethod = builderClazz.declaredMethods.firstOrNull { m ->
                            m.parameterTypes.size == 1 && m.parameterTypes[0] == String::class.java && m.returnType == builderClazz
                        }
                        val addHeaderMethod = builderClazz.declaredMethods.firstOrNull { m ->
                            m.parameterTypes.size == 2 &&
                                m.parameterTypes[0] == String::class.java &&
                                m.parameterTypes[1] == String::class.java &&
                                (m.returnType == Void.TYPE || m.returnType == builderClazz)
                        }
                        val buildMethod = builderClazz.declaredMethods.firstOrNull { m ->
                            m.parameterTypes.isEmpty() && m.returnType == reqObj.javaClass
                        }
                        if (setUrlMethod != null && buildMethod != null) {
                            setUrlMethod.isAccessible = true
                            if (addHeaderMethod != null) addHeaderMethod.isAccessible = true
                            buildMethod.isAccessible = true
                            setUrlMethod.invoke(builderObj, newUrl)
                            try {
                                if (addHeaderMethod != null) {
                                    addHeaderMethod.invoke(builderObj, "Accept", "application/json")
                                    addHeaderMethod.invoke(builderObj, "User-Agent", DESKTOP_UA)
                                    addHeaderMethod.invoke(builderObj, "Referer", DESKTOP_REFERER)
                                    addHeaderMethod.invoke(builderObj, "Origin", "https://www.bilibili.com")
                                }
                            } catch (_: Throwable) {
                            }
                            val rebuiltReq = buildMethod.invoke(builderObj) ?: return

                            var replaced = false
                            try {
                                val f = callObj.javaClass.getDeclaredField("f19632c")
                                f.isAccessible = true
                                f.set(callObj, rebuiltReq)
                                replaced = true
                            } catch (_: Throwable) {
                            }
                            if (!replaced) {
                                try {
                                    for (f in callObj.javaClass.declaredFields) {
                                        try {
                                            if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                                            f.isAccessible = true
                                            val v = f.get(callObj) ?: continue
                                            if (v.javaClass == reqObj.javaClass || v.javaClass.name.endsWith(".elp")) {
                                                f.set(callObj, rebuiltReq)
                                                replaced = true
                                                break
                                            }
                                        } catch (_: Throwable) {
                                        }
                                    }
                                } catch (_: Throwable) {
                                }
                            }

                            if (replaced) {
                                XposedBridge.log(
                                    "RecBiliOld: rewrite bundled reply/reply oid=$oidRaw0 -> $oidFixed" +
                                        (if (!rootFixed0.isNullOrBlank() && root0.isNullOrBlank()) " (inject root=$rootFixed0)" else "")
                                )
                            }
                        }
                    }
                }
                return
            }
        } catch (_: Throwable) {
        }

        val query = try {
            java.net.URI(url).rawQuery.orEmpty()
        } catch (_: Throwable) {
            url.substringAfter('?', "")
        }

        fun getParam(name: String): String? {
            val parts = query.split('&')
            for (p in parts) {
                if (p.isBlank()) continue
                val idx = p.indexOf('=')
                val k = if (idx >= 0) p.substring(0, idx) else p
                if (!k.equals(name, ignoreCase = true)) continue
                return if (idx >= 0) p.substring(idx + 1) else ""
            }
            return null
        }

        val pn = getParam("pn") ?: "1"
        if (pn != "1") return

        val oidRaw = getParam("oid")?.takeIf { it.isNotBlank() } ?: return
        val type = getParam("type")?.takeIf { it.isNotBlank() } ?: return
        val ps = getParam("ps")?.takeIf { it.isNotBlank() }
        val sort = getParam("sort")
        val nohot = getParam("nohot")

        val oid = run {
            // 1) If the app truncated an oversized aid into low32, restore full 64-bit aid based on view request cache.
            try {
                val restored = PlayurlFixer.resolveFullAidByLow32(oidRaw)
                if (!restored.isNullOrBlank() && restored != oidRaw) {
                    XposedBridge.log("RecBiliOld: restore truncated oid=$oidRaw -> fullAid=$restored")
                    return@run restored
                }
            } catch (_: Throwable) {
            }

            // 1.5) If we have a recent full 64-bit aid from card click, prefer it.
            try {
                val clicked = PlayurlFixer.getLastClickedAid()
                if (!clicked.isNullOrBlank()) {
                    val c = clicked.toLongOrNull()
                    // Only use as fallback when current oid looks like a 32-bit value.
                    val n = oidRaw.toLongOrNull()
                    if (c != null && c > Int.MAX_VALUE.toLong() && n != null && n in 1..0xFFFF_FFFFL) {
                        XposedBridge.log("RecBiliOld: fallback oid via lastClickedAid oid=$oidRaw -> $clicked")
                        return@run clicked
                    }
                }
            } catch (_: Throwable) {
            }

            // 2) Legacy client may overflow 32-bit oid into a negative signed int.
            val n = oidRaw.toLongOrNull()
            if (n != null && n < 0) {
                // Convert to unsigned 32-bit string to make server accept it.
                val fixed = (n and 0xFFFF_FFFFL)
                XposedBridge.log("RecBiliOld: normalize negative oid=$oidRaw -> $fixed")
                fixed.toString()
            } else {
                oidRaw
            }
        }

        val mode = when (sort) {
            "0", null -> "2"
            else -> "3"
        }

        val baseParams = LinkedHashMap<String, String>()
        baseParams["oid"] = oid
        baseParams["type"] = type
        baseParams["mode"] = mode
        if (!ps.isNullOrBlank()) baseParams["ps"] = ps
        if (!nohot.isNullOrBlank()) baseParams["nohot"] = nohot

        val signed = try {
            PlayurlFixer.signWbiParams(baseParams)
        } catch (t: Throwable) {
            XposedBridge.log(t)
            return
        }
        val scheme = if (url.startsWith("http://", ignoreCase = true)) "http" else "https"
        val newUrl = "$scheme://api.bilibili.com/x/v2/reply/wbi/main?" + toQueryStringForWbi(signed)

        val builderObj = try {
            XposedHelpers.callMethod(reqObj, "m22172f")
        } catch (_: Throwable) {
            null
        } ?: return

        val builderClazz = builderObj.javaClass

        val setUrlMethod = builderClazz.declaredMethods.firstOrNull { m ->
            m.parameterTypes.size == 1 &&
                m.parameterTypes[0] == String::class.java &&
                m.returnType == builderClazz
        }

        val addHeaderMethod = builderClazz.declaredMethods.firstOrNull { m ->
            m.parameterTypes.size == 2 &&
                m.parameterTypes[0] == String::class.java &&
                m.parameterTypes[1] == String::class.java &&
                (m.returnType == Void.TYPE || m.returnType == builderClazz)
        }

        val buildMethod = builderClazz.declaredMethods.firstOrNull { m ->
            m.parameterTypes.isEmpty() && m.returnType == reqObj.javaClass
        }
        if (setUrlMethod == null || buildMethod == null) {
            XposedBridge.log(
                "RecBiliOld: comment rewrite failed: builder methods not found setUrl=${setUrlMethod != null} build=${buildMethod != null} builder=${builderClazz.name}"
            )
            return
        }

        setUrlMethod.isAccessible = true
        if (addHeaderMethod != null) addHeaderMethod.isAccessible = true
        buildMethod.isAccessible = true

        setUrlMethod.invoke(builderObj, newUrl)

        // Inject minimal headers required by newer comment endpoints (and generally helps anti-abuse).
        try {
            if (addHeaderMethod != null) {
                addHeaderMethod.invoke(builderObj, "Accept", "application/json")
                addHeaderMethod.invoke(builderObj, "User-Agent", DESKTOP_UA)
                addHeaderMethod.invoke(builderObj, "Referer", DESKTOP_REFERER)
                addHeaderMethod.invoke(builderObj, "Origin", "https://www.bilibili.com")
            }
        } catch (_: Throwable) {
        }

        val rebuiltReq = buildMethod.invoke(builderObj) ?: return

        var replaced = false
        try {
            val f = callObj.javaClass.getDeclaredField("f19632c")
            f.isAccessible = true
            f.set(callObj, rebuiltReq)
            replaced = true
        } catch (_: Throwable) {
        }

        if (!replaced) {
            try {
                for (f in callObj.javaClass.declaredFields) {
                    try {
                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                        f.isAccessible = true
                        val v = f.get(callObj) ?: continue
                        if (v.javaClass == reqObj.javaClass || v.javaClass.name.endsWith(".elp")) {
                            f.set(callObj, rebuiltReq)
                            replaced = true
                            break
                        }
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
        }

        if (replaced) {
            XposedBridge.log("RecBiliOld: rewrite bundled comment /x/v2/reply -> /x/v2/reply/wbi/main oid=$oid type=$type mode=$mode")
        } else {
            XposedBridge.log("RecBiliOld: comment rewrite failed: could not replace request field in call=${callObj.javaClass.name}")
        }
    }

    private fun toQueryStringForWbi(params: Map<String, String>): String {
        return params.entries.joinToString("&") { (k, v) ->
            "${percentEncodeForWbi(k)}=${percentEncodeForWbi(v)}"
        }
    }

    private fun parseQueryParam(url: String, name: String): String? {
        return try {
            val query = try {
                java.net.URI(url).rawQuery.orEmpty()
            } catch (_: Throwable) {
                url.substringAfter('?', "")
            }
            for (p in query.split('&')) {
                if (p.isBlank()) continue
                val idx = p.indexOf('=')
                val k = if (idx >= 0) p.substring(0, idx) else p
                if (!k.equals(name, ignoreCase = true)) continue
                return if (idx >= 0) p.substring(idx + 1) else ""
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private val bundledCommentPatchedOnce = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private fun tryPatchBundledCommentResponseBodyIfNeeded(responseObj: Any, url: String, bodyStr: String): String? {
        try {
            if (!url.contains("/x/v2/reply", ignoreCase = true)) return null
            if (!bodyStr.trimStart().startsWith("{")) return null

            val root = JSONObject.parseObject(bodyStr) ?: return null

            // Nested replies (楼中楼): /x/v2/reply/reply
            // This endpoint often returns -400 when oid is a signed32 overflow/truncated value.
            // Rescue by re-fetching the same legacy endpoint with corrected oid and replace body.
            try {
                if (url.contains("/x/v2/reply/reply", ignoreCase = true)) {
                    val code0 = root.getIntValue("code")
                    val msg0 = try { root.getString("message") } catch (_: Throwable) { null }
                    val shouldRescue = code0 == -404 || code0 == -400 || (code0 != 0 && (msg0?.contains("啥都木有") == true || msg0?.contains("请求错误") == true))
                    if (shouldRescue) {
                        val oidRaw = parseQueryParam(url, "oid")
                        val type = parseQueryParam(url, "type")
                        val rootRpid = parseQueryParam(url, "root")
                        val pn = parseQueryParam(url, "pn")
                        val ps = parseQueryParam(url, "ps")

                        val rootRpidFixed = run {
                            val r0 = rootRpid?.takeIf { it.isNotBlank() }
                                ?: try { PlayurlFixer.getLastCommentContextRpid() } catch (_: Throwable) { null }
                            if (r0.isNullOrBlank()) return@run null
                            val rn = r0.toLongOrNull()
                            if (rn != null && rn < 0) (rn and 0xFFFF_FFFFL).toString() else r0
                        }

                        if (!oidRaw.isNullOrBlank() && !type.isNullOrBlank() && !rootRpidFixed.isNullOrBlank()) {
                            val rescueKey = "rescue-r2r-fetch:oid=$oidRaw&type=$type&root=$rootRpid&pn=${pn ?: ""}&ps=${ps ?: ""}"
                            if (!bundledCommentPatchedOnce.add(rescueKey)) {
                                return null
                            }

                            val oid = run {
                                try {
                                    val normalizedForLookup = run {
                                        val n0 = oidRaw.toLongOrNull()
                                        if (n0 != null && n0 < 0) (n0 and 0xFFFF_FFFFL).toString() else oidRaw
                                    }
                                    val restored = PlayurlFixer.resolveFullAidByLow32(normalizedForLookup)
                                    if (!restored.isNullOrBlank() && restored != oidRaw) {
                                        return@run restored
                                    }
                                } catch (_: Throwable) {
                                }

                                try {
                                    val clicked = PlayurlFixer.getLastClickedAid()
                                    if (!clicked.isNullOrBlank()) return@run clicked
                                } catch (_: Throwable) {
                                }

                                val n = oidRaw.toLongOrNull()
                                if (n != null && n < 0) (n and 0xFFFF_FFFFL).toString() else oidRaw
                            }

                            try {
                                if (bundledCommentPatchedOnce.add("rescue-r2r-attempt:" + url)) {
                                    XposedBridge.log(
                                        "RecBiliOld: try rescue bundled reply/reply code=$code0 msg=${msg0 ?: "<null>"} oidRaw=$oidRaw -> oid=$oid type=$type root=$rootRpid url=${url.take(220)}"
                                    )
                                }
                            } catch (_: Throwable) {
                            }

                            val (http, fetched) = try {
                                PlayurlFixer.fetchReplyReplyLegacy(
                                    oid = oid,
                                    root = rootRpidFixed,
                                    type = type,
                                    pn = pn,
                                    ps = ps,
                                )
                            } catch (_: Throwable) {
                                0 to ""
                            }

                            if (http in 200..299 && fetched.isNotBlank()) {
                                val fetchedRoot = try { JSONObject.parseObject(fetched) } catch (_: Throwable) { null }
                                if (fetchedRoot != null && fetchedRoot.getIntValue("code") == 0) {
                                    // Some builds treat null list fields as fatal (addAll(null) -> NPE).
                                    // Ensure list-like fields exist as arrays.
                                    try {
                                        val d = fetchedRoot.getJSONObject("data")
                                        if (d != null) {
                                            if (!d.containsKey("replies") || d.get("replies") == null) {
                                                d["replies"] = JSONObject.parseArray("[]")
                                            }
                                            val rootObj = d.getJSONObject("root")
                                            if (rootObj != null && (!rootObj.containsKey("replies") || rootObj.get("replies") == null)) {
                                                rootObj["replies"] = JSONObject.parseArray("[]")
                                            }
                                        }
                                    } catch (_: Throwable) {
                                    }
                                    if (bundledCommentPatchedOnce.add("rescue-r2r:" + url)) {
                                        XposedBridge.log("RecBiliOld: rescued bundled reply/reply oid=$oid type=$type root=$rootRpid")
                                    }
                                    val out = fetchedRoot.toJSONString()
                                    return patchReplyReplyListFields(out)
                                }

                                try {
                                    if (bundledCommentPatchedOnce.add("rescue-r2r-failed:code:" + url)) {
                                        val fc = try { fetchedRoot?.getIntValue("code") } catch (_: Throwable) { null }
                                        val fm = try { fetchedRoot?.getString("message") } catch (_: Throwable) { null }
                                        XposedBridge.log(
                                            "RecBiliOld: rescue reply/reply fetched but not ok http=$http code=${fc ?: "?"} msg=${fm ?: "<null>"} body=${fetched.take(200)}"
                                        )
                                    }
                                } catch (_: Throwable) {
                                }
                            } else {
                                try {
                                    if (bundledCommentPatchedOnce.add("rescue-r2r-http-failed:" + url)) {
                                        XposedBridge.log("RecBiliOld: rescue reply/reply http failed http=$http body=${fetched.take(200)}")
                                    }
                                } catch (_: Throwable) {
                                }
                            }
                        }
                    }

                    // For /x/v2/reply/reply, do not apply the wbi/main -> legacy conversion below.
                    return null
                }
            } catch (_: Throwable) {
            }

            // Response-stage rescue: legacy /x/v2/reply may return -404/-400 for truncated/overflow oid.
            // If request-stage rewrite was missed, fetch wbi/main here and replace body.
            try {
                val code0 = root.getIntValue("code")
                val msg0 = try { root.getString("message") } catch (_: Throwable) { null }
                val shouldRescue = code0 == -404 || code0 == -400 || (code0 != 0 && (msg0?.contains("啥都木有") == true || msg0?.contains("请求错误") == true))
                if (shouldRescue) {
                    val oidRaw = parseQueryParam(url, "oid")
                    val type = parseQueryParam(url, "type")
                    val ps = parseQueryParam(url, "ps")
                    val sort = parseQueryParam(url, "sort")
                    val nohot = parseQueryParam(url, "nohot")
                    val pn = parseQueryParam(url, "pn")

                    if (!oidRaw.isNullOrBlank() && !type.isNullOrBlank()) {
                        // Load-more uses legacy /x/v2/reply with pn>1. wbi/main uses cursor pagination and is not compatible.
                        // For pn>1, rescue by re-fetching the same legacy endpoint with corrected oid.
                        if (!pn.isNullOrBlank() && pn != "1") {
                            val rescueKey = "rescue-reply-legacy:oid=$oidRaw&type=$type&pn=$pn&ps=${ps ?: ""}&sort=${sort ?: ""}&nohot=${nohot ?: ""}"
                            if (!bundledCommentPatchedOnce.add(rescueKey)) {
                                return null
                            }

                            val oid = run {
                                try {
                                    val normalizedForLookup = run {
                                        val n0 = oidRaw.toLongOrNull()
                                        if (n0 != null && n0 < 0) (n0 and 0xFFFF_FFFFL).toString() else oidRaw
                                    }
                                    val restored = PlayurlFixer.resolveFullAidByLow32(normalizedForLookup)
                                    if (!restored.isNullOrBlank() && restored != oidRaw) {
                                        return@run restored
                                    }
                                } catch (_: Throwable) {
                                }
                                try {
                                    val clicked = PlayurlFixer.getLastClickedAid()
                                    if (!clicked.isNullOrBlank()) return@run clicked
                                } catch (_: Throwable) {
                                }
                                val n = oidRaw.toLongOrNull()
                                if (n != null && n < 0) (n and 0xFFFF_FFFFL).toString() else oidRaw
                            }

                            try {
                                if (bundledCommentPatchedOnce.add("rescue-reply-legacy-attempt:" + url)) {
                                    XposedBridge.log(
                                        "RecBiliOld: try rescue legacy /x/v2/reply pn=$pn code=$code0 msg=${msg0 ?: "<null>"} oidRaw=$oidRaw -> oid=$oid type=$type url=${url.take(220)}"
                                    )
                                }
                            } catch (_: Throwable) {
                            }

                            val (http, fetched) = try {
                                PlayurlFixer.fetchReplyLegacyMainListCloneAndResign(
                                    originalUrl = url,
                                    oid = oid,
                                    pn = pn,
                                    ps = ps,
                                    sort = sort,
                                    nohot = nohot,
                                )
                            } catch (_: Throwable) {
                                0 to ""
                            }

                            if (http in 200..299 && fetched.isNotBlank()) {
                                val fetchedRoot = try { JSONObject.parseObject(fetched) } catch (_: Throwable) { null }
                                if (fetchedRoot != null && fetchedRoot.getIntValue("code") == 0) {
                                    if (bundledCommentPatchedOnce.add("rescue-reply-legacy:" + url)) {
                                        XposedBridge.log("RecBiliOld: rescued legacy /x/v2/reply pn=$pn oid=$oid type=$type")
                                    }
                                    val s = patchReplyMainListFields(fetchedRoot.toJSONString())
                                    // Ensure page fields are usable for legacy hasMore calculation.
                                    try {
                                        val rr = JSONObject.parseObject(s)
                                        val d = rr?.getJSONObject("data")
                                        val page = d?.getJSONObject("page")
                                        val count0 = (try { page?.getLongValue("count") } catch (_: Throwable) { null }) ?: 0L
                                        val acount0 = (try { page?.getLongValue("acount") } catch (_: Throwable) { null }) ?: 0L
                                        val num0 = (try { page?.getIntValue("num") } catch (_: Throwable) { null }) ?: 0
                                        val size0 = (try { page?.getIntValue("size") } catch (_: Throwable) { null }) ?: 0
                                        val psInt = ps?.toIntOrNull() ?: 20
                                        val pnInt = pn.toIntOrNull() ?: 1
                                        if (page != null) {
                                            if (num0 <= 0) page["num"] = pnInt
                                            if (size0 <= 0) page["size"] = psInt
                                            val cachedTotal = try { PlayurlFixer.getCommentTotalCount(oid = oid, type = type) } catch (_: Throwable) { null }
                                            if ((count0 <= 0L || acount0 <= 0L) && cachedTotal != null && cachedTotal > 0L) {
                                                page["count"] = cachedTotal
                                                page["acount"] = cachedTotal
                                            }
                                        }
                                        return rr?.toJSONString() ?: s
                                    } catch (_: Throwable) {
                                    }
                                    return s
                                }
                            }

                            // Fall through to wbi/main rescue only when pn==1.
                            return null
                        }

                        val mode = when (sort) {
                            "0", null -> "2"
                            else -> "3"
                        }

                        // Avoid repeated rescue fetches for the same comment request.
                        val rescueKey = "rescue-fetch:oid=$oidRaw&type=$type&mode=$mode&ps=${ps ?: ""}&nohot=${nohot ?: ""}"
                        if (!bundledCommentPatchedOnce.add(rescueKey)) {
                            return null
                        }

                        val oid = run {
                            try {
                                val normalizedForLookup = run {
                                    val n0 = oidRaw.toLongOrNull()
                                    if (n0 != null && n0 < 0) (n0 and 0xFFFF_FFFFL).toString() else oidRaw
                                }
                                val restored = PlayurlFixer.resolveFullAidByLow32(normalizedForLookup)
                                if (!restored.isNullOrBlank() && restored != oidRaw) {
                                    return@run restored
                                }
                            } catch (_: Throwable) {
                            }
                            try {
                                val clicked = PlayurlFixer.getLastClickedAid()
                                if (!clicked.isNullOrBlank()) return@run clicked
                            } catch (_: Throwable) {
                            }
                            val n = oidRaw.toLongOrNull()
                            if (n != null && n < 0) (n and 0xFFFF_FFFFL).toString() else oidRaw
                        }

                        try {
                            if (bundledCommentPatchedOnce.add("rescue-attempt:" + url)) {
                                XposedBridge.log(
                                    "RecBiliOld: try rescue bundled comment code=$code0 msg=${msg0 ?: "<null>"} oidRaw=$oidRaw -> oid=$oid type=$type mode=$mode url=${url.take(220)}"
                                )
                            }
                        } catch (_: Throwable) {
                        }

                        val (http, fetched) = try {
                            PlayurlFixer.fetchCommentWbiMain(
                                oid = oid,
                                type = type,
                                mode = mode,
                                ps = ps,
                                nohot = nohot,
                            )
                        } catch (_: Throwable) {
                            0 to ""
                        }

                        if (http in 200..299 && fetched.isNotBlank()) {
                            val fetchedRoot = try { JSONObject.parseObject(fetched) } catch (_: Throwable) { null }
                            if (fetchedRoot != null && fetchedRoot.getIntValue("code") == 0) {
                                // Convert wbi/main json into legacy reply shape (page/top/cursor removal)
                                val d = fetchedRoot.getJSONObject("data")
                                if (d != null && d.containsKey("cursor")) {
                                    val cursor = d.getJSONObject("cursor")
                                    val pn1 = parseQueryParam(url, "pn")?.toIntOrNull() ?: 1
                                    val ps1 = ps?.toIntOrNull() ?: 20
                                    val allCount = (try { cursor?.getLongValue("all_count") } catch (_: Throwable) { null }) ?: 0L
                                    try {
                                        if (allCount > 0L) {
                                            PlayurlFixer.putCommentTotalCount(oid = oid, type = type, totalCount = allCount)
                                        }
                                    } catch (_: Throwable) {
                                    }
                                    val pageObj = JSONObject()
                                    pageObj["num"] = pn1
                                    pageObj["size"] = ps1
                                    pageObj["count"] = allCount
                                    pageObj["acount"] = allCount
                                    d["page"] = pageObj
                                    d["top"] = null
                                    d.remove("cursor")
                                }
                                if (bundledCommentPatchedOnce.add("rescue:" + url)) {
                                    XposedBridge.log("RecBiliOld: rescued bundled comment via wbi/main oid=$oid type=$type mode=$mode")
                                }
                                return fetchedRoot.toJSONString()
                            }

                            try {
                                if (bundledCommentPatchedOnce.add("rescue-failed:code:" + url)) {
                                    val fc = try { fetchedRoot?.getIntValue("code") } catch (_: Throwable) { null }
                                    val fm = try { fetchedRoot?.getString("message") } catch (_: Throwable) { null }
                                    XposedBridge.log(
                                        "RecBiliOld: rescue fetched but not ok http=$http code=${fc ?: "?"} msg=${fm ?: "<null>"} body=${fetched.take(200)}"
                                    )
                                }
                            } catch (_: Throwable) {
                            }
                        } else {
                            try {
                                if (bundledCommentPatchedOnce.add("rescue-http-failed:" + url)) {
                                    XposedBridge.log("RecBiliOld: rescue http failed http=$http body=${fetched.take(200)}")
                                }
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }

            val data = root.getJSONObject("data") ?: return null

            // Only patch if it looks like the new lazy-load shape.
            if (!data.containsKey("cursor")) return null

            val cursor = data.getJSONObject("cursor")

            val pn = parseQueryParam(url, "pn")?.toIntOrNull() ?: 1
            val ps = parseQueryParam(url, "ps")?.toIntOrNull() ?: 20
            val allCount = try { cursor?.getLongValue("all_count") } catch (_: Throwable) { 0L }

            val pageObj = JSONObject()
            pageObj["num"] = pn
            pageObj["size"] = ps
            pageObj["count"] = allCount
            pageObj["acount"] = allCount

            // Make response compatible with old model tv.danmaku.bili.ui.comment.api.BiliCommentList
            data["page"] = pageObj
            data["top"] = null
            data.remove("cursor")

            val patched = root.toJSONString()

            // Logging (only once per unique url to avoid spam)
            try {
                if (bundledCommentPatchedOnce.add(url)) {
                    val (code, msg) = getBundledElrCodeMessage(responseObj)
                    XposedBridge.log(
                        "RecBiliOld: patch bundled comment response (wbi/main -> legacy) http=${code ?: "?"} msg=${msg ?: "<null>"} url=${url.take(220)}"
                    )
                }
            } catch (_: Throwable) {
            }

            return patched
        } catch (_: Throwable) {
            return null
        }
    }

    private fun patchReplyMainListFields(json: String): String {
        // Ensure list fields are never null for old models (BiliCommentList.mList / mHotList).
        return try {
            val root = try { JSONObject.parseObject(json) } catch (_: Throwable) { null } ?: return json
            val data = try { root.getJSONObject("data") } catch (_: Throwable) { null } ?: return json

            val repliesVal = try { data.get("replies") } catch (_: Throwable) { null }
            if (repliesVal == null) {
                data["replies"] = com.alibaba.fastjson.JSONArray()
            }
            val hotsVal = try { data.get("hots") } catch (_: Throwable) { null }
            if (hotsVal == null) {
                data["hots"] = com.alibaba.fastjson.JSONArray()
            }

            root.toJSONString()
        } catch (_: Throwable) {
            json
        }
    }

    private fun patchReplyReplyListFields(json: String): String {
        // Ensure list fields are never null for old models (BiliCommentList.mList / mHotList).
        return try {
            val root = try { JSONObject.parseObject(json) } catch (_: Throwable) { null } ?: return json
            val data = try { root.getJSONObject("data") } catch (_: Throwable) { null } ?: return json

            val repliesVal = try { data.get("replies") } catch (_: Throwable) { null }
            if (repliesVal == null) {
                data["replies"] = com.alibaba.fastjson.JSONArray()
            }
            val hotsVal = try { data.get("hots") } catch (_: Throwable) { null }
            if (hotsVal == null) {
                data["hots"] = com.alibaba.fastjson.JSONArray()
            }

            root.toJSONString()
        } catch (_: Throwable) {
            json
        }
    }

    private fun percentEncodeForWbi(s: String): String {
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

    private fun maybeInjectDesktopHeadersForBundledCall(callObj: Any, reqObj: Any, url: String) {
        if (!url.contains("bilivideo.com", ignoreCase = true)) return

        // Avoid extremely spammy logs for repeated segment fetches.
        if (bundledHeaderInjectedOnce.add(url)) {
            XposedBridge.log("RecBiliOld: try inject desktop headers (bundled call) url=$url")
        }

        val reqClazz = reqObj.javaClass
        val builderObj = try {
            XposedHelpers.callMethod(reqObj, "m22172f")
        } catch (_: Throwable) {
            null
        } ?: return

        val builderClazz = builderObj.javaClass

        val addHeaderMethod = builderClazz.declaredMethods.firstOrNull { m ->
            m.parameterTypes.size == 2 &&
                m.parameterTypes[0] == String::class.java &&
                m.parameterTypes[1] == String::class.java &&
                (m.returnType == Void.TYPE || m.returnType == builderClazz)
        } ?: return

        val buildMethod = builderClazz.declaredMethods.firstOrNull { m ->
            m.parameterTypes.isEmpty() && (m.returnType == reqClazz || m.returnType.name.endsWith(".elp"))
        } ?: return

        addHeaderMethod.isAccessible = true
        buildMethod.isAccessible = true

        addHeaderMethod.invoke(builderObj, "User-Agent", DESKTOP_UA)
        addHeaderMethod.invoke(builderObj, "Referer", DESKTOP_REFERER)
        val newReq = buildMethod.invoke(builderObj) ?: return

        // Replace elo.f19632c (final) via reflection; this is the most reliable injection point.
        val replaced = try {
            val f = callObj.javaClass.getDeclaredField("f19632c")
            f.isAccessible = true
            f.set(callObj, newReq)
            true
        } catch (_: Throwable) {
            false
        }

        if (replaced && bundledHeaderInjectedOnce.contains(url)) {
            XposedBridge.log("RecBiliOld: inject desktop headers ok (bundled call) url=$url")
        }
    }

    private fun ingestBundledResponse(responseObj: Any): Any? {
        if (bundledIngestGuard.get() == true) return null
        if (!bundledIngestGlobalGuard.compareAndSet(false, true)) return null
        bundledIngestGuard.set(true)
        var replacement: Any? = null
        try {
            // Only handle bundled okhttp Response objects. We sometimes see request-like objects (e.g. bl.elp)
            // flowing through callback args, and attempting to parse bodies from those just spams logs.
            try {
                val cn = responseObj.javaClass.name
                if (cn != "p000bl.elr" && !cn.endsWith(".elr")) return null
            } catch (_: Throwable) {
                return null
            }

            val url = extractUrlFromObjectFields(responseObj)

            // Diagnose actual playback failures: UPOS may return 403/412/404 even if we extracted a playable URL.
            // Log the HTTP status for UPOS resources.
            try {
                if (!url.isNullOrBlank() && (url.contains("upgcxcode", ignoreCase = true) || url.contains("bilivideo.com", ignoreCase = true))) {
                    val (code, msg) = getBundledElrCodeMessage(responseObj)
                    if (code != null) {
                        if (uposRespLoggedOnce.add(url)) {
                            val hdrDump = tryDumpBundledResponseHeaders(responseObj)
                            XposedBridge.log(
                                "RecBiliOld: [upos-resp] http=$code msg=${msg ?: "<null>"} url=${url.take(220)}" +
                                    (if (!hdrDump.isNullOrBlank()) " headers=\n$hdrDump" else "")
                            )
                        }
                    }
                }
            } catch (_: Throwable) {
            }

            // Some entry paths (e.g. author space) trigger bangumi get_source with episode_id=0.
            // This call is invalid for normal videos and can block the player state machine.
            // Patch the response body into a successful JSON to allow playback to continue.
            if (url != null && url.contains("bangumi.bilibili.com/api/get_source", ignoreCase = true) && url.contains("episode_id=0", ignoreCase = true)) {
                try {
                    val fake = "{\"code\":0,\"message\":\"0\",\"ttl\":1,\"data\":[]}"

                    // Some builds return null from response.request() (m22198a). Fall back to:
                    // 1) locating the request field on the response
                    // 2) rebuilding from response.newBuilder() (m22210i) without needing request
                    var req: Any? = try {
                        XposedHelpers.callMethod(responseObj, "m22198a")
                    } catch (_: Throwable) {
                        null
                    }
                    if (req == null) {
                        req = try {
                            responseObj.javaClass.declaredFields.firstOrNull { f ->
                                if (Modifier.isStatic(f.modifiers)) return@firstOrNull false
                                val tn = f.type.name
                                tn == "p000bl.elp" || tn.endsWith(".elp")
                            }?.let { f ->
                                f.isAccessible = true
                                f.get(responseObj)
                            }
                        } catch (_: Throwable) {
                            null
                        }
                    }

                    val built = if (req != null) {
                        buildBundledElrJsonResponse(responseObj.javaClass.classLoader, req, fake)
                    } else {
                        XposedBridge.log("RecBiliOld: short-circuit bangumi get_source episode_id=0 failed: cannot extract request from response (response-path)")
                        null
                    }

                    if (built != null) {
                        replacement = built
                        XposedBridge.log("RecBiliOld: short-circuit bangumi get_source episode_id=0 -> synthetic elr (response-path)")
                    } else {
                        XposedBridge.log("RecBiliOld: short-circuit bangumi get_source episode_id=0 failed: cannot build replacement (response-path)")
                    }
                } catch (t: Throwable) {
                    XposedBridge.log(t)
                }
            }

            // If view URL already contains aid/bvid in query, persist it immediately.
            // This makes legacy playurl (cid-only) able to fallback via persisted aid/bvid across processes.
            if (url != null && isViewUrl(url)) {
                try {
                    XposedBridge.log("RecBiliOld: ingest view request (from url) url=$url")
                    PlayurlFixer.ingestVideoViewRequest(url)
                } catch (t: Throwable) {
                    XposedBridge.log("RecBiliOld: ingestVideoViewRequest failed url=$url")
                    XposedBridge.log(t)
                }
            }

            // Extra diagnostics for comment endpoints. These often go through bundled okhttp (bl.elr).
            try {
                if (!url.isNullOrBlank() && url.contains("/x/v2/reply", ignoreCase = true)) {
                    val (code, msg) = getBundledElrCodeMessage(responseObj)
                    XposedBridge.log(
                        "RecBiliOld: [bundled-comment] http=${code ?: "?"} msg=${msg ?: "<null>"} url=${url.take(220)}"
                    )
                }
            } catch (_: Throwable) {
            }

            try {
                val cls = responseObj.javaClass.name
                val sb = StringBuilder()
                sb.append("RecBiliOld: [bundled-resp] class=").append(cls)
                sb.append(" url=").append(url ?: "<null>")

                // Dump fields: try to surface URL candidates / byte[] bodies.
                for (f in responseObj.javaClass.declaredFields) {
                    try {
                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                        f.isAccessible = true
                        val v = f.get(responseObj) ?: continue
                        val tn = v.javaClass.name
                        when (v) {
                            is String -> {
                                val s = v
                                if (s.contains("http", ignoreCase = true) || s.contains("/", ignoreCase = true)) {
                                    sb.append(" | ").append(f.name).append("=").append(s.take(300))
                                }
                            }
                            is CharSequence -> {
                                val s = v.toString()
                                if (s.contains("http", ignoreCase = true) || s.contains("/", ignoreCase = true)) {
                                    sb.append(" | ").append(f.name).append("=").append(s.take(300))
                                }
                            }
                            is ByteArray -> {
                                val s = try { String(v, Charsets.UTF_8) } catch (_: Throwable) { null }
                                if (!s.isNullOrBlank()) {
                                    sb.append(" | ").append(f.name).append("[bytes]=").append(s.take(500))
                                } else {
                                    sb.append(" | ").append(f.name).append("[bytes]=len=").append(v.size)
                                }
                            }
                            else -> {
                                // Safe-ish: only call toString on known URL-like classes
                                if (tn == "android.net.Uri" || tn.endsWith(".Uri") || tn == "java.net.URL" || tn.contains("HttpUrl", ignoreCase = true)) {
                                    sb.append(" | ").append(f.name).append("=").append(v.toString().take(300))
                                }
                            }
                        }
                    } catch (_: Throwable) {
                    }
                }
                XposedBridge.log(sb.toString())
            } catch (_: Throwable) {
            }

            // Try to extract and log bodies for key endpoints to aid debugging.
            // Only /x/v2/view is parsed for cid->aid/bvid caching.
            if (url == null) return replacement
            val shouldTryBody = isViewUrl(url) ||
                url.contains("/x/player/wbi/playurl", ignoreCase = true) ||
                url.contains("/x/web-interface/view", ignoreCase = true) ||
                url.contains("/x/v2/reply", ignoreCase = true)
            if (!shouldTryBody) return replacement

            // 1) use cached body if we've already extracted this response once.
            var bodyStr: String? = null
            try {
                val cached = bundledRespBodyCache[responseObj]
                if (!cached.isNullOrBlank()) {
                    bodyStr = cached
                }
            } catch (_: Throwable) {
            }

            // 2) try existing peek-body path
            try {
                val peekedBody = run {
                    val m = responseObj.javaClass.declaredMethods.firstOrNull {
                        val p = it.parameterTypes
                        p.size == 1 && (p[0] == java.lang.Long.TYPE || p[0] == java.lang.Long::class.java) &&
                            it.returnType != java.lang.Void.TYPE
                    } ?: return@run null
                    m.isAccessible = true
                    m.invoke(responseObj, 1024L * 1024L)
                }
                if (peekedBody != null) {
                    val candidates = peekedBody.javaClass.declaredMethods.filter {
                        it.parameterTypes.isEmpty() &&
                            (it.returnType == String::class.java || CharSequence::class.java.isAssignableFrom(it.returnType))
                    }
                    for (m in candidates) {
                        try {
                            m.isAccessible = true
                            val v = m.invoke(peekedBody) ?: continue
                            val s = v.toString()
                            if (s.isNotBlank()) {
                                bodyStr = s
                                break
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
            } catch (_: Throwable) {
            }

            // 3) fallback: scan response fields for byte[]/String json
            if (bodyStr.isNullOrBlank()) {
                bodyStr = tryExtractJsonBodyFromFields(responseObj)
            }

            // IMPORTANT: if we cannot restore body, do not use any extraction that might consume the source.
            // This prevents breaking the whole video details page.
            val canRestore = if (!bodyStr.isNullOrBlank()) {
                try {
                    patchBundledResponseBody(responseObj, bodyStr!!)
                } catch (_: Throwable) {
                    false
                }
            } else {
                false
            }

            // 4) bundled elr/emv extraction (may consume body) only if we can restore.
            if (!canRestore && (isViewUrl(url) || url.contains("/x/player/wbi/playurl", ignoreCase = true))) {
                XposedBridge.log("RecBiliOld: patchBundledResponseBody failed url=$url")
                return replacement
            }

            if (bodyStr.isNullOrBlank() && canRestore && (responseObj.javaClass.name == "p000bl.elr" || responseObj.javaClass.name.endsWith(".elr"))) {
                bodyStr = tryExtractBundledElrBodyString(responseObj)
                if (!bodyStr.isNullOrBlank()) {
                    try {
                        patchBundledResponseBody(responseObj, bodyStr!!)
                    } catch (_: Throwable) {
                    }
                }
            }

            if (bodyStr.isNullOrBlank()) {
                try {
                    // Deep-dump only once per URL to avoid log spam.
                    deepDumpBundledElrOnce(url, responseObj)
                } catch (_: Throwable) {
                }

                // Brute-force fallback: try to locate JSON by probing 0-arg methods.
                val brute = bruteForceExtractBundledElrJson(responseObj)
                if (!brute.isNullOrBlank()) {
                    bodyStr = brute
                    try {
                        patchBundledResponseBody(responseObj, bodyStr!!)
                    } catch (_: Throwable) {
                    }
                } else {
                    XposedBridge.log("RecBiliOld: [bundled-view] body not found url=$url")
                    return replacement
                }
            }

            // Reading from bundled els/emv may consume the underlying source; restore the body so
            // downstream app code can still parse the response.
            try {
                patchBundledResponseBody(responseObj, bodyStr!!)
            } catch (_: Throwable) {
            }

            // Always ingest possible full 64-bit aids from list-style responses before any rewriting.
            try {
                PlayurlFixer.ingestAidMappingsFromListJson(url, bodyStr!!)
            } catch (_: Throwable) {
            }

            // Patch comment JSON for old client if needed.
            try {
                if (url.contains("/x/v2/reply", ignoreCase = true)) {
                    val patchedComment = tryPatchBundledCommentResponseBodyIfNeeded(responseObj, url, bodyStr!!)
                    if (!patchedComment.isNullOrBlank()) {
                        if (patchBundledResponseBody(responseObj, patchedComment)) {
                            bodyStr = patchedComment
                        }
                    }
                }
            } catch (_: Throwable) {
            }

            // Patch list-style responses (rank/feed/etc.) that carry oversized avid in `param`.
            // Old client code frequently does Integer.parseInt(param), which turns oversized avid into 0.
            // We rewrite `param` to signed-32 while keeping `uri` intact; full aid is restored later via mapping.
            try {
                val patchedList = tryPatchBundledListParamAvidIfNeeded(url, bodyStr!!)
                if (!patchedList.isNullOrBlank()) {
                    if (patchBundledResponseBody(responseObj, patchedList)) {
                        bodyStr = patchedList
                    }
                }
            } catch (_: Throwable) {
            }

            try {
                bundledRespBodyCache[responseObj] = bodyStr!!
            } catch (_: Throwable) {
            }

            if (isViewUrl(url)) {
                try {
                    val patched = tryStripBangumiFieldsFromViewJson(bodyStr!!)
                    if (!patched.isNullOrBlank()) {
                        if (patchBundledResponseBody(responseObj, patched)) {
                            XposedBridge.log("RecBiliOld: strip view bangumi/movie fields (bundled) url=$url")
                            bodyStr = patched
                        }
                    }
                } catch (t: Throwable) {
                    XposedBridge.log(t)
                }
            }

            val snippet = bodyStr!!.take(3000)
            XposedBridge.log("RecBiliOld: [bundled-body] url=$url body=$snippet")
            try {
                // Populate low32->fullAid mapping from list-style responses (rank/feed/etc.)
                PlayurlFixer.ingestAidMappingsFromListJson(url, bodyStr!!)
            } catch (_: Throwable) {
            }
            if (isViewUrl(url)) {
                PlayurlFixer.ingestVideoViewResponse(url, bodyStr!!)
            }
        } catch (t: Throwable) {
            XposedBridge.log(t)
        } finally {
            bundledIngestGuard.set(false)
            bundledIngestGlobalGuard.set(false)
        }

        return replacement
    }

    private fun tryExtractBundledRequestMethod(reqObj: Any?): String? {
        if (reqObj == null) return null
        return try {
            // Best-effort: find a 0-arg String method that looks like an HTTP method.
            val m = reqObj.javaClass.declaredMethods.firstOrNull { mm ->
                mm.parameterTypes.isEmpty() && mm.returnType == String::class.java
            } ?: return null
            m.isAccessible = true
            val s = (m.invoke(reqObj) as? String)?.trim()
            if (s.isNullOrBlank()) return null
            if (s.length in 3..8 && s.all { it.isLetter() }) s else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun tryPatchBundledListParamAvidIfNeeded(url: String, body: String): String? {
        return XposedHooksListPatch.tryPatchBundledListParamAvidIfNeeded(url, body)
    }

    private fun tryDumpBundledRequestHeaders(reqObj: Any?): String? {
        if (reqObj == null) return null
        return try {
            val headersObj = reqObj.javaClass.declaredMethods.firstOrNull { m ->
                m.parameterTypes.isEmpty() && (m.returnType.name == "p000bl.elq" || m.returnType.name.endsWith(".elq"))
            }?.let { m ->
                m.isAccessible = true
                m.invoke(reqObj)
            } ?: return null

            val s = headersObj.toString()
            if (s.isNullOrBlank()) null else s
        } catch (_: Throwable) {
            null
        }
    }

    private fun tryDumpBundledResponseHeaders(respObj: Any?): String? {
        if (respObj == null) return null
        return try {
            val headersObj = respObj.javaClass.declaredMethods.firstOrNull { m ->
                m.parameterTypes.isEmpty() && (m.returnType.name == "p000bl.elq" || m.returnType.name.endsWith(".elq"))
            }?.let { m ->
                m.isAccessible = true
                m.invoke(respObj)
            } ?: return null

            val s = headersObj.toString()
            if (s.isNullOrBlank()) null else s
        } catch (_: Throwable) {
            null
        }
    }

    private fun getBundledElrCodeMessage(responseObj: Any): Pair<Int?, String?> {
        // p000bl.elr exposes:
        // - int m22204c()  (code)
        // - String m22206e() (message)
        // But method names may vary across builds; find by signature.
        var code: Int? = null
        var msg: String? = null
        try {
            val mCode = responseObj.javaClass.declaredMethods.firstOrNull { m ->
                m.parameterTypes.isEmpty() &&
                    (m.returnType == Int::class.javaPrimitiveType || m.returnType == Int::class.java)
            }
            if (mCode != null) {
                mCode.isAccessible = true
                code = (mCode.invoke(responseObj) as? Int)
            }
        } catch (_: Throwable) {
        }
        try {
            val mMsg = responseObj.javaClass.declaredMethods.firstOrNull { m ->
                m.parameterTypes.isEmpty() && m.returnType == String::class.java
            }
            if (mMsg != null) {
                mMsg.isAccessible = true
                msg = mMsg.invoke(responseObj) as? String
            }
        } catch (_: Throwable) {
        }
        return code to msg
    }

    private fun buildBundledElrJsonResponseNoRequest(responseObj: Any, jsonBody: String): Any? {
        return try {
            val cl = responseObj.javaClass.classLoader

            val newBuilderMethod = responseObj.javaClass.declaredMethods.firstOrNull { m ->
                m.parameterTypes.isEmpty() &&
                    !Modifier.isStatic(m.modifiers) &&
                    m.returnType != Void.TYPE &&
                    m.returnType.name.contains("elr", ignoreCase = true)
            } ?: return null
            newBuilderMethod.isAccessible = true
            val builder = newBuilderMethod.invoke(responseObj) ?: return null
            val builderClazz = builder.javaClass

            val elsClazz =
                XposedHelpers.findClassIfExists("p000bl.els", cl)
                    ?: XposedHelpers.findClassIfExists("bl.els", cl)
                    ?: return null
            val ellClazz =
                XposedHelpers.findClassIfExists("p000bl.ell", cl)
                    ?: XposedHelpers.findClassIfExists("bl.ell", cl)

            val mediaType = try {
                val parse = ellClazz?.declaredMethods?.firstOrNull { m ->
                    Modifier.isStatic(m.modifiers) && m.parameterTypes.size == 1 && m.parameterTypes[0] == String::class.java
                }
                parse?.isAccessible = true
                parse?.invoke(null, "application/json; charset=utf-8")
            } catch (_: Throwable) {
                null
            }

            val bodyObj = try {
                val create = elsClazz.declaredMethods.firstOrNull { m ->
                    Modifier.isStatic(m.modifiers) && m.parameterTypes.size == 2 && m.parameterTypes[1] == String::class.java
                }
                create?.isAccessible = true
                if (create != null) create.invoke(null, mediaType, jsonBody) else null
            } catch (_: Throwable) {
                null
            } ?: return null

            // code(int)
            try {
                val m = builderClazz.declaredMethods.firstOrNull { mm ->
                    mm.parameterTypes.size == 1 && (mm.parameterTypes[0] == Int::class.javaPrimitiveType || mm.parameterTypes[0] == Int::class.java)
                }
                if (m != null) {
                    m.isAccessible = true
                    m.invoke(builder, 200)
                }
            } catch (_: Throwable) {
            }

            // message(String)
            try {
                val m = builderClazz.declaredMethods.firstOrNull { mm ->
                    mm.parameterTypes.size == 1 && mm.parameterTypes[0] == String::class.java
                }
                if (m != null) {
                    m.isAccessible = true
                    m.invoke(builder, "OK")
                }
            } catch (_: Throwable) {
            }

            // header(String, String)
            try {
                val m = builderClazz.declaredMethods.firstOrNull { mm ->
                    mm.parameterTypes.size == 2 && mm.parameterTypes[0] == String::class.java && mm.parameterTypes[1] == String::class.java
                }
                if (m != null) {
                    m.isAccessible = true
                    m.invoke(builder, "Content-Type", "application/json; charset=utf-8")
                }
            } catch (_: Throwable) {
            }

            // body(els)
            try {
                val m = builderClazz.declaredMethods.firstOrNull { mm ->
                    mm.parameterTypes.size == 1 && (mm.parameterTypes[0] == elsClazz || mm.parameterTypes[0].name.endsWith(".els"))
                }
                if (m != null) {
                    m.isAccessible = true
                    m.invoke(builder, bodyObj)
                }
            } catch (_: Throwable) {
            }

            // build() -> elr
            val buildMethod = builderClazz.declaredMethods.firstOrNull { mm ->
                mm.parameterTypes.isEmpty() && mm.returnType.name.endsWith(".elr")
            } ?: return null
            buildMethod.isAccessible = true
            buildMethod.invoke(builder)
        } catch (_: Throwable) {
            null
        }
    }

    private fun hookOkHttpViewResponseIfPresent(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        val realCallClazz = XposedHelpers.findClassIfExists("okhttp3.RealCall", cl) ?: return
        val callbackClazz = XposedHelpers.findClassIfExists("okhttp3.Callback", cl) ?: return

        try {
            XposedHelpers.findAndHookMethod(
                realCallClazz,
                "execute",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val resp = param.result ?: return
                            val patchedView = tryPatchOkHttpResponseBodyIfView(resp)
                            val patchedComment = tryPatchOkHttpResponseBodyIfComment(patchedView ?: resp)
                            val out = patchedComment ?: patchedView ?: resp
                            if (out !== resp) {
                                param.result = out
                            }
                            ingestOkHttpResponse(out)
                        } catch (t: Throwable) {
                            XposedBridge.log(t)
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }

        try {
            XposedHelpers.findAndHookMethod(
                realCallClazz,
                "enqueue",
                callbackClazz,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val originalCb = param.args[0] ?: return
                            val cbProxy = java.lang.reflect.Proxy.newProxyInstance(
                                cl,
                                arrayOf(callbackClazz),
                                java.lang.reflect.InvocationHandler { _, method, args ->
                                    try {
                                        if (method.name == "onResponse" && args != null && args.size >= 2) {
                                            val resp = args[1]
                                            if (resp != null) {
                                                val patchedView = tryPatchOkHttpResponseBodyIfView(resp)
                                                val patchedComment = tryPatchOkHttpResponseBodyIfComment(patchedView ?: resp)
                                                if (patchedComment != null) {
                                                    args[1] = patchedComment
                                                } else if (patchedView != null) {
                                                    args[1] = patchedView
                                                }
                                                ingestOkHttpResponse(args[1]!!)
                                            }
                                        }
                                    } catch (t: Throwable) {
                                        XposedBridge.log(t)
                                    }
                                    if (args == null) {
                                        method.invoke(originalCb)
                                    } else {
                                        method.invoke(originalCb, *args)
                                    }
                                }
                            )
                            param.args[0] = cbProxy
                        } catch (t: Throwable) {
                            XposedBridge.log(t)
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    private fun ingestOkHttpResponse(responseObj: Any) {
        try {
            val req = XposedHelpers.callMethod(responseObj, "request") ?: return
            val urlObj = XposedHelpers.callMethod(req, "url") ?: return
            val url = urlObj.toString()
            if (!url.contains("/x/v2/view", ignoreCase = true)) return

            val peeked = XposedHelpers.callMethod(responseObj, "peekBody", 1024L * 1024L) ?: return
            val bodyStr = XposedHelpers.callMethod(peeked, "string") as? String ?: return
            if (bodyStr.isBlank()) return

            PlayurlFixer.ingestVideoViewResponse(url, bodyStr)
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    private fun hookOversizedAvidIfPresent(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        val actClazz1 = XposedHelpers.findClassIfExists(
            "tv.danmaku.bili.ui.video.VideoDetailsActivity",
            cl
        )
        val actClazz2 = XposedHelpers.findClassIfExists(
            "tv.danmaku.bili.p046ui.video.VideoDetailsActivity",
            cl
        )

        val targets = listOfNotNull(actClazz1, actClazz2)
        if (targets.isEmpty()) return

        for (actClazz in targets) {
            try {
                XposedHelpers.findAndHookMethod(
                    actClazz,
                    "onCreate",
                    android.os.Bundle::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val intent = (param.thisObject as? android.app.Activity)?.intent
                                val avid = intent?.getIntExtra("avid", -1) ?: -1
                                if (avid == Int.MAX_VALUE) {
                                    if (XposedSharedState.oversizedAidAtomicRef.get() == null) {
                                        val persisted = PlayurlFixer.getPersistedAidBvidForJump()
                                        val persistedAid = persisted?.first
                                        val realAid = persistedAid?.toLongOrNull()
                                        if (realAid != null && realAid > 0) {
                                            XposedSharedState.oversizedAidAtomicRef.set(realAid)
                                            PlayurlFixer.ingestOversizedAid(realAid.toString())
                                            XposedBridge.log("RecBiliOld: seed oversized aid from persisted aid=$realAid")
                                        }
                                    }
                                    return
                                }

                                val data = intent?.data
                                val seg = data?.lastPathSegment
                                val parsed = seg?.toLongOrNull()
                                if (parsed != null && parsed > Int.MAX_VALUE.toLong()) {
                                    XposedSharedState.oversizedAidAtomicRef.set(parsed)
                                    intent.putExtra("avid", Int.MAX_VALUE)
                                    PlayurlFixer.ingestOversizedAid(parsed.toString())
                                    XposedBridge.log("RecBiliOld: oversized avid detected real=$parsed, inject Int.MAX_VALUE")
                                }
                            } catch (t: Throwable) {
                                XposedBridge.log(t)
                            }
                        }
                    }
                )
                XposedBridge.log("RecBiliOld: oversized avid hook installed act=${actClazz.name}")
            } catch (t: Throwable) {
                XposedBridge.log(t)
            }
        }

        val apiClazz = findClassAny(lpparam.classLoader, "bl.fwr\$C4391a", "p000bl.fwr\$C4391a") ?: return
        val ctors = apiClazz.declaredConstructors
        for (ctor in ctors) {
            XposedBridge.hookMethod(
                ctor,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val realAid = XposedSharedState.oversizedAidAtomicRef.get() ?: return
                            if (realAid <= 0L) return

                            val obj = param.thisObject
                            val synthetic = Int.MAX_VALUE.toString()
                            if (!replaceQueryMapValueIfEquals(obj, key = "aid", expectedOldValue = synthetic, newValue = realAid.toString())) return

                            XposedSharedState.oversizedAidAtomicRef.set(null)
                            XposedBridge.log("RecBiliOld: patched x/v2/view aid=$realAid")
                        } catch (t: Throwable) {
                            XposedBridge.log(t)
                        }
                    }
                }
            )
        }
    }

    private fun replaceQueryMapValueIfEquals(mapObj: Any, key: String, expectedOldValue: String, newValue: String): Boolean {
        val arr = XposedHelpers.getObjectField(mapObj, "f32464g") as? Array<Any?> ?: return false
        var replaced = false
        var i = 0
        while (i + 1 < arr.size) {
            val k = arr[i] as? String
            if (k != null && k.equals(key, ignoreCase = false)) {
                val oldV = arr[i + 1] as? String
                if (oldV == expectedOldValue) {
                    arr[i + 1] = newValue
                    replaced = true
                }
                break
            }
            i += 2
        }
        return replaced
    }

    private fun hookLuaHttpIfPresent(lpparam: XC_LoadPackage.LoadPackageParam) {
        val wrapperClazz =
            XposedHelpers.findClassIfExists("com.bilibili.lua.BLHttpWrapper", lpparam.classLoader) ?: return

        XposedHelpers.findAndHookMethod(
            wrapperClazz,
            "open",
            String::class.java,
            String::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val url = param.args[0] as? String ?: return
                        if (PlayurlFixer.isLegacyPlayurlUrl(url)) {
                            PlayurlFixer.markIntercept(param.thisObject, url)
                            XposedBridge.log("RecBiliOld: intercept legacy playurl -> $url")
                        } else if (isBangumiGetSourceEpisode0(url)) {
                            val playable = PlayurlFixer.getLastExtractedPlayableUrl()
                            if (!playable.isNullOrBlank()) {
                                XposedSharedState.luaGetSourceBypassMap[param.thisObject] = playable
                                XposedBridge.log("RecBiliOld: arm lua get_source bypass url=$url playable=${playable.take(200)}")
                            } else {
                                XposedBridge.log("RecBiliOld: lua get_source episode_id=0 but no cached playable url url=$url")
                            }
                        }
                    } catch (t: Throwable) {
                        XposedBridge.log(t)
                    }
                }
            }
        )

        XposedHelpers.findAndHookMethod(
            wrapperClazz,
            "writeRequestHeader",
            String::class.java,
            String::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (!PlayurlFixer.shouldIntercept(param.thisObject)) return
                        val k = param.args[0] as? String ?: return
                        val v = param.args[1] as? String ?: return
                        PlayurlFixer.onLegacyRequestHeader(param.thisObject, k, v)
                    } catch (t: Throwable) {
                        XposedBridge.log(t)
                    }
                }
            }
        )

        XposedHelpers.findAndHookMethod(
            wrapperClazz,
            "request",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (PlayurlFixer.shouldIntercept(param.thisObject)) {
                            param.result = true
                        }
                    } catch (t: Throwable) {
                        XposedBridge.log(t)
                    }
                }
            }
        )

        XposedHelpers.findAndHookMethod(
            wrapperClazz,
            "getStatusCode",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (PlayurlFixer.shouldIntercept(param.thisObject)) {
                            param.result = 200
                            return
                        }
                        val bypass = XposedSharedState.luaGetSourceBypassMap[param.thisObject]
                        if (!bypass.isNullOrBlank()) {
                            param.result = 200
                        }
                    } catch (t: Throwable) {
                        XposedBridge.log(t)
                    }
                }
            }
        )

        XposedHelpers.findAndHookMethod(
            wrapperClazz,
            "getResponseHeaderAsJson",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (PlayurlFixer.shouldIntercept(param.thisObject)) {
                            param.result = "{}"
                            return
                        }
                        val bypass = XposedSharedState.luaGetSourceBypassMap[param.thisObject]
                        if (!bypass.isNullOrBlank()) {
                            param.result = "{}"
                        }
                    } catch (t: Throwable) {
                        XposedBridge.log(t)
                    }
                }
            }
        )

        XposedHelpers.findAndHookMethod(
            wrapperClazz,
            "getResponseBody",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (!PlayurlFixer.shouldIntercept(param.thisObject)) return
                        param.result = PlayurlFixer.buildLegacyPlayurlResponseBytes(param.thisObject)
                    } catch (t: Throwable) {
                        XposedBridge.log(t)
                    }
                }
            }
        )

        XposedHelpers.findAndHookMethod(
            wrapperClazz,
            "getResponseBody",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (PlayurlFixer.shouldIntercept(param.thisObject)) return
                        val playable = XposedSharedState.luaGetSourceBypassMap[param.thisObject]
                        if (playable.isNullOrBlank()) return

                        // Minimal JSON that upstream parsers usually accept: we mainly need to unblock the state machine.
                        val json = "{\"code\":0,\"message\":\"OK\",\"result\":\"suee\",\"data\":{\"url\":\"" +
                            playable.replace("\\", "\\\\").replace("\"", "\\\"") +
                            "\"}}"

                        param.result = json.toByteArray(Charsets.UTF_8)
                        XposedSharedState.luaGetSourceBypassMap.remove(param.thisObject)
                        XposedBridge.log("RecBiliOld: bypass lua bangumi get_source episode_id=0 -> served playable url=${playable.take(200)}")
                    } catch (t: Throwable) {
                        XposedBridge.log(t)
                    }
                }
            }
        )
    }

    private fun hookEpisodeParamsResolver(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHooksEpisodeParamsResolver.hookEpisodeParamsResolver(lpparam)
    }

    private fun hookRankingListAvidFixIfPresent(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        XposedBridge.log("RecBiliOld: attempting to find RankVideoListFragment\$ViewHolder in ${lpparam.packageName}")

        val viewHolderClass = XposedHelpers.findClassIfExists("tv.danmaku.bili.p046ui.rank.RankVideoListFragment\$ViewHolder", cl)
            ?: XposedHelpers.findClassIfExists("tv.danmaku.bili.ui.rank.RankVideoListFragment\$ViewHolder", cl)

        if (viewHolderClass == null) {
            XposedBridge.log("RecBiliOld: RankVideoListFragment\$ViewHolder not found, skipping ranking list avid fix")
            return
        }

        XposedBridge.log("RecBiliOld: Found RankVideoListFragment\$ViewHolder: ${viewHolderClass.name}")

        val targetMethod = viewHolderClass.declaredMethods.find { m ->
            m.name == "m49647a" &&
            m.parameterTypes.size == 4 &&
            android.content.Context::class.java.isAssignableFrom(m.parameterTypes[0]) &&
            String::class.java.isAssignableFrom(m.parameterTypes[1]) &&
            String::class.java.isAssignableFrom(m.parameterTypes[2]) &&
            String::class.java.isAssignableFrom(m.parameterTypes[3])
        } ?: run {
            XposedBridge.log("RecBiliOld: RankVideoListFragment\$ViewHolder.m49647a method not found")
            return
        }

        XposedBridge.hookMethod(targetMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val context = param.args[0] as? android.content.Context ?: return
                    val paramStr = param.args[1] as? String ?: return
                    val title = param.args[2] as? String ?: return
                    val cover = param.args[3] as? String ?: return

                    // Parse the param field as a long instead of int
                    val avidLong = paramStr.toLongOrNull()
                    if (avidLong != null && avidLong > Int.MAX_VALUE.toLong()) {
                        XposedBridge.log("RecBiliOld: Ranking list avid fix - converting long avid: $avidLong")
                        
                        // Store the full avid for later use
                        try {
                            PlayurlFixer.ingestOversizedAid(avidLong.toString())
                            XposedSharedState.oversizedAidAtomicRef.set(avidLong)
                        } catch (t: Throwable) {
                            XposedBridge.log("RecBiliOld: Failed to store oversized avid: $t")
                        }
                        
                        // Convert to signed 32-bit int for the intent
                        val signed32Int = (avidLong and 0xFFFF_FFFFL).toInt()
                        
                        // Create BiliVideoDetail with the converted avid
                        val biliVideoDetail = try {
                            val biliVideoDetailClass = XposedHelpers.findClass("tv.danmaku.bili.p046ui.video.api.BiliVideoDetail", cl)
                            XposedHelpers.callStaticMethod(biliVideoDetailClass, "create", signed32Int, title, cover)
                        } catch (t: Throwable) {
                            XposedBridge.log("RecBiliOld: Failed to create BiliVideoDetail: $t")
                            return
                        }
                        
                        // Call fat.m25107a with the corrected BiliVideoDetail
                        try {
                            val fatClass = XposedHelpers.findClass("p000bl.fat", cl)
                            XposedHelpers.callStaticMethod(fatClass, "m25107a", context, biliVideoDetail, "fwe.f27847x")
                            
                            // Skip the original method call
                            param.result = null
                            XposedBridge.log("RecBiliOld: Ranking list avid fix applied - avid: $avidLong -> $signed32Int")
                        } catch (t: Throwable) {
                            XposedBridge.log("RecBiliOld: Failed to call fat.m25107a: $t")
                        }
                    }
                } catch (t: Throwable) {
                    XposedBridge.log(t)
                }
            }
        })
    }

    // shared state moved to XposedSharedState
}
