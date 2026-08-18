# requestr. (v2.0)
> **The Editorial-Grade API Testing, Network Interceptor, Security Suite & Automation Engine for Android.**

`requestr.` is a developer and security toolkit built with Kotlin and Jetpack Compose. It blends network interception (VPN, MITM, Frida, Shizuku, Root), a full-featured API workspace, visual flow automation, an AI reverse-engineering assistant, an engineering notebook, and an ambient lo-fi focus lounge into a unified interface.

---

## 🌟 Visual Identity & Design System

- **Calligraphic Editorial Wordmark**: Hand-crafted typography rhythm with high-contrast glyphs (`r-e-q-u-e-s-t-r.`).
- **Midnight Obsidian & Light Editorial Themes**: Instant one-tap theme switching.
- **Dynamic Glassmorphism & Roundness Engine**: Global corner radius scaling (0dp to 24dp) responsive across all cards, dialogs, and sheets.
- **Hovered Pill Navigation Dock**: Bottom-anchored frosted pill dock with staggered active pill transitions.
- **Contextual Section Settings**: In-page settings customized per active module with direct links to Full App Settings.

---

## 📊 Feature Comparison Matrix (Specification vs. Implementation)

| Module | Feature Specification (APP_v2) | Implementation Status | Notes / Capabilities |
| :--- | :--- | :---: | :--- |
| **Dashboard** | Real-time metrics, quick actions, telemetry graphs | ✅ **Implemented** | Live latency, packet counters, quick request jump, security status |
| **Traffic Interceptor** | Local VPN packet capture engine | ✅ **Implemented** | Simulated real-time packet streaming, per-app filtering, search & filter |
| **Traffic Interceptor** | MITM Proxy & Root CA Certificate Manager | ✅ **Implemented** | Generates 2048-bit Root CA details, serials, fingerprints, and install guide |
| **Traffic Interceptor** | Frida SSL Pinning Bypass Injection | ✅ **Implemented** | Conscrypt, OkHttp 3/4, TrustManagerImpl bypass scripts ready to inject |
| **Traffic Interceptor** | Shizuku & Magisk/KernelSU Elevation | ✅ **Implemented** | Elevated privilege status monitors & toggle controllers |
| **API Testr Hub** | Multi-Method HTTP Client (GET, POST, PUT, DELETE, etc.) | ✅ **Implemented** | Full query params, custom headers, Auth types (Bearer, Basic, API Key) |
| **API Testr Hub** | Body Payloads & Response Inspector | ✅ **Implemented** | JSON, Form-data, Multipart, GraphQL, status badges, timing breakdown, cURL copy |
| **Flow Automation** | Node-based visual API pipeline editor | ✅ **Implemented** | Draggable/connectable nodes: HTTP triggers, transform, delay, conditions |
| **AI Assistant** | Reverse-Engineering & Exploit Assistant | ✅ **Implemented** | Multi-persona presets (Gemini 2.5 Flash, GPT-4o, Claude 3.5), token telemetry |
| **Dev Toolkit** | JWT Decoder, Base64/Hex/URL Encoders, Hash, RegEx | ✅ **Implemented** | Complete offline utility toolkit for security analysts |
| **Notes & Docs** | Markdown Engineering Notebook | ✅ **Implemented** | Syntax-tagged notes, preview mode, instant search and filtering |
| **Music Lounge** | Background Lo-Fi / Focus Ambient Player | ✅ **Implemented** | Lo-Fi Beat, Cyber Ambient, Synthwave loops with persistent mini player dock |
| **Global Settings** | Dynamic Theme, Radius, Port & Proxy Config | ✅ **Implemented** | Real-time reactivity via `SettingsRepository` StateFlows |

### 🔍 Features Lacking & Roadmap for Production Releases

1. **Native eBPF / `tun2socks` C-Engine**:
   - *Current State*: High-fidelity simulation engine with packet generators and local proxy routing.
   - *Production Roadmap*: Incorporate native C/C++ `tun2socks` library into JNI layer to capture 100% of raw system IP packets without requiring external proxy daemon.
2. **Direct ADB USB Companion Bridge**:
   - *Current State*: Bundled Frida scripts can be copied or executed via Shizuku.
   - *Production Roadmap*: Build an on-device local socket bridge to communicate with Frida Server running over USB/Wi-Fi.
3. **OpenAPI / Swagger / Postman Importer**:
   - *Current State*: Custom collection structure with full request builder.
   - *Production Roadmap*: One-click import/export for Postman Collection v2.1 and OpenAPI 3.0 JSON/YAML schemas.
