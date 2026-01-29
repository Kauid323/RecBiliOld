package com.example.recbiliold.xposed

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicReference

internal object XposedHooksBundledOkHttp {

    fun hookBundledOkHttpViewResponseIfPresent(
        lpparam: XC_LoadPackage.LoadPackageParam,
        findClassAny: (ClassLoader, Array<out String>) -> Class<*>?,
        hookBundledOkHttpWithClasses: (ClassLoader, Class<*>, Class<*>) -> Unit,
        hookBundledOkHttpWhenLoaded: (ClassLoader) -> Unit,
    ) {
        val cl = lpparam.classLoader
        val callClazz = findClassAny(cl, arrayOf("bl.elo", "p000bl.elo"))
        val callbackClazz = findClassAny(cl, arrayOf("bl.ekv", "p000bl.ekv"))

        if (callClazz == null || callbackClazz == null) {
            hookBundledOkHttpWhenLoaded(cl)
            return
        }

        hookBundledOkHttpWithClasses(cl, callClazz, callbackClazz)
    }

    fun hookBundledOkHttpWhenLoaded(
        cl: ClassLoader,
        findClassAny: (ClassLoader, Array<out String>) -> Class<*>?,
        hookBundledOkHttpWithClasses: (ClassLoader, Class<*>, Class<*>) -> Unit,
    ) {
        val installed = AtomicReference(false)

        fun tryInstall() {
            if (installed.get() == true) return
            val callClazz = findClassAny(cl, arrayOf("bl.elo", "p000bl.elo")) ?: return
            val callbackClazz = findClassAny(cl, arrayOf("bl.ekv", "p000bl.ekv")) ?: return
            if (installed.compareAndSet(false, true)) {
                hookBundledOkHttpWithClasses(cl, callClazz, callbackClazz)
            }
        }

        try {
            XposedHelpers.findAndHookMethod(
                ClassLoader::class.java,
                "loadClass",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val name = param.args[0] as? String ?: return
                            if (name == "p000bl.elo" || name == "p000bl.ekv" || name == "bl.elo" || name == "bl.ekv") {
                                tryInstall()
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log(t)
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }

        tryInstall()
    }

    fun hookBundledOkHttpWithClasses(
        cl: ClassLoader,
        callClazz: Class<*>,
        callbackClazz: Class<*>,
        hookedMethods: MutableSet<java.lang.reflect.Method>,
        isBangumiGetSourceEpisode0: (String) -> Boolean,
        buildBundledElrJsonResponse: (ClassLoader, Any, String) -> Any?,
        ingestBundledRequest: (Any) -> Unit,
        ingestBundledResponse: (Any) -> Any?,
    ) {
        XposedBridge.log("RecBiliOld: bundled okhttp hook installed call=${callClazz.name} cb=${callbackClazz.name}")

        val responseClazz = XposedHelpers.findClassIfExists("p000bl.elr", cl)
            ?: XposedHelpers.findClassIfExists("bl.elr", cl)
        val requestClazz = XposedHelpers.findClassIfExists("p000bl.elp", cl)
            ?: XposedHelpers.findClassIfExists("bl.elp", cl)

        // sync execute-like: 0 args, non-void return
        try {
            for (m in callClazz.declaredMethods) {
                if (m.parameterTypes.isNotEmpty()) continue
                if (m.returnType == java.lang.Void.TYPE) continue

                if (requestClazz != null && m.returnType == requestClazz) continue
                if (responseClazz != null && m.returnType != responseClazz) continue

                if (hookedMethods.contains(m)) continue
                XposedBridge.hookMethod(
                    m,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                try {
                                    val req = XposedHelpers.callMethod(param.thisObject, "a")
                                    val httpUrl = XposedHelpers.callMethod(req, "a")
                                    val url = httpUrl?.toString()
                                    if (!url.isNullOrBlank()) {
                                        if (isBangumiGetSourceEpisode0(url)) {
                                            XposedBridge.log("RecBiliOld: match bundled get_source episode_id=0 (sync) url=$url")
                                            val fakeJson = "{\"code\":0,\"message\":\"0\",\"ttl\":1,\"data\":{}}"
                                            val resp = try { buildBundledElrJsonResponse(cl, req, fakeJson) } catch (t: Throwable) {
                                                XposedBridge.log(t)
                                                null
                                            }
                                            if (resp != null) {
                                                param.result = resp
                                                XposedBridge.log("RecBiliOld: short-circuit bundled get_source episode_id=0 -> synthetic elr (SUCCESS)")
                                                return
                                            }
                                        }
                                    }
                                } catch (t: Throwable) {
                                    XposedBridge.log(t)
                                }
                                ingestBundledRequest(param.thisObject)
                            } catch (t: Throwable) {
                                XposedBridge.log(t)
                            }
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val resp = param.result ?: return
                                val replaced = ingestBundledResponse(resp)
                                if (replaced != null) {
                                    param.result = replaced
                                }
                            } catch (t: Throwable) {
                                XposedBridge.log(t)
                            }
                        }
                    }
                )
                hookedMethods.add(m)
            }
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }

        // async enqueue-like: 1 arg that is callback interface
        try {
            for (m in callClazz.declaredMethods) {
                val p = m.parameterTypes
                if (p.size != 1) continue
                if (!p[0].isAssignableFrom(callbackClazz) && p[0] != callbackClazz) continue
                XposedBridge.hookMethod(
                    m,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                ingestBundledRequest(param.thisObject)
                                val originalCb = param.args[0] ?: return
                                val cbProxy = java.lang.reflect.Proxy.newProxyInstance(
                                    cl,
                                    arrayOf(callbackClazz),
                                    java.lang.reflect.InvocationHandler { _, method, args ->
                                        try {
                                            if (args != null) {
                                                for (i in args.indices) {
                                                    val a = args[i]
                                                    if (a != null) {
                                                        val replaced = ingestBundledResponse(a)
                                                        if (replaced != null) args[i] = replaced
                                                    }
                                                }
                                            }
                                        } catch (t: Throwable) {
                                            XposedBridge.log(t)
                                        }
                                        if (args == null) method.invoke(originalCb) else method.invoke(originalCb, *args)
                                    }
                                )
                                param.args[0] = cbProxy
                            } catch (t: Throwable) {
                                XposedBridge.log(t)
                            }
                        }
                    }
                )
            }
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }
}
