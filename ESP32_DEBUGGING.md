# ESP32 Firmware Debugging Guide

## Current Situation

The Android app is working correctly, but the ESP32 is reporting **measurement count = 0** and occasionally experiencing connection timeouts.

## Android App Improvements (Just Committed)

I've made the following improvements to make the Android app more robust:

1. **Increased connection timeout** from 5s to 10s - Gives more time for BLE service discovery
2. **Improved disconnect handling** - Now waits 1 second after disconnect before closing GATT connection
3. **Better logging** - More detailed error messages to help debug ESP32 issues
4. **Better notifications** - Shows "Waiting for data..." when measurement count is 0

## ESP32 Firmware Checklist

The measurement count = 0 indicates the ESP32 firmware is not properly implementing the buffering system. Here's what to check:

### 1. Verify Measurement Buffer Implementation

Your ESP32 firmware should have:

```cpp
// Buffer to store measurements (last 10)
struct Measurement {
    uint32_t timestamp;
    uint16_t pm1;
    uint16_t pm25;
    uint16_t pm10;
    uint8_t flags;
    float temperature;
    float humidity;
    float pressure;
    float iaq;
    float gasResistance;
    uint8_t iaqAccuracy;
};

#define BUFFER_SIZE 10
Measurement measurementBuffer[BUFFER_SIZE];
int bufferCount = 0;  // Number of measurements currently in buffer
int bufferWriteIndex = 0;  // Where to write next measurement
```

### 2. Verify Measurement Count Characteristic (0xAAA9)

This characteristic should:
- Return a single byte (uint8_t)
- Value should be the number of measurements in the buffer (0-10)
- Be readable by the Android app

**Check your ESP32 code:**
```cpp
// When Android reads characteristic 0xAAA9
class MeasurementCountCallbacks : public BLECharacteristicCallbacks {
    void onRead(BLECharacteristic *pCharacteristic) {
        uint8_t count = bufferCount;
        pCharacteristic->setValue(&count, 1);
        Serial.printf("Android read measurement count: %d\n", count);
    }
};
```

### 3. Verify Measurements Are Being Stored

When the ESP32 takes a measurement, it should:
1. Read sensor data
2. Store in the buffer
3. Increment `bufferCount` (up to max 10)
4. Wrap `bufferWriteIndex` when it reaches BUFFER_SIZE

**Example:**
```cpp
void storeMeasurement(Measurement& m) {
    measurementBuffer[bufferWriteIndex] = m;
    bufferWriteIndex = (bufferWriteIndex + 1) % BUFFER_SIZE;

    if (bufferCount < BUFFER_SIZE) {
        bufferCount++;
    }

    Serial.printf("Stored measurement. Buffer count: %d\n", bufferCount);
}
```

### 4. Verify Measurement Data Characteristic (0xAAA1)

When Android reads this characteristic:
1. Check if buffer has data (`bufferCount > 0`)
2. Return the OLDEST unread measurement (FIFO - First In First Out)
3. Format data correctly (see BLE_DATA_FORMAT.md)
4. Decrement `bufferCount` after reading

**Example:**
```cpp
class MeasurementDataCallbacks : public BLECharacteristicCallbacks {
    void onRead(BLECharacteristic *pCharacteristic) {
        if (bufferCount > 0) {
            // Calculate read index (oldest measurement)
            int readIndex = (bufferWriteIndex - bufferCount + BUFFER_SIZE) % BUFFER_SIZE;

            // Format the measurement into 100-byte buffer
            uint8_t data[100] = {0};
            formatMeasurement(&measurementBuffer[readIndex], data);

            pCharacteristic->setValue(data, 100);

            bufferCount--;  // Mark as read
            Serial.printf("Sent measurement. Remaining: %d\n", bufferCount);
        }
    }
};
```

## Known Issue: Time Sync Not Applied

### Symptoms
- Android logs show `timeValid=true` in flags
- BUT timestamp is around **410,669,059** instead of **~1,731,679,200**
- Timestamp is off by ~42 years (would be around 1983 instead of 2025)
- Temperature readings may be incorrect (e.g., 55°C when room temp)

### Root Cause
The ESP32 is receiving the time sync write and setting the `timeValid` flag to `true`, but **NOT actually using the synced timestamp value** for measurements. Instead, it's likely using:
- `millis() / 1000` (milliseconds since ESP32 boot)
- Some internal counter that starts from 0
- A hardcoded offset that's incorrect

