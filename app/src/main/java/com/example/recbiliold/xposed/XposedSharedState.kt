package com.example.recbiliold.xposed

import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicReference

internal object XposedSharedState {
    val oversizedAidAtomicRef: AtomicReference<Long?> = AtomicReference<Long?>(null)

    val fullCidAtomicRef: AtomicReference<Long?> = AtomicReference<Long?>(null)

    val luaGetSourceBypassMap: MutableMap<Any, String> =
        Collections.synchronizedMap(WeakHashMap())
}
