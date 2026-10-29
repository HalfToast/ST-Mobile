package dev.halftoast.stmobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Slim screen header: back arrow, title, then [actions] (small icon buttons), over a 1 dp border line. */
@Composable
fun StTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    val p = LocalStPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .drawBehind {
                val y = size.height - 0.5.dp.toPx()
                drawLine(Color(p.border), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color(p.text))
        }
        Text(
            title,
            Modifier.weight(1f),
            color = Color(p.text),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

private val BoxShape = RoundedCornerShape(5.dp)

/** ST's font scale, recovered from the scaled typography (bodyMedium is 14 sp unscaled). */
@Composable
private fun fontScale(): Float = MaterialTheme.typography.bodyMedium.fontSize.value / 14f

/** Bold-ish heading above a group of controls, like ST's drawer section titles. */
@Composable
fun StSectionTitle(text: String) {
    Text(
        text,
        color = Color(LocalStPalette.current.text),
        fontSize = (13.5f * fontScale()).sp,
        fontWeight = FontWeight.SemiBold,
    )
}

/** ST's bordered, tinted container. */
@Composable
fun StBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalStPalette.current
    Column(
        modifier
            .clip(BoxShape)
            .background(Color(p.box))
            .border(1.dp, Color(p.border), BoxShape)
            .padding(horizontal = 10.dp),
        content = content,
    )
}

/** One label/value row inside an [StBox]; [last] drops the divider. */
@Composable
fun StKeyValue(key: String, value: String, last: Boolean = false) {
    val p = LocalStPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                if (!last) {
                    val y = size.height - 0.5.dp.toPx()
                    drawLine(Color(p.border), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }
            }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(key, color = Color(p.muted), style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            Modifier.weight(1f),
            color = Color(p.text),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
        )
    }
}

enum class StTone { Normal, Danger }

/** ST's `menu_button`: tinted box with a border; Danger uses the red fill. */
@Composable
fun StButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: StTone = StTone.Normal,
    busy: Boolean = false,
) {
    val p = LocalStPalette.current
    val fill = if (tone == StTone.Danger) p.danger else p.box
    val edge = if (tone == StTone.Danger) p.dangerBorder else p.border
    Box(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .clip(BoxShape)
            .background(Color(fill))
            .border(BorderStroke(1.dp, Color(edge)), BoxShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color(p.text))
        } else {
            Text(
                text,
                color = Color(p.text),
                fontSize = (12.5f * fontScale()).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Modal card in ST style: [title], [content], then [dismiss] and [confirm] buttons on the right. */
@Composable
fun StDialog(
    title: String,
    onDismiss: () -> Unit,
    confirm: @Composable () -> Unit,
    dismiss: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val p = LocalStPalette.current
    val shape = RoundedCornerShape(12.dp)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Color(p.background))
                .border(1.dp, Color(p.border), shape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, color = Color(p.text), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                dismiss?.invoke()
                confirm()
            }
        }
    }
}
