# Tholz SmartHeat — Hubitat driver

Hubitat Elevation driver for the **Tholz SmartHeat 2nd generation** solar water-heater controller (e.g. TSZ1505N). It talks to the controller **locally over TCP** (port 4000, JSON frames). No cloud is needed.

Based on the original driver by VH / TRATO ([hhorigian](https://github.com/hhorigian)). Extended and maintained by Ricardo Nogueira Garcia.

> 🇧🇷 Driver para Hubitat do controlador de aquecimento solar Tholz SmartHeat 2ª geração, com comunicação local via TCP. Inclui medição estimada de consumo de energia por apoio (bomba solar, resistência elétrica, gás e recirculação), integrável ao Painel de Energia do Home Assistant.

## Features

- Temperatures: collector (T1), reservoir (T2), consumption (T3), recirculation (T4), plus a main `temperature` attribute (T3 → T2 → T1)
- Heat mode for the main channel: `Off`, `Ligado`, `Automático`, `Econômico` (ECO)
- Heat setpoint, ±1 °C buttons, mode toggle
- Turn each channel on/off: solar, gas, electric backup, recirculation (barrilete)
- **Energy estimate per channel**: `power` (W) and `energy` (kWh), from on-time × nominal power set in Preferences
- One child device per channel (`Generic Component Switch`) carrying `switch`, `power` and `energy`, so each one can appear on its own in Home Assistant through Maker API
- Child devices for the controller's physical outputs, when the firmware reports them
- Equality guards and a temperature threshold, to keep event traffic low (Maker API friendly)
- Auto-reconnect when the TCP socket drops

## Installation

### Hubitat Package Manager (HPM)

In HPM, choose **Install → Search by Keywords** and search for `Tholz`. Or use **From a URL** with:
`https://raw.githubusercontent.com/rngarcia/hubitat-tholz-smartheat/main/packageManifest.json`

Then create the device and set the preferences as described in steps 2–5 below.

### Manual

1. **Drivers Code → New Driver → Import** and paste:
   `https://raw.githubusercontent.com/rngarcia/hubitat-tholz-smartheat/main/hubitat_Tholz_SmartHeat.groovy`
   Then **Save**.
2. **Devices → Add Device → Virtual** and choose **Tholz SmartHeat**.
3. In **Preferences**, enter the controller IP and port (default `4000`).
4. Enter the **nominal power (W)** of each channel you want to measure. Read it from the equipment label: solar pump, electric heating element, recirculation pump.
5. **Save Preferences**. The driver connects, polls every `autoRefreshSecs` (default 300 s) and also processes the frames the controller pushes on its own.

To update later, open the driver code and click **Import**. The `importUrl` already points to this repository.

## Channel mapping (protocol)

| Channel | `type` | Function | Energy counted by |
|---|---|---|---|
| `heat0` | 3 | Solar (collector ↔ tank pump) | `heat0.on`: pump actually running |
| `heat1` | 4 | Gas backup | `opMode == 1` |
| `heat2` | 5 | Electric backup (heating element) | `opMode == 1` |
| `heat3` | 6 | Recirculation (barrilete) | `opMode == 1` |

Field meanings, as observed:
- `opMode`: 0 Off, 1 Ligado (forced on), 2 Automático, 3 Econômico
- `on`: the channel is actively driven. For `heat0` this is the solar pump; it is what the Tholz app reports under "Uso das saídas".
- `onAut`: a schedule window is active. Seen on the electric backup; it does not mean the element is heating.
- Temperatures are in tenths of °C. `0xAAAA` (43690) means the sensor is invalid or missing.

## Home Assistant Energy dashboard

The energy entities come in through the Hubitat integration / Maker API. Add the energy child devices to Maker API **manually**, since it does not pick up children created dynamically. Then set the classes in `customize.yaml`:

```yaml
sensor.boiler_apoio_solar_energy:
  device_class: energy
  state_class: total_increasing
  unit_of_measurement: kWh
sensor.boiler_apoio_solar_power:
  device_class: power
  state_class: measurement
  unit_of_measurement: W
# repeat for apoio_eletrico, apoio_gas, recirculacao_barrilete
```

If the entity already has history with other metadata, go to **Developer Tools → Statistics → Fix issue → Update metadata** before picking it in the Energy dashboard.

## Known limitations

- Energy is an **estimate**: on-time × configured power, not a measurement.
- The local protocol does not expose relay states. For the electric backup, `opMode == 1` covers the whole time the channel is set to Ligado, including periods when the thermostat is already satisfied, so it can **overestimate**. For real figures, put a power meter on that circuit.
- Energy accumulated while the hub is down or the socket is disconnected is posted all at once when the connection comes back. Total is right, the hourly distribution is not.

## Caution with automations

Before v1.7, `setHeatMode()` also sent `on:true` for `heat0`. To the controller, that is a **manual activation of the solar pump**. It stayed forced on until the F17 timeout (360 min by default), even at night, when it cools the tank through the collectors. A rule calling `setHeatMode('Automático')` on every hub Mode change caused up to ~18 h/day of pump runtime. Fixed in v1.7: `setHeatMode()` now sends only the `opMode`.

## Changelog

See [CHANGELOG.md](CHANGELOG.md).

## License

Apache License 2.0. See [LICENSE](LICENSE).
