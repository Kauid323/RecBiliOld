package com.example.recbiliold.xposed

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

internal object PlayurlFixerHttp {

    fun httpGet(url: String, headers: Map<String, String>): String {
        return httpGetWithCode(url, headers).second
    }

    fun httpGetWithCode(url: String, headers: Map<String, String>): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 8000
            readTimeout = 10000
            // Ensure UA exists (some endpoints respond differently without it)
            if (!headers.keys.any { it.equals("User-Agent", ignoreCase = true) }) {
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
                )
            }
            for ((k, v) in headers) {
                setRequestProperty(k, v)
            }
        }

        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val sb = StringBuilder()
            if (stream != null) {
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { br ->
                    var line: String?
                    while (true) {
                        line = br.readLine() ?: break
                        sb.append(line)
                    }
                }
            }
            return code to sb.toString()
        } finally {
            conn.disconnect()
        }
    }

    fun httpGetBytesWithCode(url: String, headers: Map<String, String>): Pair<Int, ByteArray> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 8000
            readTimeout = 10000
            if (!headers.keys.any { it.equals("User-Agent", ignoreCase = true) }) {
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
                )
            }
            for ((k, v) in headers) {
                setRequestProperty(k, v)
            }
        }

        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.readBytes() ?: ByteArray(0)
            return code to bytes
        } finally {
            conn.disconnect()
        }
    }
}
