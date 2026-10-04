// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise.ui.presentation

import io.github.xiaomeng2568.meldwise.provider.*

enum class PickerScope { Single, Lane }
/** Browsing is UI-only, even for Single. A model choice, not a provider heading, changes identity. */
data class ModelPickerState(val expandedProvider:String) {
    fun browse(id:String)=if(id in setOf(ProviderIds.CHATGPT,ProviderIds.DEEPSEEK)) copy(expandedProvider=id) else this
    fun shows(id:String)=expandedProvider==id
    companion object {
        fun initial(selection:ModelRef?,active:String)=ModelPickerState(selection?.providerId ?: active)
    }
}
