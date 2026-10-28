/**
 *  Heiman HS2WD-E Siren — Hubitat Driver
 *  Versão: 1.0.5 — Build: 2026-10-28
 *  Autor: Ricardo Nogueira Garcia
 *  Repositório: https://github.com/rngarcia/hubitat
 *  Gostou? Buy me a beer: https://www.paypal.com/donate/?business=rngarcia%40gmail.com&currency_code=USD
 *
 *  Changelog:
 *  v1.0.5 (2026-10-28) - RNG
 *    - Gravação do atributo maxDuration em UINT16 little-endian (antes saía {00F0}, que o aparelho lia como 61440)
 *    - Resposta ao enroll request do IAS Zone (a sirene repetia o pedido a cada ~5 s e gerava um evento lastCheckin a cada vez)
 *    - Strobe testado no aparelho: pisca continuamente durante a duração (não só uma vez);
 *      strobeSupported passa a "supported" e os textos de log foram corrigidos
 *  v1.0.4 (2026-10-28) - RNG
 *    - Strobe corrigido: bit de strobe ligado no Start Warning (0x17 = siren + strobe, 0x04 = strobe-only)
 *    - Payload do Start Warning na ordem do ZCL (info, duração, duty cycle, strobe level);
 *      duração em UINT16 little-endian explícito
 *    - siren() agora usa warning mode burglar (0x13) em vez de 0x03 (mode Stop)
 *    - strobe() agora é strobe-only (o LED pisca uma vez); atributo strobeSupported = flash_once
 *    - Novos comandos healthCheckSiren (chirp curto, OFF delay configurável) e healthCheckStrobe
 *    - alarm/switch voltam para off quando a duração termina (autoOff)
 *    - installed()/updated() enviam os comandos via sendHubCommand (o retorno de updated() não é executado no Hubitat)
 *    - Fingerprint HEIMAN / WarningDevice-EF-3.0 adicionado
 *    - Novo atributo lastCheckin
 *    - Preferência maxDuration limitada a 1..600 s (máximo do atributo segundo o Zigbee2MQTT)
 *    - Parse de maxDuration sem inversão de bytes (parseDescriptionAsMap já entrega o valor ordenado)
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
/**
 *  Créditos e licença
 *
 *  Partes deste driver (fingerprints, comandos health check, montagem do payload do IAS WD e
 *  tratamento de delays) derivam do driver "Heiman HS2WD-E Siren" v0.7 (2026-08-03):
 *    - Autor original: gabriele-v (2019)
 *    - Atualizações Hubitat: OpenAI, com base em testes de John Land
 *    - Post: https://community.hubitat.com/t/heiman-hs2wd-e-zigbee-siren-driver-updated-2026-08-03/165500
 *
 *  Copyright 2019 gabriele-v
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
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

        command "healthCheckSiren"
        command "healthCheckStrobe"

        attribute "maxDuration", "number"
        attribute "hwVer", "string"
        attribute "strobeSupported", "string"
        attribute "lastCheckin", "string"

        // Variante antiga
        fingerprint profileId: "0104",
            endpointId: "01",
            inClusters: "0000,0001,0003,0004,0009,0500,0502",
            outClusters: "0003,0019",
            manufacturer: "Heiman",
            model: "WarningDevice",
            deviceJoinName: "Heiman HS2WD-E Siren"

        // Variante WarningDevice-EF-3.0
        fingerprint profileId: "0104",
            endpointId: "01",
            inClusters: "0000,0001,0003,0004,0500,0502,0B05",
            outClusters: "0019",
            manufacturer: "HEIMAN",
            model: "WarningDevice-EF-3.0",
            deviceJoinName: "Heiman HS2WD-E Siren"
    }

    preferences {
        input name: "maxDuration", type: "number",
            title: "Max duration of siren/strobe (seconds)",
            range: "1..600",
            defaultValue: 240,
            required: true

        input name: "healthCheckSirenDelayMs", type: "number",
            title: "Health Check Siren OFF delay (milliseconds)",
            description: "Time between siren ON and explicit OFF. Very short values may be limited by Zigbee/device processing. The siren runs at FULL volume.",
            range: "10..1000",
            defaultValue: 50,
            required: true

        input name: "infoLogging", type: "bool",
            title: "Enable info logging",
            defaultValue: true

        input name: "debugLogging", type: "bool",
            title: "Enable debug logging",
            defaultValue: false
    }
}

/* ------------------------------------------------------------------ */
/*  Lifecycle                                                          */
/* ------------------------------------------------------------------ */

