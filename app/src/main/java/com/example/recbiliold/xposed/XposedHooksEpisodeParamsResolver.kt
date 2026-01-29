package com.example.recbiliold.xposed

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

internal object XposedHooksEpisodeParamsResolver {

    fun hookEpisodeParamsResolver(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        XposedBridge.log("RecBiliOld: attempting to find EpisodeParamsResolver (bl.fol) in ${lpparam.packageName}")

        val resolverClass = XposedHelpers.findClassIfExists("bl.fol", cl)
            ?: XposedHelpers.findClassIfExists("p000bl.fol", cl)

        if (resolverClass == null) {
            try {
                val indicator = XposedHelpers.findClassIfExists("bl.elr", cl)
                XposedBridge.log("RecBiliOld: debug check - bl.elr found=${indicator != null}")
            } catch (_: Throwable) {
            }

            XposedBridge.log("RecBiliOld: bl.fol not found, skipping EpisodeParamsResolver hook")
            return
        }

        XposedBridge.log("RecBiliOld: Found EpisodeParamsResolver: ${resolverClass.name}")

        val resolveMethod = resolverClass.declaredMethods.find { m ->
            m.parameterTypes.size == 3 && android.content.Context::class.java.isAssignableFrom(m.parameterTypes[0])
        } ?: run {
            XposedBridge.log("RecBiliOld: EpisodeParamsResolver resolve method not found")
            return
        }

        XposedBridge.hookMethod(resolveMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val extra = param.args.getOrNull(2) ?: return

                    var hasAvid = false
                    var hasZeroEpId = false

                    extra.javaClass.declaredMethods.forEach { m ->
                        if (m.parameterTypes.isEmpty() &&
                            (m.returnType == Long::class.javaPrimitiveType || m.returnType == Int::class.javaPrimitiveType ||
                                m.returnType == Long::class.java || m.returnType == Int::class.java)
                        ) {
                            try {
                                m.isAccessible = true
                                val res = m.invoke(extra)
                                val value = when (res) {
                                    is Number -> res.toLong()
                                    else -> -1L
                                }

                                if (value > 100000) {
                                    hasAvid = true
                                } else if (value == 0L) {
                                    hasZeroEpId = true
                                }
                            } catch (_: Throwable) {
                            }
                        }
                    }

                    if (!hasAvid && param.args.size >= 2 && param.args[1] != null) {
                        val paramsObj = param.args[1]
                        paramsObj.javaClass.declaredMethods.forEach { m ->
                            if (m.parameterTypes.isEmpty() &&
                                (m.returnType == Long::class.javaPrimitiveType || m.returnType == Int::class.javaPrimitiveType ||
                                    m.returnType == Long::class.java || m.returnType == Int::class.java)
                            ) {
                                try {
                                    m.isAccessible = true
                                    val res = m.invoke(paramsObj)
                                    val value = when (res) {
                                        is Number -> res.toLong()
                                        else -> -1L
                                    }
                                    if (value > 100000) hasAvid = true
                                } catch (_: Throwable) {
                                }
                            }
                        }
                    }

                    if (hasZeroEpId) {
                        if (hasAvid) {
                            XposedBridge.log(
                                "RecBiliOld: Correcting mis-routed normal video in EpisodeParamsResolver (avid found, epId zero) -> SKIPPING"
                            )
                            param.result = null
                        } else {
                            XposedBridge.log(
                                "RecBiliOld: Correcting mis-routed normal video in EpisodeParamsResolver (no avid found, but epId zero) -> SKIPPING"
                            )
                            param.result = null
                        }
                    }
                } catch (t: Throwable) {
                    XposedBridge.log(t)
                }
            }
        })
    }
}
