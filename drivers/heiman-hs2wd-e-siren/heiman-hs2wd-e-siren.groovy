/**
 *  Heiman HS2WD-E Siren — Hubitat Driver
 *  Versão: 1.1.0 — Build: 2026-10-30
 *  Autor: Ricardo Nogueira Garcia
 *  Repositório: https://github.com/rngarcia/hubitat
 *  Gostou? Buy me a beer: https://www.paypal.com/donate/?business=rngarcia%40gmail.com&currency_code=USD
 *
 *  Créditos:
 *    - John Land (@John_Land, Hubitat Community): driver v0.7 para a HS2WD-E, revisão e o fork "Improved"
 *      que originou a maior parte das mudanças da v1.1.0
 *    - gabriele-v: driver original que serviu de base (Apache 2.0)
 *
 *  Changelog:
 *  v1.1.0 (2026-10-30) - RNG
 *    - Incorpora o fork "Improved" do John Land (changelog dele abaixo, como v1.0.8-fork). Ajustes na incorporação:
 *      o driver mantém o nome "Heiman HS2WD-E Siren" e o importUrl do repositório; o aviso de "NOT enrolled"
 *      virou info, porque esta sirene responde a comandos com ZoneState 0 mesmo depois do enroll response
 *  v1.0.8 (2026-10-30) - RNG
 *    - Sugestões de revisão do John Land: notificações de mudança de zona, bits 8 (test) e 9 (battery defect),
 *      leituras 0xFF ignoradas, health checks pulados com alarme ativo, evento do autoOff "digital"
 *  v1.0.8-fork (2026-10-29) - John Land, "Improved" fork, merged from a code review of v1.0.7
 *    - Driver renamed "Heiman HS2WD-E Siren Improved" so it can coexist with older drivers; importUrl removed
 *      so the Import button cannot overwrite this fork with the upstream version
 *    - New commands sirenOn / sirenOff / strobeOn / strobeOff (buttons "Siren On", "Siren Off", "Strobe On",
 *      "Strobe Off"). ON adds that component to whatever is already running; OFF removes it and keeps the
 *      other component running for the remaining time. The Alarm capability's Siren/Strobe/Both/Off keep
 *      their standard behavior for HSM and rules
 *    - Preference maxDuration renamed warningDuration (it shared a name with the maxDuration attribute);
 *      an existing value is migrated automatically on Save Preferences
 *    - Warning duration and the autoOff timer use the smaller of the preference and the device-reported
 *      maxDuration attribute, so the alarm state matches what the device actually does
 *    - Health checks are skipped while a real alarm is active (they would silence or replace it)
 *    - IAS Zone status change notifications are parsed ("zone status" messages and cluster 0x0500 cmd 0x00);
 *      new attribute zoneFlags; warning logged when battery low, battery defect or AC fault is set
 *    - ZoneStatus decoding adds bit 8 (test) and bit 9 (battery defect)
 *    - Battery voltage, percentage and rated voltage of 0xFF are treated as "not reported" instead of 25.5 V / 100 %
 *    - configure() binds the Power Configuration cluster and configures battery reporting, sends an enroll
 *      response, and reads ZoneState and the CIE address; the CIE address is compared with the hub's EUI
 *    - Logs rejected attribute writes, reporting configuration results and IAS WD commands the device rejects
 *    - autoOff event type changed from "physical" to "digital"
 *  v1.0.7 (2026-10-28) - RNG
 *    - Rotina de diagnóstico de bateria: comando batteryDiagnostics e preferência para executá-lo a cada
 *      15 min / 30 min / 1 h / 3 h. Lê tensão, percentual e tensão nominal (cluster 0x0001), fonte de energia
 *      (0x0000:0x0007) e estado do IAS Zone (0x0500: ZoneState, ZoneType, ZoneStatus e endereço CIE)
 *    - Novos atributos powerSource e zoneStatus; o ZoneStatus é decodificado no log (bits de bateria baixa e falta de energia)
 *    - Histórico das últimas 48 leituras de tensão em state.voltageHistory
 *    - Atributos não suportados passam a aparecer no log como "unsupported"
 *  v1.0.6 (2026-10-28) - RNG
 *    - Gravação do atributo maxDuration volta a usar o inteiro direto: o hub já converte a ordem dos bytes
 *      (a v1.0.5 gravava 58880 no aparelho em vez de 230)
 *    - Bateria: refresh também lê a tensão (0x0020) e publica o atributo batteryVoltage. Medido no aparelho:
 *      2,4 V (nominal 4,1 V) e percentual 0, então o valor é publicado como o aparelho informa
 *    - Resposta ao enroll request enviada com sendHubCommand (antes era só o retorno de parse()) e logada em debug;
 *      o aviso a cada ~5 s deixou de ir para o log info
 *  v1.0.5 (2026-10-28) - RNG
 *    - Resposta ao enroll request do IAS Zone (a sirene repetia o pedido a cada ~5 s e gerava um evento lastCheckin a cada vez)
 *    - Strobe testado no aparelho: pisca continuamente durante a duração (não só uma vez);
 *      strobeSupported passa a "supported" e os textos de log foram corrigidos
 *  v1.0.4 (2026-10-28) - RNG
 *    - Strobe corrigido: bit de strobe ligado no Start Warning (0x17 = siren + strobe, 0x04 = strobe-only)
 *    - Payload do Start Warning na ordem do ZCL (info, duração, duty cycle, strobe level);
 *      duração em UINT16 little-endian explícito
 *    - siren() agora usa warning mode burglar (0x13) em vez de 0x03 (mode Stop)
 *    - strobe() agora é strobe-only
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
 *  v1.0.8-fork "Improved": changes from a code review of v1.0.7 by John Land (see changelog above).
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

import groovy.transform.Field
import hubitat.zigbee.zcl.DataType

// IAS WD Start Warning parameters per alarm mode.
// Warning info: bits 7-4 = mode, bits 3-2 = strobe, bits 1-0 = siren level.
@Field static final Map WARNING_MODES = [
    siren : [info: "13", duty: "00", level: "00", text: "Siren on"],             // burglar, no strobe, very high
    strobe: [info: "04", duty: "32", level: "03", text: "Strobe on"],            // no sound, strobe, 50 % duty
    both  : [info: "17", duty: "32", level: "03", text: "Siren and strobe on"]   // burglar, strobe, very high
]

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

        command "sirenOn"
        command "sirenOff"
        command "strobeOn"
        command "strobeOff"
        command "healthCheckSiren"
        command "healthCheckStrobe"
        command "batteryDiagnostics"

        attribute "maxDuration", "number"
        attribute "hwVer", "string"
        attribute "strobeSupported", "string"
        attribute "lastCheckin", "string"
        attribute "batteryVoltage", "number"
        attribute "powerSource", "string"
        attribute "zoneStatus", "string"
        attribute "zoneFlags", "string"

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
        input name: "warningDuration", type: "number",
            title: "Duration of siren/strobe (seconds)",
            description: "The device also enforces its own maximum (maxDuration attribute); the shorter of the two is used.",
            range: "1..600",
            defaultValue: 240,
            required: true

        input name: "healthCheckSirenDelayMs", type: "number",
            title: "Health Check Siren OFF delay (milliseconds)",
            description: "Time between siren ON and explicit OFF. Very short values may be limited by Zigbee/device processing. The siren runs at FULL volume.",
            range: "10..1000",
            defaultValue: 50,
            required: true

        input name: "batteryDiagInterval", type: "enum",
            title: "Battery diagnostics routine",
            description: "Reads battery, power source and IAS Zone attributes on a schedule. Results go to the info log and state.voltageHistory.",
            options: ["0": "Off", "15": "Every 15 minutes", "30": "Every 30 minutes", "60": "Every hour", "180": "Every 3 hours"],
            defaultValue: "0"

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
    migrateSettings()

    logInfo "Updated - warningDuration=${getDuration()}s"

    sendEvent(name: "strobeSupported", value: "supported")

    unschedule("logsOff")
    if (settings.debugLogging) {
        runIn(1800, "logsOff")
    }

    scheduleBatteryDiagnostics()

    sendZigbeeCommands(configureCmds())
}

// v1.0.8: preference maxDuration renamed warningDuration (the old name collided with the maxDuration attribute)
private void migrateSettings() {
    if (settings.warningDuration == null && settings.maxDuration != null) {
        Integer oldValue = clampDuration(settings.maxDuration)
        device.updateSetting("warningDuration", [value: oldValue, type: "number"])
        logInfo "Migrated preference maxDuration (${oldValue}s) to warningDuration"
    }

    if (settings.maxDuration != null) {
        device.removeSetting("maxDuration")
    }
}

private void scheduleBatteryDiagnostics() {
    unschedule("batteryDiagnostics")

    String interval = (settings.batteryDiagInterval ?: "0") as String

    switch (interval) {
        case "15":
            runEvery15Minutes("batteryDiagnostics")
            break
        case "30":
            runEvery30Minutes("batteryDiagnostics")
            break
        case "60":
            runEvery1Hour("batteryDiagnostics")
            break
        case "180":
            runEvery3Hours("batteryDiagnostics")
            break
        default:
            interval = "0"
            break
    }

    logInfo(interval == "0" ? "Battery diagnostics routine: off" : "Battery diagnostics routine: every ${interval} minutes")
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

    // O hub converte o inteiro para a ordem de bytes do ZCL (ex.: 230 sai como {00E6})
    cmds += zigbee.writeAttribute(0x0502, 0x0000, DataType.UINT16, getDuration())
    cmds += zigbee.readAttribute(0x0502, 0x0000)

    // Battery reporting: bind the Power Configuration cluster and request periodic reports
    // (1 h minimum, 6 h maximum). Some firmware ignores this; the diagnostics routine remains the fallback.
    cmds += "zdo bind 0x${device.deviceNetworkId} 0x${device.endpointId} 0x01 0x0001 {${device.zigbeeId}} {}".toString()
    cmds += "delay 200"
    cmds += zigbee.configureReporting(0x0001, 0x0020, DataType.UINT8, 3600, 21600, 1)    // voltage, 100 mV change
    cmds += zigbee.configureReporting(0x0001, 0x0021, DataType.UINT8, 3600, 21600, 2)    // percentage, 1 % change

    // IAS Zone: enroll response proativo e leitura do estado de enrollment / endereço CIE
    cmds += zigbee.enrollResponse()
    cmds += zigbee.readAttribute(0x0500, 0x0000)    // ZoneState
    cmds += zigbee.readAttribute(0x0500, 0x0010)    // IAS CIE address

    cmds += refreshCmds()

    logDebug "configure cmds: ${cmds}"
    return cmds
}

private List<String> refreshCmds() {
    List<String> cmds = []
    cmds += zigbee.readAttribute(0x0000, 0x0003)    // Hardware version
    cmds += zigbee.readAttribute(0x0001, 0x0020)    // Battery voltage (100 mV)
    cmds += zigbee.readAttribute(0x0001, 0x0021)    // Battery percentage (0.5 %)
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
        logDebug "IAS Zone enroll request received - sending enroll response"
        sendZigbeeCommands(zigbee.enrollResponse())
        return null
    }

    sendEvent(
        name: "lastCheckin",
        value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
    )

    // IAS Zone Status Change Notification sent by the device on its own (battery low, AC fault, ...)
    if (description.startsWith("zone status")) {
        def matcher = description =~ /zone status 0x([0-9A-Fa-f]{4})/
        if (matcher.find()) {
            handleZoneStatus(Integer.parseInt(matcher.group(1), 16), "notification")
        }
        return null
    }

    Map descMap = zigbee.parseDescriptionAsMap(description)
    logDebug "descMap: ${descMap}"

    if (!descMap) return null

    String clusterId = (descMap.clusterId ?: descMap.cluster) as String

    // Zone Status Change Notification that arrives as a catchall instead of a "zone status" message
    if (descMap.isClusterSpecific == true && clusterId == "0500" && descMap.command == "00" && descMap.data?.size() >= 2) {
        handleZoneStatus(Integer.parseInt(descMap.data[1] + descMap.data[0], 16), "notification")
        return null
    }

    // Global ZCL responses: read errors, write results, reporting configuration, default responses
    if (descMap.isClusterSpecific == false && descMap.profileId != "0000" &&
        descMap.command in ["01", "04", "07", "0B"] && descMap.data) {
        if (handleGlobalResponse(clusterId, descMap)) return null
    }

    if (!descMap.value) return null

    if (clusterId == "0000" && descMap.attrId == "0003") {
        sendEvent(name: "hwVer", value: descMap.value,
            descriptionText: "Hardware version is ${descMap.value}")
        return null
    }

    // Basic cluster: fonte de energia atual (0x01 = rede elétrica, 0x03 = bateria; bit 0x80 = há bateria de backup)
    if (clusterId == "0000" && descMap.attrId == "0007") {
        Integer rawValue = Integer.parseInt(descMap.value, 16)
        String source = powerSourceName(rawValue)
        sendEvent(name: "powerSource", value: source,
            descriptionText: "Power source is ${source}")
        logInfo "diag: powerSource => ${source} (raw 0x${descMap.value})"
        return null
    }

    if (clusterId == "0001" && descMap.attrId == "0020") {
        Integer rawValue = Integer.parseInt(descMap.value, 16)
        if (rawValue == 0xFF) {
            logInfo "diag: battery voltage not reported by the device (0xFF)"
            return null
        }
        BigDecimal volts = rawValue / 10.0
        sendEvent(name: "batteryVoltage", value: volts, unit: "V",
            descriptionText: "Battery voltage is ${volts} V")
        logInfo "batteryVoltage => ${volts} V (raw ${rawValue})"
        recordVoltage(volts)
        return null
    }

    if (clusterId == "0001" && descMap.attrId == "0021") {
        Integer rawValue = Integer.parseInt(descMap.value, 16)
        if (rawValue == 0xFF) {
            logInfo "diag: battery percentage not reported by the device (0xFF)"
            return null
        }
        Integer batteryValue = Math.max(0, Math.min(100, Math.round(rawValue / 2.0d) as Integer))
        sendEvent(name: "battery", value: batteryValue, unit: "%",
            descriptionText: "Battery level is ${batteryValue}%")
        logInfo "battery => ${batteryValue}%"
        return null
    }

    if (clusterId == "0001" && descMap.attrId == "0034") {
        Integer rawValue = Integer.parseInt(descMap.value, 16)
        if (rawValue == 0xFF) {
            logInfo "diag: battery rated voltage not reported by the device (0xFF)"
            return null
        }
        logInfo "diag: batteryRatedVoltage => ${rawValue / 10.0} V (raw ${rawValue})"
        return null
    }

    // IAS Zone (0x0500)
    if (clusterId == "0500" && descMap.attrId == "0000") {
        Integer rawValue = Integer.parseInt(descMap.value, 16)
        // Na HS2WD-E testada o ZoneState fica em 0 mesmo com a sirene respondendo a todos os comandos,
        // então isto é só informativo (não é um aviso)
        logInfo "diag: zoneState => ${rawValue == 1 ? 'enrolled' : 'not enrolled'} (raw ${rawValue})"
        return null
    }

    if (clusterId == "0500" && descMap.attrId == "0001") {
        logInfo "diag: zoneType => 0x${descMap.value} (0x0225 = standard warning device)"
        return null
    }

    if (clusterId == "0500" && descMap.attrId == "0002") {
        handleZoneStatus(Integer.parseInt(descMap.value, 16), "read")
        return null
    }

    if (clusterId == "0500" && descMap.attrId == "0010") {
        String match = compareCieAddress(descMap.value)
        String msg = "diag: iasCieAddress => ${descMap.value} (${match})"
        if (match.startsWith("does NOT")) {
            logWarn(msg)
        } else {
            logInfo(msg)
        }
        return null
    }

    if (clusterId == "0502" && descMap.attrId == "0000") {
        Integer durationValue = Integer.parseInt(descMap.value, 16)
        sendEvent(name: "maxDuration", value: durationValue, unit: "s",
            descriptionText: "Max siren/strobe duration is ${durationValue}s")
        logInfo "maxDuration => ${durationValue}s"
        if (durationValue < getDuration()) {
            logWarn "Device maxDuration (${durationValue}s) is shorter than the warningDuration preference (${getDuration()}s); alarms will last ${durationValue}s"
        }
        return null
    }

    return null
}

/**
 * Handles global (non cluster-specific) ZCL responses. Returns true when the message was fully handled.
 *   0x01 Read Attributes Response (error status only; successes arrive as "read attr" messages)
 *   0x04 Write Attributes Response
 *   0x07 Configure Reporting Response
 *   0x0B Default Response
 */
