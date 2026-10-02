package dev.outsync.sample

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.outsync.SyncEvent
import dev.outsync.SyncStatus
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(app: SampleApp) {
    val notes by app.store.notes.collectAsStateWithLifecycle()
    val status by app.engine.status.collectAsStateWithLifecycle()
    val online by app.cloud.online.collectAsStateWithLifecycle()
    val strategy by app.strategy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<Note?>(null) }

    fun syncNow() = app.scope.launch { app.engine.sync() }

    LaunchedEffect(Unit) {
        app.engine.events.collect { event ->
            val message = when (event) {
                is SyncEvent.Rejected -> "Server rejected a change: ${event.reason}"
                is SyncEvent.ConflictGaveUp -> "A conflict kept repeating, the server version was kept"
            }
            snackbar.showSnackbar(message)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Outsync", fontWeight = FontWeight.SemiBold)
                        StatusLine(status, online)
                    }
                },
                actions = {
                    IconButton(onClick = ::syncNow, enabled = !status.isSyncing) {
                        Icon(Icons.Default.Refresh, contentDescription = "Sync now")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = Note(UUID.randomUUID().toString(), "", "") }) {
                Icon(Icons.Default.Add, contentDescription = "New note")
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 88.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ControlPanel(
                    online = online,
                    onOnlineChange = { value ->
                        app.cloud.online.value = value
                        if (value) syncNow()
                    },
                    strategy = strategy,
                    onStrategyChange = { app.strategy.value = it },
                    onRemoteEdit = {
                        app.scope.launch {
                            val edited = app.cloud.editFromAnotherDevice()
                            snackbar.showSnackbar(
                                if (edited == null) "Sync a note first, then edit it remotely"
                                else "\"${edited.title}\" was edited on another device",
                            )
                        }
                    },
                )
            }
            if (notes.isEmpty()) {
                item { EmptyState() }
            }
            items(notes.values.toList(), key = { it.id }) { note ->
                NoteCard(
                    note = note,
                    onClick = { editing = note },
                    onDelete = { app.scope.launch { app.engine.delete(note.id) } },
                )
            }
        }
    }

    editing?.let { note ->
        EditDialog(
            note = note,
            onDismiss = { editing = null },
            onSave = { title, body ->
                editing = null
                app.scope.launch { app.engine.save(note.copy(title = title, body = body)) }
            },
        )
    }
}

@Composable
private fun StatusLine(status: SyncStatus, online: Boolean) {
    val text = when {
        status.isSyncing -> "Syncing…"
        !online && status.pendingCount > 0 -> "Offline · ${status.pendingCount} pending"
        !online -> "Offline · changes are saved locally"
        status.pendingCount > 0 -> "${status.pendingCount} pending"
        status.lastError != null -> "Last sync failed, will retry"
        else -> "All changes synced ✓"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (status.isSyncing) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(6.dp))
        }
        AnimatedContent(targetState = text, label = "status") {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ControlPanel(
    online: Boolean,
    onOnlineChange: (Boolean) -> Unit,
    strategy: Strategy,
    onStrategyChange: (Strategy) -> Unit,
    onRemoteEdit: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Network", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (online) "Connected to the simulated server" else "Airplane mode: keep editing",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = online, onCheckedChange = onOnlineChange)
            }
            Text("When both sides edit the same note", style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Strategy.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = option == strategy,
                        onClick = { onStrategyChange(option) },
                        shape = SegmentedButtonDefaults.itemShape(index, Strategy.entries.size),
                    ) { Text(option.label) }
                }
            }
            Text(
                when (strategy) {
                    Strategy.Server -> "The server copy wins, your change is dropped."
                    Strategy.Client -> "Your copy wins and overwrites the server."
                    Strategy.Merge -> "Your title is kept, the other device's body is kept."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onRemoteEdit, modifier = Modifier.fillMaxWidth()) {
                Text("Edit the first note from another device")
            }
        }
    }
}

@Composable
private fun NoteCard(note: Note, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(note.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium)
                if (note.body.isNotBlank()) {
                    Text(note.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.size(6.dp))
                AssistChip(
                    onClick = {},
                    label = { Text(note.version?.let { "v$it on server" } ?: "Only on this phone") },
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No notes yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Turn the network off, add a few notes, then turn it back on.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EditDialog(note: Note, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var title by remember { mutableStateOf(note.title) }
    var body by remember { mutableStateOf(note.body) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (note.title.isEmpty()) "New note" else "Edit note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true)
                OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text("Body") }, minLines = 3)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(title, body) }, enabled = title.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
