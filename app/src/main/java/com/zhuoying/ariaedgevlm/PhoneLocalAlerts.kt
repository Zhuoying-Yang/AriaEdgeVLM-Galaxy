package com.zhuoying.ariaedgevlm

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** Phone-owned, bounded alert history. Never invents demo detections. */
internal object PhoneLocalAlerts {
    private var loaded = false
    var alerts by mutableStateOf<List<JSONObject>>(emptyList())
        private set
    var active by mutableStateOf<JSONObject?>(null)
        private set

    fun ensureLoaded(context: Context) {
        if (loaded) return
        val raw = context.getSharedPreferences("phone_yamnet_alerts", Context.MODE_PRIVATE)
            .getString("history", "[]") ?: "[]"
        alerts = try {
            val json = JSONArray(raw)
            (0 until json.length()).mapNotNull { json.optJSONObject(it) }
        } catch (_: Exception) { emptyList() }
        loaded = true
    }

    fun record(context: Context, key: String, label: String, confidence: Float) {
        ensureLoaded(context)
        val event = JSONObject()
            .put("id", System.currentTimeMillis())
            .put("ts", System.currentTimeMillis() / 1000.0)
            .put("label", label)
            .put("category", key)
            .put("source", "galaxy_yamnet")
            .put("confidence", confidence.toDouble())
            .put("acknowledged", 0)
        alerts = (listOf(event) + alerts).take(100)
        persist(context)
        if (active == null) active = event
    }

    fun dismiss(context: Context) {
        val original = active ?: return
        val id = original.optLong("id")
        alerts = alerts.map {
            if (it.optLong("id") == id) JSONObject(it.toString()).put("acknowledged", 1) else it
        }
        active = null
        persist(context)
    }

    private fun persist(context: Context) {
        val arr = JSONArray()
        alerts.forEach { arr.put(it) }
        context.getSharedPreferences("phone_yamnet_alerts", Context.MODE_PRIVATE)
            .edit().putString("history", arr.toString()).apply()
    }
}
