package ch.fhnw.pepper_realtime.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    onSetEditingRule: (EventRule?) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var ruleToDelete by remember { mutableStateOf<EventRule?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }

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

@OptIn(ExperimentalMaterial3Api::class)
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
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Name
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Rule Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                
                // Event Type dropdown
                ExposedDropdownMenuBox(
                    expanded = eventTypeExpanded,
                    onExpandedChange = { eventTypeExpanded = it }
                ) {
                    OutlinedTextField(
                        value = eventType.displayName,
                        onValueChange = { },
                        readOnly = true,
                        label = { Text("Event") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = eventTypeExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = eventTypeExpanded,
                        onDismissRequest = { eventTypeExpanded = false }
                    ) {
                        PersonEventType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = { 
                                    Column {
                                        Text(type.displayName)
                                        Text(
                                            type.description,
                                            fontSize = 11.sp,
                                            color = RulesColors.TextLight
                                        )
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
                
                // Conditions
                Text(
                    text = "Conditions (${conditions.size})",
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
                
                if (conditions.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        conditions.forEachIndexed { index, condition ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(RulesColors.BorderColor, RoundedCornerShape(4.dp))
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = "${condition.field} ${condition.operator.symbol} ${condition.value}",
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        conditions = conditions.toMutableList().also { it.removeAt(index) }
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                
                TextButton(onClick = { showConditionDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Condition")
                }
                
                // Action Type dropdown
                ExposedDropdownMenuBox(
                    expanded = actionTypeExpanded,
                    onExpandedChange = { actionTypeExpanded = it }
                ) {
                    OutlinedTextField(
                        value = actionType.displayName,
                        onValueChange = { },
                        readOnly = true,
                        label = { Text("Action") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = actionTypeExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = actionTypeExpanded,
                        onDismissRequest = { actionTypeExpanded = false }
                    ) {
                        RuleActionType.entries.forEach { type ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(type.displayName)
                                        Text(
                                            type.description,
                                            fontSize = 11.sp,
                                            color = RulesColors.TextLight
                                        )
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
                
                // Template
                OutlinedTextField(
                    value = template,
                    onValueChange = { template = it },
                    label = { Text("Message Template") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
                
                // Placeholder help
                Text(
                    text = "Placeholders: {personName} {distance} {age} {gender} {emotion} {attention} {peopleCount}",
                    fontSize = 10.sp,
                    color = RulesColors.TextLight
                )
                
                // Cooldown
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Cooldown:", fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                        value = (cooldownMs / 1000).toString(),
                        onValueChange = { 
                            it.toLongOrNull()?.let { secs -> cooldownMs = secs * 1000 }
                        },
                        suffix = { Text("sec") },
                        singleLine = true,
                        modifier = Modifier.width(100.dp)
                    )
                }
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
                            .menuAnchor()
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
                            .menuAnchor()
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