private boolean handleGlobalResponse(String clusterId, Map descMap) {
    List<String> data = descMap.data as List<String>

    switch (descMap.command) {
        case "01":
            // Read Attributes Response com status de erro (ex.: 0x86 = atributo não suportado)
            if (data.size() >= 3 && data[2] != "00") {
                logInfo "diag: cluster 0x${clusterId} attribute 0x${data[1]}${data[0]} unsupported (status 0x${data[2]})"
                return true
            }
            return false

        case "04":
            if (data[0] == "00") {
                logDebug "Write accepted on cluster 0x${clusterId}"
            } else {
                String attr = data.size() >= 3 ? "0x${data[2]}${data[1]}" : "unknown"
                logWarn "Device rejected write to cluster 0x${clusterId} attribute ${attr} (status 0x${data[0]})"
                if (clusterId == "0502") {
                    logWarn "maxDuration write failed - alarm timing will follow the device's reported maxDuration"
                }
            }
            return true

        case "07":
            if (data.size() == 1 && data[0] == "00") {
                logInfo "Reporting configured on cluster 0x${clusterId}"
            } else {
                logInfo("diag: reporting configuration on cluster 0x${clusterId} rejected (data ${data}) - " +
                    "the battery diagnostics routine remains the fallback")
            }
            return true

        case "0B":
            if (data.size() >= 2 && data[1] != "00") {
                logWarn "Device rejected command 0x${data[0]} on cluster 0x${clusterId} (status 0x${data[1]})"
            } else {
                logDebug "Default response on cluster 0x${clusterId}: ${data}"
            }
            return true
    }

    return false
}

