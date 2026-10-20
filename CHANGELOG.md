# Changelog

## v1.7 — 2026-10-19
- **Fix:** `setHeatMode()` no longer sends `on:true` along with `opMode`. On `heat0`, `on` is a manual activation of the solar pump, which stayed forced until the F17 timeout (360 min), including at night. Now only `opMode` is sent; `on:false` goes along only for `Off`.
- **Fix:** solar energy (`apoioSolar*`) is now counted from `heat0.on`, meaning the pump is actually running, instead of `opMode == 1`. The solar channel stays in Automático, so it never accumulated before. The other channels still count by `opMode`.
- `importUrl` in the metadata, so the driver can be updated from Hubitat's "Import" button.

## v1.6 — 2026-10-16
- **Fix:** equality guard on the energy child devices. `Generic Component Switch` does not declare `power`/`energy`, so `currentValue()` always returned null and every frame produced repeated events (Maker API flood). The last published value is now cached in the parent's `state`.
- "Conectado." log after a successful connect; removed the duplicated "Tholz TCP:" log prefix.

## v1.5 — 2026-06-22
- Removed `statusHtml` (too heavy for Maker API).
- Equality guards on energy attributes; temperature throttle (configurable, default 0.5 °C).
- Fix: `refreshLoop` now always reads the interval from settings.

## v1.4 — 2026-06-04
- Energy monitoring per channel (solar, gas, electric, recirculation) from configurable nominal power.
- Child devices per channel with `switch`, `power`, `energy`; `resetApoio*Energy` commands.
- `setApoioSolar/Gas/Eletrico`, `setRecirculacaoBarrilete` commands.

## v1.3 — 2025-11-16
- Connection over `rawSocket` (port 4000); `getDevice`/`setDevice`.
- Child devices for outputs; `heat0` attributes; heat modes Off/Ligado/Automático/Econômico.
