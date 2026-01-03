package ch.fhnw.pepper_realtime.ui.compose.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.fhnw.pepper_realtime.service.LocalFaceRecognitionService
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import kotlinx.coroutines.launch

/**
 * Tab 3: Known Faces - Shows registered faces with management options
 */
@Composable
internal fun KnownFacesContent(
    faceState: FaceManagementState,
    faceService: LocalFaceRecognitionService,
    onAddAngle: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRefreshFaces: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    
    Column(modifier = Modifier.fillMaxWidth()) {
        when {
            !faceState.isServerAvailable -> {
                ServerUnavailableMessage(pepperIp = faceService.getPepperHeadIp())
            }
            faceState.error != null -> {
                ErrorMessage(error = faceState.error)
            }
            faceState.isLoading -> {
                LoadingIndicator()
            }
            faceState.faces.isEmpty() -> {
                EmptyFacesMessage()
            }
            else -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    items(faceState.faces) { face ->
                        FaceListItem(
                            face = face,
                            imageUrls = face.imageUrls,
                            onAddAngle = { onAddAngle(face.name) },
                            onDeleteImage = { index -> 
                                scope.launch {
                                    faceService.deleteFaceImage(face.name, index)
                                    onRefreshFaces()
                                }
                            },
                            onDelete = { onDelete(face.name) }
                        )
                    }
                }
                
                // Footer
                Text(
                    text = "${faceState.faces.size} registered face(s)",
                    color = DashboardColors.TextLight,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
internal fun FaceListItem(
    face: LocalFaceRecognitionService.RegisteredFace,
    imageUrls: List<String>,
    onAddAngle: () -> Unit,
    onDeleteImage: (Int) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DashboardColors.CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, DashboardColors.BorderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Header row: Name + action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = face.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = DashboardColors.TextDark
                    )
                    Text(
                        text = "${face.count} encoding(s) • ${imageUrls.size} image(s)",
                        fontSize = 12.sp,
                        color = DashboardColors.TextLight
                    )
                }
                
                // Add angle button
                IconButton(onClick = onAddAngle) {
                    Icon(
                        Icons.Default.AddAPhoto,
                        contentDescription = "Add angle for ${face.name}",
                        tint = DashboardColors.AccentBlue
                    )
                }
                
                // Delete all button
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete ${face.name}",
                        tint = DashboardColors.DeleteRed
                    )
                }
            }
            
            // Thumbnail row
            if (imageUrls.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(imageUrls.size) { index ->
                        Box(
                            modifier = Modifier.size(64.dp)
                        ) {
                            // Thumbnail image
                            SubcomposeAsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(imageUrls[index])
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "Face ${index + 1} of ${face.name}",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(DashboardColors.HeaderBackground),
                                contentScale = ContentScale.Crop,
                                loading = {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp
                                        )
                                    }
                                },
                                error = {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.Person,
                                            contentDescription = null,
                                            tint = DashboardColors.TextLight,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                },
                                success = {
                                    SubcomposeAsyncImageContent()
                                }
                            )
                            
                            // Delete button overlay (top-right corner)
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(20.dp)
                                    .offset(x = 4.dp, y = (-4).dp)
                                    .clip(CircleShape)
                                    .background(DashboardColors.DeleteRed)
                                    .clickable { onDeleteImage(index) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Delete image ${index + 1}",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                // No images placeholder
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .background(DashboardColors.HeaderBackground, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No images registered",
                        color = DashboardColors.TextLight,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
