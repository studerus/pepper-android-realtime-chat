package ch.fhnw.pepper_realtime.ui.compose.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.fhnw.pepper_realtime.R
import ch.fhnw.pepper_realtime.ui.compose.ChatColors

/**
 * Section header for settings groups
 */
@Composable
fun SettingsSectionHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 16.dp, bottom = 8.dp)
    )
}

/**
 * Label for a setting
 */
@Composable
fun SettingsLabel(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        modifier = modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

/**
 * Dropdown selector for settings
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDropdown(
    label: String,
    options: List<String>,
    selectedOption: String,
    onOptionSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    
    Column(modifier = modifier) {
        SettingsLabel(text = label)
        
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (enabled) expanded = !expanded }
        ) {
            OutlinedTextField(
                value = selectedOption,
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
            )
            
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onOptionSelected(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

/**
 * Voice dropdown with descriptions shown in the menu.
 * Selected value shows only the voice name, dropdown items show "Name — Description".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsVoiceDropdown(
    label: String,
    voices: Map<String, String>,
    selectedVoice: String,
    onVoiceSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val description = voices[selectedVoice] ?: ""
    
    Column(modifier = modifier) {
        SettingsLabel(text = label)
        
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = if (description.isNotEmpty()) "$selectedVoice — $description" else selectedVoice,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
            )
            
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                voices.forEach { (name, desc) ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = name,
                                    fontWeight = FontWeight.Medium
                                )
                                if (desc.isNotEmpty()) {
                                    Text(
                                        text = desc,
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                }
                            }
                        },
                        onClick = {
                            onVoiceSelected(name)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

/**
 * Segmented button for settings with 2-4 options.
 * Stays inline (no popup) to avoid fullscreen mode issues.
 */
@Composable
fun SettingsSegmentedButton(
    label: String,
    options: List<String>,
    selectedOption: String,
    onOptionSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SettingsLabel(text = label)
        
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = options.size
                    ),
                    onClick = { onOptionSelected(option) },
                    selected = option == selectedOption,
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = ChatColors.Primary.copy(alpha = 0.1f),
                        activeContentColor = ChatColors.Primary,
                        activeBorderColor = ChatColors.Primary,
                        inactiveContainerColor = Color.White,
                        inactiveContentColor = Color.Black
                    )
                ) {
                    Text(
                        text = option,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * Slider with value display
 */
@Composable
fun SettingsSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    valueDisplay: String,
    modifier: Modifier = Modifier,
    steps: Int = 0
) {
    Column(modifier = modifier) {
        SettingsLabel(text = label)
        
        Text(
            text = valueDisplay,
            fontSize = 14.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = ChatColors.Primary,
                activeTrackColor = ChatColors.Primary,
                inactiveTrackColor = ChatColors.RobotBubble // Light gray
            )
        )
    }
}

/**
 * Switch toggle for boolean settings
 */
@Composable
fun SettingsSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                if (description != null) {
                    Text(
                        text = description,
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
            }
            
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = ChatColors.Primary,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = Color.LightGray
                )
            )
        }
    }
}

/**
 * Text input field for settings
 */
@Composable
fun SettingsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1
) {
    Column(modifier = modifier) {
        SettingsLabel(text = label)
        
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(hint, color = Color.Gray) },
            singleLine = singleLine,
            minLines = minLines,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Tool toggle item with description
 */
@Composable
fun ToolSettingItem(
    toolName: String,
    description: String,
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    apiKeyRequired: String? = null,
    isApiKeyAvailable: Boolean = true
) {
    var isExpanded by remember { mutableStateOf(false) }
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded }
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(0.85f)
                ) {
                    Checkbox(
                        checked = isEnabled && isApiKeyAvailable,
                        onCheckedChange = { if (isApiKeyAvailable) onToggle(it) },
                        enabled = isApiKeyAvailable
                    )
                    
                    Column {
                        Text(
                            text = toolName,
                            fontWeight = FontWeight.Medium
                        )
                        
                        if (apiKeyRequired != null && !isApiKeyAvailable) {
                            Text(
                                text = "API key required: $apiKeyRequired",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) stringResource(R.string.content_desc_collapse) else stringResource(R.string.content_desc_expand)
                )
            }
            
            if (isExpanded) {
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(start = 48.dp, top = 8.dp)
                )
            }
        }
    }
}

/**
 * Expandable section for settings that take up a lot of space.
 * The header is clickable to expand/collapse, and content appears below with full width.
 */
@Composable
fun ExpandableSettingsCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        // Clickable header row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded }
                .padding(top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )

            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (isExpanded) stringResource(R.string.content_desc_collapse) else stringResource(R.string.content_desc_expand)
            )
        }

        // Expandable content - full width, same as other settings
        if (isExpanded) {
            content()
        }
    }
}
