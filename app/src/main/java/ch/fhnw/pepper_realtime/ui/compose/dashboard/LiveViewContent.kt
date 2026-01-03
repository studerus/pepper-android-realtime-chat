package ch.fhnw.pepper_realtime.ui.compose.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.fhnw.pepper_realtime.R
import ch.fhnw.pepper_realtime.data.PerceptionData

/**
 * Tab 1: Live View - Shows detected humans in a table format
 */
@Composable
internal fun LiveViewContent(
    humans: List<PerceptionData.HumanInfo>,
    lastUpdate: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Table Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DashboardColors.HeaderBackground, RoundedCornerShape(4.dp))
                .padding(vertical = 6.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val displayHeaders = listOf("Name", "Distance", "Position", "Gaze", "Duration")
            
            displayHeaders.forEachIndexed { index, title ->
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = DashboardColors.TextLight,
                    modifier = Modifier.weight(ColWeights[index]),
                    textAlign = if (index == 0) TextAlign.Start else TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Humans List
        if (humans.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.no_humans_detected), color = DashboardColors.TextLight, fontSize = 16.sp)
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f, fill = false)
            ) {
                items(humans) { human ->
                    HumanDetectionItem(human)
                }
            }
        }
        
        // Footer
        Text(
            text = stringResource(R.string.last_update_format, lastUpdate),
            color = DashboardColors.TextLight,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        )
    }
}

@Composable
internal fun HumanDetectionItem(human: PerceptionData.HumanInfo) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DashboardColors.CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, DashboardColors.BorderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Name and Track ID
            Column(modifier = Modifier.weight(ColWeights[0])) {
                human.recognizedName?.let { name ->
                    Text(
                        text = "👤 $name",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = DashboardColors.SuccessGreen
                    )
                } ?: Text(
                    text = "Unknown",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Normal,
                    color = DashboardColors.TextLight
                )
                if (human.trackId >= 0) {
                    Text(
                        text = "Track #${human.trackId}",
                        fontSize = 11.sp,
                        color = DashboardColors.TextLight
                    )
                }
            }
            
            // Distance
            Text(
                text = human.getDistanceString(), 
                fontSize = 15.sp, 
                color = DashboardColors.TextDark, 
                textAlign = TextAlign.Center, 
                modifier = Modifier.weight(ColWeights[1])
            )
            
            // Position (world angle)
            Text(
                text = human.getPositionDisplay(), 
                fontSize = 14.sp, 
                color = DashboardColors.TextDark, 
                textAlign = TextAlign.Center, 
                modifier = Modifier.weight(ColWeights[2])
            )
            
            // Gaze (looking at robot or away)
            Text(
                text = human.getGazeDisplay(), 
                fontSize = 14.sp, 
                color = if (human.lookingAtRobot) DashboardColors.SuccessGreen else DashboardColors.TextLight, 
                fontWeight = if (human.lookingAtRobot) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center, 
                modifier = Modifier.weight(ColWeights[3])
            )
            
            // Tracking duration - how long this person has been tracked
            Text(
                text = human.getTrackingDurationDisplay(), 
                fontSize = 13.sp, 
                color = DashboardColors.TextDark,
                textAlign = TextAlign.Center, 
                modifier = Modifier.weight(ColWeights[4])
            )
        }
    }
}
