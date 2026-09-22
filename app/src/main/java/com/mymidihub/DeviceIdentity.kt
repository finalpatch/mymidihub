package com.mymidihub

import java.security.MessageDigest

/** Runtime Android device IDs change on reconnect; never persist them. Length-prefixing
 * fields also avoids collisions caused by separators appearing in device names. */
object DeviceIdentity {
    fun key(fields: List<String>): String {
        val canonical = fields.joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