private void handleZoneStatus(Integer status, String source) {
    String hex = String.format("0x%04X", status)
    String flags = zoneStatusFlags(status)
    boolean changed = device.currentValue("zoneStatus") != hex

    sendEvent(name: "zoneStatus", value: hex, descriptionText: "IAS zone status is ${hex}")
    sendEvent(name: "zoneFlags", value: flags, descriptionText: "IAS zone flags: ${flags}")

    String msg = "diag: zoneStatus (${source}) => ${hex} (${flags})"

    // 0x0288 = battery low (bit 3) | AC fault (bit 7) | battery defect (bit 9)
    if (changed && (status & 0x0288)) {
        logWarn msg
    } else {
        logInfo msg
    }
}

/* ------------------------------------------------------------------ */
/*  Commands                                                           */
/* ------------------------------------------------------------------ */

def on() {
    return both()
}

def off() {
    logInfo "Turning off"

    clearWarningState()
    setAlarmState("off", "off", "Siren off")

    return stopWarningCmd()
}

// Alarm capability commands: standard behavior (each replaces whatever is running)
def siren() {
    return startAlarm("siren")
}

def strobe() {
    return startAlarm("strobe")
}

def both() {
    return startAlarm("both")
}

// Siren On: adds the siren to whatever is running (strobe running => both)
def sirenOn() {
    return startAlarm(isStrobeActive() ? "both" : "siren")
}

