package com.example.recbiliold.xposed

import java.security.MessageDigest

internal object PlayurlFixerUtil {

    fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        val pairs = query.split('&')
        for (p in pairs) {
            if (p.isBlank()) continue
            val idx = p.indexOf('=')
            if (idx < 0) {
                out[p] = ""
            } else {
                val k = p.substring(0, idx)
                val v = p.substring(idx + 1)
                out[k] = v
            }
        }
        return out
    }

    fun toQueryString(params: Map<String, String>): String {
        return params.entries.joinToString("&") { (k, v) ->
            "${percentEncode(k)}=${percentEncode(v)}"
        }
    }

    fun percentEncode(s: String): String {
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

    fun md5Hex(s: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}
