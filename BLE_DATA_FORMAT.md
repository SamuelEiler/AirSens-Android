# BLE Measurement Data Format

## Overview
This document describes the binary data structure used for the MEASUREMENT characteristic (UUID: 0xAAA1) in the AirSens BLE protocol.

## Data Structure

The measurement characteristic returns **100 bytes** containing buffered measurement data from the ESP32 sensor.

### Single Measurement Structure (38 bytes)

| Offset | Size | Type    | Field           | Description                                    | Example Value |
|--------|------|---------|-----------------|------------------------------------------------|---------------|
| 0      | 1    | uint8   | Sensor Mask     | Bit flags for active sensors                   | 0x03          |
| 1-4    | 4    | uint32  | Timestamp       | Unix timestamp (seconds since epoch)           | 1731679200    |
| 5-8    | 4    | float   | PM10            | Particulate Matter 10 µg/m³                    | 64.0          |
| 9-12   | 4    | float   | PM2.5           | Particulate Matter 2.5 µg/m³                   | 66.0          |
| 13-16  | 4    | float   | PM1.0           | Particulate Matter 1.0 µg/m³                   | 105.0         |
| 17     | 1    | uint8   | Flags           | Bit 0: Obstructed, Bit 1: Time Valid, Bits 2-3: IAQ Accuracy | 0x03 |
| 18-21  | 4    | float   | Temperature     | Temperature in °C (if sensor_mask & 0x02)      | 25.33         |
| 22-25  | 4    | float   | Humidity        | Relative humidity % (if sensor_mask & 0x02)    | 44.04         |
| 26-29  | 4    | float   | Pressure        | Atmospheric pressure in **Pascals** (if sensor_mask & 0x02) | 94408 (944 hPa)|
| 30-33  | 4    | float   | IAQ             | Indoor Air Quality index 0-500 (if sensor_mask & 0x02) | 75.54 |
| 34-37  | 4    | float   | Gas Resistance  | Gas sensor resistance in Ohms (if sensor_mask & 0x02) | 21751 |

## Important Notes

### Sensor Mask (Offset 0)
- Bit 0 (0x01): PM sensor active
- Bit 1 (0x02): BME680 environmental sensor active
- Typical value: 0x03 (both sensors active)

### Timestamp (Offset 1-4)
- Unix timestamp in seconds since epoch (1970-01-01 00:00:00 UTC)
- Little-endian uint32
- Range: 0 to 4,294,967,295 (year 2106)

### PM Values (Offsets 5, 9, 13)
- **Stored as IEEE 754 single-precision floats** (4 bytes each)
- Order: PM10, PM2.5, PM1.0
- Values represent µg/m³ (micrograms per cubic meter)
- Typical range: 0.0 to 500.0 µg/m³

### Flags Byte (Offset 17)
```
Bit 0 (0x01): Sensor Obstructed (1 = obstructed, 0 = clear)
Bit 1 (0x02): Time Valid (1 = time synchronized, 0 = not synced)
Bits 2-3:     IAQ Accuracy (0-3, from BME680)
Bits 4-7:     Reserved
```

### Environmental Data (Offsets 18-37)
- Only present if `sensor_mask & 0x02` (BME680 active)
- All stored as IEEE 754 single-precision floats (4 bytes each)
- **Pressure is in Pascals**, must be divided by 100 to get hPa/mbar
- Temperature in Celsius, typical range: -50 to +100°C
- Humidity in percent, range: 0-100%
- Pressure typical range: 30,000-120,000 Pa (300-1200 hPa)
- IAQ range: 0-500 (lower is better, 0-50 is excellent)
- Gas Resistance in Ohms, varies widely based on air quality

### Byte Order
All multi-byte values use **little-endian** byte order.

## Example Hex Data

```
03 B9 4F 18  69 00 00 10  42 00 00 D0  41 00 00 40
41 00 01 00  00 00 C3 B2  CA 41 5D 2A  30 42 0D 1C
B9 47 34 2C  97 42 92 6E  AA 46 ...
```

Parsed as:
- **Timestamp**: `03 B9 4F 18` = 407,877,891 (2012-12-01 22:58:11 UTC)
- **PM1.0**: `69 00` = 105 µg/m³
- **PM2.5**: `42 00` = 66 µg/m³
- **PM10**: `41 00` = 65 µg/m³
- **Flags**: `41` = 0b01000001 (obstructed=true, timeValid=false)
- **Temperature**: `C3 B2 CA 41` = 25.33°C
- **Humidity**: `5D 2A 30 42` = 44.04%
- **Pressure**: `0D 1C B9 47` = 94,408 Pa = 944.08 hPa
- **IAQ**: `34 2C 97 42` = 75.54
- **Gas**: `92 6E AA 46` = 21,751 Ω

## Historical Changes

### Initial Incorrect Assumptions (Pre-2025-11-15)
The initial implementation incorrectly assumed:
- PM values were stored as floats at offsets 4, 8, 12
- Environmental data started at offset 17
- All values were stored as floats

This led to garbage values like `PM2.5=-8.590002E9` and `Temp=3.59E-43`.

### First Correction (2025-11-15)
After analyzing actual BLE characteristic data, the structure was updated:
- PM values are uint16 integers (not floats)
- Environmental data starts at offset 22
- Flags field was 5 bytes (offset 16-21)
- Proper byte alignment with padding/reserved bytes

### Second Correction (2025-11-15, later)
ESP32 firmware changed flags field from 5 bytes to 1 byte:
- Flags field now only 1 byte at offset 16
- This shifted all environmental data by -4 bytes
- Environmental data now starts at **offset 18** (was 22)
- IAQ accuracy now at **offset 38** (was 42)
- Minimum message size reduced to 38 bytes (was 42)

### Third Correction (2025-11-15, final)
ESP32 firmware updated to final format with sensor mask:
- **Sensor mask added at offset 0** (1 byte)
- Timestamp moved to **offset 1-4** (was 0-3)
- **PM values changed to floats** (4 bytes each, were uint16)
  - PM10 at offset 5-8 (was uint16 at offset 12-13)
  - PM2.5 at offset 9-12 (was uint16 at offset 8-9)
  - PM1.0 at offset 13-16 (was uint16 at offset 4-5)
- Flags at **offset 17** (was offset 16)
- **IAQ accuracy now in flags bits 2-3** (was separate byte at offset 38)
- Environmental data still at offset 18-37 (unchanged)
- Total message size: **38 bytes** (fixed)