// Strobe On: adds the strobe to whatever is running (siren running => both)
def strobeOn() {
    return startAlarm(isSirenActive() ? "both" : "strobe")
}

// Siren Off: removes the siren; a running strobe continues for the remaining time
def sirenOff() {
    String current = getActiveAlarm()

    if (current == "both") return continueAlarm("strobe")

    if (current == "strobe") {
        logInfo "Siren Off: siren is not active (strobe keeps running)"
        return []
    }

    return off()
}

// Strobe Off: removes the strobe; a running siren continues for the remaining time
def strobeOff() {
    String current = getActiveAlarm()

    if (current == "both") return continueAlarm("siren")

    if (current == "siren") {
        logInfo "Strobe Off: strobe is not active (siren keeps running)"
        return []
    }

    return off()
}

/**
 * Chirp curto: liga a sirene por 1 s (fallback de segurança caso o OFF se perca)
 * e envia OFF explícito após o delay configurado (padrão 50 ms).
 * A duração audível real pode ser maior: Zigbee e o processamento do aparelho não são tempo real.
 * Não altera os atributos alarm/switch. Ignorado enquanto um alarme real estiver ativo.
 */
def healthCheckSiren() {
    if (skipBecauseAlarmActive("Health-check siren")) return []

    Integer offDelayMs = getHealthCheckDelay()

    logInfo "Health-check siren: OFF approximately ${offDelayMs} ms after ON"

    List<String> startCmd = removeDelayTokens(startWarning("13", 1, "00", "00"))
    List<String> stopCmd = removeDelayTokens(stopWarningCmd())

    List<String> cmds = startCmd + ["delay ${offDelayMs}".toString()] + stopCmd
    logDebug "healthCheckSiren cmds: ${cmds}"
    return cmds
}

