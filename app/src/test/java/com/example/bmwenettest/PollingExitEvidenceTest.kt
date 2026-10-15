package com.example.bmwenettest

import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PollingExitEvidenceTest {
    private val P=PollingExitEvidence

    @Test fun intentionalStopAndServiceDestroyAreDistinctFromUnexpectedCancellation() {
        assertEquals("CANCELLED_BY_STOP",
            P.classification(P.EndKind.CANCELLED,P.StopOrigin.USER_STOP))
        assertEquals("CANCELLED_BY_SERVICE_DESTROY",
            P.classification(P.EndKind.CANCELLED,P.StopOrigin.SERVICE_DESTROY))
        assertEquals("UNEXPECTED_CANCELLATION",
            P.classification(P.EndKind.CANCELLED,P.StopOrigin.NONE))
    }

    @Test fun swallowedExceptionNeverLooksLikeNormalCompletion() {
        // Coroutine Job completion may have cause == null after the outer catch.
        assertEquals("CAUGHT_EXCEPTION",
            P.classification(P.EndKind.HANDLED_EXCEPTION,P.StopOrigin.NONE))
        assertEquals("CAUGHT_EXCEPTION",
            P.classification(P.EndKind.HANDLED_EXCEPTION,P.StopOrigin.USER_STOP))
        assertEquals("UNHANDLED_THROWABLE",
            P.classification(P.EndKind.FATAL_ERROR,P.StopOrigin.NONE))
    }

    @Test fun naturalReturnWithoutStopIsExplicitlyUnexpected() {
        assertEquals("UNEXPECTED_NORMAL_RETURN",
            P.classification(P.EndKind.NORMAL_RETURN,P.StopOrigin.NONE))
        assertEquals("NORMAL_AFTER_STOP",
            P.classification(P.EndKind.NORMAL_RETURN,P.StopOrigin.USER_STOP))
        assertEquals("NORMAL_AFTER_STOP",
            P.classification(P.EndKind.NORMAL_RETURN,P.StopOrigin.SERVICE_DESTROY))
    }

    @Test fun cancelledBeforeCoroutineBeginsIsNotMistakenForNormal() {
        assertEquals("COMPLETION_WITHOUT_FINALLY",
            P.classification(P.EndKind.UNSET,P.StopOrigin.NONE))
    }

    @Test fun exceptionSummaryRetainsClassSourceAndCauseButSanitizesCsv() {
        val error=IOException("socket,broken\ntransport",
            IllegalStateException("underlying"))
        val value=P.throwableDetail(error)
        assertTrue(value.contains("exception=IOException"))
        assertTrue(value.contains("cause=IllegalStateException"))
        assertTrue(value.contains("socket;broken transport"))
        assertFalse(value.contains('\n'))
        assertFalse(value.contains(','))
        assertEquals("none",P.throwableDetail(null))
    }

    @Test fun cancellationStillGetsItsOwnReason() {
        val reason=P.classification(P.EndKind.CANCELLED,P.StopOrigin.NONE)
        assertNotEquals(P.classification(P.EndKind.HANDLED_EXCEPTION,
            P.StopOrigin.NONE),reason)
        assertTrue(P.throwableDetail(CancellationException("STOP"))
            .contains("CancellationException"))
    }
}
