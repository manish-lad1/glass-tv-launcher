package com.manishlad.glasslauncher.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

/** Widgets that can go in Home's three slots. [id] is what's stored and sent to the phone. */
enum class WidgetKind(val id: String, val label: String) {
    Weather("weather", "Weather"),
    UpNext("upnext", "Up next (calendar)"),
    Watching("watching", "Now watching"),
    F1("f1", "Formula 1");

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id }
    }
}

val DEFAULT_WIDGETS = listOf(WidgetKind.Weather, WidgetKind.UpNext, WidgetKind.Watching)

/** Which widget is in each of Home's three slots (left, middle, right). Chosen from the phone. */
class WidgetSlotsRepository(context: Context) {
    private val file = File(context.filesDir, "widgets.json")
    private val _slots = MutableStateFlow(DEFAULT_WIDGETS)
    val slots: StateFlow<List<WidgetKind>> = _slots

    suspend fun load() = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext
        val a = runCatching { JSONArray(file.readText()) }.getOrNull() ?: return@withContext
        val read = (0 until a.length()).mapNotNull { WidgetKind.of(a.getString(it)) }
        if (read.size == 3 && read.distinct().size == 3) _slots.value = read
    }

    /** Put [kind] in [slot]; if it was already in another slot, the two swap so each shows once. */
    suspend fun set(slot: Int, kind: WidgetKind) = withContext(Dispatchers.IO) {
        if (slot !in 0..2) return@withContext
        val next = _slots.value.toMutableList()
        val other = next.indexOf(kind)
        if (other >= 0) next[other] = next[slot]
        next[slot] = kind
        file.writeText(JSONArray(next.map { it.id }).toString())
        _slots.value = next
    }
}