def healthCheckStrobe() {
    if (skipBecauseAlarmActive("Health-check strobe")) return []

    Integer seconds = 3

    logInfo "Health-check strobe: ${seconds}s at 50% duty cycle"

    // 0x04: sem som + strobe habilitado. Não altera alarm/switch.
    return startWarning("04", seconds, "32", "03")
}

/**
 * Rotina de diagnóstico de bateria: lê os atributos que ajudam a entender a bateria e a alimentação.
 * Resultados no log (prefixo "diag:") e em state.voltageHistory. Também roda no agendamento da preferência
 * "Battery diagnostics routine".
 *   0x0000:0x0007  fonte de energia atual
 *   0x0001:0x0020  tensão (100 mV)      0x0001:0x0021  percentual (0,5 %)
 *   0x0001:0x0034  tensão nominal (100 mV)
 *   0x0500:0x0000  ZoneState   0x0500:0x0001  ZoneType   0x0500:0x0002  ZoneStatus   0x0500:0x0010  endereço CIE
 */
def batteryDiagnostics() {
    logInfo "Battery diagnostics: reading power source, battery and IAS Zone attributes"
    sendZigbeeCommands(batteryDiagnosticsCmds())
}

private List<String> batteryDiagnosticsCmds() {
    List<String> cmds = []
    cmds += zigbee.readAttribute(0x0000, 0x0007, [:], 500)    // Power source
    cmds += zigbee.readAttribute(0x0001, 0x0020, [:], 500)    // Battery voltage
    cmds += zigbee.readAttribute(0x0001, 0x0021, [:], 500)    // Battery percentage
    cmds += zigbee.readAttribute(0x0001, 0x0034, [:], 500)    // Battery rated voltage
    cmds += zigbee.readAttribute(0x0500, 0x0000, [:], 500)    // IAS ZoneState
    cmds += zigbee.readAttribute(0x0500, 0x0001, [:], 500)    // IAS ZoneType
    cmds += zigbee.readAttribute(0x0500, 0x0002, [:], 500)    // IAS ZoneStatus
    cmds += zigbee.readAttribute(0x0500, 0x0010, [:], 500)    // IAS CIE address

    logDebug "batteryDiagnostics cmds: ${cmds}"
    return cmds
}

