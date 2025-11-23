package io.github.anonymous.pepper_realtime.ui.settings;

import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

import io.github.anonymous.pepper_realtime.R;
import io.github.anonymous.pepper_realtime.manager.ApiKeyManager;
import io.github.anonymous.pepper_realtime.tools.Tool;
import io.github.anonymous.pepper_realtime.tools.ToolRegistry;
import io.github.anonymous.pepper_realtime.ui.ChatActivity;

public class ToolsSettingsUiController {

    private final ChatActivity activity;
    private final SettingsViewModel viewModel;
    private final View rootView;

    private LinearLayout functionCallsContainer;

    public ToolsSettingsUiController(ChatActivity activity, View rootView, SettingsViewModel viewModel) {
        this.activity = activity;
        this.rootView = rootView;
        this.viewModel = viewModel;
        initializeViews();
        loadInitialValues();
    }

    private void initializeViews() {
        functionCallsContainer = rootView.findViewById(R.id.function_calls_container);
    }

    public void loadInitialValues() {
        setupFunctionCallsUI();
    }

    public void applyChanges() {
        viewModel.setEnabledTools(getCurrentlySelectedTools());
    }

    private void setupFunctionCallsUI() {
        if (functionCallsContainer == null)
            return;

        functionCallsContainer.removeAllViews();

        ApiKeyManager keyManager = new ApiKeyManager(activity);
        Set<String> enabledTools = viewModel.getEnabledTools();

        // Get tool info from new registry
        ToolRegistry registry = new ToolRegistry();
        for (String toolId : registry.getAllToolNames()) {
            View toolItemView = activity.getLayoutInflater().inflate(R.layout.item_tool_setting, functionCallsContainer,
                    false);

            CheckBox toolCheckbox = toolItemView.findViewById(R.id.tool_checkbox);
            TextView toolName = toolItemView.findViewById(R.id.tool_name);
            TextView toolApiKeyStatus = toolItemView.findViewById(R.id.tool_api_key_status);
            TextView toolDescription = toolItemView.findViewById(R.id.tool_description);
            ImageView expandIcon = toolItemView.findViewById(R.id.expand_icon);
            LinearLayout descriptionContainer = toolItemView.findViewById(R.id.description_container);

            // Set tool information
            toolName.setText(toolId);
            toolCheckbox.setChecked(enabledTools.contains(toolId));

            // Get the actual tool description from the tool definition
            try {
                Tool tool = registry.createTool(toolId);
                if (tool != null) {
                    JSONObject definition = tool.getDefinition();
                    String description = definition.optString("description", "No description available");
                    toolDescription.setText(description);
                } else {
                    toolDescription.setText(activity.getString(R.string.tool_not_available));
                }
            } catch (Exception e) {
                toolDescription.setText(activity.getString(R.string.tool_description_error));
            }

            // Check API key availability if required
            boolean isApiKeyAvailable = true;
            String apiKeyType = null;

            switch (toolId) {
                case "analyze_vision":
                    apiKeyType = "Groq";
                    isApiKeyAvailable = keyManager.isVisionAnalysisAvailable();
                    break;
                case "search_internet":
                    apiKeyType = "Tavily";
                    isApiKeyAvailable = keyManager.isInternetSearchAvailable();
                    break;
                case "get_weather":
                    apiKeyType = "OpenWeatherMap";
                    isApiKeyAvailable = keyManager.isWeatherAvailable();
                    break;
                case "play_youtube_video":
                    apiKeyType = "YouTube";
                    isApiKeyAvailable = keyManager.isYouTubeAvailable();
                    break;
            }

            if (apiKeyType != null && !isApiKeyAvailable) {
                toolApiKeyStatus.setVisibility(View.VISIBLE);
                toolApiKeyStatus.setText(activity.getString(R.string.api_key_required_format, apiKeyType));
                toolCheckbox.setEnabled(false);
                toolCheckbox.setChecked(false);
            }

            // Setup expand/collapse functionality
            View.OnClickListener toggleDescription = v -> {
                boolean isExpanded = descriptionContainer.getVisibility() == View.VISIBLE;
                descriptionContainer.setVisibility(isExpanded ? View.GONE : View.VISIBLE);
                expandIcon.setRotation(isExpanded ? 0 : 180);
            };

            toolItemView.setOnClickListener(toggleDescription);
            expandIcon.setOnClickListener(toggleDescription);

            // Store tool name as tag for later retrieval
            toolCheckbox.setTag(toolId);

            functionCallsContainer.addView(toolItemView);
        }
    }

    private Set<String> getCurrentlySelectedTools() {
        Set<String> selectedTools = new HashSet<>();
        if (functionCallsContainer == null)
            return selectedTools;

        for (int i = 0; i < functionCallsContainer.getChildCount(); i++) {
            View toolItemView = functionCallsContainer.getChildAt(i);
            CheckBox toolCheckbox = toolItemView.findViewById(R.id.tool_checkbox);
            if (toolCheckbox != null && toolCheckbox.isChecked() && toolCheckbox.getTag() != null) {
                selectedTools.add((String) toolCheckbox.getTag());
            }
        }
        return selectedTools;
    }
}
