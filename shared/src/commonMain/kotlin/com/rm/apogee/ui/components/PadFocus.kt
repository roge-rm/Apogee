package com.rm.apogee.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.rm.apogee.ui.theme.ApogeeColors
import com.rm.apogee.ui.theme.Dimens

/**
 * An accent ring round whatever the D-pad is on, since Material's focus wash can't be seen on
 * these dark screens. Put it before the clickable in the chain. It rings while it or anything in
 * it has focus.
 */
fun Modifier.padFocus(
    shape: Shape = RoundedCornerShape(Dimens.CornerTight),
    /** White on something already filled with the accent, where an accent ring wouldn't show. */
    ring: Color = ApogeeColors.Accent,
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    onFocusChanged { focused = it.hasFocus }
        .border(2.dp, if (focused) ring else Color.Transparent, shape)
}
