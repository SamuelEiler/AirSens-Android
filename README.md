# AirSens Android Monitor

An Android application for monitoring air quality data from ESP-based sensors via Bluetooth Low Energy (BLE).

## Features

- **BLE Device Scanning**: Automatically discovers and connects to BMV080 air quality sensors
- **Real-time Data Display**: Shows particulate matter (PM1.0, PM2.5, PM10) readings
- **Environmental Monitoring**: Displays temperature, humidity, and pressure data (when available)
- **Air Quality Index**: Shows IAQ (Indoor Air Quality) values and accuracy
- **Gas Profile Monitoring**: Tracks gas resistance and heater temperature
- **Time Synchronization**: Automatically syncs time with the sensor device

## Sensor Data

The app receives and displays the following data:

### Particulate Matter
- PM1.0 (µg/m³)
- PM2.5 (µg/m³)
- PM10 (µg/m³)
- Sensor obstruction status

### Environmental Data (BME690 sensor)
- Temperature (°C)
- Humidity (%)
- Pressure (hPa)

### Air Quality
- IAQ (Indoor Air Quality Index)
- IAQ Accuracy level
- Gas Resistance (Ω)

### Gas Profile
- Heater Temperature (°C)
- Gas Resistance (Ω)

## Requirements

- Android device with BLE support
- Android 5.0 (API 21) or higher
- Location permissions (for BLE scanning on Android < 12)
- Bluetooth permissions

## Setup

### Prerequisites

1. **Android Studio**: Download and install [Android Studio](https://developer.android.com/studio)
2. **Android SDK**: API level 34 (automatically installed with Android Studio)
3. **Kotlin**: Version 1.8.0 or higher (included with Android Studio)

### Building the App

1. Clone this repository:
   ```bash
   git clone <repository-url>
   cd AirSens-Android
   ```

2. Open the project in Android Studio:
   - Launch Android Studio
   - Select "Open an Existing Project"
   - Navigate to the AirSens-Android directory

3. Sync Gradle:
   - Android Studio should automatically prompt to sync Gradle
   - If not, click "File" → "Sync Project with Gradle Files"

4. Connect your Android device:
   - Enable Developer Options and USB Debugging on your device
   - Connect via USB

5. Run the app:
   - Click the "Run" button (green triangle) in Android Studio
   - Or use the keyboard shortcut: Shift + F10

### Installing via APK

1. Build the APK:
   ```bash
   ./gradlew assembleDebug
   ```

2. The APK will be generated at:
   ```
   app/build/outputs/apk/debug/app-debug.apk
   ```

3. Transfer the APK to your Android device and install it

## Usage

1. **Launch the app**: Open "AirSens Monitor" on your Android device

2. **Grant Permissions**: The app will request Bluetooth and Location permissions (required for BLE scanning)

3. **Scan for Devices**:
   - Tap "Scan for Devices"
   - The app will search for nearby BMV080 sensors
   - Found devices will appear in the list

4. **Connect to Sensor**:
   - Tap on a device in the list to connect
   - Wait for the connection to establish

5. **View Data**:
   - Once connected, the app will display real-time sensor data
   - Data updates automatically as new measurements arrive

6. **Disconnect**:
   - Tap "Disconnect" to close the connection
   - You can scan for devices again to connect to a different sensor

## BLE Protocol

The app communicates with sensors using the following BLE characteristics:

### Service UUID
- `0000AAAA-0000-1000-8000-00805F9B34FB`

### Characteristics
- **Measurement Data**: `0000AAA1-0000-1000-8000-00805F9B34FB` (Notify)
- **Time Sync**: `0000AAA2-0000-1000-8000-00805F9B34FB` (Write)
- **Gas Profile**: `0000AAA8-0000-1000-8000-00805F9B34FB` (Notify)
- **Status**: `0000AAA3-0000-1000-8000-00805F9B34FB` (Read)

### Data Format

**Measurement Data** (variable length):
- Byte 0: Sensor mask (0x01=BMV080, 0x02=BME690)
- Bytes 1-4: Timestamp (uint32, little-endian)
- Bytes 5-8: PM10 (float32, little-endian)
- Bytes 9-12: PM2.5 (float32, little-endian)
- Bytes 13-16: PM1.0 (float32, little-endian)
- Byte 17: Obstructed flag (boolean)
- Byte 18: Time valid flag (boolean)
- Byte 19: IAQ accuracy (uint8)
- Bytes 20-21: Padding
- Bytes 22-41: BME690 data (if present)
  - Temperature (float32)
  - Humidity (float32)
  - Pressure (float32)
  - IAQ (float32)
  - Gas Resistance (float32)

**Gas Profile Data** (14 bytes):
- Bytes 0-1: Heater temperature (uint16, little-endian)
- Bytes 2-5: Gas resistance (float32, little-endian)
- Bytes 6-9: Humidity (float32, little-endian)
- Bytes 10-13: Pressure (float32, little-endian)

## Permissions

The app requires the following permissions:

### Android 12+ (API 31+)
- `BLUETOOTH_SCAN`: To scan for BLE devices
- `BLUETOOTH_CONNECT`: To connect to BLE devices

### Android 11 and below
- `BLUETOOTH`: For BLE operations
- `BLUETOOTH_ADMIN`: For BLE operations
- `ACCESS_FINE_LOCATION`: Required for BLE scanning

## Troubleshooting

### App can't find devices
- Ensure Bluetooth is enabled on your Android device
- Grant all requested permissions
- Make sure your sensor is powered on and advertising
- Check that the sensor name matches "BMV080" or "AirSens"

### Connection fails
- Move closer to the sensor device
- Try restarting Bluetooth on your phone
- Restart the sensor device
- Check that no other app is connected to the sensor

### No data displayed
- Wait a few seconds after connecting (the sensor may need time to start sending data)
- Check that the sensor is properly configured and functioning
- Look for error messages in the status text

### Permission denied errors
- Go to Settings → Apps → AirSens Monitor → Permissions
- Enable all required permissions
- Restart the app

## Project Structure

```
AirSens-Android/
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/com/airsens/monitor/
│   │       │   ├── MainActivity.kt              # Main UI and BLE scanning
│   │       │   └── AirQualitySensorClient.kt    # BLE communication logic
│   │       ├── res/
│   │       │   ├── layout/
│   │       │   │   └── activity_main.xml        # Main UI layout
│   │       │   └── values/
│   │       │       ├── strings.xml              # String resources
│   │       │       └── colors.xml               # Color definitions
│   │       └── AndroidManifest.xml              # App manifest and permissions
│   └── build.gradle                             # App-level build config
├── build.gradle                                  # Project-level build config
├── settings.gradle                               # Gradle settings
└── README.md                                     # This file
```

## Development

### Code Structure

- **MainActivity.kt**: Handles UI, BLE scanning, permissions, and data display
- **AirQualitySensorClient.kt**: Manages BLE connection, GATT operations, and data parsing

### Key Components

1. **BLE Scanner**: Uses Android's BluetoothLeScanner for device discovery
2. **GATT Client**: Connects to sensor and handles BLE communication
3. **Data Parser**: Decodes binary sensor data into readable values
4. **UI Update**: Real-time display of sensor measurements

## License

[Specify your license here]

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## Support

For issues, questions, or suggestions, please open an issue in the GitHub repository.
