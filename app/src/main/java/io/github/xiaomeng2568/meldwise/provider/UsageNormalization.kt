// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise.provider

/** Optional provider metadata only. Any negative field invalidates the entire usage snapshot.
 * Null is unknown, including an all-null snapshot. No arithmetic, estimation or answer failure.
 */
object UsageNormalization {
    fun normalize(usage:Usage?):Usage? {
        if(usage==null) return null
        val values=listOf(usage.inputTokens,usage.outputTokens,usage.totalTokens)
        if(values.all {it==null} || values.any {it!=null && it<0}) return null
        return usage
    }
}
