package com.example.recbiliold.xposed

import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject

internal object XposedHooksListPatch {

    fun tryPatchBundledListParamAvidIfNeeded(url: String, body: String): String? {
        try {
            val t = body.trimStart()
            if (!t.startsWith("{")) return null
            if (!t.contains("\"param\"")) return null

            val root = JSONObject.parseObject(t) ?: return null
            if (root.getIntValue("code") != 0) return null

            var changed = false
            fun patchItem(obj: JSONObject?) {
                if (obj == null) return

                val param0 = try { obj.getString("param")?.trim() } catch (_: Throwable) { null }
                val fullAid = if (!param0.isNullOrBlank() && param0.all { ch -> ch.isDigit() }) {
                    param0.toLongOrNull()?.takeIf { it > 0L }
                } else {
                    null
                }

                val uri0 = try { obj.getString("uri")?.trim() } catch (_: Throwable) { null }
                val fullAidFromUri = try {
                    if (!uri0.isNullOrBlank()) XposedHookUtils.extractAidFromUriStringLong(uri0) else null
                } catch (_: Throwable) {
                    null
                }

                val aidLong = fullAid ?: fullAidFromUri
                if (aidLong != null && aidLong > 0L) {
                    try { PlayurlFixer.seedLow32AidMappingFromFullAid(aidLong.toString()) } catch (_: Throwable) { }

                    if (aidLong > Int.MAX_VALUE.toLong()) {
                        val signed32Str = (aidLong and 0xFFFF_FFFFL).toInt().toString()

                        if (!param0.isNullOrBlank() && signed32Str != param0) {
                            obj["param"] = signed32Str
                            changed = true
                        }

                        if (!uri0.isNullOrBlank()) {
                            val patchedUri = uri0.replace(
                                Regex("(?i)bilibili://video/\\d+"),
                                "bilibili://video/$signed32Str"
                            )
                            if (patchedUri != uri0) {
                                obj["uri"] = patchedUri
                                changed = true
                            }
                        }
                    }
                }
            }

            fun walk(any: Any?) {
                when (any) {
                    is JSONObject -> {
                        patchItem(any)
                        for (k in any.keys) {
                            try { walk(any.get(k)) } catch (_: Throwable) { }
                        }
                    }
                    is JSONArray -> {
                        for (i in 0 until any.size) {
                            try { walk(any.get(i)) } catch (_: Throwable) { }
                        }
                    }
                }
            }

            walk(root)

            if (!changed) return null
            return root.toJSONString()
        } catch (_: Throwable) {
            return null
        }
    }
}
