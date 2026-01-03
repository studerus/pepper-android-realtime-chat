package ch.fhnw.pepper_realtime.manager

import ch.fhnw.pepper_realtime.service.LocalFaceRecognitionService
import ch.fhnw.pepper_realtime.ui.compose.FaceManagementState
import ch.fhnw.pepper_realtime.ui.compose.PerceptionSettingsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manager for face recognition and perception settings.
 * Extracted from ChatViewModel for better separation of concerns.
 */
@Singleton
class FaceManager @Inject constructor(
    private val localFaceRecognitionService: LocalFaceRecognitionService
) {

    private var coroutineScope: CoroutineScope? = null

    private val _faceManagementState = MutableStateFlow(FaceManagementState())
    val faceManagementState: StateFlow<FaceManagementState> = _faceManagementState.asStateFlow()

    private val _perceptionSettingsState = MutableStateFlow(PerceptionSettingsState())
    val perceptionSettingsState: StateFlow<PerceptionSettingsState> = _perceptionSettingsState.asStateFlow()

    /**
     * Set the coroutine scope for launching async operations.
     * Should be called with viewModelScope during ViewModel initialization.
     */
    fun setCoroutineScope(scope: CoroutineScope) {
        this.coroutineScope = scope
    }

    // ==================== Face Management ====================

    fun refreshFaceList() {
        val scope = coroutineScope ?: return
        scope.launch {
            _faceManagementState.update { it.copy(isLoading = true, error = null) }

            // listFaces() will automatically start the server if needed
            val faces = localFaceRecognitionService.listFaces()

            // Check if we got faces (empty list could mean server not available or no faces)
            val isAvailable = localFaceRecognitionService.isServerAvailable()

            _faceManagementState.update {
                it.copy(
                    isLoading = false,
                    isServerAvailable = isAvailable,
                    faces = faces,
                    error = null
                )
            }
        }
    }

    fun registerFace(name: String) {
        val scope = coroutineScope ?: return
        scope.launch {
            _faceManagementState.update { it.copy(isLoading = true) }
            val success = localFaceRecognitionService.registerFace(name)
            if (success) {
                refreshFaceList()
            } else {
                _faceManagementState.update {
                    it.copy(isLoading = false, error = "Failed to register face")
                }
            }
        }
    }

    fun deleteFace(name: String) {
        val scope = coroutineScope ?: return
        scope.launch {
            _faceManagementState.update { it.copy(isLoading = true) }
            val success = localFaceRecognitionService.deleteFace(name)
            if (success) {
                refreshFaceList()
            } else {
                _faceManagementState.update {
                    it.copy(isLoading = false, error = "Failed to delete face")
                }
            }
        }
    }

    // ==================== Perception Settings ====================

    fun refreshPerceptionSettings() {
        val scope = coroutineScope ?: return
        scope.launch {
            _perceptionSettingsState.update { it.copy(isLoading = true, error = null) }

            val settings = localFaceRecognitionService.fetchSettings()

            _perceptionSettingsState.update {
                if (settings != null) {
                    it.copy(isLoading = false, settings = settings, error = null)
                } else {
                    it.copy(isLoading = false, error = "Could not fetch settings from server")
                }
            }
        }
    }

    fun updatePerceptionSettings(settings: LocalFaceRecognitionService.PerceptionSettings) {
        val scope = coroutineScope ?: return
        scope.launch {
            _perceptionSettingsState.update { it.copy(isSaving = true, error = null) }

            val success = localFaceRecognitionService.updateSettings(settings)

            _perceptionSettingsState.update {
                if (success) {
                    it.copy(isSaving = false, settings = settings, error = null)
                } else {
                    it.copy(isSaving = false, error = "Failed to update settings")
                }
            }
        }
    }

    /**
     * Recognize faces from Pepper's camera.
     * Returns the recognition result for use in chat flow.
     */
    suspend fun recognizeFaces(): LocalFaceRecognitionService.RecognitionResult {
        return localFaceRecognitionService.recognize()
    }
}
