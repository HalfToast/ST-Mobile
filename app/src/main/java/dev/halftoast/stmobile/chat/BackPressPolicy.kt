package dev.halftoast.stmobile.chat

enum class BackAction { ConsumedByPage, ShowPressAgainHint, MoveTaskToBack }

object BackPressPolicy {
    const val EXIT_WINDOW_MS = 2000L

    fun decide(consumedByPage: Boolean, lastUnconsumedBackAtMs: Long?, nowMs: Long): BackAction = when {
        consumedByPage -> BackAction.ConsumedByPage
        lastUnconsumedBackAtMs != null && nowMs - lastUnconsumedBackAtMs <= EXIT_WINDOW_MS -> BackAction.MoveTaskToBack
        else -> BackAction.ShowPressAgainHint
    }
}
