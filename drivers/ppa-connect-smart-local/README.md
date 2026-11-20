# PPA Connect Smart (Tuya Garage Door) Local — Hubitat driver

[![Buy me a beer](https://img.shields.io/badge/Buy%20me%20a%20beer-PayPal-f5a623?logo=paypal&logoColor=white)](https://www.paypal.com/donate/?business=rngarcia%40gmail.com&currency_code=USD)

Local driver (port 6668, protocols 3.3, 3.4 and 3.5, no Tuya Cloud) for the PPA Connect Smart Wi-Fi module, a Tuya garage door opener (category `ckmkzq`, product ID `acuik4cpfk85ngao`) that plugs into the PROG connector of PPA Agility controllers. Built for a skylight, it works for any door or gate driven by that module.

## Tested with

| Device | Protocol | Status |
|---|---|---|
| PPA Connect Smart on a PPA Agility controller (skylight) | 3.4 | Working on a real device (state and inversion confirmed on a Hubitat hub) |

Data points used: 1 (`switch_1`, the pulse), 2 (`countdown_1`), 3 (`doorcontact_state`), 4 (`tr_timecon`, travel time 30–120 s), 5 (`countdown_alarm`) and 10 (`door_sensor_state`: opened, closed, opening, closing). DP 10 takes priority; DP 3 is used when DP 10 is missing.

## Commands

The module is a single-pulse (toggle) input, so there are no separate open and close commands on the device. `pulse()` always sends the pulse. `open()` and `close()` send it only when the current state is not already the target, unless **Always send the pulse** is on. `setTravelTime` accepts 30–120 s.

## Invert state

The module on the author's skylight reports the state swapped: `closed` while the glass is open (the controller's own LEDs are correct). The single preference **Inverter estado** (on by default) swaps `opened`/`closed` and also `opening`/`closing`. Turn it off if your module reports correctly. The `opening`/`closing` mapping has not been validated yet.

## Requirements

- A fixed IP for the module (DHCP reservation on your router).
- The device ID, local key and protocol version. Get them with [TinyTuya](https://github.com/jasonacox/tinytuya): `python -m tinytuya wizard`. Pair the module in the Tuya / Smart Life app first; the local key changes every time it is re-paired.
- Close other local Tuya clients while this driver keeps its connection open.

## Installation

**Hubitat Package Manager:** Install → Search by Keywords, search for `PPA Connect Smart`. Or Install → From a URL with:
`https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/ppa-connect-smart-local/packageManifest.json`

**Manual:** Drivers Code → New Driver → Import, paste `https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/ppa-connect-smart-local/ppa-connect-smart-local.groovy` and Save.

## Notes

- Hubitat's sandbox does not allow `javax.crypto.spec.GCMParameterSpec`, so AES-GCM for protocol 3.5 is implemented in the driver on top of AES-ECB.
- Protocol implementation based on [TinyTuya](https://github.com/jasonacox/tinytuya) (MIT), shared with the Tuya Tank Level Monitor (Wi-Fi) Local driver.

## Support

If this driver is useful to you, you can [buy me a beer](https://www.paypal.com/donate/?business=rngarcia%40gmail.com&currency_code=USD) 🍺.

## License

Apache License 2.0. See [LICENSE](../../LICENSE).
