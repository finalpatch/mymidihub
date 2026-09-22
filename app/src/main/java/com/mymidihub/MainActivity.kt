package com.mymidihub

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.view.*
import android.widget.*

class MainActivity : Activity() {
    private var service: RoutingService? = null
    private var bound = false
    private var state = Snapshot()
    private lateinit var board: RoutingView
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private val observer: (Snapshot) -> Unit = { next ->
        state = next
        status.text = next.message
        toggle.text = if (next.running) "Stop" else "Start"
        board.snapshot = next
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as RoutingService.LocalBinder).service
            service?.observe(observer)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null; status.text = "Service disconnected · reopen app to retry"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        root.addView(TextView(this).apply {
            text = "My MIDI Hub"; textSize = 23f; setPadding(dp(12), dp(12), dp(12), dp(2))
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        status = TextView(this).apply { text = "Starting…"; textSize = 13f; setPadding(dp(12), dp(4), dp(12), dp(4)) }
        root.addView(status)
        val toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(6), 0, dp(6), 0) }
        toggle = button("Stop") {
            if (state.running) service?.stopRouting() else startRouting()
        }
        toolbar.addView(toggle, LinearLayout.LayoutParams(0, dp(52), 1f))
        toolbar.addView(button("Ports") { showPorts() }, LinearLayout.LayoutParams(0, dp(52), 1f))
        toolbar.addView(button("Routes") { showRoutes() }, LinearLayout.LayoutParams(0, dp(52), 1f))
        toolbar.addView(button("⋮") { showMore() }.apply { contentDescription = "More options" }, LinearLayout.LayoutParams(dp(48), dp(52)))
        root.addView(toolbar)
        root.addView(TextView(this).apply {
            text = "Drag left → right to connect. Hold a port to edit."
            textSize = 12f; setPadding(dp(12), dp(4), dp(12), dp(6))
        })
        board = RoutingView(this).apply {
            onConnect = { source, destination ->
                service?.engine?.connect(source, destination)
                Toast.makeText(this@MainActivity, "Route added", Toast.LENGTH_SHORT).show()
            }
            onPortMenu = { showPortMenu(it) }
        }
        root.addView(ScrollView(this).apply { addView(board); isFillViewport = true }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        if (savedInstanceState?.getBoolean("running", true) != false) startRouting()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            val prefs = getPreferences(MODE_PRIVATE)
            if (!prefs.getBoolean("notificationAsked", false)) {
                prefs.edit().putBoolean("notificationAsked", true).apply()
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, RoutingService::class.java), connection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        service?.unobserve(observer)
        if (bound) { unbindService(connection); bound = false }
        service = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("running", state.running)
        super.onSaveInstanceState(outState)
    }

