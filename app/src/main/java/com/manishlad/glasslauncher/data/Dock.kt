package com.manishlad.glasslauncher.data

import android.content.Context
import com.manishlad.glasslauncher.DEFAULT_DOCK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

const val DOCK_MAX = 8

/** The dock's apps, in order. Edited from the phone (phase 5) and on the TV (phase 6). */
class DockRepository(context: Context) {
    private val file = File(context.filesDir, "dock.json")
    private val _packages = MutableStateFlow(DEFAULT_DOCK)
    val packages: StateFlow<List<String>> = _packages

    suspend fun load() = withContext(Dispatchers.IO) {
        if (file.exists()) {
            val a = JSONArray(file.readText())
            _packages.value = (0 until a.length()).map { a.getString(it) }
        }
    }

    suspend fun set(packages: List<String>) = withContext(Dispatchers.IO) {
        val clean = packages.distinct().take(DOCK_MAX)
        file.writeText(JSONArray(clean).toString())
        _packages.value = clean
    }
}