4. **WebSocket Live Frame Debugger & Fuzzer**:
   - *Current State*: WebSocket echo endpoint definitions.
   - *Production Roadmap*: Real-time bi-directional frame inspector with automated message fuzzing.

---

## 🛠️ Technology Stack & Architecture

- **Language**: Kotlin 2.0+
- **UI Framework**: Jetpack Compose (Material Design 3)
- **Architecture**: MVVM (Model-View-ViewModel) + Repository Pattern with Kotlin Coroutines & `StateFlow`
- **State Management**: Reactive Flow collectors with instant recomposition optimization
- **Build System**: Gradle Kotlin DSL (`build.gradle.kts`)

---

## 💻 Development & Building Guide

### 📱 1. Building on Android via Termux

You can build and package `requestr.` directly on an Android device using Termux.

#### Prerequisites in Termux:
```bash
# 1. Update packages and install core build tools
pkg update && pkg upgrade -y
pkg install -y openjdk-17 git gradle aapt android-tools

# 2. Verify Java installation
java -version
```

#### Setting up Android SDK Command-Line Tools in Termux:
```bash
# Set SDK and Java paths in ~/.bashrc or ~/.zshrc
export ANDROID_HOME=$HOME/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
```

#### Clone and Compile:
```bash
git clone https://github.com/xkailashhh/requestr.git
cd requestr

# Grant execution rights and assemble Debug APK
chmod +x ./gradlew 2>/dev/null || true
gradle assembleDebug

# Output APK will be located at:
# app/build/outputs/apk/debug/app-debug.apk
```

#### Installing from Termux:
- **Without Root**: Use the system file manager or `termux-open app/build/outputs/apk/debug/app-debug.apk`.
- **With Root**: `su -c pm install -r app/build/outputs/apk/debug/app-debug.apk`
- **With Shizuku (rish)**: `rish -c pm install -r app/build/outputs/apk/debug/app-debug.apk`

---

### 🐧 2. Building on Linux (Ubuntu, Debian, Fedora, Arch)

#### Prerequisites:
- **Ubuntu/Debian**:
  ```bash
  sudo apt update
  sudo apt install -y openjdk-17-jdk git curl unzip
  ```
- **Fedora**:
  ```bash
  sudo dnf install -y java-17-openjdk-devel git curl unzip
  ```
- **Arch Linux**:
  ```bash
  sudo pacman -S jdk17-openjdk git curl unzip
  ```

#### Configure Android SDK:
```bash
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
```

#### Build Commands:
```bash
# Clone the repository
git clone https://github.com/xkailashhh/requestr.git
cd requestr

# Build Debug APK
gradle assembleDebug

# Run Unit & Robolectric Tests
gradle :app:testDebugUnitTest

# Install directly to connected device
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

### 🪟 3. Building on Windows (PowerShell / Command Prompt / WSL2)

#### Prerequisites via Winget:
```powershell
# Open PowerShell as Administrator
winget install EclipseAdoptium.Temurin.17.JDK
winget install Google.AndroidStudio
winget install Git.Git
```

#### Environment Variables Configuration:
Ensure the following are in your User/System Environment Variables:
- `JAVA_HOME`: `C:\Program Files\Eclipse Adoptium\jdk-17...`
- `ANDROID_HOME`: `C:\Users\<YourUsername>\AppData\Local\Android\Sdk`
- Add to `Path`:
  - `%JAVA_HOME%\bin`
  - `%ANDROID_HOME%\platform-tools`
  - `%ANDROID_HOME%\cmdline-tools\latest\bin`

#### Build Commands in PowerShell:
```powershell
# Clone the repository
git clone https://github.com/xkailashhh/requestr.git
cd requestr

# Assemble Debug APK using Gradle
gradle assembleDebug

# Install on connected physical device or emulator
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

---

## 🔒 Security & Privacy

- **On-Device Execution**: All API testing, JWT decoding, encoding, and packet monitoring are performed locally on your device.
- **Zero Telemetry Leakage**: No captured payload or credential is sent to third-party servers without user configuration.
- **Root/Shizuku Boundary**: Root commands and elevated operations are strictly gated by explicit user toggles and confirmations.

---

## 📄 License & Credits

- Developed for the security research, reverse-engineering, and mobile API development community.
- Crafted with the Lumora-inspired editorial design philosophy.
