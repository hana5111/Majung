package kr.ddakta.pickup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Static, cached light layers keep the glass readable on the vehicle display. */
internal fun Modifier.glassBackdrop() = drawWithCache {
    val base = Brush.linearGradient(listOf(Color(0xFFF1F2F5), Color(0xFFECEEF2), Color(0xFFF4F5F7)), end = Offset(size.width, size.height))
    val light = Brush.radialGradient(listOf(Color.White.copy(alpha = .9f), Color.Transparent),
        center = Offset(size.width * .12f, size.height * .05f), radius = size.width * .8f)
    val lime = Brush.radialGradient(listOf(Mint.copy(alpha = .10f), Color.Transparent),
        center = Offset(size.width * .86f, size.height * .42f), radius = size.width * .48f)
    onDrawBehind { drawRect(base); drawRect(light); drawRect(lime) }
}

internal fun Modifier.liquidGlass(lime: Boolean = false, ribbon: Boolean = false, radius: Dp = 28.dp): Modifier {
    val shape = RoundedCornerShape(radius)
    return shadow(16.dp, shape, clip = false, ambientColor = Color(0xFF566173).copy(alpha = .10f), spotColor = Color(0xFF566173).copy(alpha = .10f))
        .clip(shape)
        .drawWithCache {
            val w = size.width; val h = size.height
            val fill = Brush.linearGradient(
                if (lime) listOf(Color(0xFFF5FBEF).copy(alpha = .86f), Color(0xFFF5FFE8).copy(alpha = .70f), Color(0xFFE8FFCC).copy(alpha = .76f))
                else listOf(Color.White.copy(alpha = .77f), Color(0xFFF9FAFF).copy(alpha = .44f), Color.White.copy(alpha = .62f)),
                end = Offset(w, h))
            val bloom = Brush.radialGradient(
                listOf(if (lime) Color(0xFFCCFF91).copy(alpha = .46f) else Color.White.copy(alpha = .6f), Color.Transparent),
                center = Offset(w * .25f, h * .95f), radius = w * .85f)
            val reflection = Brush.linearGradient(listOf(Color.White.copy(alpha = .28f), Color.Transparent, Color.White.copy(alpha = .12f)), end = Offset(w * .85f, h))
            val glow = Brush.radialGradient(listOf(Color(0xFFD5FFA3).copy(alpha = if(lime) .65f else .42f), Color.Transparent),
                center = Offset(w * .80f, h * .47f), radius = w * .39f)
            val ridge = Path().apply {
                if (lime) {
                    moveTo(w * .27f, h * .79f)
                    cubicTo(w * .56f, h * .77f, w * .67f, h * .39f, w * 1.08f, h * .31f)
                } else {
                    moveTo(w * .45f, h * .66f)
                    cubicTo(w * .67f, h * .65f, w * .69f, h * .22f, w * 1.08f, h * .30f)
                }
            }
            val fold = Path().apply {
                if (lime) {
                    moveTo(w * .22f, h * .82f)
                    cubicTo(w * .68f, h * .85f, w * .64f, h * .44f, w * 1.08f, h * .57f)
                } else {
                    moveTo(w * .45f, h * .62f)
                    cubicTo(w * .70f, h * .15f, w * .83f, h * .44f, w * 1.08f, h * .37f)
                }
            }
            val ribbonFill = Path().apply {
                addPath(ridge)
                if (lime) {
                    lineTo(w * 1.08f, h * .57f)
                    cubicTo(w * .64f, h * .44f, w * .68f, h * .85f, w * .22f, h * .82f)
                } else {
                    lineTo(w * 1.08f, h * .37f)
                    cubicTo(w * .83f, h * .44f, w * .70f, h * .15f, w * .45f, h * .62f)
                }
                close()
            }
            val ribbonColor = Brush.linearGradient(listOf(Color(0xFFE8FFC9).copy(alpha = .05f), Color(0xFFDEFFAF).copy(alpha = .76f), Color(0xFFD4FF9D).copy(alpha = .18f)),
                start = Offset(w * .4f, h * .65f), end = Offset(w, h * .27f))
            val edge = Brush.linearGradient(listOf(Color.White.copy(alpha = .03f), Color.White.copy(alpha = .98f), Color.White.copy(alpha = .12f)),
                start = Offset(w * .4f, h * .65f), end = Offset(w, h * .27f))
            onDrawBehind {
                drawRect(fill)
                drawRect(bloom)
                if (ribbon) {
                    drawRect(glow)
                    drawPath(ribbonFill, ribbonColor)
                    drawPath(ridge, edge, style = Stroke(1.1.dp.toPx()))
                    drawPath(fold, edge, style = Stroke(.7.dp.toPx()))
                }
                drawRect(reflection)
            }
        }
        .border(1.3.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = .98f), Color.White.copy(alpha = .44f), Color.White.copy(alpha = .88f))), shape)
}

internal fun Modifier.glassControl(tint: Color = Color.White, radius: Dp = 28.dp, prominent: Boolean = false): Modifier {
    val shape = RoundedCornerShape(radius)
    return shadow(4.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = .06f), spotColor = Color.Black.copy(alpha = .07f))
        .clip(shape)
        .background(Brush.verticalGradient(if (prominent) listOf(Color(0xFFE2FFBD).copy(alpha = .96f), tint.copy(alpha = .90f))
            else listOf(Color.White.copy(alpha = .77f), tint.copy(alpha = .42f))))
        .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = .9f), Color(0xFFD8DDE2).copy(alpha = .62f))), shape)
}

@Composable
internal fun GlassButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    Button(onClick = onClick, enabled = enabled, shape = CircleShape,
        modifier = modifier.glassControl(if (primary && enabled) Mint else Color.White, prominent = primary && enabled),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Forest,
            disabledContainerColor = Color.White.copy(alpha = .12f), disabledContentColor = Muted.copy(alpha = .65f)),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp, disabledElevation = 0.dp),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp), content = content)
}

@Composable
internal fun glassFieldColors() = OutlinedTextFieldDefaults.colors(
    unfocusedBorderColor = Color.White.copy(alpha = .55f), focusedBorderColor = Mint,
    disabledBorderColor = Color.White.copy(alpha = .3f), focusedTextColor = Ink, unfocusedTextColor = Ink,
    cursorColor = Forest, unfocusedContainerColor = Color.Transparent,
    focusedContainerColor = Mint.copy(alpha = .08f), disabledContainerColor = Color.Transparent)
