package com.mymidihub

import java.io.ByteArrayOutputStream

/** Reconstruct messages before merging streams. Running status belongs to a SOURCE,
 * never to a destination shared by multiple sources. Real-time bytes stay immediate.
 * A complete SysEx is sent atomically so another source cannot corrupt its contents. */
class MidiFramer(private val emit: (ByteArray, Long) -> Unit) {
    private var status = 0
    private var expected = 0
    private val data = ArrayList<Byte>(2)
    private var stamp = 0L
    private var explicitStatus = false
    private var sysex: ByteArrayOutputStream? = null
    private var discardingSysex = false

    fun reset() {
        status = 0; expected = 0; data.clear(); sysex = null; discardingSysex = false; explicitStatus = false
    }

    fun accept(bytes: ByteArray, timestamp: Long) {
        for (byte in bytes) {
            val value = byte.toInt() and 255
            if (value >= 0xF8) {
                emit(byteArrayOf(byte), timestamp)
                continue
            }
            if (sysex != null || discardingSysex) {
                if (value == 0xF7) {
                    sysex?.let { it.write(value); emit(it.toByteArray(), stamp) }
                    sysex = null; discardingSysex = false
                    continue
                }
                if (value < 0x80) {
                    sysex?.let {
                        if (it.size() >= MAX_SYSEX) { sysex = null; discardingSysex = true }
                        else it.write(value)
                    }
                    continue
                }
                sysex = null; discardingSysex = false // Resync on a new status.
            }
            if (value >= 0x80) {
                data.clear(); stamp = timestamp; explicitStatus = true
                status = value
                expected = when (value) {
                    in 0x80..0xBF, in 0xE0..0xEF, 0xF2 -> 2
                    in 0xC0..0xDF, 0xF1, 0xF3 -> 1
                    else -> 0
                }
                if (value == 0xF0) {
                    sysex = ByteArrayOutputStream().also { it.write(value) }; status = 0
                } else if (expected == 0) {
                    emit(byteArrayOf(byte), timestamp); status = 0
                }
            } else if (status != 0 && expected > 0) {
                if (data.isEmpty() && !explicitStatus) stamp = timestamp
                explicitStatus = false
                data.add(byte)
                if (data.size == expected) {
                    emit(byteArrayOf(status.toByte()) + data.toByteArray(), stamp)
                    data.clear()
                    if (status >= 0xF0) { status = 0; expected = 0 }
                }
            }
        }
    }

    companion object { const val MAX_SYSEX = 1024 * 1024 }
}
