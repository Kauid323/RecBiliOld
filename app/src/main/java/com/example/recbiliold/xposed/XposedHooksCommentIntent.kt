package com.example.recbiliold.xposed

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

internal object XposedHooksCommentIntent {

    fun hookCommentActivityIntentCaptureIfPresent(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Capture CommentActivity extras (oid/rpId/type) to improve fullAid restoration.
        // The legacy client may pass overflowed signed32 values; caching helps correlation/debug.
        try {
            XposedHelpers.findAndHookMethod(
                android.app.Activity::class.java,
                "startActivityForResult",
                android.content.Intent::class.java,
                Int::class.javaPrimitiveType,
                android.os.Bundle::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val intent = param.args.getOrNull(0) as? android.content.Intent ?: return
                            captureCommentActivityIntentIfAny(intent)
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }

        try {
            XposedHelpers.findAndHookMethod(
                android.app.Activity::class.java,
                "startActivityForResult",
                android.content.Intent::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val intent = param.args.getOrNull(0) as? android.content.Intent ?: return
                            captureCommentActivityIntentIfAny(intent)
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }

        try {
            XposedHelpers.findAndHookMethod(
                android.app.Activity::class.java,
                "startActivity",
                android.content.Intent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val intent = param.args.getOrNull(0) as? android.content.Intent ?: return
                            captureCommentActivityIntentIfAny(intent)
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }
    }

    private fun captureCommentActivityIntentIfAny(intent: android.content.Intent) {
        try {
            val cn = try { intent.component?.className } catch (_: Throwable) { null }
            if (cn.isNullOrBlank()) return
            if (!cn.endsWith("CommentActivity")) return

            val extras = try { intent.extras } catch (_: Throwable) { null } ?: return
            val oid = try {
                when {
                    extras.containsKey("oid") -> extras.get("oid")?.toString()
                    else -> null
                }
            } catch (_: Throwable) { null }

            val rpid = try {
                when {
                    extras.containsKey("rpId") -> extras.get("rpId")?.toString()
                    extras.containsKey("rpid") -> extras.get("rpid")?.toString()
                    else -> null
                }
            } catch (_: Throwable) { null }

            val type = try {
                when {
                    extras.containsKey("type") -> extras.get("type")?.toString()
                    else -> null
                }
            } catch (_: Throwable) { null }

            PlayurlFixer.setLastCommentContext(oid = oid, rpid = rpid, type = type)
        } catch (_: Throwable) {
        }
    }
}
