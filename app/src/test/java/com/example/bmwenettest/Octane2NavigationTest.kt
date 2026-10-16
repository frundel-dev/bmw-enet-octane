package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class Octane2NavigationTest {
    @Test fun allFourTabsHaveStablePositions() {
        assertEquals(listOf("Сбор", "Прогресс", "Заправки", "Ещё"),
            Octane2Navigation.labels)
        assertEquals(0, Octane2Navigation.COLLECT)
        assertEquals(1, Octane2Navigation.PROGRESS)
        assertEquals(2, Octane2Navigation.REFUELS)
        assertEquals(3, Octane2Navigation.MORE)
        assertEquals(4, Octane2Navigation.labels.distinct().size)
    }

    @Test fun restoreBoundsSavedTabFromOlderLayouts() {
        assertEquals(Octane2Navigation.COLLECT, Octane2Navigation.restore(-1))
        assertEquals(Octane2Navigation.COLLECT, Octane2Navigation.restore(0))
        assertEquals(Octane2Navigation.PROGRESS, Octane2Navigation.restore(1))
        assertEquals(Octane2Navigation.REFUELS, Octane2Navigation.restore(2))
        assertEquals(Octane2Navigation.MORE, Octane2Navigation.restore(3))
        assertEquals(Octane2Navigation.MORE, Octane2Navigation.restore(4))
        assertEquals(Octane2Navigation.MORE, Octane2Navigation.restore(999))
    }

    @Test fun guidedCoverageRefreshesOnlyWhenRelevant() {
        assertTrue(Octane2Navigation.needsGuidedRefresh(Octane2Navigation.COLLECT))
        assertTrue(Octane2Navigation.needsGuidedRefresh(Octane2Navigation.PROGRESS))
        assertFalse(Octane2Navigation.needsGuidedRefresh(Octane2Navigation.REFUELS))
        assertFalse(Octane2Navigation.needsGuidedRefresh(Octane2Navigation.MORE))
    }
}
