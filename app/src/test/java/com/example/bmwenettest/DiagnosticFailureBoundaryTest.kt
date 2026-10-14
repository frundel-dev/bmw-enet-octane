package com.example.bmwenettest

import java.io.IOException
import java.net.SocketException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DiagnosticFailureBoundaryTest {
    @Test fun reconnectTelemetryFailureDoesNotEscape() = runBlocking {
        assertFalse(DiagnosticFailureBoundary.attempt {
            throw IOException("failed to append DISCONNECTED status")
        })
    }

    @Test fun transportExceptionsAreContainedInFailureReporting() = runBlocking {
        for (message in listOf("Software caused connection abort", "Socket closed")) {
            assertFalse(DiagnosticFailureBoundary.attempt {
                throw SocketException(message)
            })
        }
    }

    @Test fun cleanRecoverySucceeds() = runBlocking {
        var called = false
        assertTrue(DiagnosticFailureBoundary.attempt { called = true })
        assertTrue(called)
    }

    @Test fun cancellationIsPropagated() = runBlocking {
        try {
            DiagnosticFailureBoundary.attempt {
                throw CancellationException("STOP during blocking read")
            }
            fail("Cancellation must not be swallowed")
        } catch (_: CancellationException) {
            // expected
        }
    }
}
