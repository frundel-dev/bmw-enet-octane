package com.example.bmwenettest

/**
 * Stable, transport-independent classification of why the sole polling Job
 * ended. Coroutine completion's Throwable is not sufficient: the Service
 * catches and contains non-cancellation Exceptions at its outer boundary.
 *
 * No network access, ECU writes, timers or Android APIs.
 */
internal object PollingExitEvidence {
    enum class StopOrigin { NONE, USER_STOP, SERVICE_DESTROY }
    enum class EndKind { NORMAL_RETURN, HANDLED_EXCEPTION, CANCELLED, FATAL_ERROR, UNSET }

    fun classification(kind:EndKind,stop:StopOrigin):String=when(kind) {
        EndKind.HANDLED_EXCEPTION -> "CAUGHT_EXCEPTION"
        EndKind.FATAL_ERROR -> "UNHANDLED_THROWABLE"
        EndKind.CANCELLED -> when(stop) {
            StopOrigin.USER_STOP -> "CANCELLED_BY_STOP"
            StopOrigin.SERVICE_DESTROY -> "CANCELLED_BY_SERVICE_DESTROY"
            StopOrigin.NONE -> "UNEXPECTED_CANCELLATION"
        }
        EndKind.NORMAL_RETURN -> when(stop) {
            StopOrigin.NONE -> "UNEXPECTED_NORMAL_RETURN"
            else -> "NORMAL_AFTER_STOP"
        }
        EndKind.UNSET -> "COMPLETION_WITHOUT_FINALLY"
    }

    /** Compact for bmw_connection_events.csv's 240-character detail cap. */
    fun throwableDetail(t:Throwable?):String {
        if(t==null)return "none"
        val top=t.stackTrace.firstOrNull()
        val frame=top?.let { it.className.substringAfterLast('.')+
            "."+it.methodName+":"+it.lineNumber } ?: "unknown"
        val cause=t.cause?.takeUnless{it===t}
        val causeName=cause?.javaClass?.simpleName?.ifBlank{"Throwable"} ?: "none"
        val message=(t.message?: "").replace('\n',' ').replace('\r',' ')
            .replace(',', ';').take(65)
        return "exception="+t.javaClass.simpleName+
            ";message="+message+";source="+frame.take(75)+
            ";cause="+causeName
    }
}
