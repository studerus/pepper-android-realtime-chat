# Pepper Android Realtime Chat 🤖

A sophisticated conversational AI application for the Pepper robot using OpenAI's Realtime API. This app enables natural voice conversations with advanced features like vision analysis, internet search, autonomous navigation, dynamic gesture control, and interactive tablet games.

## ✨ Features

### Core Capabilities
- **🎙️ Real-time Voice Chat** - Natural conversations using OpenAI's Realtime API with confidence-based speech recognition
- **🤖 Pepper Robot Integration** - Synchronized gestures and expressive animations (wave, bow, applause, kisses, etc.)
- **👋 Touch Interaction** - Responds to touches on head, hands, and bumpers with contextual AI reactions
- **🗺️ Navigation & Mapping** - Complete room mapping and autonomous navigation system
- **👥 Human Approach** - Intelligent human detection and social interaction initiation
- **👁️ Human Perception Dashboard** - Real-time display of detected people with emotions, attention, and distance
- **🔍 Azure Face Analysis** - Advanced facial analysis with pose, glasses, mask detection, and image quality assessment
- **👁️ Vision Analysis** - Camera-based image understanding and analysis
- **🌐 Internet Search** - Real-time web search capabilities via Tavily API
- **🌤️ Weather Information** - Current weather and forecasts via OpenWeatherMap API
- **🎯 Interactive Quizzes** - Dynamic quiz generation and interaction
- **🎮 Tic Tac Toe Game** - Play against the AI with voice commands and visual board
- **🧠 Memory Game** - Card-matching game with multiple difficulty levels

### Navigation & Mapping Features
- **🗺️ Manual Mapping** - Guide Pepper through rooms to create detailed maps
- **📍 Location Saving** - Save named locations with high precision during mapping
- **🧭 Autonomous Navigation** - Navigate to any saved location with voice commands
- **👥 Human Approach** - Automatically detect and approach people for social interaction
- **🎯 Intelligent Location Recognition** - AI automatically suggests similar location names
- **⚡ Dynamic Location Lists** - AI always knows available locations

### Technical Features
- **Multi-modal AI** - Audio, text, and vision processing
- **WebSocket-based** - Low-latency real-time communication
- **Modular Architecture** - Clean separation of concerns
- **Graceful Degradation** - Optional features work independently
- **Dynamic Tool Registration** - Only available features are registered

## 🚀 Quick Start

### Prerequisites
- Android device or Pepper robot
- Android Studio or ADB for installation
- API keys for desired features (see below)

### 1. Clone and Configure

```bash
git clone https://github.com/studerus/pepper-android-realtime-chat.git
cd pepper-android-realtime-chat

# Copy configuration template
cp local.properties.example local.properties
```

### 2. Configure API Keys

Edit `local.properties` and add your API keys:

```properties
# REALTIME API PROVIDERS (Choose one or configure both)
# Option 1: Azure OpenAI - Realtime API
AZURE_OPENAI_KEY=your_azure_openai_key_here
AZURE_OPENAI_ENDPOINT=your-resource.openai.azure.com

# Option 2: OpenAI Direct - Latest gpt-realtime model
OPENAI_API_KEY=your_openai_api_key_here

# REQUIRED: Speech Recognition
AZURE_SPEECH_KEY=your_azure_speech_key_here
AZURE_SPEECH_REGION=switzerlandnorth

# OPTIONAL: Additional features
GROQ_API_KEY=your_groq_key_here          # For alternative vision analysis
TAVILY_API_KEY=your_tavily_key_here      # For internet search
OPENWEATHER_API_KEY=your_weather_key     # For weather information
YOUTUBE_API_KEY=your_youtube_api_key     # For video playback
```

### 3. Build and Install

