# Quick Start Guide

## TL;DR - Get Started in 5 Minutes

### Option 1: Android Studio (Recommended)

1. Install [Android Studio](https://developer.android.com/studio)
2. Open this project in Android Studio
3. Connect your Android device via USB (with USB debugging enabled)
4. Click the green "Run" button
5. Launch the app on your device

### Option 2: Command Line Build

```bash
# Build the APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug
```

The APK will be at: `app/build/outputs/apk/debug/app-debug.apk`

## Using the App

### First Time Setup

1. **Enable Bluetooth** on your Android device
2. **Grant Permissions** when prompted:
   - Bluetooth permissions
   - Location permissions (required for BLE on Android < 12)

### Connecting to Your Sensor

1. **Power on** your BMV080/AirSens sensor
2. Open the app and tap **"Scan for Devices"**
3. Wait for your sensor to appear in the list
4. **Tap on the device** to connect
5. View real-time air quality data!

### Understanding the Data

**Particulate Matter**
- PM1.0, PM2.5, PM10 values in µg/m³
- Lower values = better air quality

**Environmental**
- Temperature, humidity, pressure readings

**Air Quality Index (IAQ)**
- 0-50: Good
- 51-100: Average
- 101-150: Little bad
- 151-200: Bad
- 201-300: Worse
- 301-500: Very bad

## Troubleshooting

**Can't find devices?**
- Ensure Bluetooth is ON
- Grant all permissions
- Check sensor is powered and advertising

**Connection fails?**
- Move closer to sensor
- Restart Bluetooth
- Restart the sensor

**No data showing?**
- Wait 10-15 seconds after connecting
- Check sensor is functioning properly

## Sensor Configuration

Your ESP sensor should:
- Advertise with name containing "BMV080" or "AirSens"
- Use BLE Service UUID: `0000AAAA-0000-1000-8000-00805F9B34FB`
- Implement the characteristics defined in README.md

## Next Steps

- Read the full [README.md](README.md) for detailed information
- Customize the app for your needs
- Add data logging features
- Create charts and graphs

## Support

Having issues? Check the [README.md](README.md) troubleshooting section or open an issue on GitHub.
