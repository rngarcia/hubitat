# Hubitat — drivers by rngarcia

Hubitat Elevation drivers and apps by Ricardo Nogueira Garcia, in one repository and one Hubitat Package Manager (HPM) index.

[![Buy me a beer](https://img.shields.io/badge/Buy%20me%20a%20beer-PayPal-f5a623?logo=paypal&logoColor=white)](https://www.paypal.com/donate/?business=rngarcia%40gmail.com&currency_code=USD)

> 🇧🇷 Drivers e apps para Hubitat Elevation, organizados por tema, todos instaláveis pelo Hubitat Package Manager a partir de um único índice.

## Install with HPM

Every package here is listed in HPM: **Install → Search by Keywords**, then search for the package name.

To install straight from this repository, use **Install → From a URL** with the manifest link in the table below.

## Packages

<!-- PACKAGES:START -->
### Energia e solar
| Package | Version | Drivers | Manifest |
|---|---|---|---|
| [FusionSolar OpenAPI](drivers/fusionsolar) | 0.2.1 | 1 | [packageManifest.json](https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/fusionsolar/packageManifest.json) |
| [Goldshell BYTE Miner](drivers/goldshell-byte) | 3.5.1 | 2 | [packageManifest.json](https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/goldshell-byte/packageManifest.json) |
| [Zemismart SDM02T Energy Meter](drivers/zemismart-sdm02t) | 1.4.2 | 1 | [packageManifest.json](https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/zemismart-sdm02t/packageManifest.json) |
| [FoxESS Cloud OpenAPI](drivers/foxess-cloud) | 0.1.1 | 1 | [packageManifest.json](https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/foxess-cloud/packageManifest.json) |

### Água e aquecimento
| Package | Version | Drivers | Manifest |
|---|---|---|---|
| [Tholz SmartHeat](drivers/tholz-smartheat) | 1.7.1 | 1 | [packageManifest.json](https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/tholz-smartheat/packageManifest.json) |
| [Tuya Tank Level Monitor (Wi-Fi) Local](drivers/tuya-tank-level-local) | 2.0.1 | 1 | [packageManifest.json](https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/tuya-tank-level-local/packageManifest.json) |

### Climatização
| Package | Version | Drivers | Manifest |
|---|---|---|---|
| [Remotec ZXT-120 IR Extender](drivers/remotec-zxt-120) | 0.1.1 | 1 | [packageManifest.json](https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/remotec-zxt-120/packageManifest.json) |

### Iluminação e tomadas
| Package | Version | Drivers | Manifest |
|---|---|---|---|
| [Aeon Smart Dimmer 2nd Edition](drivers/aeon-smart-dimmer-2nd) | 1.0.1 | 1 | [packageManifest.json](https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/aeon-smart-dimmer-2nd/packageManifest.json) |

### Sensores
| Package | Version | Drivers | Manifest |
|---|---|---|---|
| [Aeon Multisensor Gen5](drivers/aeon-multisensor-gen5) | 1.1.1 | 1 | [packageManifest.json](https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/aeon-multisensor-gen5/packageManifest.json) |

### Segurança
| Package | Version | Drivers | Manifest |
|---|---|---|---|
| [Heiman HS2WD-E Siren](drivers/heiman-hs2wd-e-siren) | 1.0.3 | 1 | [packageManifest.json](https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/heiman-hs2wd-e-siren/packageManifest.json) |
| [Hikvision Audible Alarm Trigger (ISAPI)](drivers/hikvision-audible-alarm) | 1.5.1 | 1 | [packageManifest.json](https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/hikvision-audible-alarm/packageManifest.json) |

### Eletrodomésticos
| Package | Version | Drivers | Manifest |
|---|---|---|---|
| [Roomba (Rest980)](drivers/roomba-rest980) | 1.0.1 | 1 | [packageManifest.json](https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/roomba-rest980/packageManifest.json) |

### Integrações cloud
| Package | Version | Drivers | Manifest |
|---|---|---|---|
| [Tuya IoT Platform (Cloud)](drivers/tuya-iot-cloud) | 1.0.5 | 4 | [packageManifest.json](https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/tuya-iot-cloud/packageManifest.json) |

### Not in HPM
- [GiEX Irrigation Valve (sh1btabb)](drivers/giex-irrigation-valve): not maintained; use kkossev's Tuya Zigbee Valve
- [Tuya Zigbee Water Level Sensor](drivers/tuya-zigbee-water-level): not maintained; use kkossev's Tuya Zigbee Tank Level Monitor
<!-- PACKAGES:END -->

## Layout

```
repository.json              HPM index (generated, do not edit by hand)
drivers/<package>/
  <package>.groovy           driver code; importUrl points here
  packageManifest.json       HPM manifest + "x-catalog" block (theme, order, description, packageId)
  README.md, CHANGELOG.md    optional
apps/<package>/              same layout, for Hubitat apps
tools/build_repository.py    rebuilds repository.json and the package tables in this README, ordered by theme
tools/sniffers/              diagnostic drivers for reverse-engineering Tuya DPs (not in HPM)
```

Folders are named by package, not by theme, so raw URLs never change when a package moves to another theme. The theme lives in each manifest's `x-catalog.theme`.

## Adding or updating a package

1. Edit the `.groovy` file. Keep the original author's credit, add yourself as modifier, bump the version and date, and add a changelog line in the header.
2. Bump `version`, `dateReleased` and `releaseNotes` in that package's `packageManifest.json`. HPM only offers an update when `version` changes.
3. For a new package, copy an existing `packageManifest.json`, generate fresh UUIDs for `drivers[].id` and `x-catalog.packageId`, and set its theme.
4. Run `python3 tools/build_repository.py` and commit `repository.json` with the change. CI fails if it is out of date.

Never change an existing `packageId` or driver `id`: HPM uses them to match installed packages.

## Conventions

- Namespace: `rng`
- Every driver declares `importUrl`, so the **Import** button on the driver code page also updates it.
- Header: name, version and build date, original author (when there is one), modifier, changelog.

## Contributions upstream

LG ThinQ air conditioners: I use and contribute to [jonozzz/hubitat-thinqconnect](https://github.com/jonozzz/hubitat-thinqconnect) instead of keeping a separate driver here.

## Support

If these drivers are useful to you, you can [buy me a beer](https://www.paypal.com/donate/?business=rngarcia%40gmail.com&currency_code=USD) 🍺. The same link appears in HPM under the author's name.

Tips go toward the drivers I wrote or mostly rewrote; their headers and READMEs carry the link. Drivers derived from someone else's work (Aeon, ZXT-120) credit the original author and have no tip link. The Tuya IoT Cloud parent driver is Jonathan Bradshaw's work with my patches (the component drivers in that package are mine); please consider supporting him directly.

## License

Apache License 2.0 (see [LICENSE](LICENSE)), unless a file's header says otherwise. `drivers/tuya-iot-cloud` is derived from Jonathan Bradshaw's MIT-licensed driver and stays under MIT.
