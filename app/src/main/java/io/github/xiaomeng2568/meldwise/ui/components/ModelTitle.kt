// SPDX-License-Identifier: GPL-3.0-only
package io.github.xiaomeng2568.meldwise.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.xiaomeng2568.meldwise.ui.theme.*

/** The arrow belongs to the title line, never to the width of the model-pair subtitle. */
@Composable fun ModelTitle(title:String,subtitle:String,modifier:Modifier=Modifier,onClick:()->Unit) {
    MeldwiseTextButton(onClick,modifier) {
        Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {
            Row(Modifier.testTag("modelTitleLine"),verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(Space.micro)) {
                Text(title,Modifier.weight(1f,fill=false),maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium)
                MeldwiseIcon(Glyph.Chevron,Modifier.testTag("modelTitleArrow"),opticalSize=18.dp)
            }
            Text(subtitle,Modifier.fillMaxWidth().testTag("modelSubtitle"),maxLines=1,overflow=TextOverflow.Ellipsis,
                textAlign=androidx.compose.ui.text.style.TextAlign.Center,style=MaterialTheme.typography.labelSmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