```bash
# Build the APK
./gradlew assembleDebug

# Install on device/robot
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 🔑 API Key Setup

### Required APIs (Core Functionality)

#### Azure OpenAI (Realtime API)
1. Go to [Azure Portal](https://portal.azure.com/)
2. Create an Azure OpenAI resource
3. Deploy one or more of the supported models:
   - `gpt-realtime` (GA model with built-in vision)
   - `gpt-4o-realtime-preview` (Preview model)
   - `gpt-4o-mini-realtime-preview` (Mini preview model)
4. Copy your API key and endpoint

#### Azure Speech Services
1. Go to [Azure Portal](https://portal.azure.com/)
2. Create a Speech Services resource
3. Copy your API key and region

### Optional APIs (Extended Features)

#### Azure Face API (Advanced Face Analysis - Optional)
- **Free Tier**: 30,000 transactions/month
- **Get Key**: [Azure Portal](https://portal.azure.com/) → Cognitive Services → Face
- **Enables**: Advanced facial analysis, pose detection, glasses/mask detection, image quality assessment
- **Note**: Currently supports detection only; identification features require additional approval

#### Groq API (Vision Analysis - Optional)  
- **Free Tier**: 14,400 requests/day
- **Get Key**: [console.groq.com](https://console.groq.com/)
- **Enables**: Alternative vision analysis provider (gpt-realtime has built-in vision)

#### Tavily API (Internet Search)
- **Free Tier**: 1,000 searches/month
- **Get Key**: [tavily.com](https://tavily.com/)
- **Enables**: Real-time web search

#### OpenWeatherMap API (Weather)
- **Free Tier**: 1,000 calls/day
- **Get Key**: [openweathermap.org/api](https://openweathermap.org/api)
- **Enables**: Weather information and forecasts

#### YouTube Data API (Video Playback)
- **Free Tier**: 10,000 requests/day
- **Get Key**: [console.cloud.google.com](https://console.cloud.google.com/)
- **Enables**: Video search and playback in popup window

## 🎯 Usage

### Basic Operation
1. **Launch** the app on your Pepper robot
2. **Wait** for "Ready" status
3. **Speak** naturally to start conversation
4. **Tap** status bar to interrupt during robot speech
5. **Tap** dashboard symbol in status bar to toggle Human Perception Dashboard overlay
6. **Touch** robot's head, hands, or bumpers for physical interaction
7. **Tap** navigation icon (📍) in toolbar to show/hide the map preview with saved locations
8. **Tap** function call cards in chat to view detailed arguments and results
9. **Tap** vision analysis photos in chat to view them in full-screen overlay

### Available Voice Commands

#### Basic Interaction
- **"What do you see?"** - Triggers vision analysis (uses gpt-realtime or Groq API)
- **"What time is it?"** or **"What's the date?"** - Gets current date and time information
- **"What's the weather like?"** - Gets weather information (requires OpenWeather API)
- **"Search for [topic]"** - Performs internet search (requires Tavily API)
- **"Play [song/video]"** - Searches and plays YouTube videos (requires YouTube API)
- **"Tell me a joke"** - Random joke from local database
- **"Show me [animation]"** - Plays Pepper animations (hello/wave, bow, applause, kisses, laugh, happy, etc.)
- **"Start a Tic Tac Toe game"** - Opens the game dialog for voice-controlled gameplay

#### Movement Commands
- **"Move [distance] [direction]"** - Basic movement in any direction (0.1-4.0m)
  - *"Move 2 meters forward"* - Simple forward movement
  - *"Move 1 meter forward and 2 meters right"* - Combined diagonal movement
  - *"Move 0.5 meters backward and 1 meter left"* - Combined movement in any direction
- **"Turn [direction] [degrees]"** - Rotate in place (left/right, 15-180 degrees)
- **"Approach him/her"** - Intelligently approach a detected person for interaction
- **"Come to me"** - Alternative command to approach the user

#### Navigation & Mapping
- **"Create a map"** - Start mapping the current environment
- **"Move forward 2 meters"** - Guide Pepper during mapping
- **"Save this location as [name]"** - Save current position with a name
- **"Finish the map"** - Complete and save the environment map
- **"Go to [location]"** - Navigate to any saved location
- **"Navigate to [location]"** - Alternative navigation command

### Settings Access
- **Tap the menu** (⋮) in the top-right corner or **swipe from right** to access settings drawer

### Available Settings
- **API Provider** - Choose between Azure OpenAI and OpenAI Direct
- **Model Selection** - Select from gpt-realtime, gpt-4o-realtime-preview, or gpt-4o-mini-realtime-preview
- **Voice Selection** - Choose from 10 available voices (alloy, ash, ballad, cedar, coral, echo, marin, sage, shimmer, verse)
- **System Prompt** - Customize the AI's personality and behavior instructions
- **Recognition Language** - Set speech recognition language (German, English, French, Italian variants)
- **Temperature** - Adjust AI creativity/randomness (0-100%)
- **Volume Control** - Set audio output volume
- **Silence Timeout** - Configure required silence duration for speech recognition completion
- **Tool Management** - Enable/disable specific function tools (vision, weather, search, games, etc.)
- **ASR Confidence Threshold** - Set minimum confidence level for speech recognition acceptance

#### API Provider Selection
You can switch between different Realtime API providers in the settings:

- **Azure OpenAI**: Supports all three models (`gpt-realtime`, `gpt-4o-realtime-preview`, `gpt-4o-mini-realtime-preview`) with your Azure deployment
- **OpenAI Direct**: Supports all three models (`gpt-realtime`, `gpt-4o-realtime-preview`, `gpt-4o-mini-realtime-preview`) directly from OpenAI

**Note**: Changing the API provider will restart the session automatically.

## 🗺️ Navigation Workflow

### Complete Setup Process

#### 1. Create Environment Map
```bash
User: "Create a map of this room"
Robot: "I have started mapping. I have cleared 3 existing locations (printer, kitchen, entrance) since they would be invalid with the new map coordinate system. Guide me through the room..."
```

**Note**: Creating a new map automatically deletes all existing saved locations since they become invalid with the new coordinate system.

#### 2. Guide Pepper Through the Room
```bash
User: "Move forward 2 meters"
User: "Turn right 90 degrees"  
User: "Move forward 1 meter"
# Continue exploring all areas you want mapped
```

#### 3. Save Important Locations
```bash
User: "Save this location as printer"
Robot: "Saved location 'printer' with high precision during mapping"

