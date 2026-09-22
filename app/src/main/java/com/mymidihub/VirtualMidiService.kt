package com.mymidihub

import android.media.midi.MidiDeviceService
import android.media.midi.MidiReceiver

/** This service may be bound by other apps while routing is stopped; that MUST NOT
 * start routing. Only the user-started foreground service installs the sink. */
class VirtualMidiService : MidiDeviceService() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onGetInputPortReceivers(): Array<MidiReceiver> = Array(8) { port ->
        object : MidiReceiver() {
            override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
                sink?.invoke("v:in:$port", data.copyOfRange(offset, offset + count), timestamp)
            }
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: VirtualMidiService? = null
        @Volatile var sink: ((String, ByteArray, Long) -> Unit)? = null
            private set

        @Synchronized fun attach(receiver: (String, ByteArray, Long) -> Unit) { sink = receiver }
        @Synchronized fun detach(receiver: ((String, ByteArray, Long) -> Unit)?) {
            if (sink === receiver) sink = null
        }
    }
}
