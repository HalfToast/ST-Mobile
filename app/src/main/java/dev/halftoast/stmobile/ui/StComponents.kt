package dev.halftoast.stmobile.ui

import androidx.compose.foundation.layout.Row
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
