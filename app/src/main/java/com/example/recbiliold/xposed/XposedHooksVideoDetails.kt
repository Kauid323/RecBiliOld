package com.example.recbiliold.xposed

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

internal object XposedHooksVideoDetails {

    fun hookVideoDetailsIntentSanitizerIfPresent(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        val actClazz1 = XposedHelpers.findClassIfExists("tv.danmaku.bili.ui.video.VideoDetailsActivity", cl)
        val actClazz2 = XposedHelpers.findClassIfExists("tv.danmaku.bili.p046ui.video.VideoDetailsActivity", cl)
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
                                val act = param.thisObject as? android.app.Activity ?: return
                                val intent = act.intent ?: return

                                val dataStr = try { intent.data?.toString() } catch (_: Throwable) { null }
                                val extras = try { intent.extras } catch (_: Throwable) { null }
                                val keys = try { extras?.keySet()?.toList().orEmpty() } catch (_: Throwable) { emptyList() }

                                XposedBridge.log(
                                    "RecBiliOld: VideoDetailsActivity.onCreate intent" +
                                        " data=${dataStr ?: "<null>"}" +
                                        " extrasKeys=${keys.joinToString(prefix = "[", postfix = "]") }"
                                )

                                try {
                                    tryFixVideoDetailsAvidZero(intent)
                                } catch (t: Throwable) {
                                    XposedBridge.log(t)
                                }

                                val removed = sanitizeVideoDetailsIntent(intent, preferAggressive = true)
                                if (removed.isNotEmpty()) {
                                    XposedBridge.log(
                                        "RecBiliOld: VideoDetailsActivity.onCreate sanitized removed=${removed.joinToString(prefix = "[", postfix = "]") }"
                                    )
                                }
                            } catch (t: Throwable) {
                                XposedBridge.log(t)
                            }
                        }
                    }
                )
                XposedBridge.log("RecBiliOld: VideoDetailsActivity intent sanitizer hook installed act=${actClazz.name}")
            } catch (t: Throwable) {
                XposedBridge.log(t)
            }
        }
    }

    internal fun sanitizeVideoDetailsIntent(intent: android.content.Intent, preferAggressive: Boolean): List<String> {
        val removed = ArrayList<String>()
        val extras = try { intent.extras } catch (_: Throwable) { null } ?: return removed
        val keys = try { extras.keySet()?.toList().orEmpty() } catch (_: Throwable) { emptyList() }
        if (keys.isEmpty()) return removed

        fun shouldRemove(k: String): Boolean {
            val s = k.lowercase()
            if (s.contains("season") || s.contains("episode") || s == "epid" || s == "ep_id" || s == "episode_id") return true
            if (s.contains("bangumi") || s.contains("pgc") || s.contains("ogv")) return true
            if (s.contains("movie")) return true
            if (s.contains("is_bangumi") || s.contains("isbangumi") || s.contains("from_bangumi") || s.contains("frombangumi")) return true
            if (preferAggressive && (s.contains("spm") || s.contains("track") || s.contains("from"))) return false
            return false
        }

        for (k in keys) {
            try {
                if (!shouldRemove(k)) continue
                if (!intent.hasExtra(k)) continue
                intent.removeExtra(k)
                removed.add(k)
            } catch (_: Throwable) {
            }
        }

        try {
            val nestedKeys = listOf("bundle", "extra", "extras")
            for (nk in nestedKeys) {
                val b = try { extras.get(nk) } catch (_: Throwable) { null }
                if (b is android.os.Bundle) {
                    val nks = try { b.keySet()?.toList().orEmpty() } catch (_: Throwable) { emptyList() }
                    var removedAny = false
                    for (k in nks) {
                        if (shouldRemove(k)) {
                            try {
                                b.remove(k)
                                removed.add("$nk.$k")
                                removedAny = true
                            } catch (_: Throwable) {
                            }
                        }
                    }
                    if (removedAny) {
                        try { intent.putExtra(nk, b) } catch (_: Throwable) { }
                    }
                }
            }
        } catch (_: Throwable) {
        }

        return removed
    }

    internal fun tryFixVideoDetailsAvidZero(intent: android.content.Intent) {
        val extras = try { intent.extras } catch (_: Throwable) { null } ?: return

        fun readLongExtra(key: String): Long? {
            return try {
                if (!intent.hasExtra(key)) return null
                val v = extras.get(key)
                when (v) {
                    is Long -> v
                    is Int -> v.toLong()
                    is String -> v.toLongOrNull()
                    else -> null
                }
            } catch (_: Throwable) {
                null
            }
        }

        val avid = readLongExtra("avid") ?: readLongExtra("aid") ?: return

        val clickedAid = try { PlayurlFixer.getLastClickedAid() } catch (_: Throwable) { null }
        val clickedAidLong = clickedAid?.toLongOrNull()

        if (clickedAidLong != null && clickedAidLong > 0L) {
            val looksTruncated = avid == 0L || avid == Int.MAX_VALUE.toLong()
            if (looksTruncated && clickedAidLong.toString() != avid.toString()) {
                try { PlayurlFixer.seedLow32AidMappingFromFullAid(clickedAidLong.toString()) } catch (_: Throwable) { }
                val signed32Int = (clickedAidLong and 0xFFFF_FFFFL).toInt()
                try { intent.putExtra("avid", signed32Int) } catch (_: Throwable) { }
                try { intent.putExtra("aid", signed32Int) } catch (_: Throwable) { }
                XposedBridge.log(
                    "RecBiliOld: force VideoDetailsActivity avid -> signed32=$signed32Int from lastClickedAid=$clickedAidLong (was $avid)"
                )
                return
            }
        }

        if (avid != 0L) return

        var fixedAid: Long? = null

        try {
            val v = extras.get("video")
            if (v != null) {
                val candidates = arrayOf("aid", "avid", "avId", "av_id", "AID", "AVID")
                for (name in candidates) {
                    try {
                        val f = v.javaClass.declaredFields.firstOrNull { it.name == name }
                        if (f != null) {
                            f.isAccessible = true
                            val fv = f.get(v)
                            val n = when (fv) {
                                is Long -> fv
                                is Int -> fv.toLong()
                                is String -> fv.toLongOrNull()
                                else -> null
                            }
                            if (n != null && n > 0L) {
                                fixedAid = n
                                break
                            }
                        }
                    } catch (_: Throwable) {
                    }
                }

                if (fixedAid == null) {
                    val methods = arrayOf("getAid", "getAvid", "aid", "avid")
                    for (mn in methods) {
                        try {
                            val m = v.javaClass.declaredMethods.firstOrNull { it.name == mn && it.parameterTypes.isEmpty() }
                            if (m != null) {
                                m.isAccessible = true
                                val r = m.invoke(v)
                                val n = when (r) {
                                    is Long -> r
                                    is Int -> r.toLong()
                                    is String -> r.toLongOrNull()
                                    else -> null
                                }
                                if (n != null && n > 0L) {
                                    fixedAid = n
                                    break
                                }
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }

        if (fixedAid == null) {
            try {
                val p = PlayurlFixer.getPersistedAidBvidForJump()
                val a = p?.first?.toLongOrNull()
                if (a != null && a > 0L) fixedAid = a
            } catch (_: Throwable) {
            }
        }

        val realAid = fixedAid ?: return
        try { PlayurlFixer.seedLow32AidMappingFromFullAid(realAid.toString()) } catch (_: Throwable) { }

        val signed32Int = (realAid and 0xFFFF_FFFFL).toInt()
        try { intent.putExtra("avid", signed32Int) } catch (_: Throwable) { }
        try { intent.putExtra("aid", signed32Int) } catch (_: Throwable) { }

        XposedBridge.log("RecBiliOld: fix VideoDetailsActivity avid=0 -> signed32=$signed32Int (full=$realAid)")
    }
}
