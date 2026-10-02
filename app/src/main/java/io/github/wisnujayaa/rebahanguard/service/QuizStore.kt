package io.github.wisnujayaa.rebahanguard.service

import android.content.Context
import io.github.wisnujayaa.rebahanguard.core.QuizCard
import io.github.wisnujayaa.rebahanguard.core.QuizCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object QuizStore {
    private const val FILE = "quiz.txt"
    private const val LAST_PAGE = "last_page.txt"
    private val lock = Any()
    private val state = MutableStateFlow<List<QuizCard>?>(null)

    fun flow(context: Context): StateFlow<List<QuizCard>?> {
        load(context)
        return state.asStateFlow()
    }

    fun load(context: Context): List<QuizCard> = synchronized(lock) {
        state.value ?: QuizCodec.decode(TextFile.read(context, FILE)).also { state.value = it }
    }

    fun update(context: Context, change: (List<QuizCard>) -> List<QuizCard>) = synchronized(lock) {
        val next = change(load(context)).takeLast(QuizCodec.MAX_CARDS)
        if (TextFile.write(context, FILE, QuizCodec.encode(next))) state.value = next
    }

    /** OCR text of the last photographed page, per habit — to tell a new page from the same one. */
    fun lastPage(context: Context, habitId: Long): String? =
        TextFile.read(context, "$habitId-$LAST_PAGE")

    fun setLastPage(context: Context, habitId: Long, text: String) {
        TextFile.write(context, "$habitId-$LAST_PAGE", text.take(20_000))
    }
}
