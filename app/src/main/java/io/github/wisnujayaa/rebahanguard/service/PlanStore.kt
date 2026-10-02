package io.github.wisnujayaa.rebahanguard.service

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import io.github.wisnujayaa.rebahanguard.core.Plan
import io.github.wisnujayaa.rebahanguard.core.PlanCodec
import io.github.wisnujayaa.rebahanguard.core.PlanItem
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user's plan, stored in app-private storage. Written through [AtomicFile], so a crash or a
 * dead battery mid-write leaves the previous version intact instead of an empty plan.
 */
object PlanStore {
    private const val TAG = "PlanStore"
    private const val FILE = "plan.txt"
    private val lock = Any()
    private val _items = MutableStateFlow<List<PlanItem>?>(null)

    fun items(context: Context): StateFlow<List<PlanItem>?> {
        if (_items.value == null) _items.value = load(context)
        return _items.asStateFlow()
    }

    fun load(context: Context): List<PlanItem> = synchronized(lock) {
        _items.value?.let { return it }
        val file = AtomicFile(File(context.filesDir, FILE))
        val list = try {
            PlanCodec.decode(file.readFully().toString(Charsets.UTF_8))
        } catch (e: java.io.FileNotFoundException) {
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the plan", e)
            emptyList()
        }
        _items.value = list
        list
    }

    fun update(context: Context, change: (List<PlanItem>) -> List<PlanItem>) = synchronized(lock) {
        val next = Plan.prune(change(load(context)))
        val file = AtomicFile(File(context.filesDir, FILE))
        var out: java.io.FileOutputStream? = null
        try {
            out = file.startWrite()
            out.write(PlanCodec.encode(next).toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
            _items.value = next
        } catch (e: Exception) {
            Log.w(TAG, "Could not save the plan", e)
            if (out != null) file.failWrite(out)
        }
    }

    fun add(context: Context, item: PlanItem) = update(context) { it + item }

    fun replace(context: Context, item: PlanItem) = update(context) { list -> list.map { if (it.id == item.id) item else it } }

    fun remove(context: Context, id: Long) = update(context) { list -> list.filterNot { it.id == id } }

    fun setDone(context: Context, id: Long, done: Boolean, nowMs: Long = System.currentTimeMillis()) =
        update(context) { list -> list.map { if (it.id == id) it.copy(doneAtMs = if (done) nowMs else null) else it } }
}
