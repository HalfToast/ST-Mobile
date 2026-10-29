package dev.halftoast.stmobile.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Holds the current [StTheme]; persists it only when the web UI reports something different. */
class ThemeStore(load: () -> String?, private val save: (String) -> Unit) {
    private val _theme = MutableStateFlow(StTheme.fromJson(load()))
    val theme: StateFlow<StTheme> = _theme

    fun update(vars: Map<String, String>) {
        if (vars.values.all { it.isBlank() }) return // unreadable page: keep the remembered theme
        val next = StTheme.fromCssVars(vars)
        if (next == _theme.value) return
        _theme.value = next
        save(next.toJson())
    }
}
