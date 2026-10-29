package dev.halftoast.stmobile.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    // Runs on entering the tab; the load itself lives in the ViewModel and survives leaving.
    LaunchedEffect(vm) { vm.refresh() }

    PullToRefreshBox(isRefreshing = ui.loading, onRefresh = vm::refresh, modifier = modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { Text("Characters", style = MaterialTheme.typography.titleLarge) }
            ui.error?.let { msg ->
                item { StatsError(msg, ui.hostKeyChanged, onRetry = vm::refresh, onOpenSettings = onOpenSettings) }
            }
            val stats = ui.stats
            if (stats != null) {
                item {
                    Text(
                        "${stats.size} characters · most recent first",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(stats, key = { it.name }) { s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                formatLastActive(s.lastActiveEpochSec),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("${s.messages} msgs", style = MaterialTheme.typography.bodyMedium)
                    }
                    HorizontalDivider()
                }
            } else if (ui.error == null) {
                item { Text("Reading statistics…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun StatsError(message: String, hostKeyChanged: Boolean, onRetry: () -> Unit, onOpenSettings: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (hostKeyChanged) "Host key changed" else "Can't read the stats",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hostKeyChanged) {
                    Button(onClick = onOpenSettings) { Text("Open Settings") }
                    TextButton(onClick = onRetry) { Text("Retry") }
                } else {
                    Button(onClick = onRetry) { Text("Retry") }
                }
            }
        }
    }
}
