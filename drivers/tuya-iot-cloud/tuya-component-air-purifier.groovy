/**
 *  Generic Component Air Purifier (Tuya) — Hubitat Driver
 *  Versão: 1.0.0 — Build: 2026-10-21
 *  Autor: Ricardo Nogueira Garcia
 *  Repositório: https://github.com/rngarcia/hubitat
 *  Gostou? Buy me a beer: https://www.paypal.com/donate/?business=rngarcia%40gmail.com&currency_code=USD
 *
 *  Changelog:
 *  v1.0.0 (2026-10-21) - RNG
 *    - Publicado em rngarcia/hubitat: cabeçalho padronizado, namespace "rng" e importUrl
 *    - Primeira versão publicada
 *  (histórico anterior no bloco de comentário abaixo)
 */
/**
 * Generic Component Air Purifier (for Tuya Cloud parent)
 * Namespace MUST be: rng
 */
metadata {
    definition(name: "Generic Component Air Purifier (Tuya)", namespace: "rng", author: "Ricardo Nogueira Garcia",
        importUrl: "https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/tuya-iot-cloud/tuya-component-air-purifier.groovy"
    ) {
        capability "Switch"
        capability "Refresh"
        capability "FanControl"
        capability "Sensor"

        // attributes from parent events
        attribute "airQuality", "string"     // excellent/good/fair/poor (ROPO: great->excellent)
        attribute "filterLife", "number"     // %
        attribute "filterStatus", "string"   // normal/check/replace
        attribute "uv", "string"             // on/off
        attribute "filterReset", "string"    // true/false
        attribute "countdownSet", "string"   // cancel/...
        attribute "countdownLeft", "number"  // number
        attribute "fault", "string"          // ok / fault(n)
    }
}

void parse(List<Map> events) {
    events?.each { e ->
        if (e?.name) sendEvent(e)
    }
}

void on()      { parent?.componentOn(device) }
void off()     { parent?.componentOff(device) }
void refresh() { parent?.componentRefresh(device) }

void setSpeed(String speed) {
    // supports: off, on, auto, low, medium-low, medium, medium-high, high
    parent?.componentSetSpeed(device, speed)
}

// Convenience commands (optional)
void uvOn()  { sendCmd('uv', true) }
void uvOff() { sendCmd('uv', false) }

void resetFilter() { sendCmd('filter_reset', true) }

void cancelCountdown() { sendCmd('countdown_set', 'cancel') }

private void sendCmd(String code, Object value) {
    parent?.tuyaSendDeviceCommandsAsync(device.getDataValue('id'), ['code': code, 'value': value])
}
