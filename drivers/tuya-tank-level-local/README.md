# Tuya Tank Level Monitor (Wi-Fi) Local — Hubitat driver

[![Buy me a beer](https://img.shields.io/badge/Buy%20me%20a%20beer-PayPal-f5a623?logo=paypal&logoColor=white)](https://www.paypal.com/donate/?business=rngarcia%40gmail.com&currency_code=USD)

Local-protocol driver (port 6668, AES-128-ECB, Tuya protocol 3.1/3.3/3.4) for Tuya Wi-Fi ultrasonic tank level monitors, with no Tuya Cloud. Optional volume calculation for rectangular and vertical cylindrical tanks.

## Requirements

The device IP, device ID and local key, which you can get with [TinyTuya](https://github.com/jasonacox/tinytuya) (`python -m tinytuya wizard`). The local key changes every time the device is re-paired in the Tuya / Smart Life app.

## Installation

**Hubitat Package Manager:** Install → Search by Keywords, search for `Tuya Tank Level Monitor (Wi-Fi) Local`. Or Install → From a URL with:
`https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/tuya-tank-level-local/packageManifest.json`

**Manual:** Drivers Code → New Driver → Import, paste `https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/tuya-tank-level-local/tuya-tank-level-local.groovy` and Save. Later updates use the same **Import** button.

## Support

If this driver is useful to you, you can [buy me a beer](https://www.paypal.com/donate/?business=rngarcia%40gmail.com&currency_code=USD) 🍺.

## License

Apache License 2.0. See [LICENSE](../../LICENSE).
