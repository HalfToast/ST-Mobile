package dev.halftoast.stmobile.stats

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.halftoast.stmobile.StMobileApp
import dev.halftoast.stmobile.panel.CharacterStat
import dev.halftoast.stmobile.ui.LocalStPalette
import dev.halftoast.stmobile.ui.StBox
import dev.halftoast.stmobile.ui.StButton
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val LAST_ACTIVE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** "Never" for 0 (a character with no chats), else local `yyyy-MM-dd HH:mm`. */
internal fun formatLastActive(epochSec: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    if (epochSec <= 0L) "Never" else LAST_ACTIVE_FORMAT.format(Instant.ofEpochSecond(epochSec).atZone(zone))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(vm: StatsViewModel, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val ui by vm.ui.collectAsState()
    val p = LocalStPalette.current
    val avatars = (LocalContext.current.applicationContext as StMobileApp).avatars
    // Runs on entering the tab; the load itself lives in the ViewModel and survives leaving.
    LaunchedEffect(vm) { vm.refresh() }

    PullToRefreshBox(isRefreshing = ui.loading, onRefresh = vm::refresh, modifier = modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Text("Characters", color = Color(p.text), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            ui.error?.let { msg ->
                item { StatsError(msg, ui.hostKeyChanged, onRetry = vm::refresh, onOpenSettings = onOpenSettings) }
            }
            val stats = ui.stats
            if (stats != null) {
                item {
                    Text(
                        "${stats.size} characters \u00b7 most recent first",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(p.muted),
                    )
                }
                items(stats, key = { it.name }) { s -> StatRow(s) { avatars.get(it) } }
            } else if (ui.error == null) {
                item { Text("Reading statistics\u2026", color = Color(p.muted)) }
            }
        }
    }
}

@Composable
private fun StatRow(s: CharacterStat, loadAvatar: suspend (String) -> ImageBitmap?) {
    val p = LocalStPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val y = size.height - 0.5.dp.toPx()
                drawLine(Color(p.border), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(s, loadAvatar)
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(s.name, color = Color(p.text), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(formatLastActive(s.lastActiveEpochSec), style = MaterialTheme.typography.bodySmall, color = Color(p.muted))
        }
        Text("${s.messages} msgs", style = MaterialTheme.typography.bodyMedium, color = Color(p.muted))
    }
}

/** 40 dp round avatar; until it loads (or if it never does) the name's first letter stands in. */
@Composable
private fun Avatar(s: CharacterStat, loadAvatar: suspend (String) -> ImageBitmap?) {
    val p = LocalStPalette.current
    val image by produceState<ImageBitmap?>(null, s.avatarFile) {
        value = s.avatarFile?.let { loadAvatar(it) }
    }
    val shape = CircleShape
    Box(
        Modifier.size(40.dp).clip(shape).background(Color(p.box)).border(1.dp, Color(p.border), shape),
        contentAlignment = Alignment.Center,
    ) {
        val img = image
        if (img != null) {
            Image(img, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(s.name.take(1).uppercase(), color = Color(p.muted), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun StatsError(message: String, hostKeyChanged: Boolean, onRetry: () -> Unit, onOpenSettings: () -> Unit) {
    val p = LocalStPalette.current
    StBox(Modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (hostKeyChanged) "Host key changed" else "Can't read the stats",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(p.text),
            )
            Text(message, color = Color(p.muted))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hostKeyChanged) StButton("Open Settings", onOpenSettings, Modifier.weight(1f))
                StButton("Retry", onRetry, Modifier.weight(1f))
            }
        }
    }
}
