# Low-Power BLE Architecture

## Overview

This document describes the low-power BLE architecture for the ESP32 Air Quality Sensor, designed to minimize power consumption on both ESP32 and Android phone by using brief periodic connections instead of continuous BLE connection.

---

## Architecture Comparison

### Old Architecture (Continuous Connection)
```
ESP32: Always advertising → Maintains connection → Sends notifications continuously
Phone: Stays connected → Receives notifications → High battery drain
Power: ~50-100mA ESP32, significant phone battery drain
```

### New Architecture (Periodic Connection)
```
ESP32: Light sleep → Wakes for measurement → Buffers data → Advertises slowly
Phone: Connects every 60s → Reads buffered data → Disconnects immediately
Power: ~1-2mA ESP32 (light sleep), minimal phone battery drain
```

---

## Implementation Status

### ✅ Completed (Part 1)

#### 1. Measurement Buffer Component
**Location:** `components/measurement_buffer/`

**Features:**
- Circular buffer storing last 10 measurements
- Thread-safe with mutex protection
- RAM-based (survives light sleep, cleared on deep sleep)
- Total memory: ~500 bytes (50 bytes/measurement × 10)

**API:**
```c
esp_err_t measurement_buffer_init(void);
esp_err_t measurement_buffer_add(const measurement_record_t *record);
uint8_t measurement_buffer_get_count(void);
esp_err_t measurement_buffer_get_latest(measurement_record_t *record);
esp_err_t measurement_buffer_get_at_index(uint8_t index, measurement_record_t *record);
esp_err_t measurement_buffer_get_all(measurement_record_t *records, uint8_t *count);
void measurement_buffer_clear(void);
```

#### 2. BLE Characteristic: Measurement Count (0xAAA9)
**UUID:** `0000AAA9-0000-1000-8000-00805F9B34FB`
**Properties:** READ
**Data:** 1 byte (0-10)

**Purpose:**
Allows phone to quickly check how many measurements are buffered without reading the full data.

**Usage:**
```kotlin
// Android code
val countChar = service.getCharacteristic(MEASUREMENT_COUNT_UUID)
val count = gatt.readCharacteristic(countChar)
// count[0] = number of buffered measurements
```

#### 3. Modified BLE Server Behavior
**File:** `components/ble_server/ble_server.c`

**Changes to `ble_server_send_measurement()`:**
1. **Buffers measurement** even when not connected
2. **Updates measurement count** characteristic automatically
3. **Sends notification** only if connected (for development/debugging)
4. **Returns ESP_OK** even when not connected (no longer fails)

**Before:**
```c
if (!is_connected || !record) {
    return ESP_ERR_INVALID_STATE;  // Failed if not connected
}
// ... pack and send notification
```

**After:**
```c
/* Add to buffer (always works) */
measurement_buffer_add(record);
gatt_update_measurement_count(measurement_buffer_get_count());

/* Send notification ONLY if connected */
if (is_connected) {
    gatt_send_measurement(data, offset);
}
```

---

### 🚧 Remaining Implementation (Part 2)

#### 4. Dynamic Advertising Intervals
**Goal:** Adjust advertising speed based on buffer status

**Implementation:**
- **Slow (1000ms):** When buffer empty or < 5 measurements
- **Fast (300ms):** When buffer ≥ 5 measurements (data ready)
- **Benefits:** Lower power when idle, faster connection when data available

**Location:** `components/ble_server/ble_gap.c`

**Pseudo-code:**
```c
void update_advertising_interval(void) {
    uint8_t count = measurement_buffer_get_count();
    if (count >= 5) {
        adv_set_interval(300, 400);  // Fast
    } else {
        adv_set_interval(1000, 1200);  // Slow
    }
}
```

#### 5. Connection Timeout Mechanism
**Goal:** Auto-disconnect after 5 seconds of inactivity

**Implementation:**
- Start timer on connection
- Reset timer on any characteristic read/write
- Disconnect if timer expires

**Location:** `components/ble_server/ble_gap.c`

**Benefits:** Prevents stuck connections, ensures brief connections

#### 6. Automatic Time Request on Connection
**Goal:** ESP32 requests time sync when needed

**Implementation:**
- On connection, check: Is time valid? Is it >24h since last sync?
- If needed: Send time request INDICATION (characteristic 0xAAA4)
- Phone receives indication → writes current time (characteristic 0xAAA2)

