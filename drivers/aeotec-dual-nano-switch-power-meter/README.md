# Aeotec Dual Nano Switch with Power Meter — Hubitat driver

Z-Wave driver for the Aeotec Dual Nano Switch **ZW132** (the model with energy metering; the ZW140 has no meter and does not need this driver). The built-in driver creates one *Generic Component Switch* child per channel, which cannot hold power or energy. This package adds a child driver with Switch, Power Meter and Energy Meter and a parent that reads each channel through Z-Wave Multi Channel.

## What it does

- Parent `Aeotec Dual Nano Switch with Power Meter`: on/off for both channels; `power` / `energy` as the **sum** of the channels; `power1`, `power2`, `energy1`, `energy2` per channel.
- Child `Aeotec Dual Nano Switch Channel` (one per channel): `switch`, `power` (W), `energy` (kWh).
- Power is polled per channel (default every 1 minute), energy every 15 minutes. Both are configurable; 0 turns a poll off.
- **No report parameters are changed.** The ZW132 parameter table was not confirmed, so the driver only sets the hub association (group 1) and asks for readings.

## Tested on

Two ZW132 units (Z-Wave manufacturer 134, device type 259, device id 132, `0x32` in the In Clusters): per-channel power and energy match each channel's load, on/off from the children works, `recreateChildren` works, and the hub association (group 1) is confirmed by read-back. Loads were small (about 3 W and 14.5 W).

A ZW140 (device id 140, no `0x32`) is detected: the driver logs a warning and does not poll.

Not tested: 4-channel or other Aeotec models, loads close to the 5 A per-channel limit.

## Install

1. Install with HPM, or import both files from `drivers/aeotec-dual-nano-switch-power-meter/` under **Drivers Code**.
2. On the device page of the existing Dual Nano, set **Type** to `Aeotec Dual Nano Switch with Power Meter` and Save Device, then press **Configure** and **Refresh**.
3. The Type of children created by the native driver cannot be changed in the Hubitat UI. To move them to the new child driver, run **recreateChildren** on the parent and type `RECREATE`. It deletes each child and creates a new one with the same network id, name and label.

> **recreateChildren changes device ids.** Rules, groups, dashboards, Maker API, Google Home and Safety Monitor lose the reference to the old child, and the room is cleared. The log and the `migration` state variable show old id, new id and room for each channel. A new install (no children yet) does not need this: **Configure** creates the children.

To go back, set the parent to the system `Aeotec Dual Nano Switch` type. The children stay on this package's child driver; remove them and run the native driver's configuration if you want its own children back.

## Diagnostic commands

`getMeterSupport` (which meter scales each endpoint reports), `getParam` / `setParam` (configuration parameters), `createChildren` (also run by Configure) creates missing children.

## License

Apache License 2.0. See [LICENSE](../../LICENSE).
