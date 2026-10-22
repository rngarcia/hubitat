# Tuya IoT Platform (Cloud) — Hubitat drivers

Cloud integration for Tuya devices through the Tuya IoT Platform, plus component drivers for devices that the generic components do not cover well.

## Drivers in this package

| Driver | Author | Required |
|---|---|---|
| Tuya IoT Platform (Cloud) | Jonathan Bradshaw, with patches by Ricardo Nogueira Garcia (ROPO and towel-warmer-safe) | Yes |
| Tuya Component Tank Level Monitor (Wi-Fi) | Ricardo Nogueira Garcia | Optional |
| Tuya Component Towel Warmer with Power Monitor | Ricardo Nogueira Garcia | Optional |
| Generic Component Air Purifier (Tuya) | Ricardo Nogueira Garcia | Optional |

The parent creates each Tuya device as a child with a generic driver. To use a component driver from this package, open the child device and change its **Type** to the matching driver.

## Installation

**Hubitat Package Manager:** Install → Search by Keywords, search for `Tuya IoT Platform (Cloud)`, and tick the optional components you need. Or Install → From a URL with:
`https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/tuya-iot-cloud/packageManifest.json`

Then create a virtual device with the **Tuya IoT Platform (Cloud)** driver and fill in your Tuya IoT Platform access ID and secret and your Tuya app account.

## Support

The parent driver is Jonathan Bradshaw's work; please consider supporting him directly. If the component drivers are useful to you, you can [buy me a beer](https://www.paypal.com/donate/?business=rngarcia%40gmail.com&currency_code=USD) 🍺.

## License

The parent driver stays under the MIT License of the original (see its header). The component drivers are under the Apache License 2.0; see [LICENSE](../../LICENSE).
