# BLE Measurement Data Format

## Overview
This document describes the binary data structure used for the MEASUREMENT characteristic (UUID: 0xAAA1) in the AirSens BLE protocol.

## Data Structure

The measurement characteristic returns **100 bytes** containing buffered measurement data from the ESP32 sensor.

### Single Measurement Structure (42+ bytes)

| Offset | Size | Type    | Field           | Description                                    | Example Value |
|--------|------|---------|-----------------|------------------------------------------------|---------------|
| 0-3    | 4    | uint32  | Timestamp       | Unix timestamp (seconds since epoch)           | 407877891     |
| 4-5    | 2    | uint16  | PM1.0           | Particulate Matter 1.0 µg/m³                   | 105           |
| 6-7    | 2    | -       | Reserved/Padding| Unknown or padding                             | -             |
| 8-9    | 2    | uint16  | PM2.5           | Particulate Matter 2.5 µg/m³                   | 66            |
| 10-11  | 2    | -       | Reserved/Padding| Unknown or padding                             | -             |
| 12-13  | 2    | uint16  | PM10            | Particulate Matter 10 µg/m³                    | 65            |
| 14-15  | 2    | -       | Reserved/Padding| Unknown or padding                             | -             |
| 16     | 1    | uint8   | Flags           | Bit 0: Obstructed, Bit 1: Time Valid           | 0x41          |
| 17-21  | 5    | -       | Reserved/Padding| Unknown or padding                             | -             |
| 22-25  | 4    | float   | Temperature     | Temperature in °C                              | 25.33         |
| 26-29  | 4    | float   | Humidity        | Relative humidity in %                         | 44.04         |
| 30-33  | 4    | float   | Pressure        | Atmospheric pressure in **Pascals**            | 94408 (944 hPa)|
| 34-37  | 4    | float   | IAQ             | Indoor Air Quality index (0-500)               | 75.54         |
| 38-41  | 4    | float   | Gas Resistance  | Gas sensor resistance in Ohms                  | 21751         |
| 42+    | ?    | uint8?  | IAQ Accuracy    | IAQ accuracy level (0-3)                       | 0             |

## Important Notes

### PM Values
- **NOT stored as floats!** They are unsigned 16-bit integers.
- Located at offsets 4, 8, and 12 (with 2-byte spacing/padding between each)
- Values represent µg/m³ (micrograms per cubic meter)
- Range: 0-65535 µg/m³

### Environmental Data
- **Starts at offset 22**, not 17!
- All stored as IEEE 754 single-precision floats (4 bytes each)
- Pressure is in **Pascals**, must be divided by 100 to get hPa/mbar
- Temperature in Celsius, typical range: -50 to +100°C
- Humidity in percent, range: 0-100%
- Pressure typical range: 30,000-120,000 Pa (300-1200 hPa)
- IAQ range: 0-500 (lower is better)
- Gas Resistance in Ohms, varies widely

### Flags Byte (Offset 16)
```
Bit 0 (0x01): Sensor Obstructed (1 = obstructed, 0 = clear)
Bit 1 (0x02): Time Valid (1 = time synchronized, 0 = not synced)
Bits 2-7:     Reserved
```

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

### Previous Incorrect Assumptions
The initial implementation incorrectly assumed:
- PM values were stored as floats at offsets 4, 8, 12
- Environmental data started at offset 17
- All values were stored as floats

This led to garbage values like `PM2.5=-8.590002E9` and `Temp=3.59E-43`.

### Correction (2025-11-15)
After analyzing actual BLE characteristic data, the correct structure was determined:
- PM values are uint16 integers
- Environmental data starts at offset 22
- Proper byte alignment with padding/reserved bytes