/* ------------------------------------------------------------------ */
/*  Alarm state                                                        */
/* ------------------------------------------------------------------ */

// Starts a warning in the given mode for the full effective duration.
private List<String> startAlarm(String mode) {
    Integer seconds = getEffectiveDuration()
    logInfo "Turning ${mode} on (${seconds}s)"
    return runWarning(mode, seconds)
}

// Replaces the running warning with the given mode for the time that is left.
private List<String> continueAlarm(String mode) {
    Integer remaining = getRemainingSeconds()

    if (remaining < 1) return off()

    logInfo "Switching to ${mode} only for the remaining ${remaining}s"
    return runWarning(mode, remaining)
}

private List<String> runWarning(String mode, Integer seconds) {
    Map params = WARNING_MODES[mode]

    setAlarmState(mode, "on", params.text as String)
    state.warningEndsAt = now() + seconds * 1000L
    runIn(seconds, "autoOff", [overwrite: true])

    return startWarning(params.info as String, seconds, params.duty as String, params.level as String)
}

// Current alarm mode; "off" when the warning time has elapsed or is unknown.
private String getActiveAlarm() {
    String current = (device.currentValue("alarm") ?: "off") as String
    if (current != "off" && getRemainingSeconds() < 1) return "off"
    return current
}

private boolean isSirenActive() {
    return getActiveAlarm() in ["siren", "both"]
}