### Solution
The ESP32 firmware must:
1. ✓ Receive the time sync (you're doing this - flag is set)
2. ✗ **STORE the synced timestamp** (missing!)
3. ✗ **USE the stored timestamp** for future measurements (missing!)

See the code example in "Issue 4: Time Sync Not Being Used" below.

## Common ESP32 Issues

### Issue 1: Measurements Not Being Stored

**Symptom:** `bufferCount` stays at 0

**Possible causes:**
- Sensor measurement loop not calling `storeMeasurement()`
- Measurement timing too long (should measure every 60 seconds)
- Sensor initialization failed

**Debug:**
```cpp
void loop() {
    static unsigned long lastMeasurement = 0;

    if (millis() - lastMeasurement >= 60000) {  // Every 60 seconds
        Measurement m;
        readSensors(&m);  // Your sensor reading function
        storeMeasurement(m);
        Serial.printf("Buffer count: %d\n", bufferCount);

        lastMeasurement = millis();
    }
}
```

### Issue 2: Connection Timeouts

**Symptom:** Android logs show "Connection timeout" after 2-3 successful connections

**Possible causes:**
- ESP32 not handling multiple sequential connections properly
- Connection parameters too aggressive
- BLE stack not resetting between connections

**Fix:**
```cpp
// In your BLE server setup
BLEDevice::setMTU(517);  // Set reasonable MTU

// Add connection callbacks to monitor state
class ServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
        Serial.println("Client connected");
        // Don't start advertising while connected
    }

    void onDisconnect(BLEServer* pServer) {
        Serial.println("Client disconnected");
        delay(500);  // Small delay before advertising again
        pServer->startAdvertising();
    }
};
```

### Issue 3: Time Sync Not Being Used

**Symptom:** `timeValid=true` but timestamp is wrong (410,669,059 instead of ~1,731,679,200)

**Possible causes:**
- Time sync callback only sets flag, doesn't store the timestamp
- Measurements still use `millis()` instead of synced time
- Timestamp arithmetic is incorrect

**Fix:**
```cpp
// Global variables for time tracking
uint32_t syncedTimestamp = 0;
uint32_t syncedAtMillis = 0;
bool timeIsSynced = false;

// Time sync characteristic callback
class TimeSyncCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pCharacteristic) {
        std::string value = pCharacteristic->getValue();

        if (value.length() == 4) {
            // Extract the Unix timestamp from Android
            syncedTimestamp = *(uint32_t*)value.data();
            syncedAtMillis = millis();
            timeIsSynced = true;

            Serial.printf("Time sync received: %u\n", syncedTimestamp);
            Serial.printf("Expected: ~1731679200 (Nov 2025)\n");
            Serial.printf("Synced at millis: %u\n", syncedAtMillis);
        }
    }
};

// Function to get current time
uint32_t getCurrentTime() {
    if (!timeIsSynced) {
        return 0;  // No valid time
    }

    // Calculate how many seconds have elapsed since sync
    uint32_t elapsedSeconds = (millis() - syncedAtMillis) / 1000;

    // Current time = sync point + elapsed time
    return syncedTimestamp + elapsedSeconds;
}

// When storing measurements
void storeMeasurement() {
    Measurement m;

    // Use the synced time, NOT millis()!
    m.timestamp = getCurrentTime();

    // Set flags correctly
    m.flags = 0;
    if (sensorObstructed) m.flags |= 0x01;
    if (timeIsSynced) m.flags |= 0x02;  // Only set if we have valid time

    // Read sensors...
    m.pm25 = readPM25();
    m.temperature = readTemperature();
    // ...

    measurementBuffer[bufferWriteIndex] = m;
    bufferWriteIndex = (bufferWriteIndex + 1) % BUFFER_SIZE;
    if (bufferCount < BUFFER_SIZE) bufferCount++;

    Serial.printf("Stored measurement with timestamp: %u\n", m.timestamp);
}
```

**Verify the fix:**
After implementing this, you should see:
```
Time sync received: 1731679200
Expected: ~1731679200 (Nov 2025)
Synced at millis: 123456
Stored measurement with timestamp: 1731679200
Stored measurement with timestamp: 1731679260  (60 seconds later)
```

### Issue 4: Characteristic Not Found

**Symptom:** Android logs show "Characteristic 0xAAA9 not found"

**Possible causes:**
- Characteristic not created in ESP32 firmware
- Wrong UUID
- Service not started

**Fix:**
Verify your ESP32 service setup:
```cpp
BLEService *pService = pServer->createService(SERVICE_UUID);

// Create all characteristics
BLECharacteristic *pMeasurementData = pService->createCharacteristic(
    MEASUREMENT_UUID,
    BLECharacteristic::PROPERTY_READ
);

BLECharacteristic *pMeasurementCount = pService->createCharacteristic(
    MEASUREMENT_COUNT_UUID,
    BLECharacteristic::PROPERTY_READ
);

BLECharacteristic *pTimeSync = pService->createCharacteristic(
    TIME_SYNC_UUID,
    BLECharacteristic::PROPERTY_WRITE
);

// Set callbacks
pMeasurementData->setCallbacks(new MeasurementDataCallbacks());
pMeasurementCount->setCallbacks(new MeasurementCountCallbacks());
pTimeSync->setCallbacks(new TimeSyncCallbacks());

// Start service
pService->start();
```

## How to Debug

### 1. Monitor ESP32 Serial Output

When Android connects, you should see:
```
Client connected
Android read measurement count: 3
Sent measurement. Remaining: 2
Client disconnected
```

If you see `count: 0`, the problem is measurements aren't being stored.

### 2. Force Store a Test Measurement

Add this to your setup() to verify the buffer works:
```cpp
void setup() {
    // ... your existing setup ...

    // Store a test measurement
    Measurement test;
    test.timestamp = 1700000000;
    test.pm25 = 42;
    test.temperature = 25.0;
    storeMeasurement(test);

    Serial.printf("Test measurement stored. Count: %d\n", bufferCount);
}
```

If Android can read this test measurement, your BLE implementation is correct and the issue is with sensor measurement timing.

### 3. Check Sensor Measurement Timing

Add debug output to your measurement loop:
```cpp
void loop() {
    static unsigned long lastMeasurement = 0;
    unsigned long now = millis();

    if (now - lastMeasurement >= 60000) {
        Serial.printf("Taking measurement at %lu ms\n", now);

        Measurement m;
        m.timestamp = getCurrentTime();  // Your time sync implementation

        // Read sensors
        readPMS7003(&m);  // PM sensor
        readBME680(&m);   // Environmental sensor

        storeMeasurement(m);
        Serial.printf("Stored. Buffer count: %d\n", bufferCount);

        lastMeasurement = now;
    }

    // Rest of your loop
}
```

## Next Steps

1. **Add serial debug output** to your ESP32 firmware as shown above
2. **Rebuild and flash** the ESP32 firmware
3. **Monitor serial output** while the Android app connects
4. **Share the ESP32 logs** with me if you need help

The Android app is now as robust as possible. The remaining issue is definitely in the ESP32 firmware's measurement buffering implementation.