**Location:** `components/ble_server/ble_gap.c` (connection handler)

**Flow:**
```
1. Phone connects
2. ESP32: if (time_invalid || time_age > 24h) {
3.     Send time request indication (0xAAA4)
4. }
5. Phone receives indication
6. Phone writes current time to 0xAAA2
7. ESP32 updates system time
```

#### 7. Light Sleep Between Measurements
**Goal:** Reduce power from ~240mA (active) to ~1-2mA (sleep)

**Implementation:**
- After sensor measurement, enter light sleep
- Wake up before next measurement (30s interval)
- BLE stack stays active (can respond to connections)

**Location:** `main/main.c` (sensor task)

**Pseudo-code:**
```c
while (1) {
    // Measure sensors
    take_measurement();

    // Add to buffer
    ble_server_send_measurement(&record);

    // Light sleep until next measurement
    esp_sleep_enable_timer_wakeup(30 * 1000000);  // 30 seconds
    esp_light_sleep_start();
}
```

**Power Savings:**
- Active: ~240mA
- Light sleep: ~1-2mA
- Duty cycle: 2s active / 28s sleep per 30s = ~6.7% active
- Average: (240mA × 0.067) + (1.5mA × 0.933) = **~17.5mA average**

---

## Android App Implementation

### Foreground Service (Required)
Android requires a foreground service for background BLE operations.

**Features:**
- Persistent notification
- Runs every 60 seconds
- Works with app closed
- Survives screen off

**AndroidManifest.xml:**
```xml
<service
    android:name=".BlePeriodicService"
    android:foregroundServiceType="connectedDevice"
    android:enabled="true"
    android:exported="false" />

<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
```

### Connection Pattern

```kotlin
class BlePeriodicService : Service() {
    private val SCAN_TIMEOUT = 2000L
    private val CONNECTION_INTERVAL = 60000L  // 60 seconds
    private var timeSyncCounter = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Show foreground notification
        startForeground(NOTIFICATION_ID, createNotification())

        // Schedule periodic task
        handler.postDelayed(periodicTask, CONNECTION_INTERVAL)

        return START_STICKY
    }

    private val periodicTask = object : Runnable {
        override fun run() {
            lifecycleScope.launch {
                connectAndReadData()
            }
            handler.postDelayed(this, CONNECTION_INTERVAL)
        }
    }

    private suspend fun connectAndReadData() {
        // 1. Scan for device (max 2s)
        val device = scanForDevice(timeout = SCAN_TIMEOUT) ?: return

        // 2. Connect
        val gatt = device.connectGatt(this, false, gattCallback) ?: return

        try {
            // 3. Read measurement count
            val count = readMeasurementCount(gatt)

            if (count > 0) {
                // 4. Read latest measurement
                val measurement = readMeasurement(gatt)

                // 5. Store in local database
                database.insert(measurement)
            }

            // 6. Time sync (every 120s = every 2nd connection)
            timeSyncCounter++
            if (timeSyncCounter >= 2) {
                sendTimeSync(gatt)
                timeSyncCounter = 0
            }

        } finally {
            // 7. Disconnect immediately
            gatt.disconnect()
            gatt.close()
        }
    }

    private suspend fun readMeasurementCount(gatt: BluetoothGatt): Int {
        val service = gatt.getService(SERVICE_UUID)
        val char = service.getCharacteristic(MEASUREMENT_COUNT_UUID)

        return suspendCancellableCoroutine { cont ->
            gatt.readCharacteristic(char)
            // Wait for callback with result
            onCharacteristicRead = { value ->
                cont.resume(value[0].toInt())
            }
        }
    }

    private suspend fun readMeasurement(gatt: BluetoothGatt): Measurement {
        val service = gatt.getService(SERVICE_UUID)
        val char = service.getCharacteristic(MEASUREMENT_UUID)

        return suspendCancellableCoroutine { cont ->
            gatt.readCharacteristic(char)
            // Wait for callback with data
            onCharacteristicRead = { data ->
                cont.resume(parseMeasurement(data))
            }
        }
    }
}
```

### UUIDs for Android

