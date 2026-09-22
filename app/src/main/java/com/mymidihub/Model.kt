package com.mymidihub

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Endpoint(val id: String, val name: String, val source: Boolean, val virtual: Boolean = false)
data class Route(val source: String, val destination: String)
data class PortState(val endpoint: Endpoint, val status: String, val available: Boolean)
data class Snapshot(
    val running: Boolean = false,
    val ports: List<PortState> = emptyList(),
    val routes: List<Route> = emptyList(),
    val active: Set<Route> = emptySet(),
    val message: String = "Starting…"
)

/** All mutations happen on the routing worker. A single JSON value is written atomically. */
class RoutingStore(context: Context) {
    private val preferences = context.getSharedPreferences("routing", Context.MODE_PRIVATE)
    val known = linkedMapOf<String, Endpoint>()
    val routes = linkedSetOf<Route>()
    val aliases = linkedMapOf<String, String>()
    val visibleVirtual = linkedSetOf<String>()

    init {
        for (i in 0..7) {
            known["v:in:$i"] = Endpoint("v:in:$i", "Hub In ${i + 1}", true, true)
            known["v:out:$i"] = Endpoint("v:out:$i", "Hub Out ${i + 1}", false, true)
        }
        try {
            val raw = preferences.getString("state", null)
            if (raw != null) {
                val state = JSONObject(raw)
                val ports = state.getJSONArray("ports")
                for (i in 0 until ports.length()) {
                    val p = ports.getJSONObject(i)
                    val e = Endpoint(p.getString("id"), p.getString("name"), p.getBoolean("source"))
                    if (!e.id.startsWith("v:")) known[e.id] = e
                }
                val saved = state.getJSONArray("routes")
                for (i in 0 until saved.length()) {
                    val r = saved.getJSONObject(i)
                    val route = Route(r.getString("source"), r.getString("destination"))
                    if (known[route.source]?.source == true && known[route.destination]?.source == false)
                        routes.add(route)
                }
                val labels = state.getJSONObject("aliases")
                labels.keys().forEach { aliases[it] = labels.getString(it) }
                val visible = state.getJSONArray("virtual")
                for (i in 0 until visible.length()) visibleVirtual.add(visible.getString(i))
                routes.forEach { r ->
                    if (r.source.startsWith("v:")) visibleVirtual.add(r.source)
                    if (r.destination.startsWith("v:")) visibleVirtual.add(r.destination)
                }
                if (state.optInt("version", 1) < 2) {
                    // Older installs added port 1 automatically. Remove unused defaults,
                    // but preserve any port the user named or connected.
                    listOf("v:in:0", "v:out:0").forEach { id ->
                        if (id !in aliases && routes.none { it.source == id || it.destination == id })
                            visibleVirtual.remove(id)
                    }
                    save()
                }
            }
        } catch (_: Exception) {
            // Retain the previous raw value for diagnosis; don't erase it on a failed read.
            preferences.getString("state", null)?.let { preferences.edit().putString("recovery", it).apply() }
            routes.clear(); visibleVirtual.clear()
        }
    }

    fun label(id: String) = aliases[id] ?: known[id]?.name ?: id

    fun save(): Boolean {
        val ports = JSONArray()
        known.values.filterNot { it.virtual }.forEach {
            ports.put(JSONObject().put("id", it.id).put("name", it.name).put("source", it.source))
        }
        val connections = JSONArray()
        routes.forEach { connections.put(JSONObject().put("source", it.source).put("destination", it.destination)) }
        return preferences.edit().putString("state", JSONObject()
            .put("version", 2).put("ports", ports).put("routes", connections)
            .put("aliases", JSONObject(aliases.toMap())).put("virtual", JSONArray(visibleVirtual.toList()))
            .toString()).commit()
    }
}