private boolean isStrobeActive() {
    return getActiveAlarm() in ["strobe", "both"]
}

private Integer getRemainingSeconds() {
    Long endsAt = state.warningEndsAt as Long
    if (!endsAt) return 0

    Integer seconds = Math.ceil((endsAt - now()) / 1000.0d) as Integer
    return Math.max(0, seconds)
}

private boolean skipBecauseAlarmActive(String name) {
    String current = getActiveAlarm()
    if (current != "off") {
        logInfo "${name} skipped: alarm is active (${current})"
        return true
    }
    return false
}

private void clearWarningState() {
    unschedule("autoOff")
    state.remove("warningEndsAt")
}

private void setAlarmState(String alarmValue, String switchValue, String text) {
    sendEvent(name: "alarm", value: alarmValue, descriptionText: text, type: "digital")
    sendEvent(name: "switch", value: switchValue, descriptionText: text, type: "digital")
}

// A sirene não reporta o fim do warning; reseta o estado quando a duração expira.
def autoOff() {
    logInfo "Duration elapsed - state reset to off"
    clearWarningState()
    setAlarmState("off", "off", "Siren duration elapsed")
}

/* ------------------------------------------------------------------ */
/*  Helpers                                                            */
/* ------------------------------------------------------------------ */

/**
 * IAS Warning Device - Start Warning (cluster 0x0502, comando 0x00).
 * Payload: warning info (1 byte), duração (UINT16 LE), strobe duty cycle (1 byte), strobe level (1 byte).
 * Warning info: bits 7-4 = mode, bits 3-2 = strobe, bits 1-0 = siren level.
 * Aqui o payload é enviado byte a byte (he cmd), por isso a duração vai em little-endian explícito.
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

/**
 * Remove os tokens "delay" inseridos pelo zigbee.command() para permitir um delay
 * customizado entre ON e OFF no healthCheckSiren().
 */
