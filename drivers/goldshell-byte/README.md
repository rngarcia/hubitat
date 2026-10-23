# Goldshell BYTE Miner — Hubitat driver

[![Buy me a beer](https://img.shields.io/badge/Buy%20me%20a%20beer-PayPal-f5a623?logo=paypal&logoColor=white)](https://www.paypal.com/donate/?business=rngarcia%40gmail.com&currency_code=USD)

Local driver for the Goldshell BYTE miner (MCB panel + CGMiner API). The parent device reports system status and creates one child device per hash card with its metrics, and exposes LED, idle mode and per-card power plan control.

## Installation

**Hubitat Package Manager:** Install → Search by Keywords, search for `Goldshell BYTE Miner`. Or Install → From a URL with:
`https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/goldshell-byte/packageManifest.json`

**Manual:** Drivers Code → New Driver → Import, paste `https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/goldshell-byte/goldshell-byte.groovy` and Save. Later updates use the same **Import** button.

## Setup

Miner IP in Preferences. Then run the `setBearerToken` command with the panel's JWT token (token only, without `Bearer `); it is kept in state to avoid the preference length limit.

## Credits

Written from scratch against the miner's own web panel API (`/mcb/status`, `/mcb/setting`, `/mcb/cgminer?cgminercmd=devs`). The same API is also used by [pyasic](https://github.com/UpstreamData/pyasic), a useful reference for other Goldshell models.

## Support

If this driver is useful to you, you can [buy me a beer](https://www.paypal.com/donate/?business=rngarcia%40gmail.com&currency_code=USD) 🍺.

## License

Apache License 2.0. See [LICENSE](../../LICENSE).