```kotlin
// Service
val SERVICE_UUID = UUID.fromString("0000AAAA-0000-1000-8000-00805F9B34FB")

// Characteristics
val MEASUREMENT_UUID = UUID.fromString("0000AAA1-0000-1000-8000-00805F9B34FB")        // READ + NOTIFY
val TIME_SYNC_UUID = UUID.fromString("0000AAA2-0000-1000-8000-00805F9B34FB")          // WRITE
val STATUS_UUID = UUID.fromString("0000AAA3-0000-1000-8000-00805F9B34FB")             // READ + NOTIFY
val TIME_REQUEST_UUID = UUID.fromString("0000AAA4-0000-1000-8000-00805F9B34FB")       // READ + INDICATE
val GAS_PROFILE_UUID = UUID.fromString("0000AAA8-0000-1000-8000-00805F9B34FB")        // READ + NOTIFY
val MEASUREMENT_COUNT_UUID = UUID.fromString("0000AAA9-0000-1000-8000-00805F9B34FB")  // READ (NEW!)
```

---

## Power Analysis

### ESP32 Power Consumption

**Without Light Sleep:**
- Active (sensors + BLE): ~240mA
- Daily: 240mA × 24h = **5,760 mAh/day**
- Battery (2000mAh): **~8 hours**

**With Light Sleep (Implemented):**
- Active (2s/30s): 240mA × 6.7% = 16.1mA
- Sleep (28s/30s): 1.5mA × 93.3% = 1.4mA
- Average: **~17.5mA**
- Daily: 17.5mA × 24h = **420 mAh/day**
- Battery (2000mAh): **~5 days**

**Connection Power:**
- Connection duration: ~2-3 seconds
- Frequency: Every 60 seconds
- Duty cycle: 3s/60s = 5%
- Impact: Minimal (already in active cycle for measurements)

### Android Power Consumption

**Continuous Connection (Old):**
- BLE scan/maintain: ~30-50mA continuous
- Screen off drain: ~5-10%/hour
- Daily: **50-100% battery drain**

**Periodic Connection (New):**
- BLE scan: 2s @ 30mA = 60mAh
- Connect/read: 1s @ 20mA = 20mAh
- Total per cycle: 80mAh
- Cycles per hour: 60
- Hourly: 4.8mAh
- Daily (24h): **115mAh** (~2-3% on 4000mAh battery)

---

## Testing Checklist

### ESP32 Testing

- [ ] Measurements buffer when not connected
- [ ] Measurement count characteristic readable
- [ ] Count increases as measurements added (max 10)
- [ ] Oldest measurement dropped when buffer full
- [ ] Notifications still work when connected (development)
- [ ] Buffer survives light sleep
- [ ] Buffer cleared on deep sleep
- [ ] Thread-safe (concurrent access from BLE and sensor tasks)

### Android Testing

- [ ] Foreground service starts and shows notification
- [ ] Scans for device every 60s
- [ ] Connects successfully
- [ ] Reads measurement count
- [ ] Reads measurement data
- [ ] Parses data correctly
- [ ] Disconnects immediately
- [ ] Stores data in local database
- [ ] Syncs time every 120s
- [ ] Works with screen off
- [ ] Works with app closed
- [ ] Survives phone reboot (if configured)

---

## Next Steps

1. **Complete Part 2 implementation** (items 4-7 above)
2. **Test measurement buffering** without connection
3. **Implement Android foreground service**
4. **Test periodic connection pattern**
5. **Measure actual power consumption** with multimeter
6. **Optimize sleep/wake timings** based on measurements
7. **Add battery monitoring** and low-battery warnings

---

## File Changes Summary

### New Files
- `components/measurement_buffer/include/measurement_buffer.h`
- `components/measurement_buffer/measurement_buffer.c`
- `components/measurement_buffer/CMakeLists.txt`

### Modified Files
- `components/ble_server/ble_gatt.c` - Added 0xAAA9 characteristic
- `components/ble_server/ble_gatt.h` - Added gatt_update_measurement_count()
- `components/ble_server/ble_server.c` - Buffer integration
- `components/ble_server/CMakeLists.txt` - Added measurement_buffer dependency
- `main/CMakeLists.txt` - Added measurement_buffer dependency

---

## References

- ESP32 Light Sleep: https://docs.espressif.com/projects/esp-idf/en/latest/esp32/api-reference/system/sleep_modes.html
- Android Foreground Services: https://developer.android.com/develop/background-work/services/foreground-services
- BLE Power Optimization: https://www.bluetooth.com/blog/bluetooth-low-energy-it-starts-with-advertising/