def installed() {
    logInfo "Installed"

    sendEvent(name: "alarm", value: "off")
    sendEvent(name: "switch", value: "off")
    sendEvent(name: "strobeSupported", value: "supported")

    sendZigbeeCommands(configureCmds())
}

def updated() {
    logInfo "Updated - maxDuration=${getDuration()}s"

    sendEvent(name: "strobeSupported", value: "supported")

    unschedule("logsOff")
    if (settings.debugLogging) {
        runIn(1800, "logsOff")
    }

    sendZigbeeCommands(configureCmds())
}

def configure() {
    logInfo "Configuring"
    return configureCmds()
}

def refresh() {
    logInfo "Refreshing"
    return refreshCmds()
}

private List<String> configureCmds() {
    List<String> cmds = []
    cmds += zigbee.writeAttribute(0x0502, 0x0000, DataType.UINT16, uint16LittleEndian(getDuration()))
    cmds += zigbee.readAttribute(0x0502, 0x0000)
    cmds += refreshCmds()

    logDebug "configure cmds: ${cmds}"
    return cmds
}

private List<String> refreshCmds() {
    List<String> cmds = []
    cmds += zigbee.readAttribute(0x0000, 0x0003)    // Hardware version
    cmds += zigbee.readAttribute(0x0001, 0x0021)    // Battery percentage
    cmds += zigbee.readAttribute(0x0502, 0x0000)    // Max warning duration

    logDebug "refresh cmds: ${cmds}"
    return cmds
}

/* ------------------------------------------------------------------ */
/*  Parse                                                              */
/* ------------------------------------------------------------------ */

def parse(String description) {
    logDebug "Parsing: ${description}"

    if (!description) return null

    // IAS Zone: a sirene repete o enroll request a cada ~5 s até o hub responder
    if (description.startsWith("enroll request")) {
        logInfo "IAS Zone enroll request received - sending enroll response"
        return zigbee.enrollResponse()
    }

    sendEvent(
        name: "lastCheckin",
        value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
    )

    Map descMap = zigbee.parseDescriptionAsMap(description)
    logDebug "descMap: ${descMap}"

    if (!descMap?.value) return null

    if (descMap.cluster == "0000" && descMap.attrId == "0003") {
        sendEvent(name: "hwVer", value: descMap.value,
            descriptionText: "Hardware version is ${descMap.value}")
        return null
    }

    if (descMap.cluster == "0001" && descMap.attrId == "0021") {
        Integer rawValue = Integer.parseInt(descMap.value, 16)
        Integer batteryValue = Math.max(0, Math.min(100, Math.round(rawValue / 2.0d) as Integer))
        sendEvent(name: "battery", value: batteryValue, unit: "%",
            descriptionText: "Battery level is ${batteryValue}%")
        logInfo "battery => ${batteryValue}%"
        return null
    }

    if (descMap.cluster == "0502" && descMap.attrId == "0000") {
        Integer durationValue = Integer.parseInt(descMap.value, 16)
        sendEvent(name: "maxDuration", value: durationValue, unit: "s",
            descriptionText: "Max siren/strobe duration is ${durationValue}s")
        logInfo "maxDuration => ${durationValue}s"
        return null
    }

    return null
}

/* ------------------------------------------------------------------ */
/*  Commands                                                           */
/* ------------------------------------------------------------------ */

def on() {
    return both()
}

def off() {
    logInfo "Turning off"

    unschedule("autoOff")
    setAlarmState("off", "off", "Siren off")

    return stopWarningCmd()
}

def siren() {
    logInfo "Turning siren on (${getDuration()}s)"

    setAlarmState("siren", "on", "Siren on")
    scheduleAutoOff()

    // 0x13: warning mode burglar + sem strobe + siren level 3 (very high)
    return startWarning("13", getDuration(), "00", "00")
}

def both() {
    logInfo "Turning siren + strobe on (${getDuration()}s)"

    setAlarmState("both", "on", "Siren and strobe on")
    scheduleAutoOff()

    // 0x17: warning mode burglar + strobe + siren level 3 (very high)
    // duty cycle 0x32, strobe level 0x03
    return startWarning("17", getDuration(), "32", "03")
}

