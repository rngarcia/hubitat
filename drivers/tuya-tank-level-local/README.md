# Tuya Tank Level Monitor (Wi-Fi) Local — Hubitat driver

[![Buy me a beer](https://img.shields.io/badge/Buy%20me%20a%20beer-PayPal-f5a623?logo=paypal&logoColor=white)](https://www.paypal.com/donate/?business=rngarcia%40gmail.com&currency_code=USD)

Local driver for Tuya Wi-Fi ultrasonic tank level sensors (port 6668, protocols 3.3, 3.4 and 3.5), with no Tuya Cloud. Level, water column and alarm state, plus optional volume calculation.

## Tested with

| Device | Protocol | Status |
|---|---|---|
| EPT ultrasonic sensor 3 m (product ID `npfinze0zsvta7aj`) | 3.5 | Working on a real device |
| Protocols 3.3 and 3.4 | | Tested only against a simulated device built with TinyTuya |

Data points used: 1 (state), 2 (water column, cm), 7 / 8 (upper / lower alarm, %), 19 (installation height), 21 (maximum depth) and 22 (level, %). On that firmware, 7, 8, 19 and 21 only show up in the status after they are written once, for example with the `set...` commands.

Heights follow the Tuya app: **installationHeight** is the distance from the sensor to the bottom of the tank, **liquidDepthMax** the distance from the sensor to the maximum water level, so the usable height is `installationHeight - liquidDepthMax`. To calibrate, fill the tank and run `calibSetDepthMaxFromCurrentLevel`.

## Requirements

- A fixed IP for the sensor (DHCP reservation on your router).
- The device ID, local key and protocol version. Get them with [TinyTuya](https://github.com/jasonacox/tinytuya): `python -m tinytuya wizard`, then `python -m tinytuya scan`. If the sensor does not show up in the scan, try protocol 3.5.
- The local key changes every time the sensor is re-paired in the Tuya / Smart Life app.

## Installation

**Hubitat Package Manager:** Install → Search by Keywords, search for `Tuya Tank Level Monitor (Wi-Fi) Local`. Or Install → From a URL with:
`https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/tuya-tank-level-local/packageManifest.json`

**Manual:** Drivers Code → New Driver → Import, paste `https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/tuya-tank-level-local/tuya-tank-level-local.groovy` and Save. Later updates use the same **Import** button.

## Notes

- Hubitat's sandbox does not allow `javax.crypto.spec.GCMParameterSpec`, so AES-GCM for protocol 3.5 is implemented in the driver on top of AES-ECB.
- With **Keep connection open** on, the driver sends a heartbeat every 20 s and gets changes as the sensor pushes them. Tuya sensors usually accept a single local connection, so close other local clients (TinyTuya, Home Assistant local integrations) while this driver is connected.
- Protocol implementation based on [TinyTuya](https://github.com/jasonacox/tinytuya) (MIT).

## Support

If this driver is useful to you, you can [buy me a beer](https://www.paypal.com/donate/?business=rngarcia%40gmail.com&currency_code=USD) 🍺.

## License

Apache License 2.0. See [LICENSE](../../LICENSE).
