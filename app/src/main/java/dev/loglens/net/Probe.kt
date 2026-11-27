package dev.loglens.net

import java.net.HttpURLConnection
import java.net.URL

fun ping() {
    val c = URL("http://example.com/x")
    (c.openConnection() as HttpURLConnection).inputStream.readBytes()
}