    private fun startRouting() { startForegroundService(Intent(this, RoutingService::class.java)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f; minWidth = 0; minimumWidth = 0
        setPadding(dp(4), 0, dp(4), 0); setOnClickListener { action() }
    }

    private fun showPorts() {
        val actions = arrayOf("Add virtual source (Hub In)", "Add virtual destination (Hub Out)", "Edit a port…")
        AlertDialog.Builder(this).setTitle("Virtual ports")
            .setItems(actions) { _, which ->
                when (which) {
                    0, 1 -> {
                        val source = which == 0
                        if (state.ports.count { it.endpoint.virtual && it.endpoint.source == source } >= 8)
                            Toast.makeText(this, "All 8 ports are already on the board", Toast.LENGTH_SHORT).show()
                        else service?.engine?.addVirtual(source)
                    }
                    else -> choosePort(state.ports) { showPortMenu(it) }
                }
            }.setNegativeButton("Close", null).setNeutralButton("How ports work") { _, _ -> showHelp() }.show()
    }

    private fun choosePort(ports: List<PortState>, chosen: (PortState) -> Unit) {
        if (ports.isEmpty()) { Toast.makeText(this, "No ports available", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this).setTitle("Choose port")
            .setItems(ports.map { "${it.endpoint.name}\n${it.status}" }.toTypedArray()) { _, index -> chosen(ports[index]) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showPortMenu(port: PortState) {
        val actions = mutableListOf("Rename")
        if (port.endpoint.virtual) actions.add("Remove from board")
        else if (!port.available) actions.add("Forget unavailable port")
        AlertDialog.Builder(this).setTitle(port.endpoint.name).setItems(actions.toTypedArray()) { _, which ->
            if (which == 0) {
                val input = EditText(this).apply { setSingleLine(); setText(port.endpoint.name); selectAll() }
                AlertDialog.Builder(this).setTitle("Label in My MIDI Hub").setView(input)
                    .setMessage("Other apps keep seeing the original Android port name.")
                    .setPositiveButton("Save") { _, _ -> service?.engine?.rename(port.endpoint.id, input.text.toString()) }
                    .setNegativeButton("Cancel", null).show()
            } else {
                AlertDialog.Builder(this).setTitle(actions[which])
                    .setMessage("Remove this port and its saved routes from the board?" +
                        if (port.endpoint.virtual) " Its numbered Android port remains available to other apps." else "")
                    .setPositiveButton("Remove") { _, _ -> service?.engine?.forget(port.endpoint.id) }
                    .setNegativeButton("Cancel", null).show()
            }
        }.setNegativeButton("Close", null).show()
    }

    private fun showRoutes() {
        val names = state.ports.associate { it.endpoint.id to it.endpoint.name }
        val routes = state.routes.toList()
        val builder = AlertDialog.Builder(this).setTitle("Saved routes (${routes.size})")
            .setPositiveButton("Add route") { _, _ ->
                choosePort(state.ports.filter { it.endpoint.source }) { source ->
                    choosePort(state.ports.filterNot { it.endpoint.source }) { destination ->
                        service?.engine?.connect(source.endpoint.id, destination.endpoint.id)
                    }
                }
            }.setNegativeButton("Close", null)
        if (routes.isEmpty()) builder.setMessage("Drag a source onto a destination, or use Add route.")
        else builder.setItems(routes.map {
            "${names[it.source]} → ${names[it.destination]}\n${if (it in state.active) "Active" else "Waiting"} · tap to remove"
        }.toTypedArray()) { _, i ->
            AlertDialog.Builder(this).setTitle("Remove route?")
                .setMessage("${names[routes[i].source]} → ${names[routes[i].destination]}")
                .setPositiveButton("Remove") { _, _ -> service?.engine?.disconnect(routes[i]) }
                .setNegativeButton("Cancel", null).show()
        }
        builder.show()
    }

    private fun showMore() {
        AlertDialog.Builder(this).setTitle("Options")
            .setItems(arrayOf("All notes off", "Refresh devices", "Help")) { _, which ->
                when (which) {
                    0 -> { service?.engine?.panic(); Toast.makeText(this, "All notes off sent", Toast.LENGTH_SHORT).show() }
                    1 -> service?.engine?.refresh()
                    else -> showHelp()
                }
            }.setNegativeButton("Close", null).show()
    }

    private fun showHelp() {
        AlertDialog.Builder(this).setTitle("Using My MIDI Hub")
            .setMessage("Sources are on the left; destinations are on the right. Drag horizontally from a source to a destination, or tap each in turn. Use Routes to add or remove connections. Swipe vertically to scroll.\n\n" +
                "In another app, send to Hub In 1 to feed that source here. Receive from Hub Out 1 to listen to that destination. Android exposes all eight numbered inputs and outputs; Ports adds the ones you want to the board. Renames are local labels.\n\n" +
                "Routes and labels save automatically. Missing devices reconnect when available. A busy destination may be owned by another app. Indistinguishable duplicate devices are left unconnected until only one is attached.\n\n" +
                "Routing continues with the screen off or while using other apps. Use Stop here or in the notification to end it. Open the app after a reboot to resume. If Samsung restricts it, set its battery usage to Unrestricted in Android settings.\n\n" +
                "Avoid routing an app’s output back to its own input unless you intend a feedback loop. Removing a route sends all-notes-off to its destination, which also silences any other sources sharing it.")
            .setPositiveButton("OK", null).show()
    }
}
