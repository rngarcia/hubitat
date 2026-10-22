# LG ThinQ Connect Air Conditioner — Hubitat driver

Cloud driver for LG air conditioners using the official LG ThinQ Connect API: operating mode, target temperature, fan speed, vertical swing and display light, mapped to Hubitat thermostat capabilities.

## Installation

**Hubitat Package Manager:** Install → Search by Keywords, search for `LG ThinQ Connect Air Conditioner`. Or Install → From a URL with:
`https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/lg-thinq-ac/packageManifest.json`

**Manual:** Drivers Code → New Driver → Import, paste `https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/lg-thinq-ac/lg-thinq-ac.groovy` and Save. Later updates use the same **Import** button.

## Setup

This is a **child driver**: it does not talk to LG on its own. A parent app handles the ThinQ Connect account, MQTT and device discovery, and calls this driver through `parent.*`. The parent app is not in this repository yet.

## License

Apache License 2.0. See [LICENSE](../../LICENSE).
