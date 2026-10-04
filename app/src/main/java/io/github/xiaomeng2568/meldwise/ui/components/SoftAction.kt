package io.github.xiaomeng2568.meldwise.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import io.github.xiaomeng2568.meldwise.ui.theme.*

/** One geometry for message/block/menu controls; full 48dp hit area, soft 40dp visual. */
@Composable fun SoftAction(glyph:Glyph,label:String,onClick:()->Unit,enabled:Boolean=true,tonal:Boolean=true,primary:Boolean=false,
    opticalSize:androidx.compose.ui.unit.Dp=Sizes.icon) {
    MeldwiseSurface(onClick=onClick,enabled=enabled,shape=CircleShape,color=Color.Transparent,
        modifier=Modifier.size(Sizes.touch).semantics {contentDescription=label;role=Role.Button}) {
        Box(contentAlignment=Alignment.Center) {
            val colors=MaterialTheme.colorScheme
            Box(Modifier.size(Sizes.actionVisual).background(if(primary && enabled) colors.primary else if(tonal) colors.surfaceContainer else Color.Transparent,CircleShape),contentAlignment=Alignment.Center) {
                androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides
                    if(primary && enabled) colors.onPrimary else colors.onSurfaceVariant.copy(alpha=if(enabled) 1f else .45f)) {MeldwiseIcon(glyph,opticalSize=opticalSize)}
            }
        }
    }
}
