package io.github.wisnujayaa.rebahanguard.service

import android.content.Context
import io.github.wisnujayaa.rebahanguard.core.DayClock
import io.github.wisnujayaa.rebahanguard.core.DreamBook
import io.github.wisnujayaa.rebahanguard.core.DreamCodec
import io.github.wisnujayaa.rebahanguard.core.DreamRules
import java.util.TimeZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Dreams, habits and their daily log. Pending "berlaku besok" changes are settled on every read. */
object DreamStore {
    private const val FILE = "dreams.txt"
    private val lock = Any()
    private val state = MutableStateFlow<DreamBook?>(null)

    fun today(nowMs: Long = System.currentTimeMillis()): Int = DayClock.dayOf(nowMs, TimeZone.getDefault().getOffset(nowMs))

    fun flow(context: Context): StateFlow<DreamBook?> {
        load(context)
        return state.asStateFlow()
    }

    fun load(context: Context): DreamBook = synchronized(lock) {
        val current = state.value ?: DreamCodec.decode(TextFile.read(context, FILE))
        val settled = DreamRules.settle(current, today())
        if (settled != current && state.value != null) TextFile.write(context, FILE, DreamCodec.encode(settled))
        state.value = settled
        settled
    }

    fun update(context: Context, change: (DreamBook) -> DreamBook): DreamBook = synchronized(lock) {
        val next = change(load(context))
        if (TextFile.write(context, FILE, DreamCodec.encode(next))) state.value = next
        state.value ?: next
    }

    /** Applies a change through the rules; returns the result so the UI can say "berlaku besok". */
    fun apply(context: Context, change: DreamRules.Change): DreamRules.Result = synchronized(lock) {
        val result = DreamRules.apply(load(context), change, today())
        if (result.rejected == null) update(context) { result.book }
        result
    }

    fun addProgress(context: Context, habitId: Long, delta: Int, strength: Int) =
        update(context) { DreamRules.addProgress(it, habitId, today(), delta, strength) }
}
