package com.mymidihub

import android.content.Context
import android.content.pm.ServiceInfo
import android.media.midi.*
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import java.io.Closeable
import java.io.IOException

/** One worker owns the topology, open ports, message framers and all MIDI writes.
 * Device/Binder callbacks copy their buffers and enqueue work; none touch Activity state. */
class RoutingEngine(context: Context, private val publish: (Snapshot) -> Unit) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(MidiManager::class.java)
    private val thread = HandlerThread("MIDI routing", Process.THREAD_PRIORITY_AUDIO).apply { start() }
    private val worker = Handler(thread.looper)
    private val store = RoutingStore(app)
    private var running = false
    private var generation = 0
    private var saveError = false
    private val infos = mutableMapOf<Int, MidiDeviceInfo>()
    private val devices = mutableMapOf<Int, MidiDevice>()
    private val opening = mutableSetOf<Int>()
    private val ports = mutableMapOf<String, LocatedPort>()
    private val inputs = mutableMapOf<String, MidiInputPort>()
    private val outputs = mutableMapOf<String, MidiOutputPort>()
    private val framers = mutableMapOf<String, MidiFramer>()
    private val errors = mutableMapOf<String, String>()
    private val ambiguous = mutableSetOf<String>()
    private var destinations: Map<String, MidiReceiver> = emptyMap()
    private var active = emptySet<Route>()
    private var ownDeviceId: Int? = null
    private var virtualSink: ((String, ByteArray, Long) -> Unit)? = null

    init { worker.post { emit("Stopped · routes saved") } }

    private data class LocatedPort(val info: MidiDeviceInfo, val number: Int, val source: Boolean)
    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) { if (running) reconcile() }
        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            // A stale device handle must never survive a disconnect/reconnect.
            dropDevice(info.id)
            if (running) reconcile()
        }
        override fun onDeviceStatusChanged(status: MidiDeviceStatus) { if (running) reconcile() }
    }
    private val retry = object : Runnable {
        override fun run() {
            if (!running) return
            reconcile()
            worker.postDelayed(this, 3000)
        }
    }

    fun start() = worker.post {
        if (running) return@post
        if (manager == null) { emit("Android MIDI is unavailable on this device"); return@post }
        running = true; generation++
        val epoch = generation
        virtualSink = { id, bytes, timestamp ->
            worker.post { if (epoch == generation) ingest(id, bytes, timestamp) }
            Unit
        }
        VirtualMidiService.attach(virtualSink!!)
        manager.registerDeviceCallback(callback, worker)
        retry.run()
    }

    fun stop(after: () -> Unit = {}) = worker.post { stopNow(); after() }

    private fun stopNow() {
        running = false; generation++
        worker.removeCallbacks(retry)
        VirtualMidiService.detach(virtualSink)
        virtualSink = null
        manager?.unregisterDeviceCallback(callback)
        panicNow()
        active = emptySet(); destinations = emptyMap()
        outputs.values.forEach { close(it) }; outputs.clear()
        inputs.values.forEach { close(it) }; inputs.clear()
        devices.values.forEach { close(it) }; devices.clear()
        opening.clear(); framers.clear(); errors.clear()
        emit("Stopped · routes saved")
    }

    fun destroy() = worker.post { stopNow(); thread.quitSafely() }

    fun refresh() = worker.post { if (running) reconcile() else emit("Stopped · routes saved") }

    fun connect(source: String, destination: String) = worker.post {
        if (store.known[source]?.source != true || store.known[destination]?.source != false) return@post
        store.routes.add(Route(source, destination))
        saveAndReconcile()
    }

    fun disconnect(route: Route) = worker.post {
        if (route in active) destinations[route.destination]?.let { allNotesOff(it) }
        store.routes.remove(route)
        saveAndReconcile()
    }

    fun rename(id: String, label: String) = worker.post {
        if (label.isBlank()) store.aliases.remove(id) else store.aliases[id] = label.trim().take(48)
        saveAndReconcile()
    }

    fun addVirtual(source: Boolean) = worker.post {
        store.known.values.firstOrNull { it.virtual && it.source == source && it.id !in store.visibleVirtual }
            ?.let { store.visibleVirtual.add(it.id) }
        saveAndReconcile()
    }

    fun forget(id: String) = worker.post {
        store.routes.filter { it.source == id || it.destination == id }.forEach { route ->
            destinations[route.destination]?.let { allNotesOff(it) }
        }
        store.routes.removeAll { it.source == id || it.destination == id }
        if (store.known[id]?.virtual == true) store.visibleVirtual.remove(id)
        else if (id !in ports) store.known.remove(id)
        store.aliases.remove(id)
        saveAndReconcile()
    }

    fun panic() = worker.post { panicNow() }

    private fun saveAndReconcile() {
        saveError = !store.save()
        if (running) reconcile() else emit("Stopped · routes saved")
    }

    @Suppress("DEPRECATION")
    private fun identity(info: MidiDeviceInfo): String {
        val p = info.properties
        // Optional AOSP bundle metadata; not a public MidiDeviceInfo constant.
        // Identity still works from public MIDI properties if a vendor omits it.
        val service = p.getParcelable("service_info") as? ServiceInfo
        return DeviceIdentity.key(listOf(info.type.toString(), service?.packageName.orEmpty(),
            service?.name.orEmpty(), p.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER).orEmpty(),
            p.getString(MidiDeviceInfo.PROPERTY_PRODUCT).orEmpty(),
            p.getString(MidiDeviceInfo.PROPERTY_NAME).orEmpty(),
            p.getString(MidiDeviceInfo.PROPERTY_SERIAL_NUMBER).orEmpty(),
            info.ports.joinToString(";") { "${it.type}:${it.portNumber}:${it.name}" }))
    }

    @Suppress("DEPRECATION")
    private fun isOwn(info: MidiDeviceInfo): Boolean {
        return info.type == MidiDeviceInfo.TYPE_VIRTUAL &&
            info.properties.getString(MidiDeviceInfo.PROPERTY_SERIAL_NUMBER) == "com.mymidihub.virtual.v1" &&
            info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT) == "My MIDI Hub"
    }

    @Suppress("DEPRECATION")
    private fun reconcile() {
        if (!running) return
        val detected = manager?.devices.orEmpty().filter { it.type != MidiDeviceInfo.TYPE_BLUETOOTH }
        val currentIds = detected.map { it.id }.toSet()
        devices.keys.toList().filter { it !in currentIds }.forEach { dropDevice(it) }
        infos.clear(); detected.forEach { infos[it.id] = it }
        val previousPorts = ports.toMap()
        ports.clear(); ambiguous.clear(); errors.clear()
        ownDeviceId = detected.firstOrNull { isOwn(it) }?.id
        var changed = false
        detected.filterNot { isOwn(it) }.groupBy { identity(it) }.forEach { (key, matches) ->
            val info = matches.first()
            val deviceName = info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
                ?: info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT) ?: "MIDI device"
            info.ports.forEach { port ->
                val source = port.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT
                val id = "$key:${port.type}:${port.portNumber}"
                val label = port.name.takeIf { it.isNotBlank() } ?: "${if (source) "Out" else "In"} ${port.portNumber + 1}"
                val endpoint = Endpoint(id, "$deviceName · $label", source)
                if (store.known[id] != endpoint) { store.known[id] = endpoint; changed = true }
                if (matches.size == 1) ports[id] = LocatedPort(info, port.portNumber, source)
                else ambiguous.add(id) // Never silently route to the wrong identical device.
            }
        }
        if (changed) saveError = !store.save()

        // Remove handles whose identity now resolves to another runtime device, or is ambiguous.
        (inputs.keys + outputs.keys).toSet().forEach { id ->
            if (ports[id]?.info?.id != previousPorts[id]?.info?.id || id !in ports) closePort(id)
        }
        val neededSources = store.routes.map { it.source }.toSet()
        val neededDestinations = store.routes.map { it.destination }.toSet()
        outputs.keys.toList().filter { it !in neededSources }.forEach { closePort(it) }
        inputs.keys.toList().filter { it !in neededDestinations }.forEach { closePort(it) }
        val neededDevices = (neededSources + neededDestinations).mapNotNull { ports[it]?.info?.id }.toMutableSet()
        ownDeviceId?.let { neededDevices.add(it) } // Keep our virtual MIDI service bound.
        devices.keys.toList().filter { it !in neededDevices }.forEach { dropDevice(it) }
        neededDevices.forEach { id ->
            if (id !in devices && id !in opening) infos[id]?.let { openDevice(it) }
        }

        neededDestinations.forEach { id ->
            val port = ports[id] ?: return@forEach
            val device = devices[port.info.id] ?: return@forEach
            if (id !in inputs) try {
                val input = device.openInputPort(port.number)
                if (input == null) errors[id] = "Busy · waiting for port" else inputs[id] = input
            } catch (_: Exception) { closePort(id); errors[id] = "Cannot open · retrying" }
        }
        neededSources.forEach { id ->
            val port = ports[id] ?: return@forEach
            val device = devices[port.info.id] ?: return@forEach
            if (id !in outputs) try {
                val output = device.openOutputPort(port.number)
                if (output == null) errors[id] = "Cannot open · retrying"
                else {
                    outputs[id] = output
                    val epoch = generation
                    output.connect(object : MidiReceiver() {
                        override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
                            val bytes = data.copyOfRange(offset, offset + count)
                            worker.post {
                                if (epoch == generation && outputs[id] === output) ingest(id, bytes, timestamp)
                            }
                        }
                    })
                }
            } catch (_: Exception) { closePort(id); errors[id] = "Cannot open · retrying" }
        }
        val virtualOutputs = VirtualMidiService.instance?.outputPortReceivers
        val receivers = mutableMapOf<String, MidiReceiver>()
        receivers.putAll(inputs)
        virtualOutputs?.forEachIndexed { i, receiver -> receivers["v:out:$i"] = receiver }
        destinations = receivers
        val virtualReady = ownDeviceId in devices && virtualOutputs != null
        val nowActive = store.routes.filter { route ->
            (route.source in outputs || (virtualReady && route.source.startsWith("v:in:"))) &&
                route.destination in receivers
        }.toSet()
        // A vanished source should not leave a held note playing at a remaining destination.
        (active - nowActive).map { it.destination }.distinct().forEach { id -> receivers[id]?.let { allNotesOff(it) } }
        active = nowActive
        framers.keys.retainAll(active.map { it.source }.toSet())
        emit(if (ownDeviceId == null) "Waiting for Android virtual MIDI service" else "${active.size} active / ${store.routes.size} saved routes")
    }

    private fun openDevice(info: MidiDeviceInfo) {
        val epoch = generation
        opening.add(info.id)
        try {
            manager?.openDevice(info, { device ->
                if (epoch != generation || !running || info.id !in infos) { close(device); return@openDevice }
                opening.remove(info.id)
                if (device != null) { devices[info.id] = device; reconcile() }
                else emit("A MIDI device could not be opened · retrying")
            }, worker)
        } catch (_: Exception) {
            opening.remove(info.id)
        }
    }

    private fun ingest(id: String, bytes: ByteArray, timestamp: Long) {
        if (!running || active.none { it.source == id }) return
        framers.getOrPut(id) { MidiFramer { message, time -> forward(id, message, time) } }.accept(bytes, timestamp)
    }

    private fun forward(source: String, bytes: ByteArray, timestamp: Long) {
        active.filter { it.source == source }.forEach { route ->
            try { destinations[route.destination]?.send(bytes, 0, bytes.size, timestamp) }
            catch (_: IOException) {
                closePort(route.destination)
                active = active - route
                errors[route.destination] = "Connection lost · retrying"
                emit("Connection lost · retrying")
            }
        }
    }

    private fun panicNow() {
        destinations.values.toSet().forEach { allNotesOff(it) }
        framers.values.forEach { it.reset() }
    }

    private fun allNotesOff(receiver: MidiReceiver) {
        try {
            receiver.flush()
            for (channel in 0..15) {
                // Release sustain, sound, and notes on every channel.
                for (controller in listOf(64, 120, 123)) {
                    receiver.send(byteArrayOf((0xB0 + channel).toByte(), controller.toByte(), 0), 0, 3)
                }
            }
        } catch (_: IOException) { /* The device may already be unplugged. */ }
    }

    private fun closePort(id: String) {
        inputs.remove(id)?.let { allNotesOff(it); close(it) }
        outputs.remove(id)?.let { close(it) }
        framers.remove(id)
    }

    private fun dropDevice(id: Int) {
        ports.filterValues { it.info.id == id }.keys.toList().forEach { closePort(it) }
        close(devices.remove(id)); opening.remove(id)
    }

    private fun close(value: Closeable?) { try { value?.close() } catch (_: IOException) {} }

    private fun emit(message: String) {
        val virtualReady = running && ownDeviceId in devices && VirtualMidiService.instance != null
        val displayed = store.known.values.filter { !it.virtual || it.id in store.visibleVirtual }.map { endpoint ->
            val available = if (endpoint.virtual) virtualReady else endpoint.id in ports
            val state = when {
                !running -> "Stopped"
                endpoint.id in ambiguous -> "Identical devices · ambiguous"
                !available -> "Unavailable · waiting"
                endpoint.id in errors -> errors.getValue(endpoint.id)
                active.any { it.source == endpoint.id || it.destination == endpoint.id } -> "Connected"
                endpoint.virtual -> if (endpoint.source) "From other apps" else "To other apps"
                else -> "Available"
            }
            PortState(endpoint.copy(name = store.label(endpoint.id)), state, available)
        }
        publish(Snapshot(running, displayed, store.routes.toList(), active,
            if (saveError) "Could not save changes to storage" else message))
    }
}
