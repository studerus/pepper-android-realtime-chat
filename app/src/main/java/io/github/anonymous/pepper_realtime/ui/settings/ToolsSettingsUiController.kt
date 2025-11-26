package io.github.anonymous.pepper_realtime.ui.settings

import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.anonymous.pepper_realtime.R
import io.github.anonymous.pepper_realtime.manager.ApiKeyManager
import io.github.anonymous.pepper_realtime.tools.ToolRegistry
import io.github.anonymous.pepper_realtime.ui.ChatActivity

class ToolsSettingsUiController(
    private val activity: ChatActivity,
    rootView: View,
    private val viewModel: SettingsViewModel
) {

    private val functionCallsContainer: LinearLayout? = rootView.findViewById(R.id.function_calls_container)

    init {
        loadInitialValues()
    }

    fun loadInitialValues() {
        setupFunctionCallsUI()
    }

    fun applyChanges() {
        viewModel.setEnabledTools(getCurrentlySelectedTools())
    }

    private fun setupFunctionCallsUI() {
        if (functionCallsContainer == null) return

        functionCallsContainer.removeAllViews()

        val keyManager = ApiKeyManager(activity)
        val enabledTools = viewModel.getEnabledTools()

        // Get tool info from new registry
        val registry = ToolRegistry()
        for (toolId in registry.getAllToolNames()) {
            val toolItemView = activity.layoutInflater.inflate(
                R.layout.item_tool_setting, functionCallsContainer, false
            )

            val toolCheckbox: CheckBox = toolItemView.findViewById(R.id.tool_checkbox)
            val toolName: TextView = toolItemView.findViewById(R.id.tool_name)
            val toolApiKeyStatus: TextView = toolItemView.findViewById(R.id.tool_api_key_status)
            val toolDescription: TextView = toolItemView.findViewById(R.id.tool_description)
            val expandIcon: ImageView = toolItemView.findViewById(R.id.expand_icon)
            val descriptionContainer: LinearLayout = toolItemView.findViewById(R.id.description_container)

            // Set tool information
            toolName.text = toolId
            toolCheckbox.isChecked = enabledTools.contains(toolId)

            // Get the actual tool description from the tool definition
            try {
                val tool = registry.createTool(toolId)
                if (tool != null) {
                    val definition = tool.getDefinition()
                    val description = definition.optString("description", "No description available")
                    toolDescription.text = description
                } else {
                    toolDescription.text = activity.getString(R.string.tool_not_available)
                }
            } catch (e: Exception) {
                toolDescription.text = activity.getString(R.string.tool_description_error)
            }

            // Check API key availability if required
            var isApiKeyAvailable = true
            var apiKeyType: String? = null

            when (toolId) {
                "analyze_vision" -> {
                    apiKeyType = "Groq"
                    isApiKeyAvailable = keyManager.isVisionAnalysisAvailable()
                }
                "search_internet" -> {
                    apiKeyType = "Tavily"
                    isApiKeyAvailable = keyManager.isInternetSearchAvailable()
                }
                "get_weather" -> {
                    apiKeyType = "OpenWeatherMap"
                    isApiKeyAvailable = keyManager.isWeatherAvailable()
                }
                "play_youtube_video" -> {
                    apiKeyType = "YouTube"
                    isApiKeyAvailable = keyManager.isYouTubeAvailable()
                }
            }

            if (apiKeyType != null && !isApiKeyAvailable) {
                toolApiKeyStatus.visibility = View.VISIBLE
                toolApiKeyStatus.text = activity.getString(R.string.api_key_required_format, apiKeyType)
                toolCheckbox.isEnabled = false
                toolCheckbox.isChecked = false
            }

            // Setup expand/collapse functionality
            val toggleDescription = View.OnClickListener {
                val isExpanded = descriptionContainer.visibility == View.VISIBLE
                descriptionContainer.visibility = if (isExpanded) View.GONE else View.VISIBLE
                expandIcon.rotation = if (isExpanded) 0f else 180f
            }

            toolItemView.setOnClickListener(toggleDescription)
            expandIcon.setOnClickListener(toggleDescription)

            // Store tool name as tag for later retrieval
            toolCheckbox.tag = toolId

            functionCallsContainer.addView(toolItemView)
        }
    }

    private fun getCurrentlySelectedTools(): Set<String> {
        val selectedTools = mutableSetOf<String>()
        if (functionCallsContainer == null) return selectedTools

        for (i in 0 until functionCallsContainer.childCount) {
            val toolItemView = functionCallsContainer.getChildAt(i)
            val toolCheckbox: CheckBox? = toolItemView.findViewById(R.id.tool_checkbox)
            if (toolCheckbox != null && toolCheckbox.isChecked && toolCheckbox.tag != null) {
                selectedTools.add(toolCheckbox.tag as String)
            }
        }
        return selectedTools
    }
}

