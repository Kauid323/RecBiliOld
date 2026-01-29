package com.example.recbiliold.xposed

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Proxy

internal object XposedHooksOkHttpVerbose {

    fun hookOkHttpVerboseIfPresent(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        val interceptorClazz = XposedHelpers.findClassIfExists("okhttp3.Interceptor", cl) ?: return
        val builderClazz = XposedHelpers.findClassIfExists("okhttp3.OkHttpClient\$Builder", cl)
            ?: XposedHelpers.findClassIfExists("okhttp3.OkHttpClient.Builder", cl)
            ?: return

        val interceptorProxy = Proxy.newProxyInstance(
            cl,
            arrayOf(interceptorClazz)
        ) { _, method, args ->
            if (method.name != "intercept" || args == null || args.isEmpty()) {
                return@newProxyInstance if (args == null) method.invoke(this) else method.invoke(this, *args)
            }
            val chain = args[0]
            val request = try {
                XposedHelpers.callMethod(chain, "request")
            } catch (_: Throwable) {
                null
            }
            val url = try {
                val u = XposedHelpers.callMethod(request, "url")
                u?.toString()
            } catch (_: Throwable) {
                null
            }
            val shouldLog = isVerboseNetworkEnabledForce() && !url.isNullOrBlank()
            if (shouldLog) {
                try {
                    val mtd = XposedHelpers.callMethod(request, "method")?.toString()
                    val headers = XposedHelpers.callMethod(request, "headers")?.toString()
                    XposedBridge.log("RecBiliOld: [okhttp] -> ${mtd ?: "?"} ${url ?: "<null>"} headers=${headers ?: "<null>"}")
                } catch (_: Throwable) {
                }
            }

            val resp = try {
                XposedHelpers.callMethod(chain, "proceed", request)
            } catch (t: Throwable) {
                if (shouldLog) XposedBridge.log(t)
                throw t
            }

            if (shouldLog) {
                try {
                    val code = XposedHelpers.callMethod(resp, "code")?.toString()
                    val peeked = try {
                        XposedHelpers.callMethod(resp, "peekBody", 65536L)
                    } catch (_: Throwable) {
                        null
                    }
                    val bodyStr = try {
                        XposedHelpers.callMethod(peeked, "string") as? String
                    } catch (_: Throwable) {
                        null
                    }
                    XposedBridge.log(
                        "RecBiliOld: [okhttp] <- http=${code ?: "?"} ${url ?: "<null>"} body=${bodyStr?.take(3000) ?: "<null>"}"
                    )
                } catch (t: Throwable) {
                    XposedBridge.log(t)
                }
            }
            resp
        }

        try {
            XposedBridge.log("RecBiliOld: okhttp verbose hook (Builder.build) installing...")
            XposedHelpers.findAndHookMethod(
                builderClazz,
                "build",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (!isVerboseNetworkEnabledForce()) return
                            XposedHelpers.callMethod(param.thisObject, "addNetworkInterceptor", interceptorProxy)
                        } catch (_: Throwable) {
                            try {
                                XposedHelpers.callMethod(param.thisObject, "addInterceptor", interceptorProxy)
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
            )
            XposedBridge.log("RecBiliOld: okhttp verbose hook (Builder.build) installed")
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    fun hookOkHttpRealCallVerboseIfPresent(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        val realCallClazz = XposedHelpers.findClassIfExists("okhttp3.RealCall", cl)
            ?: XposedHelpers.findClassIfExists("okhttp3.internal.connection.RealCall", cl)
            ?: return
        val callbackClazz = XposedHelpers.findClassIfExists("okhttp3.Callback", cl)

        try {
            XposedBridge.log("RecBiliOld: okhttp verbose hook (RealCall) installing... realCall=${realCallClazz.name}")

            XposedHelpers.findAndHookMethod(
                realCallClazz,
                "execute",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            if (!isVerboseNetworkEnabledForce()) return
                            val req = XposedHelpers.callMethod(param.thisObject, "request")
                            val url = try { XposedHelpers.callMethod(req, "url")?.toString() } catch (_: Throwable) { null }
                            if (url.isNullOrBlank()) return
                            val mtd = try { XposedHelpers.callMethod(req, "method")?.toString() } catch (_: Throwable) { null }
                            val headers = try { XposedHelpers.callMethod(req, "headers")?.toString() } catch (_: Throwable) { null }
                            XposedBridge.log("RecBiliOld: [realcall] -> ${mtd ?: "?"} ${url ?: "<null>"} headers=${headers ?: "<null>"}")
                        } catch (_: Throwable) {
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            if (!isVerboseNetworkEnabledForce()) return
                            val resp = param.result ?: return
                            val req = XposedHelpers.callMethod(param.thisObject, "request")
                            val url = try { XposedHelpers.callMethod(req, "url")?.toString() } catch (_: Throwable) { null }
                            if (url.isNullOrBlank()) return
                            val code = try { XposedHelpers.callMethod(resp, "code")?.toString() } catch (_: Throwable) { null }
                            val peeked = try { XposedHelpers.callMethod(resp, "peekBody", 65536L) } catch (_: Throwable) { null }
                            val bodyStr = try { XposedHelpers.callMethod(peeked, "string") as? String } catch (_: Throwable) { null }
                            XposedBridge.log("RecBiliOld: [realcall] <- http=${code ?: "?"} ${url ?: "<null>"} body=${bodyStr?.take(3000) ?: "<null>"}")
                        } catch (t: Throwable) {
                            XposedBridge.log(t)
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }

        if (callbackClazz != null) {
            try {
                XposedHelpers.findAndHookMethod(
                    realCallClazz,
                    "enqueue",
                    callbackClazz,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!isVerboseNetworkEnabledForce()) return
                            val orig = param.args.getOrNull(0) ?: return
                            val proxy = Proxy.newProxyInstance(
                                cl,
                                arrayOf(callbackClazz),
                                java.lang.reflect.InvocationHandler { _, method, args ->
                                    try {
                                        if (method.name == "onResponse" && args != null && args.size >= 2) {
                                            val call = args[0]
                                            val resp = args[1]
                                            val req = try { XposedHelpers.callMethod(call, "request") } catch (_: Throwable) { null }
                                            val url = try { XposedHelpers.callMethod(req, "url")?.toString() } catch (_: Throwable) { null }
                                            if (!url.isNullOrBlank()) {
                                                val code = try { XposedHelpers.callMethod(resp, "code")?.toString() } catch (_: Throwable) { null }
                                                val peeked = try { XposedHelpers.callMethod(resp, "peekBody", 65536L) } catch (_: Throwable) { null }
                                                val bodyStr = try { XposedHelpers.callMethod(peeked, "string") as? String } catch (_: Throwable) { null }
                                                XposedBridge.log("RecBiliOld: [realcall-cb] <- http=${code ?: "?"} ${url ?: "<null>"} body=${bodyStr?.take(3000) ?: "<null>"}")
                                            }
                                        }
                                    } catch (t: Throwable) {
                                        XposedBridge.log(t)
                                    }
                                    if (args == null) method.invoke(orig) else method.invoke(orig, *args)
                                }
                            )
                            param.args[0] = proxy
                        }
                    }
                )
                XposedBridge.log("RecBiliOld: okhttp verbose hook (RealCall.enqueue) installed")
            } catch (t: Throwable) {
                XposedBridge.log(t)
            }
        }
    }

    private fun isVerboseNetworkEnabledForce(): Boolean {
        return true
    }
}
