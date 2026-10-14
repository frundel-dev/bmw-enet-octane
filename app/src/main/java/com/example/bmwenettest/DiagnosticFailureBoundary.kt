package com.example.bmwenettest

import kotlinx.coroutines.CancellationException

/**
 * A network fault is recoverable, but telemetry emitted while recovering can
 * itself fail. Never let that secondary exception crash a Service coroutine.
 * Cancellation retains normal coroutine semantics.
 */
internal object DiagnosticFailureBoundary {
    suspend fun attempt(recover: suspend () -> Unit): Boolean {
        return try {
            recover()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }
}
