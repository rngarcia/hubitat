/**
 *  Heiman HS2WD-E Siren — Hubitat Driver
 *  Versão: 1.0.3 — Build: 2026-10-21
 *  Autor: Ricardo Nogueira Garcia
 *  Repositório: https://github.com/rngarcia/hubitat
 *  Gostou? Buy me a beer: https://www.paypal.com/donate/?business=rngarcia%40gmail.com&currency_code=USD
 *
 *  Changelog:
 *  v1.0.3 (2026-10-21) - RNG
 *    - Publicado em rngarcia/hubitat: cabeçalho padronizado, namespace "rng" e importUrl
 *  (histórico anterior no bloco de comentário abaixo)
 */
/**
 *  Heiman HS2WD-E Siren - Hubitat
 *
 *  Namespace: rng
 *  Author: Ricardo Nogueira Garcia
 *  Version: 1.0.2
 *  Date: 2026-05-21
 */

import hubitat.zigbee.zcl.DataType

metadata {
    definition(
        name: "Heiman HS2WD-E Siren",
        namespace: "rng",
        author: "Ricardo Nogueira Garcia",
        importUrl: "https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/heiman-hs2wd-e-siren/heiman-hs2wd-e-siren.groovy"
    ) {
        capability "Alarm"
        capability "Switch"
        capability "Actuator"
        capability "Battery"
        capability "Refresh"
        capability "Configuration"

        attribute "maxDuration", "number"
        attribute "hwVer", "string"
        attribute "strobeSupported", "string"

        fingerprint profileId: "0104",
            endpointId: "01",
            inClusters: "0000,0001,0003,0004,0009,0500,0502",
            outClusters: "0003,0019",
            manufacturer: "Heiman",
            model: "WarningDevice",
            deviceJoinName: "Heiman HS2WD-E Siren"
    }

    preferences {
        input name: "maxDuration", type: "number",
            title: "Max duration in seconds",
            range: "1..1800",
            defaultValue: 240,
            required: true

        input name: "infoLogging", type: "bool",
            title: "Enable info logging",
            defaultValue: true

        input name: "debugLogging", type: "bool",
            title: "Enable debug logging",
            defaultValue: false
    }
}

def installed() {
    sendEvent(name: "alarm", value: "off")
    sendEvent(name: "switch", value: "off")
    sendEvent(name: "strobeSupported", value: "only_with_alarm_flash")
    configure()
}

def updated() {
    logInfo "Updated - maxDuration=${getDuration()}"
    sendEvent(name: "strobeSupported", value: "only_with_alarm_flash")

    if (debugLogging) {
        runIn(1800, "logsOff")
    }

    configure()
}

def configure() {
    logInfo "Configuring"

    def cmds = []
    cmds += zigbee.writeAttribute(0x0502, 0x0000, DataType.UINT16, getDuration())
    cmds += zigbee.readAttribute(0x0502, 0x0000)
    cmds += refresh()

    logDebug "configure cmds: ${cmds}"
    return cmds
}

def refresh() {
    logInfo "Refreshing"

    def cmds = []
    cmds += zigbee.readAttribute(0x0000, 0x0003)
    cmds += zigbee.readAttribute(0x0001, 0x0021)
    cmds += zigbee.readAttribute(0x0502, 0x0000)

    logDebug "refresh cmds: ${cmds}"
    return cmds
}

def parse(String description) {
    logDebug "Parsing: ${description}"

    if (!description) return null

    Map descMap = zigbee.parseDescriptionAsMap(description)
    logDebug "descMap: ${descMap}"

    if (descMap?.cluster == "0000" && descMap?.attrId == "0003") {
        sendEvent(name: "hwVer", value: descMap.value)
        return
    }

    if (descMap?.cluster == "0001" && descMap?.attrId == "0021") {
        Integer rawValue = Integer.parseInt(descMap.value, 16)
        Integer batteryValue = Math.max(0, Math.min(100, Math.round(rawValue / 2)))
        sendEvent(name: "battery", value: batteryValue, unit: "%")
        return
    }

    if (descMap?.cluster == "0502" && descMap?.attrId == "0000") {
        Integer durationValue = parseLittleEndian16(descMap.value)
        sendEvent(name: "maxDuration", value: durationValue)
        return
    }

    return null
}

def on() {
    return both()
}

def off() {
    logInfo "Turning off"

    sendEvent(name: "alarm", value: "off", type: "digital")
    sendEvent(name: "switch", value: "off", type: "digital")

    return zigbee.command(0x0502, 0x00, "00", "0000", "00", "00")
}

def siren() {
    logInfo "Turning siren on"

    sendEvent(name: "alarm", value: "siren", type: "digital")
    sendEvent(name: "switch", value: "on", type: "digital")

    return startWarning("03", "00")
}

def both() {
    logInfo "Turning siren + strobe flash on"

    sendEvent(name: "alarm", value: "both", type: "digital")
    sendEvent(name: "switch", value: "on", type: "digital")

    return startWarning("13", "0A")
}

def strobe() {
    logInfo "Strobe-only is not supported. Running siren + strobe flash."

    sendEvent(name: "alarm", value: "both", type: "digital")
    sendEvent(name: "switch", value: "on", type: "digital")

    return startWarning("13", "0A")
}

private startWarning(String warningByte, String strobeDutyCycle) {
    return zigbee.command(
        0x0502,
        0x00,
        warningByte,
        DataType.pack(getDuration(), DataType.UINT16),
        "00",
        strobeDutyCycle
    )
}

private Integer getDuration() {
    Integer duration = 240

    try {
        duration = (settings.maxDuration ?: 240) as Integer
    } catch (Exception ignored) {
        duration = 240
    }

    return Math.max(1, Math.min(1800, duration))
}

private Integer parseLittleEndian16(String value) {
    if (!value || value.length() < 4) return 0
    return Integer.parseInt(value.substring(2, 4) + value.substring(0, 2), 16)
}

def logsOff() {
    device.updateSetting("debugLogging", [value: "false", type: "bool"])
    log.info "${device.displayName}: Debug logging disabled automatically"
}

private void logDebug(String msg) {
    if (settings.debugLogging) log.debug "${device.displayName}: ${msg}"
}

private void logInfo(String msg) {
    if (settings.infoLogging != false) log.info "${device.displayName}: ${msg}"
}