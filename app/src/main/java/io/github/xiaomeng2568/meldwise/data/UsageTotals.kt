// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise.data

/** Known subtotal only; null means no values reported or an unrepresentable Long sum, not zero.
 * Field-level coverage must travel with each sum. This does not imply finalized provider billing.
 */
data class KnownTokenSum(val value:Long?,val reportedRequests:Int,val totalRequests:Int,val overflowed:Boolean) {
    val allRequestsReported get()=totalRequests>0 && reportedRequests==totalRequests
}

data class UsageTotals(val requestCount:Int,val usageKnownRequests:Int,val usageUnavailableRequests:Int,
    val finalUsageRequests:Int,val inFlightRequests:Int,val knownInputTokens:KnownTokenSum,
    val knownOutputTokens:KnownTokenSum,val knownTotalTokens:KnownTokenSum) {
    companion object {
        fun from(snapshot:UsageSnapshot):UsageTotals {
            val records=snapshot.records
            fun sum(select:(RequestUsage)->Long?):KnownTokenSum {
                var total=0L;var reported=0;var overflow=false
                records.forEach {record ->select(record)?.let {value ->
                    reported++
                    if(Long.MAX_VALUE-total<value) overflow=true else if(!overflow) total+=value
                }}
                return KnownTokenSum(if(reported==0 || overflow) null else total,reported,records.size,overflow)
            }
            val known=records.count {it.source==UsageSource.PROVIDER_REPORTED}
            return UsageTotals(records.size,known,records.size-known,
                records.count {it.evidence in setOf(UsageEvidence.FINAL,UsageEvidence.COMPLETED)},
                records.count {it.outcome==RequestOutcome.DISPATCHED},
                sum {it.inputTokens},sum {it.outputTokens},sum {it.totalTokens})
        }
    }
}