User: "Save this location as kitchen" 
Robot: "Saved location 'kitchen' with high precision during mapping"
```

#### 4. Finish Mapping
```bash
User: "Finish the map"
Robot: "Map completed and saved successfully. Ready for navigation!"
```

#### 5. Navigate to Saved Locations
```bash
User: "Go to the printer"
Robot: "Navigating to printer... I have arrived at printer."

User: "Navigate to kitchen"
Robot: "Navigating to kitchen (high-precision location)..."
```

### Smart Location Recognition
The AI automatically knows all available locations and can suggest corrections:

```bash
User: "Go to Tür"
Robot: "I have these locations: Türe, Kitchen, Printer. Did you mean 'Türe'?"

User: "Yes, Türe" 
Robot: "Navigating to Türe..."
```

### Key Features
- **🎯 High-Precision Locations**: Saved during mapping for maximum accuracy
- **🧠 Intelligent Suggestions**: AI suggests similar location names
- **📍 Persistent Storage**: Locations survive app restarts within the same map
- **⚡ Dynamic Updates**: AI knows new locations immediately
- **🛡️ Error Prevention**: No "location not found" errors
- **🗑️ Auto-Cleanup**: New maps automatically clear old locations to prevent confusion

## 🎮 Tic Tac Toe Game

### How to Play
The AI opponent provides an interactive Tic Tac Toe experience with voice commands and visual feedback.

#### Starting a Game
```bash
User: "Let's play Tic Tac Toe"
Robot: "Great! Let's start a game of Tic Tac Toe. You are X, I am O."
# Game dialog opens automatically
```

#### Gameplay
- **Visual Board**: 3x3 grid with clear X and O markers
- **Touch & Voice**: Make moves by tapping board positions
- **AI Opponent**: Intelligent moves via Realtime API
- **Real-time Updates**: Instant visual and voice feedback

#### Game Flow
1. **User starts** as X (always goes first)
2. **Tap any position** on the board to make your move
3. **AI responds** with voice feedback and makes its move as O
4. **Continue alternating** until someone wins or draws
5. **Game auto-closes** after 5 seconds when finished

#### Voice Integration
- All moves trigger context updates to the AI
- AI provides natural commentary and reactions
- Game state communicated through existing WebSocket connection
- Seamless integration with ongoing conversation

### Game Features
- **🎯 Smart AI**: Competitive gameplay with strategic moves
- **🎨 Clean UI**: Large, clear buttons with distinct X/O markers
- **🔊 Voice Feedback**: Natural AI commentary during gameplay
- **⚡ Fast Response**: Immediate visual updates and AI reactions
- **🎪 Auto-Close**: Game closes automatically when finished
- **🔄 Repeatable**: Start new games anytime with voice commands

## 🧠 Memory Game

### How to Play
The Memory Game provides a classic card-matching experience with customizable difficulty levels.

#### Starting a Game
```bash
User: "Let's play a memory game"
Robot: "Starting memory game! Find matching pairs by flipping two cards."
# Memory game dialog opens with card grid
```

#### Gameplay
- **Card Grid**: Face-down cards arranged in a grid pattern
- **Match Pairs**: Flip two cards at a time to find matching symbols
- **Visual Feedback**: Cards show colorful emojis when flipped
- **Progress Tracking**: Live counter shows moves and matched pairs

#### Difficulty Levels
1. **Easy**: 4 pairs (8 cards) - Perfect for beginners
2. **Medium**: 8 pairs (16 cards) - Balanced challenge
3. **Hard**: 12 pairs (24 cards) - Memory expert level

#### Game Flow
1. **Choose difficulty** when starting (defaults to medium)
2. **Tap cards** to flip them and reveal symbols
3. **Find matching pairs** - matched cards stay face-up
4. **Complete the game** when all pairs are found
5. **View stats** showing total moves and completion time

### Memory Game Features
- **🎨 Rich Symbols**: Over 80 different emojis (animals, food, objects, etc.)
- **⏱️ Timer**: Live timer tracks your completion time
- **📊 Statistics**: Move counter and pairs remaining
- **🔄 Randomization**: Different symbol combinations each game
- **📱 Touch Interface**: Responsive card flipping animations

## 👁️ Human Perception Dashboard

### Overview
The Human Perception Dashboard provides real-time visualization of all detected people around Pepper, displaying comprehensive information about each person's state and engagement level.

### How to Access
- **Tap the dashboard symbol** in the status bar to toggle the dashboard overlay
- **Dashboard appears** in the top-right corner as a floating overlay
- **Tap close button** (×) or dashboard symbol again to hide

### Displayed Information
For each detected person, the dashboard shows:

#### Basic Information
- **Person ID** - Unique identifier for tracking
- **Distance** - Real-time distance measurement in meters
- **Estimated Age** - Age estimation from Pepper's perception
- **Gender** - Gender classification

#### Emotional & Engagement Data
- **Attention State** - Whether person is looking at robot, elsewhere, or up
- **Engagement Level** - How interested the person appears
- **Pleasure State** - Emotional pleasure/displeasure level
- **Excitement State** - Energy and excitement level
- **Smile Detection** - Current smile state
- **Basic Emotion** - Primary detected emotion

#### Azure Face Analysis (when API key provided)
- **Head Pose** - Yaw, pitch, and roll angles in degrees
- **Glasses Detection** - No glasses, reading glasses, or sunglasses
- **Mask Detection** - Whether person is wearing a face mask
- **Image Quality** - Low, medium, or high quality assessment
- **Blur Level** - Numerical blur measurement (0-1 scale)
- **Exposure Level** - Under-exposed, good exposure, or over-exposed

#### Advanced Features
- **Live Updates** - Information refreshes every 1.5 seconds
- **Face Pictures** - Small profile images when available
- **Comprehensive Tracking** - Maintains data as people move around
- **Clean Interface** - Organized table view with clear headers
- **Intelligent Face Analysis** - Analyzes faces when new people detected or every 10 seconds
- **Rate Limit Handling** - Graceful degradation when Azure API limits reached

### Use Cases
- **Social Interaction** - Understand who's interested in engaging
- **Approach Decisions** - See which person to approach first
- **Group Dynamics** - Monitor multiple people simultaneously
- **Debugging** - Verify human detection accuracy
- **Research** - Study human-robot interaction patterns

### Technical Details
- **QiSDK Integration** - Uses Pepper's built-in human awareness
- **Real-time Processing** - Efficient polling system (1.5s intervals)
- **Resource Management** - Automatically stops monitoring when hidden
- **Thread-Safe** - Handles concurrent data updates safely

## 👋 Touch Interaction

Pepper responds to physical touch on various sensors. When touched, the AI receives context and can respond naturally in conversation.

### Available Touch Sensors
- **🧠 Head Touch** - Touch the top of Pepper's head
- **🤲 Hand Touch** - Touch either left or right hand  
- **⚡ Bumper Sensors** - Front left, front right, and back bumper sensors

### Features
- **Contextual AI Integration** - Touch events like "[User touched my head]" are sent to AI for natural responses
- **Debounce Protection** - 500ms delay prevents multiple rapid touches
- **Smart Pausing** - Automatically pauses during navigation/localization

## 🎛️ Interruption Handling System

Due to Pepper's hardware limitations (no echo cancellation), the app uses an intelligent microphone management system to prevent the robot from hearing itself while ensuring users can still interrupt ongoing responses.

### Microphone Management
- **Closed During Speech** - Microphone automatically closes when Pepper is speaking or processing
- **Open During Listening** - Microphone only active when waiting for user input
- **Hardware Constraint** - Necessary because Pepper's older hardware lacks echo cancellation

### Status Bar Interruption Controls
- **First Tap** (during robot speech) - Immediately stops current response, clears audio queue, cancels generation, AND mutes microphone ("Muted — tap to unmute" status)
- **Second Tap** (when muted) - Unmutes and returns to listening mode

### Automatic Response Interruption
Certain events automatically interrupt ongoing responses to provide immediate feedback:
- **Touch Events** - Physical touch triggers new contextual response
- **Navigation Updates** - Status messages like "[MAP LOADED]" or "[NAVIGATION ERROR]"
- **Game Events** - Memory game matches, quiz answers, etc.
- **System Messages** - Tool execution results and status updates

### Technical Features
- **Instant Stop** - Responses halt immediately when interrupted
- **Queue Management** - Audio buffers cleared to prevent delayed playback
- **State Coordination** - Turn management ensures proper microphone timing
- **Seamless Recovery** - Smooth transition back to listening mode

## 🎤 ASR Confidence System

The app uses **Azure Speech Recognition** with intelligent confidence scoring to ensure accurate voice input processing.

### How It Works
- **Confidence Analysis** - Each recognized speech gets a confidence score (0-100%)
- **Smart Tagging** - Speech below the configured threshold gets tagged with "[Low confidence: XX%]"
- **AI Model Awareness** - The AI can identify uncertain transcriptions and ask for clarification
- **Adjustable Settings** - Users can customize the minimum confidence level in settings (default: 70%)

### Benefits
- **Intelligent Clarification** - AI can ask "Did you say X?" or "Can you repeat?" when transcription is uncertain
- **Context Awareness** - AI knows when to be more careful about interpreting unclear speech
- **Customizable Sensitivity** - Users can adjust based on their environment and speaking style  
- **Better Understanding** - AI can handle ambiguous transcriptions more gracefully

## 🌍 Multilingual Support

The app provides comprehensive multilingual capabilities with intelligent language handling for both speech recognition and AI responses.

### AI Response Languages
- **Universal Language Support** - The Realtime API can respond in any language, including Swiss German
- **Instant Language Switching** - The AI can switch languages mid-conversation when requested by the user or specified in the system prompt
- **Contextual Language Use** - AI automatically adapts to the user's preferred language based on conversation context

### Speech Recognition Languages
The app uses **Microsoft Azure Speech Services** for speech recognition, which provides superior accuracy for European languages and dialects:

#### Supported Recognition Languages
The app supports **30+ languages** through Microsoft Azure Speech Services:

- **German variants**: de-CH (Swiss German), de-DE (Germany), de-AT (Austria)
- **English variants**: en-US, en-GB, en-AU, en-CA
- **French variants**: fr-CH (Swiss French), fr-FR (France), fr-CA (Canada)
- **Italian variants**: it-CH (Swiss Italian), it-IT (Italy)
- **Spanish variants**: es-ES (Spain), es-AR (Argentina), es-MX (Mexico)
- **Portuguese variants**: pt-BR (Brazil), pt-PT (Portugal)
- **Chinese variants**: zh-CN (Mandarin Simplified), zh-HK (Cantonese Traditional), zh-TW (Taiwanese Mandarin)
- **Asian languages**: ja-JP (Japanese), ko-KR (Korean)
- **European languages**: nl-NL (Dutch), nb-NO (Norwegian), sv-SE (Swedish), da-DK (Danish)
- **Other languages**: ru-RU (Russian), ar-AE/ar-SA (Arabic)

#### Language Configuration
- **Settings Control** - Recognition language can be changed in app settings
- **Live Switching** - Language changes take effect immediately during active sessions
- **No App Restart** - Speech recognizer reconfigures automatically

### Important Language Considerations
- **Recognition vs. Response** - Users must speak in the configured recognition language, but AI can respond in any language
- **Language Mismatch Handling** - If user speaks in different language than configured, recognition accuracy will be poor
- **Recommendation** - Change recognition language in settings before switching spoken languages

### Why Azure Speech Recognition?
While OpenAI's Realtime API can process audio directly, we chose Azure Speech Services because:
- **Superior Dialect Accuracy** - Significantly better recognition for Swiss German and regional variants
- **Specialized Models** - Language-specific models optimized for local accents and expressions  
- **Confidence Scoring** - Provides reliability metrics for better conversation handling
- **Regional Optimization** - European language variants receive better support than generic models

### Usage Examples
```bash
# System prompt in English, but AI can respond in Swiss German when requested
User (English): "Please respond in Swiss German from now on"
AI (Swiss German): "Gärn! Ab jetz schwätzen i uf Schwizerdütsch mit dir."

