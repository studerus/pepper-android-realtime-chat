package io.github.anonymous.pepper_realtime.ui.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.anonymous.pepper_realtime.R
import io.github.anonymous.pepper_realtime.data.PerceptionData
import io.github.anonymous.pepper_realtime.manager.DashboardManager
import java.util.Locale

private object DashboardColors {
    val Background = Color(0xFFFFF8E1) // Beige/Cream
    val HeaderBackground = Color(0xFFE0E0E0) // Light Gray
    val TextDark = Color.Black
    val TextLight = Color.DarkGray
    val CardBackground = Color.White
}

private val ColWeights = listOf(0.12f, 0.12f, 0.08f, 0.1f, 0.1f, 0.1f, 0.1f, 0.14f, 0.14f)

@Composable
fun DashboardOverlay(
    state: DashboardManager.DashboardState,
    onClose: () -> Unit
) {
    if (state.isVisible) {
        Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = DashboardColors.Background
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    // Header Title
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = null,
                                tint = DashboardColors.TextDark,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.perception_dashboard_title),
                                color = DashboardColors.TextDark,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.content_desc_close), tint = DashboardColors.TextLight)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))

                    // Table Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(DashboardColors.HeaderBackground)
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Use hardcoded list here to match column count, but with stringResources where available
                        val displayHeaders = listOf(
                            stringResource(R.string.dashboard_header_picture),
                            stringResource(R.string.dashboard_header_demographics),
                            stringResource(R.string.dashboard_header_distance),
                            "Emotion", "Pleasure", "Excitement", "Smile", "Attention", "Engagement"
                        )
                        
                        displayHeaders.forEachIndexed { index, title ->
                            Text(
                                text = title,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = DashboardColors.TextLight,
                                modifier = Modifier.weight(ColWeights[index]),
                                textAlign = if (index > 1) TextAlign.Center else TextAlign.Start
                            )
                        }
                    }

                    // Humans List
                    if (state.humans.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.no_humans_detected), color = DashboardColors.TextLight, fontSize = 18.sp)
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            items(state.humans) { human ->
                                HumanDetectionItem(human)
                            }
                        }
                    }
                    
                    // Footer
                    Text(
                        text = stringResource(R.string.last_update_format, state.lastUpdate),
                        color = DashboardColors.TextLight,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun HumanDetectionItem(human: PerceptionData.HumanInfo) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DashboardColors.CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Main Data Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Picture
                Box(modifier = Modifier.weight(ColWeights[0]), contentAlignment = Alignment.CenterStart) {
                    if (human.facePicture != null) {
                        Image(
                            bitmap = human.facePicture!!.asImageBitmap(),
                            contentDescription = stringResource(R.string.content_desc_face),
                            modifier = Modifier.size(50.dp).background(Color.LightGray)
                        )
                    } else {
                        Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(50.dp), tint = Color.Gray)
                    }
                }
                
                // Demographics
                Text(human.getDemographics(), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = DashboardColors.TextDark, modifier = Modifier.weight(ColWeights[1]))
                // Distance
                Text(human.getDistanceString(), fontSize = 14.sp, color = DashboardColors.TextDark, textAlign = TextAlign.Center, modifier = Modifier.weight(ColWeights[2]))
                // Emotion
                Text(human.getBasicEmotionDisplay(), fontSize = 14.sp, color = DashboardColors.TextDark, textAlign = TextAlign.Center, modifier = Modifier.weight(ColWeights[3]))
                // Pleasure
                Text(human.getPleasureStateDisplay(), fontSize = 14.sp, color = DashboardColors.TextDark, textAlign = TextAlign.Center, modifier = Modifier.weight(ColWeights[4]))
                // Excitement
                Text(human.getExcitementStateDisplay(), fontSize = 14.sp, color = DashboardColors.TextDark, textAlign = TextAlign.Center, modifier = Modifier.weight(ColWeights[5]))
                // Smile
                Text(human.getSmileStateDisplay(), fontSize = 14.sp, color = DashboardColors.TextDark, textAlign = TextAlign.Center, modifier = Modifier.weight(ColWeights[6]))
                // Attention
                Text(human.getAttentionLevel(), fontSize = 13.sp, color = DashboardColors.TextDark, textAlign = TextAlign.Center, modifier = Modifier.weight(ColWeights[7]))
                // Engagement
                Text(human.getEngagementLevel(), fontSize = 13.sp, color = DashboardColors.TextDark, textAlign = TextAlign.Center, modifier = Modifier.weight(ColWeights[8]))
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Azure Details Row
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Text(stringResource(R.string.azure_label) + " ", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF1976D2))
                human.azureYawDeg?.let {
                    val poseText = formatHeadPose(it, 
                        stringResource(R.string.dashboard_pose_right),
                        stringResource(R.string.dashboard_pose_left),
                        stringResource(R.string.dashboard_pose_forward)
                    )
                    Text(stringResource(R.string.dashboard_pose_format, poseText), fontSize = 12.sp, color = DashboardColors.TextLight)
                }
                Text(stringResource(R.string.dashboard_glasses_format, human.glassesType), fontSize = 12.sp, color = DashboardColors.TextLight)
                
                val maskText = if (human.isMasked == true) stringResource(R.string.yes) else stringResource(R.string.no)
                Text(stringResource(R.string.dashboard_mask_format, maskText), fontSize = 12.sp, color = DashboardColors.TextLight)
                
                Text(stringResource(R.string.dashboard_quality_format, human.imageQuality), fontSize = 12.sp, color = DashboardColors.TextLight)
            }
        }
    }
}

// Helper for Head Pose
private fun formatHeadPose(yawDeg: Double, right: String, left: String, forward: String): String {
    val direction = when {
        yawDeg < -10.0 -> right
        yawDeg > 10.0 -> left
        else -> forward
    }
    return String.format(Locale.US, "%s (%.0f°)", direction, yawDeg)
}
