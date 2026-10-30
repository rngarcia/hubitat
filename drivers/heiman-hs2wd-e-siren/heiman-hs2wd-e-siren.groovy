/**
 *  Heiman HS2WD-E Siren — Hubitat Driver
 *  Versão: 1.0.8 — Build: 2026-10-30
 *  Autor: Ricardo Nogueira Garcia
 *  Repositório: https://github.com/rngarcia/hubitat
 *  Gostou? Buy me a beer: https://www.paypal.com/donate/?business=rngarcia%40gmail.com&currency_code=USD
 *
 *  Créditos:
 *    - John Land (@John_Land, Hubitat Community): driver v0.7 para a HS2WD-E, revisão e sugestões da v1.0.8
 *    - gabriele-v: driver original que serviu de base (Apache 2.0)
 *
 *  Changelog:
 *  v1.0.8 (2026-10-30) - RNG
 *    - Sugestões de revisão do John Land: notificações de mudança de zona (zone status) enviadas pela
 *      sirene passam a atualizar o atributo zoneStatus e o log; ZoneStatus decodifica também os bits
 *      8 (test) e 9 (battery defect); leituras 0xFF (inválido no ZCL) de bateria e tensão são ignoradas;
 *      healthCheckSiren/healthCheckStrobe não rodam enquanto há alarme ativo; o evento do autoOff
 *      deixa de ser "physical" e passa a "digital"
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
        command "batteryDiagnostics"

        attribute "maxDuration", "number"
        attribute "hwVer", "string"
        attribute "strobeSupported", "string"
        attribute "lastCheckin", "string"
        attribute "batteryVoltage", "number"
        attribute "powerSource", "string"
        attribute "zoneStatus", "string"

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
    logInfo "Updated - maxDuration=${getDuration()}s"

    sendEvent(name: "strobeSupported", value: "supported")

    unschedule("logsOff")
    if (settings.debugLogging) {
        runIn(1800, "logsOff")
    }

    scheduleBatteryDiagnostics()

    sendZigbeeCommands(configureCmds())
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

    // IAS Zone: notificação de mudança de status enviada pela sirene (ex.: bateria baixa, falta de energia).
    // Formato: "zone status 0x0008 -- extended status 0x00"
    if (description.startsWith("zone status")) {
        def zoneMatch = (description =~ /zone status 0x([0-9A-Fa-f]+)/)
        if (zoneMatch.find()) {
            Integer zoneValue = Integer.parseInt(zoneMatch.group(1), 16)
            String hex = String.format("0x%04X", zoneValue)
            sendEvent(name: "zoneStatus", value: hex,
                descriptionText: "IAS zone status is ${hex}")
            logInfo "zoneStatus (notification) => ${hex} (${zoneStatusFlags(zoneValue)})"
        } else {
            logDebug "zone status notification not parsed: ${description}"
        }
        return null
    }

    Map descMap = zigbee.parseDescriptionAsMap(description)
    logDebug "descMap: ${descMap}"

    // Read Attributes Response com status de erro (ex.: 0x86 = atributo não suportado)
    if (descMap?.command == "01" && descMap?.isClusterSpecific == false && descMap?.data?.size() >= 3 && descMap.data[2] != "00") {
        logInfo "diag: cluster 0x${descMap.clusterId} attribute 0x${descMap.data[1]}${descMap.data[0]} unsupported (status 0x${descMap.data[2]})"
        return null
    }

    if (!descMap?.value) return null

    if (descMap.cluster == "0000" && descMap.attrId == "0003") {
        sendEvent(name: "hwVer", value: descMap.value,
            descriptionText: "Hardware version is ${descMap.value}")
        return null
    }

    // Basic cluster: fonte de energia atual (0x01 = rede elétrica, 0x03 = bateria; bit 0x80 = há bateria de backup)
    if (descMap.cluster == "0000" && descMap.attrId == "0007") {
        Integer rawValue = Integer.parseInt(descMap.value, 16)
        String source = powerSourceName(rawValue)
        sendEvent(name: "powerSource", value: source,
            descriptionText: "Power source is ${source}")
        logInfo "diag: powerSource => ${source} (raw 0x${descMap.value})"
        return null
    }

    if (descMap.cluster == "0001" && descMap.attrId == "0020") {
        Integer rawValue = Integer.parseInt(descMap.value, 16)
        if (rawValue == 0xFF) {
            logInfo "batteryVoltage not reported (0xFF = invalid)"
            return null
        }
        BigDecimal volts = rawValue / 10.0
        sendEvent(name: "batteryVoltage", value: volts, unit: "V",
            descriptionText: "Battery voltage is ${volts} V")
        logInfo "batteryVoltage => ${volts} V (raw ${rawValue})"
        recordVoltage(volts)
        return null
    }

    if (descMap.cluster == "0001" && descMap.attrId == "0034") {
        Integer rawValue = Integer.parseInt(descMap.value, 16)
        logInfo "diag: batteryRatedVoltage => ${rawValue / 10.0} V (raw ${rawValue})"
        return null
    }

    // IAS Zone (0x0500)
    if (descMap.cluster == "0500" && descMap.attrId == "0000") {
        Integer rawValue = Integer.parseInt(descMap.value, 16)
        logInfo "diag: zoneState => ${rawValue == 1 ? 'enrolled' : 'not enrolled'} (raw ${rawValue})"
        return null
    }

    if (descMap.cluster == "0500" && descMap.attrId == "0001") {
        logInfo "diag: zoneType => 0x${descMap.value} (0x0225 = standard warning device)"
        return null
    }

    if (descMap.cluster == "0500" && descMap.attrId == "0002") {
        Integer rawValue = Integer.parseInt(descMap.value, 16)
        String hex = String.format("0x%04X", rawValue)
        sendEvent(name: "zoneStatus", value: hex,
            descriptionText: "IAS zone status is ${hex}")
        logInfo "diag: zoneStatus => ${hex} (${zoneStatusFlags(rawValue)})"
        return null
    }

    if (descMap.cluster == "0500" && descMap.attrId == "0010") {
        logInfo "diag: iasCieAddress => ${descMap.value}"
        return null
    }

    if (descMap.cluster == "0001" && descMap.attrId == "0021") {
        Integer rawValue = Integer.parseInt(descMap.value, 16)
        if (rawValue == 0xFF) {
            logInfo "battery percentage not reported (0xFF = invalid)"
            return null
        }
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
    if ((device.currentValue("alarm") ?: "off") != "off") {
        logInfo "Skipping health-check siren: alarm is active"
        return []
    }

    Integer offDelayMs = getHealthCheckDelay()

    logInfo "Health-check siren: OFF approximately ${offDelayMs} ms after ON"

    List<String> startCmd = removeDelayTokens(startWarning("13", 1, "00", "00"))
    List<String> stopCmd = removeDelayTokens(stopWarningCmd())

    List<String> cmds = startCmd + ["delay ${offDelayMs}"] + stopCmd
    logDebug "healthCheckSiren cmds: ${cmds}"
    return cmds
}

def healthCheckStrobe() {
    if ((device.currentValue("alarm") ?: "off") != "off") {
        logInfo "Skipping health-check strobe: alarm is active"
        return []
    }

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
    setAlarmState("off", "off", "Siren duration elapsed", "digital")
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

// IAS Zone ZoneStatus: bit 3 = bateria baixa, bit 7 = falha de rede elétrica (AC),
// bit 8 = test, bit 9 = defeito de bateria
private String zoneStatusFlags(Integer status) {
    Map flags = [
        1: "alarm1", 2: "alarm2", 4: "tamper", 8: "battery low",
        16: "supervision reports", 32: "restore reports", 64: "trouble", 128: "AC (mains) fault",
        256: "test", 512: "battery defect"
    ]

    List<String> active = flags.findAll { bit, label -> (status & bit) != 0 }.values().toList()
    return active ? active.join(", ") : "no flags set"
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
