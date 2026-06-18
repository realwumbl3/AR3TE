package com.ar3te

import android.content.Context

object AppPreferences {
    private const val PREFS_NAME = "ar3te_preferences"
    private const val KEY_STREAM_MODE = "stream_mode"
    private const val KEY_SHOW_FRAME_PACING_GRAPH = "show_frame_pacing_graph"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getStreamMode(context: Context): StreamMode {
        val storedValue = prefs(context).getString(KEY_STREAM_MODE, StreamMode.LOW_LATENCY.name)
        return StreamMode.entries.firstOrNull { it.name == storedValue } ?: StreamMode.LOW_LATENCY
    }

    fun setStreamMode(context: Context, streamMode: StreamMode) {
        prefs(context).edit().putString(KEY_STREAM_MODE, streamMode.name).apply()
    }

    fun getShowFramePacingGraph(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SHOW_FRAME_PACING_GRAPH, false)
    }

    fun setShowFramePacingGraph(context: Context, show: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHOW_FRAME_PACING_GRAPH, show).apply()
    }
}