# User must change recognition language in settings to speak German
User: (Changes settings to de-CH, then speaks German)
"Erzähl mir einen Witz auf Hochdeutsch"
AI: "Gerne! Hier ist ein Witz auf Hochdeutsch für dich..."
```

## 🏗️ Architecture

### Key Features
- **Lazy Validation** - API keys only checked when features are used
- **Graceful Degradation** - Missing keys don't break core functionality
- **Dynamic Registration** - Only available tools are registered with AI
- **Clean Separation** - Core and optional features are independent

## 🛠️ Development

### Building from Source
```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Run tests
./gradlew test
```

### Project Structure
```
app/src/main/java/io/github/studerus/pepper_android_realtime/
├── ChatActivity.java                # Main application
├── ApiKeyManager.java               # API key management
├── RealtimeSessionManager.java      # WebSocket handling
├── MovementController.java          # Robot movement coordination
├── NavigationServiceManager.java    # Navigation system management
├── PerceptionService.java           # Human detection and awareness
├── VisionService.java               # Camera and image analysis
├── OptimizedAudioPlayer.java        # Real-time audio playback
├── GestureController.java           # Robot animations and gestures
├── SettingsManager.java             # App configuration and UI
├── DashboardManager.java            # Human perception dashboard overlay
├── FaceRecognitionService.java     # Azure Face API integration for advanced facial analysis
├── TouchSensorManager.java         # Physical touch interaction management
├── SpeechRecognizerManager.java    # Azure Speech Recognition with confidence scoring
├── data/
│   ├── LocationProvider.java        # Location data management
│   └── SavedLocation.java           # Location data model
├── managers/
│   ├── QuizDialogManager.java       # Quiz UI management
│   └── YouTubePlayerManager.java    # Video playback management
├── tools/
│   ├── entertainment/
│   │   ├── PlayAnimationTool.java   # Pepper animation control
│   │   └── PlayYouTubeVideoTool.java # Video search and playback
│   ├── games/
│   │   ├── MemoryGameTool.java      # Memory card game
│   │   ├── MemoryGameDialog.java    # Memory game UI
│   │   ├── TicTacToeStartTool.java  # Tic Tac Toe game initialization
│   │   └── QuizTool.java            # Interactive quiz system
│   ├── information/
│   │   ├── GetDateTimeTool.java     # Date/time queries
│   │   ├── GetWeatherTool.java      # Weather information
│   │   ├── SearchInternetTool.java  # Web search via Tavily
│   │   └── GetRandomJokeTool.java   # Joke database access
│   ├── navigation/
│   │   ├── ApproachHumanTool.java   # Human approach functionality
│   │   ├── MovePepperTool.java      # Basic movement control
│   │   ├── NavigateToLocationTool.java # Location navigation
│   │   └── CreateEnvironmentMapTool.java # Mapping system
│   ├── vision/
│   │   └── AnalyzeVisionTool.java   # Vision analysis (gpt-realtime/Groq)
│   ├── Tool.java                    # Tool interface definition
│   ├── ToolContext.java             # Shared tool dependencies
│   └── ToolRegistryNew.java         # Dynamic tool registration
└── ui/
    ├── MapPreviewView.java          # Navigation map visualization
    └── MapState.java                # Map state management
