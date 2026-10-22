# FusionSolar OpenAPI (Cloud) — Hubitat driver

[![Buy me a beer](https://img.shields.io/badge/Buy%20me%20a%20beer-PayPal-f5a623?logo=paypal&logoColor=white)](https://www.paypal.com/donate/?business=rngarcia%40gmail.com&currency_code=USD)

Cloud driver for Huawei FusionSolar using the Northbound OpenAPI. It logs in with the Northbound user and system code, discovers your stations and devices, and polls plant and inverter power and energy.

## Installation

**Hubitat Package Manager:** Install → Search by Keywords, search for `FusionSolar OpenAPI`. Or Install → From a URL with:
`https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/fusionsolar/packageManifest.json`

**Manual:** Drivers Code → New Driver → Import, paste `https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/fusionsolar/fusionsolar.groovy` and Save. Later updates use the same **Import** button.

## Setup

Base URL of your region (e.g. `https://la5.fusionsolar.huawei.com/thirdData`), Northbound user name and system code. Station codes and devIds are optional; leave them empty to discover all. Keep the polling interval generous: the API rate-limits (HTTP 407).

## Support

If this driver is useful to you, you can [buy me a beer](https://www.paypal.com/donate/?business=rngarcia%40gmail.com&currency_code=USD) 🍺.

## License

Apache License 2.0. See [LICENSE](../../LICENSE).
