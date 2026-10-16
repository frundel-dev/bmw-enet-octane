package com.example.bmwenettest

/**
 * Single source of truth for the simplified, four-tab UI.
 * This only governs presentation; no socket, LOGGER, fuel or CSV state lives here.
 */
internal object Octane2Navigation {
    const val COLLECT = 0
    const val PROGRESS = 1
    const val REFUELS = 2
    const val MORE = 3

    val labels: List<String> = listOf("Сбор", "Прогресс", "Заправки", "Ещё")

    fun restore(index: Int): Int = index.coerceIn(COLLECT, MORE)

    fun needsGuidedRefresh(index: Int): Boolean =
        index == COLLECT || index == PROGRESS
}
