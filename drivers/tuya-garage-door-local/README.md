# Tuya Garage Door Opener (Wi-Fi) Local — Hubitat driver

[![Buy me a beer](https://img.shields.io/badge/Buy%20me%20a%20beer-PayPal-f5a623?logo=paypal&logoColor=white)](https://www.paypal.com/donate/?business=rngarcia%40gmail.com&currency_code=USD)

Local driver (port 6668, protocols 3.3, 3.4 and 3.5, no Tuya Cloud) for Tuya Wi-Fi garage door, gate and skylight openers (Tuya category `ckmkzq`, "Garage Door Opener"). It reports the door state, sends the open/close pulse and has one option to invert the state for modules that report it swapped.

> Formerly **PPA Connect Smart (Tuya Garage Door) Local** (v1.0.0). Renamed in v1.1.0 because it is not specific to PPA: any `ckmkzq` opener with DP 1 (pulse) and DP 3 (contact) should work. If you installed v1.0.0, change your devices to the new driver type (Device → Type) after updating.

## Tested with

| Device | Protocol | Status |
|---|---|---|
| PPA Connect Smart module on a PPA Agility controller (skylight), product ID `acuik4cpfk85ngao` | 3.4 | Working on a real device (state and inversion confirmed on a Hubitat hub); reports the state swapped, so **Inverter estado** stays on |
| Automatizador TSI (Garen) gate opener, product ID `ceej8bhafaak8chw` | 3.3 | Working on a real device (state from DP 3); **Inverter estado** off |

Pick the protocol version your device reports in the TinyTuya scan.

## Data points

| DP | Code | Use |
|---|---|---|
| 1 | `switch_1` | The pulse (open/close toggle) |
| 3 | `doorcontact_state` | Contact: `true` = open on most devices |
| 10 | `door_sensor_state` | opened / closed / opening / closing, when the device has it (takes priority over DP 3) |
| 2, 5 | `countdown_1`, `countdown_alarm` | Countdowns, when present |
| 4 | `tr_timecon` | Travel time, 30–120 s, when present |

Devices with only DP 1 and DP 3 report just open and closed; there is no `opening`/`closing`, and the travel-time and countdown attributes stay empty.

## Commands

Most of these openers are a single-pulse (toggle) input, so there are no separate open and close commands on the device. `pulse()` always sends the pulse. `open()` and `close()` send it only when the current state is not already the target, unless **Always send the pulse** is on. `setTravelTime` accepts 30–120 s and only applies to devices with DP 4.

## Invert state

Some modules report the state swapped: `closed` while the door is open. The single preference **Inverter estado** swaps `opened`/`closed` and also `opening`/`closing`. It is **on by default** because the first device needed it; turn it off if your device reports correctly. The `opening`/`closing` mapping has not been validated on a moving door yet.

## Requirements

- A fixed IP for the module (DHCP reservation on your router).
- The device ID, local key and protocol version. Get them with [TinyTuya](https://github.com/jasonacox/tinytuya): `python -m tinytuya wizard`. Pair the module in the Tuya / Smart Life app first; the local key changes every time it is re-paired.
- Most of these modules accept one local connection at a time, so close other local Tuya clients while this driver keeps its connection open. Home Assistant's Tuya integration uses the cloud and does not conflict.

## Installation

**Hubitat Package Manager:** Install → Search by Keywords, search for `Tuya Garage Door Opener`. Or Install → From a URL with:
`https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/tuya-garage-door-local/packageManifest.json`

**Manual:** Drivers Code → New Driver → Import, paste `https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/tuya-garage-door-local/tuya-garage-door-local.groovy` and Save.

## Notes

- Hubitat's sandbox does not allow `javax.crypto.spec.GCMParameterSpec`, so AES-GCM for protocol 3.5 is implemented in the driver on top of AES-ECB.
- Protocol implementation based on [TinyTuya](https://github.com/jasonacox/tinytuya) (MIT), shared with the Tuya Tank Level Monitor (Wi-Fi) Local driver.

## Support

If this driver is useful to you, you can [buy me a beer](https://www.paypal.com/donate/?business=rngarcia%40gmail.com&currency_code=USD) 🍺.

## License

Apache License 2.0. See [LICENSE](../../LICENSE).
