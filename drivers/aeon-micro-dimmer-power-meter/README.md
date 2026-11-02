# Aeon Micro Dimmer with Power Meter — Hubitat driver

Z-Wave driver for the Aeon Labs Micro Dimmer. It adds **power (W)** and **energy (kWh)** readings, which the built-in "Aeon Micro Dimmer" driver does not expose (it only has Switch and Level).

The device advertises the Z-Wave Meter command class (0x32) and answers meter requests, so the hardware measures; only the driver was missing.

## What it does

- On/off and dimming (Switch, Switch Level).
- `power` (W) and `energy` (kWh) attributes (Power Meter, Energy Meter).
- Energy comes from the device's automatic reports (default every 15 minutes).
- Power is **polled** (default every 1 minute). The device's own automatic power reports turned out to be irregular, so the driver asks for it. Set the poll to 0 to turn it off.
- Wall switch changes are reported to the hub, so `switch` and `level` follow the physical switch.
- **Configure** (and saving the preferences) sets the hub association and the report parameters, then reads each parameter back and logs it, so you can see what the device accepted.

## Tested on

Two units with the same identification, shown in Hubitat as `Aeon Micro Dimmer`: Z-Wave manufacturer `0x0086`, device type `0x0003`, device id `0x0013`, in-clusters `0x26 0x32 0x27 0x2C 0x2B 0x70 0x85 0x72 0x86`. Loads: a ~23 W light on the first unit and a ~6.5 W load on the second. Most of the testing was done on the first one.

Verified:

- Power and energy readings match the load (about 23 W on the first unit and 6.5 W on the second; kWh grows as expected).
- Parameters 80, 90, 91, 92, 101, 102, 111 and 112 are accepted and read back with the stored value, on both units.
- Saving the preferences re-applies the configuration (both units).
- Energy reports arrive every 900 s, matching parameter 112 (first unit).
- With parameter 80 = 2, switching the light at the wall updates `switch` in Hubitat (first unit).
- Switch and level changes are logged as "was turned on/off" and "level is N%".

Not verified:

- The parameter numbers follow the Aeon "Micro" family and were **not confirmed against the manual of this model**.
- Automatic power reports (parameters 91, 92, 101, 111) did not behave regularly, which is why polling exists.
- Other Aeon dimmers or firmware versions, and loads much larger than ~23 W.
- The first install path (configuration applied about 10 seconds after inclusion) was not exercised; use **Configure** if the log shows no parameter read-back.

## Installation

**Hubitat Package Manager:** Install → Search by Keywords, search for `Aeon Micro Dimmer with Power Meter`. Or Install → From a URL with:
`https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/aeon-micro-dimmer-power-meter/packageManifest.json`

**Manual:** Drivers Code → New Driver → Import, paste `https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/aeon-micro-dimmer-power-meter/aeon-micro-dimmer-power-meter.groovy` and Save. Later updates use the same **Import** button.

Then, on the device page, set **Type** to `Aeon Micro Dimmer with Power Meter` and Save Device, press **Configure**, and check the log for the parameter read-back lines (they are logged at `info`, which is the default **Log Level**; if you lowered it to `warn` or `error` you will not see them). The Z-Wave pairing is kept when you switch the driver.

## Preferences

| Preference | Parameter | Default | Notes |
|---|---|---|---|
| Log Level | none (driver) | info | `debug` also logs every poll and meter report |
| Poll power every N minutes | none (driver) | 1 | 0 turns polling off |
| Report wall switch changes | 80 | on (Basic Report) | off sends nothing |
| Report power when it changes by at least (W) | 91 | 2 | |
| or by at least (%) | 92 | 5 | |
| Power report interval (s) | 111 | 300 | |
| Energy report interval (s) | 112 | 900 | |

Parameters 90, 101 and 102 are set by the driver (threshold reporting on, group 1 = watts, group 2 = kWh).

Polling sends one Z-Wave message per interval to this device. Raise the interval if you want less traffic.

## Commands

`on`, `off`, `setLevel`, `refresh` (reads level, W and kWh), `configure`, and the advanced `setParam` / `getParam` for experimenting with device parameters.

## License

Apache License 2.0. See [LICENSE](../../LICENSE).
