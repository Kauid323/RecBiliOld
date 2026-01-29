package com.example.recbiliold.xposed

internal object XposedHookUtils {

    fun extractAidFromUriStringLong(uri: String): Long? {
        try {
            val q1 = Regex("(?i)(?:[?&])(aid|avid)=(\\d+)").find(uri)
            if (q1 != null) return q1.groupValues[2].toLongOrNull()

            val q0 = Regex("(?i)bilibili://video/(\\d+)").find(uri)
            if (q0 != null) return q0.groupValues[1].toLongOrNull()

            val q2 = Regex("(?i)(?:^|/)(?:av)(\\d+)(?:\\b|/|\\?|$)").find(uri)
            if (q2 != null) return q2.groupValues[1].toLongOrNull()

            return null
        } catch (_: Throwable) {
            return null
        }
    }
}
