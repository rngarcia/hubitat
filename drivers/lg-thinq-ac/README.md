# LG ThinQ Connect Air Conditioner — Hubitat driver

Extended version of the **ThinQ Connect Air Conditioner** driver by Ionut Turturica ([jonozzz/hubitat-thinqconnect](https://github.com/jonozzz/hubitat-thinqconnect)), for LG air conditioners on the official LG ThinQ Connect API: operating mode, target temperature, fan speed, vertical swing and display light, mapped to Hubitat thermostat capabilities.

## Installation

**Hubitat Package Manager:** Install → Search by Keywords, search for `LG ThinQ Connect Air Conditioner`. Or Install → From a URL with:
`https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/lg-thinq-ac/packageManifest.json`

**Manual:** Drivers Code → New Driver → Import, paste `https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/lg-thinq-ac/lg-thinq-ac.groovy` and Save. Later updates use the same **Import** button.

## Setup

This is a **child driver**: it does not talk to LG on its own. It needs the **LG ThinQ Connect Integration** app by Ionut Turturica, installable from HPM, which handles the PAT token, MQTT and device discovery. Install that first, then switch the air conditioner device's type to *ThinQ Connect Air Conditioner 2*.

## License

Apache License 2.0, the same terms the original follows. See [LICENSE](../../LICENSE).