def strobe() {
    logInfo "Turning strobe on (${getDuration()}s)"

    setAlarmState("strobe", "on", "Strobe on")
    scheduleAutoOff()

    // 0x04: sem som (mode Stop) + strobe habilitado
    // duty cycle 0x32, strobe level 0x03
    return startWarning("04", getDuration(), "32", "03")
}

/**
 * Chirp curto: liga a sirene por 1 s (fallback de segurança caso o OFF se perca)
 * e envia OFF explícito após o delay configurado (padrão 50 ms).
 * A duração audível real pode ser maior: Zigbee e o processamento do aparelho não são tempo real.
 * Não altera os atributos alarm/switch.
 */
def healthCheckSiren() {
    Integer offDelayMs = getHealthCheckDelay()

    logInfo "Health-check siren: OFF approximately ${offDelayMs} ms after ON"

    List<String> startCmd = removeDelayTokens(startWarning("13", 1, "00", "00"))
    List<String> stopCmd = removeDelayTokens(stopWarningCmd())

    List<String> cmds = startCmd + ["delay ${offDelayMs}"] + stopCmd
    logDebug "healthCheckSiren cmds: ${cmds}"
    return cmds
}

def healthCheckStrobe() {
    Integer seconds = 3

    logInfo "Health-check strobe: ${seconds}s at 50% duty cycle"

    // 0x04: sem som + strobe habilitado. Não altera alarm/switch.
    return startWarning("04", seconds, "32", "03")
}

/* ------------------------------------------------------------------ */
/*  Helpers                                                            */
/* ------------------------------------------------------------------ */

/**
 * IAS Warning Device - Start Warning (cluster 0x0502, comando 0x00).
 * Payload: warning info (1 byte), duração (UINT16 LE), strobe duty cycle (1 byte), strobe level (1 byte).
 * Warning info: bits 7-4 = mode, bits 3-2 = strobe, bits 1-0 = siren level.
 */
private List<String> startWarning(String warningInfo, Integer seconds, String strobeDutyCycle, String strobeLevel) {
    return zigbee.command(
        0x0502,
        0x00,
        warningInfo,
        uint16LittleEndian(seconds),
        strobeDutyCycle,
        strobeLevel
    )
}

private List<String> stopWarningCmd() {
    return zigbee.command(0x0502, 0x00, "00", "0000", "00", "00")
}

private void setAlarmState(String alarmValue, String switchValue, String text, String eventType = "digital") {
    sendEvent(name: "alarm", value: alarmValue, descriptionText: text, type: eventType)
    sendEvent(name: "switch", value: switchValue, descriptionText: text, type: eventType)
}

private void scheduleAutoOff() {
    runIn(getDuration(), "autoOff", [overwrite: true])
}

// A sirene não reporta o fim do warning; reseta o estado quando a duração expira.
def autoOff() {
    logInfo "Duration elapsed - state reset to off"
    setAlarmState("off", "off", "Siren duration elapsed", "physical")
}

/**
 * Remove os tokens "delay" inseridos pelo zigbee.command() para permitir um delay
 * customizado entre ON e OFF no healthCheckSiren().
 */
private List<String> removeDelayTokens(List<String> cmds) {
    return cmds.findAll { String cmd ->
        cmd && !cmd.toLowerCase().startsWith("delay")
    }
}

private void sendZigbeeCommands(List<String> cmds) {
    logDebug "sendZigbeeCommands: ${cmds}"
    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
}

private Integer getDuration() {
    Integer duration = 240

    try {
        duration = (settings.maxDuration ?: 240) as Integer
    } catch (Exception ignored) {
        duration = 240
    }

    return Math.max(1, Math.min(600, duration))
}

private Integer getHealthCheckDelay() {
    Integer delayMs = 50

    try {
        delayMs = (settings.healthCheckSirenDelayMs ?: 50) as Integer
    } catch (Exception ignored) {
        delayMs = 50
    }

    return Math.max(10, Math.min(1000, delayMs))
}

private String uint16LittleEndian(Integer value) {
    Integer v = Math.max(0, Math.min(65535, value))
    return String.format("%02X%02X", v & 0xFF, (v >> 8) & 0xFF)
}

/* ------------------------------------------------------------------ */
/*  Logging                                                            */
/* ------------------------------------------------------------------ */

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
