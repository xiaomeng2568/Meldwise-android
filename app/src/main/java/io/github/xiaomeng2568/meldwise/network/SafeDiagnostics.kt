package io.github.xiaomeng2568.meldwise.network

enum class Operation { METADATA, TOKEN_EXCHANGE, REFRESH, MODELS, RESPONSE }
enum class Outcome { HTTP, NETWORK, TIMEOUT, PROTOCOL, CANCELLED }
data class Diagnostic(val operation: Operation, val outcome: Outcome, val httpStatus: Int?)
/** Closed-schema local diagnostics; there is intentionally no arbitrary-string/body/URL API. */
class SafeDiagnostics {
    private val entries = ArrayDeque<Diagnostic>()
    @Synchronized fun record(operation: Operation, outcome: Outcome, status: Int? = null) {
        require(status == null || status in 100..599)
        if (entries.size == 100) entries.removeFirst()
        entries.addLast(Diagnostic(operation,outcome,status))
    }
    @Synchronized fun snapshot(): List<Diagnostic> = entries.toList()
}
