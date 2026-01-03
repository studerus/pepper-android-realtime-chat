package ch.fhnw.pepper_realtime.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import ch.fhnw.pepper_realtime.data.*
import ch.fhnw.pepper_realtime.ui.EventRulesState

private object RulesColors {
    val Background = Color(0xFFF9FAFB)
    val CardBackground = Color.White
    val TextDark = Color(0xFF1F2937)
    val TextLight = Color(0xFF6B7280)
    val AccentBlue = Color(0xFF1E40AF)
    val SuccessGreen = Color(0xFF059669)
    val DeleteRed = Color(0xFFDC2626)
    val WarningOrange = Color(0xFFEA580C)
    val BorderColor = Color(0xFFE5E7EB)
    val DisabledGray = Color(0xFF9CA3AF)
}

/**
 * Overlay for managing event-based rules.
 */
@Composable
fun EventRulesOverlay(
    state: EventRulesState,
    onClose: () -> Unit,
    onAddRule: (EventRule) -> Unit,
    onUpdateRule: (EventRule) -> Unit,
    onDeleteRule: (String) -> Unit,
    onToggleRule: (String) -> Unit,
    onResetDefaults: () -> Unit,
    onSetEditingRule: (EventRule?) -> Unit,
    onExport: () -> String,
    onImport: (String) -> Int
) {
    val clipboardManager = LocalClipboardManager.current
    
    var showAddDialog by remember { mutableStateOf(false) }
    var ruleToDelete by remember { mutableStateOf<EventRule?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var exportedJson by remember { mutableStateOf("") }
    var importJson by remember { mutableStateOf("") }
    var importResult by remember { mutableStateOf<String?>(null) }

    if (state.isVisible) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.3f))
                .clickable(onClick = onClose),
            contentAlignment = Alignment.TopCenter
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 64.dp, start = 16.dp, end = 16.dp)
                    .heightIn(max = 500.dp)
                    .clickable(enabled = false) {},
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = RulesColors.Background),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = RulesColors.AccentBlue,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Event Rules",
                                color = RulesColors.TextDark,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "(${state.rules.size})",
                                color = RulesColors.TextLight,
                                fontSize = 16.sp
                            )
                        }
                        
                        Row {
                            // Import
                            IconButton(onClick = { 
                                importJson = ""
                                importResult = null
                                showImportDialog = true 
                            }) {
                                Icon(
                                    Icons.Default.FileDownload,
                                    contentDescription = "Import rules",
                                    tint = RulesColors.TextLight
                                )
                            }
                            // Export
                            IconButton(onClick = { 
                                exportedJson = onExport()
                                showExportDialog = true 
                            }) {
                                Icon(
                                    Icons.Default.FileUpload,
                                    contentDescription = "Export rules",
                                    tint = RulesColors.TextLight
                                )
                            }
                            // Reset to defaults
                            IconButton(onClick = { showResetConfirm = true }) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Reset to defaults",
                                    tint = RulesColors.TextLight
                                )
                            }
                            // Add rule
                            IconButton(onClick = { showAddDialog = true }) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "Add rule",
                                    tint = RulesColors.AccentBlue,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            // Close
                            IconButton(onClick = onClose) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = RulesColors.TextDark,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    }
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    // Description
                    Text(
                        text = "Rules trigger AI context updates when events occur. First matching rule wins.",
                        color = RulesColors.TextLight,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    // Rules list
                    if (state.rules.isEmpty()) {
                        EmptyRulesMessage(onAddRule = { showAddDialog = true })
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            items(state.rules, key = { it.id }) { rule ->
                                RuleCard(
                                    rule = rule,
                                    onToggle = { onToggleRule(rule.id) },
                                    onEdit = { onSetEditingRule(rule) },
                                    onDelete = { ruleToDelete = rule }
                                )
                            }
                        }
                    }
                    
                    // Recent triggers section
                    if (state.recentTriggeredRules.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(
                            text = "Recent Triggers",
                            color = RulesColors.TextLight,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            state.recentTriggeredRules.take(3).forEach { matched ->
                                AssistChip(
                                    onClick = { },
                                    label = { 
                                        Text(
                                            matched.rule.name,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = RulesColors.SuccessGreen.copy(alpha = 0.1f),
                                        labelColor = RulesColors.SuccessGreen
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // Add/Edit Rule Dialog
        if (showAddDialog || state.editingRule != null) {
            RuleEditorDialog(
                rule = state.editingRule,
                onDismiss = { 
                    showAddDialog = false
                    onSetEditingRule(null)
                },
                onSave = { rule ->
                    if (state.editingRule != null) {
                        onUpdateRule(rule)
                    } else {
                        onAddRule(rule)
                    }
                    showAddDialog = false
                    onSetEditingRule(null)
                }
            )
        }

        // Delete confirmation
        ruleToDelete?.let { rule ->
            AlertDialog(
                onDismissRequest = { ruleToDelete = null },
                title = { Text("Delete Rule") },
                text = { Text("Are you sure you want to delete \"${rule.name}\"?") },
                confirmButton = {
                    Button(
                        onClick = {
                            onDeleteRule(rule.id)
                            ruleToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RulesColors.DeleteRed)
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { ruleToDelete = null }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Reset confirmation
        if (showResetConfirm) {
            AlertDialog(
                onDismissRequest = { showResetConfirm = false },
                title = { Text("Reset to Defaults") },
                text = { Text("This will replace all rules with the default rules. Continue?") },
                confirmButton = {
                    Button(
                        onClick = {
                            onResetDefaults()
                            showResetConfirm = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RulesColors.WarningOrange)
                    ) {
                        Text("Reset")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showResetConfirm = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        // Export dialog
        if (showExportDialog) {
            AlertDialog(
                onDismissRequest = { showExportDialog = false },
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.FileUpload,
                            contentDescription = null,
                            tint = RulesColors.AccentBlue,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Export Rules")
                    }
                },
                text = {
                    Column {
                        Text(
                            "Copy this JSON to save or share your rules:",
                            fontSize = 14.sp,
                            color = RulesColors.TextLight,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        OutlinedTextField(
                            value = exportedJson,
                            onValueChange = { },
                            readOnly = true,
                            minLines = 6,
                            maxLines = 10,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = LocalTextStyle.current.copy(fontSize = 11.sp)
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(exportedJson))
                            showExportDialog = false
                        }
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Copy to Clipboard")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showExportDialog = false }) {
                        Text("Close")
                    }
                }
            )
        }

        // Import dialog
        if (showImportDialog) {
            AlertDialog(
                onDismissRequest = { showImportDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.FileDownload,
                            contentDescription = null,
                            tint = RulesColors.AccentBlue,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Import Rules")
                    }
                },
                text = {
                    Column {
                        Text(
                            "Paste JSON to import rules (will replace existing rules):",
                            fontSize = 14.sp,
                            color = RulesColors.TextLight,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        OutlinedTextField(
                            value = importJson,
                            onValueChange = { 
                                importJson = it
                                importResult = null
                            },
                            placeholder = { Text("Paste JSON here...") },
                            minLines = 6,
                            maxLines = 10,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = LocalTextStyle.current.copy(fontSize = 11.sp)
                        )
                        
                        // Paste from clipboard button
                        TextButton(
                            onClick = {
                                clipboardManager.getText()?.let { 
                                    importJson = it.text
                                    importResult = null
                                }
                            },
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Paste from Clipboard", fontSize = 12.sp)
                        }
                        
                        // Import result message
                        importResult?.let { result ->
                            Text(
                                text = result,
                                fontSize = 12.sp,
                                color = if (result.startsWith("✓")) RulesColors.SuccessGreen else RulesColors.DeleteRed,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            try {
                                val count = onImport(importJson)
                                if (count > 0) {
                                    importResult = "✓ Successfully imported $count rule(s)"
                                    // Close after short delay
                                    showImportDialog = false
                                } else {
                                    importResult = "✗ No rules found in JSON"
                                }
                            } catch (e: Exception) {
                                importResult = "✗ Invalid JSON format"
                            }
                        },
                        enabled = importJson.isNotBlank()
                    ) {
                        Text("Import")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showImportDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
private fun EmptyRulesMessage(onAddRule: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Bolt,
            contentDescription = null,
            tint = RulesColors.TextLight,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "No Rules Configured",
            color = RulesColors.TextDark,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Create rules to automatically send context updates when events occur.",
            color = RulesColors.TextLight,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onAddRule) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(4.dp))
            Text("Add Rule")
        }
    }
}

@Composable
private fun RuleCard(
    rule: EventRule,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (rule.enabled) RulesColors.CardBackground else RulesColors.CardBackground.copy(alpha = 0.6f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Enable/Disable switch
            Switch(
                checked = rule.enabled,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = RulesColors.SuccessGreen,
                    checkedTrackColor = RulesColors.SuccessGreen.copy(alpha = 0.3f)
                ),
                modifier = Modifier.size(40.dp)
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Rule info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rule.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (rule.enabled) RulesColors.TextDark else RulesColors.DisabledGray
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Event type badge
                    AssistChip(
                        onClick = { },
                        label = { 
                            Text(
                                rule.eventType.displayName,
                                fontSize = 10.sp
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = RulesColors.AccentBlue.copy(alpha = 0.1f),
                            labelColor = if (rule.enabled) RulesColors.AccentBlue else RulesColors.DisabledGray
                        ),
                        modifier = Modifier.height(24.dp)
                    )
                    // Conditions count
                    if (rule.conditions.isNotEmpty()) {
                        Text(
                            text = "${rule.conditions.size} condition(s)",
                            fontSize = 10.sp,
                            color = RulesColors.TextLight
                        )
                    }
                    // Action type
                    Text(
                        text = "→ ${rule.actionType.displayName}",
                        fontSize = 10.sp,
                        color = RulesColors.TextLight
                    )
                }
            }
            
            // Edit button
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Edit",
                    tint = RulesColors.TextLight
                )
            }
            
            // Delete button
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = RulesColors.DeleteRed
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RuleEditorDialog(
    rule: EventRule?,
    onDismiss: () -> Unit,
    onSave: (EventRule) -> Unit
) {
    val isEditing = rule != null
    
    var name by remember(rule) { mutableStateOf(rule?.name ?: "") }
    var eventType by remember(rule) { mutableStateOf(rule?.eventType ?: PersonEventType.PERSON_APPEARED) }
    var actionType by remember(rule) { mutableStateOf(rule?.actionType ?: RuleActionType.INTERRUPT_AND_RESPOND) }
    var template by remember(rule) { mutableStateOf(rule?.template ?: "") }
    var conditions by remember(rule) { mutableStateOf(rule?.conditions ?: emptyList()) }
    var cooldownMs by remember(rule) { mutableStateOf(rule?.cooldownMs ?: 5000L) }
    
    var eventTypeExpanded by remember { mutableStateOf(false) }
    var actionTypeExpanded by remember { mutableStateOf(false) }
    var showConditionDialog by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (isEditing) "Edit Rule" else "New Rule")
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Row 1: Name (70%) + Cooldown (30%)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.weight(0.7f)
                    )
                    OutlinedTextField(
                        value = (cooldownMs / 1000).toString(),
                        onValueChange = { 
                            it.toLongOrNull()?.let { secs -> cooldownMs = secs * 1000 }
                        },
                        label = { Text("Cooldown") },
                        suffix = { Text("s") },
                        singleLine = true,
                        modifier = Modifier.weight(0.3f)
                    )
                }
                
                // Row 2: Event Type (50%) + Action Type (50%)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Event Type dropdown
                    ExposedDropdownMenuBox(
                        expanded = eventTypeExpanded,
                        onExpandedChange = { eventTypeExpanded = it },
                        modifier = Modifier.weight(1f)
                    ) {
                        OutlinedTextField(
                            value = eventType.displayName,
                            onValueChange = { },
                            readOnly = true,
                            label = { Text("Event") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = eventTypeExpanded) },
                            singleLine = true,
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true).fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = eventTypeExpanded,
                            onDismissRequest = { eventTypeExpanded = false }
                        ) {
                            PersonEventType.entries.forEach { type ->
                                DropdownMenuItem(
                                    text = { 
                                        Column {
                                            Text(type.displayName, fontSize = 13.sp)
                                            Text(type.description, fontSize = 10.sp, color = RulesColors.TextLight)
                                        }
                                    },
                                    onClick = {
                                        eventType = type
                                        eventTypeExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    
                    // Action Type dropdown
                    ExposedDropdownMenuBox(
                        expanded = actionTypeExpanded,
                        onExpandedChange = { actionTypeExpanded = it },
                        modifier = Modifier.weight(1f)
                    ) {
                        OutlinedTextField(
                            value = actionType.displayName,
                            onValueChange = { },
                            readOnly = true,
                            label = { Text("Action") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = actionTypeExpanded) },
                            singleLine = true,
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true).fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = actionTypeExpanded,
                            onDismissRequest = { actionTypeExpanded = false }
                        ) {
                            RuleActionType.entries.forEach { type ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(type.displayName, fontSize = 13.sp)
                                            Text(type.description, fontSize = 10.sp, color = RulesColors.TextLight)
                                        }
                                    },
                                    onClick = {
                                        actionType = type
                                        actionTypeExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                
                // Row 3: Conditions as horizontal chips
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    conditions.forEachIndexed { index, condition ->
                        InputChip(
                            selected = false,
                            onClick = { },
                            label = { 
                                Text(
                                    "${condition.field} ${condition.operator.symbol} ${condition.value}",
                                    fontSize = 11.sp
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove",
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable { 
                                            conditions = conditions.toMutableList().also { it.removeAt(index) }
                                        }
                                )
                            },
                            modifier = Modifier.height(28.dp)
                        )
                    }
                    // Add condition chip
                    AssistChip(
                        onClick = { showConditionDialog = true },
                        label = { Text("+ Condition", fontSize = 11.sp) },
                        modifier = Modifier.height(28.dp),
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = RulesColors.AccentBlue.copy(alpha = 0.1f),
                            labelColor = RulesColors.AccentBlue
                        )
                    )
                }
                
                // Row 4: Template (compact)
                OutlinedTextField(
                    value = template,
                    onValueChange = { template = it },
                    label = { Text("Message Template") },
                    placeholder = { Text("{personName} {distance}m...", fontSize = 12.sp) },
                    minLines = 2,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text(
                            "{personName} {distance} {age} {gender} {emotion} {robotState} {peopleCount}",
                            fontSize = 9.sp,
                            color = RulesColors.TextLight
                        )
                    }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val newRule = EventRule(
                        id = rule?.id ?: java.util.UUID.randomUUID().toString(),
                        name = name.ifBlank { "Unnamed Rule" },
                        enabled = rule?.enabled ?: true,
                        eventType = eventType,
                        conditions = conditions,
                        actionType = actionType,
                        template = template,
                        cooldownMs = cooldownMs
                    )
                    onSave(newRule)
                },
                enabled = name.isNotBlank() && template.isNotBlank()
            ) {
                Text(if (isEditing) "Save" else "Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )

    // Condition editor dialog
    if (showConditionDialog) {
        ConditionEditorDialog(
            onDismiss = { showConditionDialog = false },
            onSave = { condition ->
                conditions = conditions + condition
                showConditionDialog = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConditionEditorDialog(
    onDismiss: () -> Unit,
    onSave: (RuleCondition) -> Unit
) {
    var field by remember { mutableStateOf("distance") }
    var operator by remember { mutableStateOf(ConditionOperator.LESS_THAN) }
    var value by remember { mutableStateOf("") }
    
    var fieldExpanded by remember { mutableStateOf(false) }
    var operatorExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Condition") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Field dropdown
                ExposedDropdownMenuBox(
                    expanded = fieldExpanded,
                    onExpandedChange = { fieldExpanded = it }
                ) {
                    OutlinedTextField(
                        value = RuleCondition.availableFields[field]?.displayName ?: field,
                        onValueChange = { },
                        readOnly = true,
                        label = { Text("Field") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = fieldExpanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = fieldExpanded,
                        onDismissRequest = { fieldExpanded = false }
                    ) {
                        RuleCondition.availableFields.forEach { (key, info) ->
                            DropdownMenuItem(
                                text = { Text("${info.displayName} (${info.type.name.lowercase()})") },
                                onClick = {
                                    field = key
                                    fieldExpanded = false
                                }
                            )
                        }
                    }
                }
                
                // Operator dropdown
                ExposedDropdownMenuBox(
                    expanded = operatorExpanded,
                    onExpandedChange = { operatorExpanded = it }
                ) {
                    OutlinedTextField(
                        value = "${operator.symbol} ${operator.displayName}",
                        onValueChange = { },
                        readOnly = true,
                        label = { Text("Operator") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = operatorExpanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = operatorExpanded,
                        onDismissRequest = { operatorExpanded = false }
                    ) {
                        ConditionOperator.entries.forEach { op ->
                            DropdownMenuItem(
                                text = { Text("${op.symbol} ${op.displayName}") },
                                onClick = {
                                    operator = op
                                    operatorExpanded = false
                                }
                            )
                        }
                    }
                }
                
                // Value
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Value") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(RuleCondition(field, operator, value))
                },
                enabled = value.isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