private List<String> removeDelayTokens(List<String> cmds) {
    return cmds.findAll { String cmd ->
        cmd && !cmd.toLowerCase().startsWith("delay")
    }
}

// Guarda as últimas 48 leituras de tensão em state.voltageHistory (visível em "State Variables")
private void recordVoltage(BigDecimal volts) {
    String stamp = new Date().format("MM-dd HH:mm", location.timeZone)
    List<String> history = (state.voltageHistory ?: []) as List<String>

    history << "${stamp} ${volts} V".toString()
    if (history.size() > 48) history = history[-48..-1]

    state.voltageHistory = history
}

// Basic cluster PowerSource: bits 0-6 = fonte, bit 7 = bit de bateria de backup
private String powerSourceName(Integer rawValue) {
    String name

    switch (rawValue & 0x7F) {
        case 0x01: name = "mains"; break
        case 0x02: name = "mains (3-phase)"; break
        case 0x03: name = "battery"; break
        case 0x04: name = "DC source"; break
        case 0x05: name = "emergency mains (constant)"; break
        case 0x06: name = "emergency mains (transfer switch)"; break
        default: name = "unknown"; break
    }

    return (rawValue & 0x80) ? "${name}, backup bit set".toString() : name
}

// IAS Zone ZoneStatus: bit 3 = bateria baixa, bit 7 = falha de rede elétrica (AC), bit 9 = bateria com defeito
private String zoneStatusFlags(Integer status) {
    Map flags = [
        1: "alarm1", 2: "alarm2", 4: "tamper", 8: "battery low",
        16: "supervision reports", 32: "restore reports", 64: "trouble", 128: "AC (mains) fault",
        256: "test", 512: "battery defect"
    ]

    List<String> active = flags.findAll { bit, label -> (status & bit) != 0 }.values().toList()
    return active ? active.join(", ") : "no flags set"
}

// Compares the device's IAS CIE address with the hub's Zigbee EUI (either byte order).
private String compareCieAddress(String value) {
    try {
        String hub = ((location.hub?.zigbeeEui ?: "") as String).toUpperCase().replaceAll(/[^0-9A-F]/, "")
        String cie = (value ?: "").toUpperCase().replaceAll(/[^0-9A-F]/, "")

        if (!hub) return "hub EUI unavailable for comparison"
        if (cie == hub) return "matches hub"

        String reversed = cie.findAll(/../).reverse().join()
        if (reversed == hub) return "matches hub, reversed byte order"

        return "does NOT match hub EUI ${hub}"
    } catch (Exception e) {
        return "could not compare with hub EUI: ${e.message}"
    }
}

private void sendZigbeeCommands(List<String> cmds) {
    logDebug "sendZigbeeCommands: ${cmds}"
    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
}

// Duration from the preference (falls back to the pre-1.0.8 preference name during migration)
private Integer getDuration() {
    def raw = settings.warningDuration != null ? settings.warningDuration : settings.maxDuration
    return clampDuration(raw)
}

private Integer clampDuration(raw) {
    Integer duration = 240

    try {
        if (raw != null) duration = raw as Integer
    } catch (Exception ignored) {
        duration = 240
    }

    return Math.max(1, Math.min(600, duration))
}

// The device caps every warning at its MaxDuration attribute, so use the shorter of the two.
private Integer getEffectiveDuration() {
    Integer duration = getDuration()
    Integer reported = null

    try {
        def current = device.currentValue("maxDuration")
        if (current != null) reported = current as Integer
    } catch (Exception ignored) {
        reported = null
    }

    if (reported != null && reported > 0 && reported < duration) {
        logDebug "Using device maxDuration ${reported}s instead of preference ${duration}s"
        return reported
    }

    return duration
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

private void logWarn(String msg) {
    log.warn "${device.displayName}: ${msg}"
}