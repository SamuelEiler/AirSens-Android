# Debugging Background Monitoring Service

## Check if Service is Running

### 1. Check the Notification
When you start background monitoring, you should see a persistent notification that shows:
- "AirSens Monitor"
- Status updates like:
  - "Starting..."
  - "Scanning for device..."
  - "Connecting..."
  - "Reading data..."
  - "Last update: [timestamp]"
  - OR error messages like "Device not found" or "Connection timeout"

**What does your notification say?**

### 2. Check Logcat (Most Important!)

The service logs everything it does. To see what's happening:

```bash
# Filter for just our service logs
adb logcat -s BlePeriodicService:* MainActivity:*

# Or see all logs
adb logcat | grep -E "BlePeriodicService|MainActivity"
```

**Look for these log messages:**

**Success path:**
```
BlePeriodicService: Starting periodic connection cycle
BlePeriodicService: Found device: [MAC_ADDRESS]
BlePeriodicService: Measurement count: 1
BlePeriodicService: Stored measurement: PM2.5=X.XX, Temp=X.X
BlePeriodicService: Disconnected
```

**Common issues:**

**Issue 1: Device not found**
```
BlePeriodicService: Device not found during scan
```
**Solution:**
- Make sure your ESP32 sensor is powered on
- Make sure it's advertising with name "BMV080" or "AirSens"
- Check if you can see it in manual scan mode

**Issue 2: Permission denied**
```
BlePeriodicService: [Permission error]
```
**Solution:**
- Go to Android Settings → Apps → AirSens Monitor → Permissions
- Grant all Bluetooth and Location permissions
- For Android 13+, also grant "Notifications" permission

**Issue 3: Connection timeout**
```
BlePeriodicService: Connection timeout
```
**Solution:**
- Move phone closer to ESP32
- Restart ESP32
- Check ESP32 is not connected to another device

**Issue 4: No measurement data**
```
BlePeriodicService: Measurement count: 0
```
**Solution:**
- ESP32 hasn't taken any measurements yet (wait 60 seconds)
- ESP32 firmware might not be implementing the measurement buffer correctly

### 3. Check Database

You can verify if data is being stored:

```bash
# Pull the database file
adb exec-out run-as com.airsens.monitor cat databases/airsens_database > airsens_database.db

# Then use sqlite3 or DB Browser to check
sqlite3 airsens_database.db "SELECT COUNT(*) FROM measurements;"
sqlite3 airsens_database.db "SELECT * FROM measurements ORDER BY timestamp DESC LIMIT 5;"
```

### 4. Check Permissions

Make sure these permissions are granted in Android Settings:
- ✅ Nearby devices (Bluetooth)
- ✅ Location (for BLE scanning on Android < 12)
- ✅ Notifications (for Android 13+)

### 5. Force a Refresh

After starting the service, try:
1. Close the app completely (swipe away from recent apps)
2. Wait 60 seconds (for service to connect once)
3. Reopen the app
4. The data should load automatically

## Quick Test Checklist

- [ ] ESP32 is powered on and running
- [ ] ESP32 name contains "BMV080" or "AirSens"
- [ ] All permissions granted
- [ ] Notification shows "Last update: ..." (not "Device not found")
- [ ] Logcat shows "Stored measurement" messages
- [ ] Close and reopen app after 60 seconds

## Next Steps

**If you see in logcat:**
- "Device not found" → Check ESP32 is on and advertising
- "Connection timeout" → Move phone closer to ESP32
- "Measurement count: 0" → Wait longer, ESP32 needs time to collect data
- "Stored measurement" → Great! Data is being saved. Close/reopen app to see it

**Share with me:**
1. What the notification says
2. The logcat output from `adb logcat -s BlePeriodicService:*`
3. Any error messages you see
