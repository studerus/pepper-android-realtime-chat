# Pepper Android Realtime Chat 🤖

A sophisticated conversational AI application for the Pepper robot using OpenAI's GPT-4o Realtime API. This app enables natural voice conversations with advanced features like vision analysis, internet search, autonomous navigation, and dynamic gesture control.

## ✨ Features

### Core Capabilities
- **🎙️ Real-time Voice Chat** - Natural conversations using OpenAI GPT-4o Realtime API
- **🤖 Pepper Robot Integration** - Synchronized gestures and animations
- **🗺️ Navigation & Mapping** - Complete room mapping and autonomous navigation system
- **👁️ Vision Analysis** - Camera-based image understanding via Groq API
- **🌐 Internet Search** - Real-time web search capabilities via Tavily API
- **🌤️ Weather Information** - Current weather and forecasts via OpenWeatherMap API
- **🎯 Interactive Quizzes** - Dynamic quiz generation and interaction

### Navigation & Mapping Features
- **🗺️ Manual Mapping** - Guide Pepper through rooms to create detailed maps
- **📍 Location Saving** - Save named locations with high precision during mapping
- **🧭 Autonomous Navigation** - Navigate to any saved location with voice commands
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
# REQUIRED: Core functionality
AZURE_OPENAI_KEY=your_azure_openai_key_here
AZURE_OPENAI_ENDPOINT=your-resource.openai.azure.com
AZURE_SPEECH_KEY=your_azure_speech_key_here
AZURE_SPEECH_REGION=switzerlandnorth

# OPTIONAL: Additional features
GROQ_API_KEY=your_groq_key_here          # For vision analysis
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

#### Azure OpenAI (GPT-4o Realtime)
1. Go to [Azure Portal](https://portal.azure.com/)
2. Create an Azure OpenAI resource
3. Deploy the `gpt-4o-realtime-preview` model
4. Copy your API key and endpoint

#### Azure Speech Services
1. Go to [Azure Portal](https://portal.azure.com/)
2. Create a Speech Services resource
3. Copy your API key and region

### Optional APIs (Extended Features)

#### Groq API (Vision Analysis)
- **Free Tier**: 14,400 requests/day
- **Get Key**: [console.groq.com](https://console.groq.com/)
- **Enables**: Camera-based vision analysis

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

### Available Voice Commands

#### Basic Interaction
- **"What do you see?"** - Triggers vision analysis (requires Groq API)
- **"What's the weather like?"** - Gets weather information (requires OpenWeather API)
- **"Search for [topic]"** - Performs internet search (requires Tavily API)
- **"Play [song/video]"** - Searches and plays YouTube videos (requires YouTube API)
- **"Tell me a joke"** - Random joke from local database
- **"Show me [animation]"** - Plays Pepper animations

#### Movement Commands
- **"Move [direction] [distance]"** - Basic movement (forward/backward/left/right, 0.1-4.0m)
- **"Turn [direction] [degrees]"** - Rotate in place (left/right, 15-180 degrees)

#### Navigation & Mapping
- **"Create a map"** - Start mapping the current environment
- **"Move forward 2 meters"** - Guide Pepper during mapping
- **"Save this location as [name]"** - Save current position with a name
- **"Finish the map"** - Complete and save the environment map
- **"Go to [location]"** - Navigate to any saved location
- **"Navigate to [location]"** - Alternative navigation command

### Settings Access
- **Tap the menu** (⋮) in the top-right corner
- **Adjust** voice, model, temperature, and other preferences
- **Swipe from right** to access settings drawer

## 🗺️ Navigation Workflow

### Complete Setup Process

#### 1. Create Environment Map
```bash
User: "Create a map of this room"
Robot: "I have started mapping. Guide me through the room..."
```

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
- **📍 Persistent Storage**: Locations survive app restarts
- **⚡ Dynamic Updates**: AI knows new locations immediately
- **🛡️ Error Prevention**: No "location not found" errors

## 🏗️ Architecture

### Core Components
```
├── ApiKeyManager     - Centralized API key management
├── RealtimeSession   - WebSocket communication with OpenAI
├── TurnManager       - Conversation state management
├── AudioPlayer       - Real-time audio playback
├── GestureController - Pepper animation control
├── ToolExecutor      - Function calling implementation
├── MovementController- Robot movement and navigation control
├── VisionService     - Camera and image analysis
└── ToolRegistry      - Dynamic tool registration with location awareness
```

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
├── ChatActivity.java           # Main application
├── ApiKeyManager.java          # API key management
├── RealtimeSessionManager.java # WebSocket handling
├── ToolExecutor.java           # Function execution and navigation
├── MovementController.java     # Robot movement and navigation
├── ToolRegistry.java           # Dynamic tool registration
├── VisionService.java          # Camera integration
├── AudioPlayer.java            # Audio playback
├── GestureController.java      # Robot animations
└── ...
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
- Add GROQ_API_KEY to `local.properties`
- Rebuild the app after adding keys
- Check camera permissions

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

- **OpenAI** - GPT-4o Realtime API
- **SoftBank Robotics** - Pepper robot platform
- **Microsoft** - Azure Speech and OpenAI services
- **Groq** - Fast inference for vision analysis
- **Tavily** - Internet search API
- **OpenWeatherMap** - Weather data

## 📞 Support

For issues and questions:
1. Check the [troubleshooting section](#-troubleshooting)
2. Search [existing issues](https://github.com/studerus/pepper-android-realtime-chat/issues)
3. Create a [new issue](https://github.com/studerus/pepper-android-realtime-chat/issues/new) with details

---

**Happy coding with Pepper! 🤖✨**
