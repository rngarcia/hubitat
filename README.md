# Hubitat — drivers by rngarcia

Hubitat Elevation drivers and apps by Ricardo Nogueira Garcia, in one repository and one Hubitat Package Manager (HPM) index.

[![Buy me a beer](https://img.shields.io/badge/Buy%20me%20a%20beer-PayPal-f5a623?logo=paypal&logoColor=white)](https://www.paypal.com/donate/?business=rngarcia%40gmail.com&currency_code=USD)

> 🇧🇷 Drivers e apps para Hubitat Elevation, organizados por tema, todos instaláveis pelo Hubitat Package Manager a partir de um único índice.

## Install with HPM

Every package here is listed in HPM: **Install → Search by Keywords**, then search for the package name.

To install straight from this repository, use **Install → From a URL** with the manifest link in the table below.

## Packages

### Energia e solar
| Package | Type | Connection | Manifest |
|---|---|---|---|
| [FusionSolar OpenAPI](drivers/fusionsolar) | Driver | Cloud | [packageManifest.json](https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/fusionsolar/packageManifest.json) |
| [Goldshell BYTE Miner](drivers/goldshell-byte) | Driver (parent + children) | LAN | [packageManifest.json](https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/goldshell-byte/packageManifest.json) |

### Água e aquecimento
| Package | Type | Connection | Manifest |
|---|---|---|---|
| [Tholz SmartHeat](drivers/tholz-smartheat) | Driver | LAN (TCP) | [packageManifest.json](https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/tholz-smartheat/packageManifest.json) |

### Climatização
| Package | Type | Connection | Manifest |
|---|---|---|---|
| [LG ThinQ Connect Air Conditioner](drivers/lg-thinq-ac) | Child driver | Cloud | Not in HPM yet: needs its parent app |

### Integrações cloud
| Package | Type | Connection | Manifest |
|---|---|---|---|
| [Tuya IoT Platform (Cloud)](drivers/tuya-iot-cloud) | Driver (parent) | Cloud | [packageManifest.json](https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/tuya-iot-cloud/packageManifest.json) |

## Layout

```
repository.json              HPM index (generated, do not edit by hand)
drivers/<package>/
  <package>.groovy           driver code; importUrl points here
  packageManifest.json       HPM manifest + "x-catalog" block (theme, order, description, packageId)
  README.md, CHANGELOG.md    optional
apps/<package>/              same layout, for Hubitat apps
tools/build_repository.py    rebuilds repository.json from the manifests, ordered by theme
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

## Support

If these drivers are useful to you, you can [buy me a beer](https://www.paypal.com/donate/?business=rngarcia%40gmail.com&currency_code=USD) 🍺. The same link appears in HPM under the author's name.

Tips go toward the drivers I wrote or mostly rewrote (FusionSolar, Goldshell BYTE, Tholz SmartHeat). The Tuya IoT Cloud driver is Jonathan Bradshaw's work with my patches; please consider supporting him directly.

## License

Apache License 2.0 (see [LICENSE](LICENSE)), unless a file's header says otherwise. `drivers/tuya-iot-cloud` is derived from Jonathan Bradshaw's MIT-licensed driver and stays under MIT.