```

### Key Configuration Files
- `local.properties.example` - API key template
- `app/build.gradle` - Build configuration and key injection
- `app/src/main/assets/jokes.json` - Joke database

## 🔧 Troubleshooting

### Common Issues

#### "Not connected to server"
- Check your Azure OpenAI API key and endpoint
- Verify internet connectivity
- Ensure the deployment name matches your Azure setup

#### "Vision analysis not available"
- Vision works automatically with gpt-realtime model
- For Groq alternative: Add GROQ_API_KEY to `local.properties`
- Check camera permissions and robot focus

#### "Feature requires API key"
- Each feature shows helpful setup instructions
- Add the corresponding API key to `local.properties`
- Rebuild and reinstall the app

#### Navigation Issues

**"No map available for navigation"**
- Create an environment map first using "Create a map"
- Guide Pepper through the room completely
- Finish the mapping process with "Finish the map"

**"Location not found"** 
- The AI now suggests available locations automatically
- Check spelling and pronunciation of location names
- Use the suggested location names from the AI

**"Mapping timeout"**
- Ensure room has good lighting and visual features
- Avoid rooms with mostly blank walls or mirrors
- Try mapping smaller sections of large rooms
- Check for obstacles blocking Pepper's movement

**"High precision vs standard locations"**
- Locations saved during active mapping are most accurate
- Locations saved after mapping are less precise but still functional
- For best results, save important locations during the mapping process

**"My saved locations disappeared"**
- Creating a new map automatically deletes all existing locations
- This prevents navigation errors caused by incompatible coordinate systems
- Each map creates its own coordinate system, making old locations invalid
- Simply re-save your important locations after creating the new map

## 🗺️ Map Visualization

### Map Preview Overlay
The app provides a **visual map preview** that shows the created environment map along with all saved locations in real-time.

#### How to Access
- **Tap the navigation icon** (📍) in the top toolbar to toggle the map preview
- **Overlay appears** in the top-right corner as a 320x240dp floating window
- **Tap again** to hide the preview

#### Visual Features
- **Environment Map** - Shows the actual mapped room layout as generated by Pepper's sensors
- **Saved Locations** - Displays all saved locations as **cyan-colored markers** with labels
- **Real-time Status** - Shows current navigation state:
  - *"No Map Available"* - No map has been created yet
  - *"Localizing..."* - Pepper is determining its position within the map
  - *"Map Loaded"* - Map is ready but robot not yet localized
  - *"Map Ready - No Locations"* - Map active but no locations saved
  - *Live view* - When localized, shows map with location markers

#### Use Cases
- **Location Overview** - See all saved locations at a glance
- **Navigation Planning** - Visually plan which locations to navigate to
- **Map Verification** - Confirm the mapped area covers desired spaces
- **Location Management** - Visual feedback when saving new locations
- **Debugging** - Verify mapping and localization status

#### Technical Details
- **Custom MapPreviewView** - Purpose-built Android custom view component
- **QiSDK Integration** - Uses Pepper's native map bitmap generation
- **Dynamic Updates** - Automatically refreshes when locations are added/removed
- **Optimized Rendering** - Efficient drawing with proper scaling and anti-aliasing

## 💬 Advanced Chat Interface

### Function Call Transparency
The chat interface provides **complete transparency** into AI function calling with professional expandable UI elements.

#### Function Call Cards
- **Expandable Design** - Each function call appears as a collapsible card in the chat
- **Status Indicators** - Visual status with ✅ (completed) or ⏳ (pending) icons
- **Function Icons** - Unique emoji icons for each tool (🌐 search, 🌤️ weather, 👁️ vision, etc.)
- **Summary View** - Condensed description when collapsed
- **Detailed View** - Full arguments and results when expanded

#### Interactive Elements
- **Tap to Expand** - Click any function call card to toggle detailed view
- **Smooth Animations** - Professional rotate animations for expand/collapse arrows  
- **JSON Formatting** - Properly formatted and readable JSON for arguments and results
- **Real-time Updates** - Function result appears automatically when operation completes

#### Transparency Benefits
- **Debugging Aid** - See exactly what parameters were sent to each function
- **Result Verification** - View complete API responses and tool outputs
- **Learning Tool** - Understand how AI decides to use different functions
- **Trust Building** - Complete visibility into AI decision-making process

### Vision Photo Display
When vision analysis is performed, photos are automatically displayed in the chat with interactive features.

#### Photo Integration
- **Automatic Capture** - Photos appear immediately when vision analysis starts
- **Thumbnail View** - Compact 220dp preview images in chat flow
- **Quality Optimization** - Efficiently sized thumbnails for smooth scrolling
- **Context Placement** - Photos appear exactly where vision analysis was requested

#### Full-Screen Viewing
- **Tap to Expand** - Click any photo thumbnail to view in full-screen overlay
- **High Resolution** - Full overlay displays up to 1024x1024 resolution
- **Overlay Interface** - Clean, distraction-free viewing experience  
- **Easy Dismissal** - Tap anywhere on overlay to return to chat

#### Professional Features
- **Session Management** - Photos are cleaned up automatically when session ends
- **Memory Optimization** - Efficient bitmap handling prevents memory leaks
- **File Path Storage** - Photos remain accessible throughout the conversation
- **Seamless Integration** - Photos and function calls work together in chat flow

### Logging
The app provides detailed logs for debugging:
```bash
# View all logs
adb logcat | grep -E "(ChatActivity|ApiKeyManager|ToolExecutor)"

# View navigation logs specifically
adb logcat | grep -E "(MovementController|ToolExecutor.*navigate|ToolRegistry.*location)"

# View mapping logs
adb logcat | grep -E "(LocalizeAndMap|mapping|create_environment_map)"
```

## 🤝 Contributing

1. **Fork** the repository
2. **Create** a feature branch
3. **Add** your improvements
4. **Test** thoroughly
5. **Submit** a pull request

### Development Guidelines
- Follow existing code style
- Add proper error handling
- Update documentation
- Test with missing API keys

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- **OpenAI** - Realtime API
- **SoftBank Robotics** - Pepper robot platform
- **Microsoft** - Azure Speech and OpenAI services
- **Groq** - Alternative vision analysis provider
- **Tavily** - Internet search API
- **OpenWeatherMap** - Weather data

## 📞 Support

For issues and questions:
1. Check the [troubleshooting section](#-troubleshooting)
2. Search [existing issues](https://github.com/studerus/pepper-android-realtime-chat/issues)
3. Create a [new issue](https://github.com/studerus/pepper-android-realtime-chat/issues/new) with details

---

**Happy coding with Pepper! 🤖✨**
