# Pepper Android Realtime Chat 🤖

A sophisticated conversational AI application for the Pepper robot using OpenAI's GPT-4o Realtime API. This app enables natural voice conversations with advanced features like vision analysis, internet search, and dynamic gesture control.

## ✨ Features

### Core Capabilities
- **🎙️ Real-time Voice Chat** - Natural conversations using OpenAI GPT-4o Realtime API
- **🤖 Pepper Robot Integration** - Synchronized gestures and animations
- **👁️ Vision Analysis** - Camera-based image understanding via Groq API
- **🌐 Internet Search** - Real-time web search capabilities via Tavily API
- **🌤️ Weather Information** - Current weather and forecasts via OpenWeatherMap API
- **🎯 Interactive Quizzes** - Dynamic quiz generation and interaction

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

## 🎯 Usage

### Basic Operation
1. **Launch** the app on your Pepper robot
2. **Wait** for "Ready" status
3. **Speak** naturally to start conversation
4. **Tap** status bar to interrupt during robot speech

### Available Voice Commands
- **"What do you see?"** - Triggers vision analysis (requires Groq API)
- **"What's the weather like?"** - Gets weather information (requires OpenWeather API)
- **"Search for [topic]"** - Performs internet search (requires Tavily API)
- **"Tell me a joke"** - Random joke from local database
- **"Show me [animation]"** - Plays Pepper animations

### Settings Access
- **Tap the menu** (⋮) in the top-right corner
- **Adjust** voice, model, temperature, and other preferences
- **Swipe from right** to access settings drawer

## 🏗️ Architecture

### Core Components
```
├── ApiKeyManager     - Centralized API key management
├── RealtimeSession   - WebSocket communication with OpenAI
├── TurnManager       - Conversation state management
├── AudioPlayer       - Real-time audio playback
├── GestureController - Pepper animation control
├── ToolExecutor      - Function calling implementation
└── VisionService     - Camera and image analysis
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
app/src/main/java/com/example/pepper_test2/
├── ChatActivity.java           # Main application
├── ApiKeyManager.java          # API key management
├── RealtimeSessionManager.java # WebSocket handling
├── ToolExecutor.java           # Function execution
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

### Logging
The app provides detailed logs for debugging:
```bash
# View logs
adb logcat | grep -E "(ChatActivity|ApiKeyManager|ToolExecutor)"
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
