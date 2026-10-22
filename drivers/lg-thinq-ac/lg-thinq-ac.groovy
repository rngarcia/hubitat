/**
 *  ThinQ Connect Air Conditioner 2 — Hubitat Driver (Cloud, LG ThinQ Connect API oficial)
 *  Versão: 1.0.1 — Build: 2026-10-21
 *  Autor: Ricardo Nogueira Garcia
 *
 *  Changelog:
 *  v1.0.1 (2026-10-21) - RNG
 *    - Cabeçalho padronizado e importUrl
 *  v1.0.0 (2025) - RNG
 *    - Versão inicial
 */

import groovy.transform.Field
import groovy.json.JsonSlurper
import groovy.json.JsonOutput

@Field List<String> LOG_LEVELS = ["error", "warn", "info", "debug", "trace"]
@Field String DEFAULT_LOG_LEVEL = LOG_LEVELS[2]

metadata {
    definition(name: "ThinQ Connect Air Conditioner 2", namespace: "rng", author: "Ricardo Nogueira Garcia", importUrl: "https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/lg-thinq-ac/lg-thinq-ac.groovy") {
        capability "Sensor"
        capability "Switch"
        capability "Initialize"
        capability "Refresh"
        capability "TemperatureMeasurement"
        capability "Thermostat"
        capability "FanControl"

        // Estado geral
        attribute "currentState", "string"           // runState.currentState (NORMAL/ERROR)
        attribute "currentJobMode", "string"        // COOL/HEAT/FAN/AIR_DRY
        attribute "airConOperationMode", "string"   // POWER_ON/POWER_OFF
        attribute "temperatureUnit", "string"       // C/F (apresentação)

        // Temperatura / setpoints
        attribute "currentTemperature", "number"
        attribute "targetTemperature", "number"
        attribute "coolTargetTemperature", "number"
        attribute "heatTargetTemperature", "number"
        attribute "minTargetTemperature", "number"
        attribute "maxTargetTemperature", "number"

        // Ventilação
        attribute "windStrength", "string"          // AUTO/HIGH/LOW/MID
        attribute "windStep", "number"              // 1..5
        attribute "windStrengthDetail", "string"    // NATURE (read-only)

        // Direção do vento – só swing vertical
        attribute "rotateUpDown", "string"          // "on"/"off"

        // Display
        attribute "displayLight", "string"          // ON/OFF

        // Timer / SleepTimer (somente leitura por enquanto)
        attribute "sleepRelativeHourToStop", "number"
        attribute "sleepRelativeMinuteToStop", "number"
        attribute "sleepRelativeStopTimer", "string"     // SET/UNSET

        attribute "absoluteHourToStart", "number"
        attribute "absoluteMinuteToStart", "number"
        attribute "absoluteHourToStop", "number"
        attribute "absoluteMinuteToStop", "number"
        attribute "absoluteStartTimer", "string"         // SET/UNSET
        attribute "absoluteStopTimer", "string"          // SET/UNSET

        // Filtro
        attribute "filterUsedTime", "number"
        attribute "filterLifetime", "number"
        attribute "filterRemainPercent", "number"

        // Qualidade do ar (se presentes no state)
        attribute "pm1", "number"
        attribute "pm2", "number"
        attribute "pm10", "number"
        attribute "humidity", "number"
        attribute "totalPollution", "number"
        attribute "totalPollutionLevel", "string"

        // Perfil bruto
        attribute "deviceProfileJson", "string"

        // Erro genérico
        attribute "error", "string"

        // Comandos principais
        command "getDeviceProfile"
        command "generateDeviceProfileJson"

        // Modos de ar-condicionado (baseado no profile: COOL/HEAT/FAN/AIR_DRY)
        command "setAirConJobMode", [
            [name: "Set AirCon Job Mode", type: "ENUM",
             description: "Select AirCon Job Mode",
             constraints: ["COOL", "HEAT", "FAN", "AIR_DRY"]]
        ]

        // Temperatura genérica (usa "temperature.targetTemperature")
        command "setTargetTemperature", [
            [name: "Set Target Temperature", type: "NUMBER",
             description: "Target temperature in selected unit"]
        ]

        // Velocidade do vento (sem SLOW / POWER)
        command "setWindStrength", [
            [name: "Set Wind Strength", type: "ENUM",
             description: "Select Wind Strength",
             constraints: ["LOW", "MID", "HIGH", "AUTO"]]
        ]

        // Controle de windStep (1..5)
        command "setWindStep", [
            [name: "Wind Step", type: "NUMBER",
             description: "Wind step (1–5)"]
        ]

        // Direção do vento – apenas swing vertical
        command "setRotateUpDown", [
            [name: "Vertical Swing", type: "ENUM",
             description: "Enable/disable vertical swing",
             constraints: ["ON", "OFF"]]
        ]

        // Display
        command "setDisplayLight", [
            [name: "Display Light", type: "ENUM",
             description: "Turn display light ON or OFF",
             constraints: ["ON", "OFF"]]
        ]
        command "toggleDisplayLight"
    }

    preferences {
        section {
            input name: "isFahrenheit", type: "bool",
                  title: "<b>Fahrenheit</b>",
                  description: "<i>Show/set temperatures in Fahrenheit (API continua em °C)</i>",
                  defaultValue: false
            input name: "logLevel", title: "Log Level",
                  type: "enum", options: LOG_LEVELS,
                  defaultValue: DEFAULT_LOG_LEVEL, required: false
            input name: "logDescText", title: "Log Description Text",
                  type: "bool", defaultValue: false, required: false
        }
    }
}

/* =========================================================
 *  Lifecycle
 * ========================================================= */

def installed() {
    logger("debug", "installed()")
    initialize()
}

def updated() {
    logger("debug", "updated()")
    initialize()
}

def uninstalled() {
    logger("debug", "uninstalled()")
}

/**
 * Conecta MQTT (se master) e faz refresh inicial
 */
def initialize() {
    logger("debug", "initialize()")

    if (getDataValue("master") == "true") {
        if (interfaces.mqtt.isConnected()) {
            interfaces.mqtt.disconnect()
        }
        mqttConnectUntilSuccessful()
    }

    // Modos suportados de thermostat e fan
    sendEvent(
        name: "supportedThermostatModes",
        value: new groovy.json.JsonBuilder(["off","cool","heat","fan","dry"]).toString()
    )
    sendEvent(
        name: "supportedFanModes",
        value: new groovy.json.JsonBuilder(
            ["off","low","medium-low","medium","medium-high","high","auto"]
        ).toString()
    )

    refresh()
}

def refresh() {
    logger("debug", "refresh()")
    def status = parent.getDeviceState(getDeviceId())
    processStateData(status)
}

/* =========================================================
 *  MQTT
 * ========================================================= */

def mqttConnectUntilSuccessful() {
    logger("debug", "mqttConnectUntilSuccessful()")

    try {
        def mqtt = parent.retrieveMqttDetails()

        interfaces.mqtt.connect(
            mqtt.server,
            mqtt.clientId,
            null,
            null,
            tlsVersion: "1.2",
            privateKey: mqtt.privateKey,
            caCertificate: mqtt.caCertificate,
            clientCertificate: mqtt.certificate,
            cleanSession: true,
            ignoreSSLIssues: true
        )

        pauseExecution(3000)

        mqtt.subscriptions.each { sub ->
            interfaces.mqtt.subscribe(sub)
        }
        return true
    } catch (e) {
        logger("warn", "Lost connection to MQTT, retrying in 15 seconds ${e}")
        runIn(15, "mqttConnectUntilSuccessful")
        return false
    }
}

def parse(message) {
    def topic = interfaces.mqtt.parseMessage(message)
    def payload = new JsonSlurper().parseText(topic.payload)
    logger("trace", "parse(${payload})")

    parent.processMqttMessage(this, payload)
}

def mqttClientStatus(String message) {
    logger("debug", "mqttClientStatus(${message})")

    if (message.startsWith("Error:")) {
        logger("error", "MQTT Error: ${message}")
        try {
            interfaces.mqtt.disconnect()
        } catch (e) { }
        mqttConnectUntilSuccessful()
    }
}

/* =========================================================
 *  Processamento de estado
 * ========================================================= */

def processStateData(data) {
    logger("debug", "processStateData(${data})")

    if (!data) return

    // runState.currentState (NORMAL / ERROR)
    if (data.runState?.currentState) {
        sendEvent(name: "currentState", value: data.runState.currentState.toString())
    }

    // Estado principal (POWER_ON / POWER_OFF)
    if (data.operation?.airConOperationMode) {
        def opMode = data.operation.airConOperationMode?.toString()
        sendEvent(name: "airConOperationMode", value: opMode)

        def sw = (opMode == "POWER_OFF") ? "off" : "on"
        sendEvent(name: "switch", value: sw)
        if (logDescText) {
            log.info "${device.displayName} Switch: ${sw}, Operation: ${opMode}"
        }
        if (sw == "off") {
            sendEvent(name: "thermostatMode", value: "off")
        }
    }

    // airConJobMode (COOL / HEAT / FAN / AIR_DRY)
    if (data.airConJobMode?.currentJobMode) {
        def jobMode = data.airConJobMode.currentJobMode?.toString()
        sendEvent(name: "currentJobMode", value: jobMode)

        // Mapear para thermostatMode
        def tMode = "off"
        switch (jobMode) {
            case "COOL":     tMode = "cool";    break
            case "HEAT":     tMode = "heat";    break
            case "FAN":      tMode = "fanOnly"; break
            case "AIR_DRY":  tMode = "dry";     break
        }
        sendEvent(name: "thermostatMode", value: tMode)
    }

    // Temperatura
    if (data.temperature?.unit) {
        sendEvent(name: "temperatureUnit", value: data.temperature.unit)
    }

    if (data.temperature?.currentTemperature != null) {
        def currentC = data.temperature.currentTemperature as BigDecimal
        def shown = isFahrenheit ? cToF(currentC) : currentC
        sendEvent(name: "temperature", value: shown, unit: isFahrenheit ? "F" : "C")
        sendEvent(name: "currentTemperature", value: shown, unit: isFahrenheit ? "F" : "C")
    }

    if (data.temperature?.targetTemperature != null) {
        def targetC = data.temperature.targetTemperature as BigDecimal
        def shown = isFahrenheit ? cToF(targetC) : targetC
        sendEvent(name: "targetTemperature", value: shown, unit: isFahrenheit ? "F" : "C")
        sendEvent(name: "coolingSetpoint", value: shown, unit: isFahrenheit ? "F" : "C")
    }

    if (data.temperature?.coolTargetTemperature != null) {
        def coolC = data.temperature.coolTargetTemperature as BigDecimal
        def shown = isFahrenheit ? cToF(coolC) : coolC
        sendEvent(name: "coolTargetTemperature", value: shown, unit: isFahrenheit ? "F" : "C")
    }

    if (data.temperature?.heatTargetTemperature != null) {
        def heatC = data.temperature.heatTargetTemperature as BigDecimal
        def shown = isFahrenheit ? cToF(heatC) : heatC
        sendEvent(name: "heatTargetTemperature", value: shown, unit: isFahrenheit ? "F" : "C")
    }

    if (data.temperature?.minTargetTemperature != null) {
        sendEvent(name: "minTargetTemperature", value: data.temperature.minTargetTemperature)
    }
    if (data.temperature?.maxTargetTemperature != null) {
        sendEvent(name: "maxTargetTemperature", value: data.temperature.maxTargetTemperature)
    }

    // Fan / windStrength / windStep / windStrengthDetail
    if (data.airFlow) {
        if (data.airFlow.windStrength) {
            def raw = data.airFlow.windStrength?.toString()  // AUTO/HIGH/LOW/MID
            sendEvent(name: "windStrength", value: raw)
        }

        if (data.airFlow.windStep != null) {
            Integer step = data.airFlow.windStep as Integer
            sendEvent(name: "windStep", value: step)

            // Mapear step -> speed FanControl
            String speed
            switch (step) {
                case 1: speed = "low";         break
                case 2: speed = "medium-low";  break
                case 3: speed = "medium";      break
                case 4: speed = "medium-high"; break
                case 5: speed = "high";        break
                default: speed = "low"
            }
            sendEvent(name: "speed", value: speed)
        }

        if (data.airFlow.windStrengthDetail) {
            sendEvent(name: "windStrengthDetail", value: data.airFlow.windStrengthDetail.toString())
        }
    }

    // Direção do vento – só rotateUpDown
    if (data.windDirection != null && data.windDirection.containsKey("rotateUpDown")) {
        sendEvent(
            name: "rotateUpDown",
            value: data.windDirection.rotateUpDown ? "on" : "off"
        )
    }

    // SleepTimer (somente leitura por enquanto)
    if (data.sleepTimer) {
        if (data.sleepTimer.relativeHourToStop != null) {
            sendEvent(name: "sleepRelativeHourToStop", value: data.sleepTimer.relativeHourToStop)
        }
        if (data.sleepTimer.relativeMinuteToStop != null) {
            sendEvent(name: "sleepRelativeMinuteToStop", value: data.sleepTimer.relativeMinuteToStop)
        }
        if (data.sleepTimer.relativeStopTimer) {
            sendEvent(name: "sleepRelativeStopTimer", value: data.sleepTimer.relativeStopTimer.toString())
        }
    }

    // Timer absoluto (somente leitura por enquanto)
    if (data.timer) {
        if (data.timer.absoluteHourToStart != null)
            sendEvent(name: "absoluteHourToStart", value: data.timer.absoluteHourToStart)
        if (data.timer.absoluteMinuteToStart != null)
            sendEvent(name: "absoluteMinuteToStart", value: data.timer.absoluteMinuteToStart)
        if (data.timer.absoluteHourToStop != null)
            sendEvent(name: "absoluteHourToStop", value: data.timer.absoluteHourToStop)
        if (data.timer.absoluteMinuteToStop != null)
            sendEvent(name: "absoluteMinuteToStop", value: data.timer.absoluteMinuteToStop)
        if (data.timer.absoluteStartTimer)
            sendEvent(name: "absoluteStartTimer", value: data.timer.absoluteStartTimer.toString())
        if (data.timer.absoluteStopTimer)
            sendEvent(name: "absoluteStopTimer", value: data.timer.absoluteStopTimer.toString())
    }

    // Display (light)
    if (data.display?.light) {
        sendEvent(name: "displayLight", value: data.display.light?.toString())
    }

    // Filtro
    if (data.filterInfo?.usedTime != null) {
        sendEvent(name: "filterUsedTime", value: data.filterInfo.usedTime, unit: "hours")
    }
    if (data.filterInfo?.filterLifetime != null) {
        sendEvent(name: "filterLifetime", value: data.filterInfo.filterLifetime, unit: "hours")
    }
    if (data.filterInfo?.filterRemainPercent != null) {
        sendEvent(name: "filterRemainPercent", value: data.filterInfo.filterRemainPercent, unit: "%")
    }

    // Qualidade do ar (se vier no state – não está no profile deste JSON, mas deixo o suporte)
    if (data.airQualitySensor) {
        def aq = data.airQualitySensor
        if (aq.PM1 != null)  sendEvent(name: "pm1", value: aq.PM1)
        if (aq.PM2 != null)  sendEvent(name: "pm2", value: aq.PM2)
        if (aq.PM10 != null) sendEvent(name: "pm10", value: aq.PM10)
        if (aq.humidity != null)        sendEvent(name: "humidity", value: aq.humidity, unit: "%")
        if (aq.totalPollution != null)  sendEvent(name: "totalPollution", value: aq.totalPollution)
        if (aq.totalPollutionLevel)     sendEvent(name: "totalPollutionLevel", value: aq.totalPollutionLevel)
    }

    // Erro se vier algo genérico
    if (data.error) {
        def err = data.error.toString()
        sendEvent(name: "error", value: err)
    }
}

/* =========================================================
 *  Comandos básicos / Profile
 * ========================================================= */

def getDeviceProfile() {
    logger("debug", "getDeviceProfile()")
    parent.getDeviceProfile(getDeviceId())
}

/**
 * Gera JSON do device profile e salva no atributo deviceProfileJson
 */
def generateDeviceProfileJson() {
    logger("debug", "generateDeviceProfileJson()")
    try {
        def profile = parent.getDeviceProfile(getDeviceId())
        def json = JsonOutput.prettyPrint(JsonOutput.toJson(profile))
        sendEvent(name: "deviceProfileJson", value: json)
        logger("info", "Device profile JSON atualizado no atributo 'deviceProfileJson'")
    } catch (e) {
        logger("error", "Falha ao gerar device profile JSON: ${e}")
    }
}

/* =========================================================
 *  Thermostat / Power / Modes
 * ========================================================= */

def on() {
    logger("debug", "on()")
    def deviceId = getDeviceId()
    def command = [
        operation: [
            airConOperationMode: "POWER_ON"
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def off() {
    logger("debug", "off()")
    def deviceId = getDeviceId()
    def command = [
        operation: [
            airConOperationMode: "POWER_OFF"
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

/**
 * Comando padrão do capability Thermostat.
 * Aceita: "off","cool","heat","auto","fan","dry"
 *
 * IMPORTANTE: este modelo NÃO tem AUTO em airConJobMode.
 * Então "auto" aqui será implementado como COOL.
 */
def setThermostatMode(String mode) {
    logger("debug", "setThermostatMode(${mode})")
    mode = (mode ?: "").toLowerCase()

    switch (mode) {
        case "off":
            off()
            sendEvent(name: "thermostatMode", value: "off")
            return

        case "cool":
            on()
            setAirConJobMode("COOL")
            break

        case "heat":
            on()
            setAirConJobMode("HEAT")
            break

        case "auto":
            // esse modelo não tem AUTO real -> usamos COOL como fallback
            on()
            setAirConJobMode("COOL")
            break

        case "fan":
        case "fanonly":
            on()
            setAirConJobMode("FAN")
            mode = "fan"
            break

        case "dry":
            on()
            setAirConJobMode("AIR_DRY")
            break

        default:
            logger("warn", "setThermostatMode: modo desconhecido '${mode}', ignorando")
            return
    }

    sendEvent(name: "thermostatMode", value: mode)
}

/* Wrappers exigidos pelo capability Thermostat */

def heat() {
    logger("debug", "heat()")
    setThermostatMode("heat")
}

def cool() {
    logger("debug", "cool()")
    setThermostatMode("cool")
}

def auto() {
    logger("debug", "auto()")
    setThermostatMode("auto")
}

def fanOn() {
    logger("debug", "fanOn()")
    setThermostatMode("fan")
}

def fanAuto() {
    logger("debug", "fanAuto()")
    setThermostatMode("auto")
}

def fanCirculate() {
    logger("debug", "fanCirculate()")
    setThermostatMode("fan")
}

def emergencyHeat() {
    logger("debug", "emergencyHeat()")
    setThermostatMode("heat")
}

/**
 * Mantemos o comando explícito do job mode para automações
 * (apenas COOL/HEAT/FAN/AIR_DRY conforme o profile)
 */
def setAirConJobMode(mode) {
    logger("debug", "setAirConJobMode(${mode})")
    def valid = ["COOL", "HEAT", "FAN", "AIR_DRY"]
    if (!valid.contains(mode)) {
        logger("warn", "setAirConJobMode: modo inválido '${mode}', aceitos: ${valid}")
        return
    }
    def deviceId = getDeviceId()
    def command = [
        airConJobMode: [
            currentJobMode: mode
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

/* ---------- Temperatura ---------- */

/**
 * Define a temperatura alvo usando o nó "temperature.targetTemperature".
 * Se isFahrenheit = true, converte de F para C antes de enviar.
 */
def setTargetTemperature(temp) {
    logger("debug", "setTargetTemperature(${temp})")
    if (temp == null) return

    BigDecimal target = temp as BigDecimal
    BigDecimal valueC = isFahrenheit ? fToC(target) : target

    def deviceId = getDeviceId()
    def command = [
        temperature: [
            targetTemperature: valueC,
            unit: "C"
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)

    // Atualiza atributos locais no formato escolhido pelo usuário
    def shown = isFahrenheit ? target : valueC
    def unit = isFahrenheit ? "F" : "C"
    sendEvent(name: "targetTemperature", value: shown, unit: unit)
    sendEvent(name: "coolingSetpoint", value: shown, unit: unit)
}

/* ---------- Fan / vento ---------- */

def setWindStrength(strength) {
    logger("debug", "setWindStrength(${strength})")
    def valid = ["LOW", "MID", "HIGH", "AUTO"]
    if (!valid.contains(strength)) {
        logger("warn", "setWindStrength: valor inválido '${strength}', usando LOW como fallback")
        strength = "LOW"
    }

    def deviceId = getDeviceId()
    def command = [
        airFlow: [
            windStrength: strength
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

/**
 * Controle de windStep 1..5 (range no profile)
 */
def setWindStep(step) {
    logger("debug", "setWindStep(${step})")
    if (step == null) return
    Integer val = (step as Integer)
    if (val < 1) val = 1
    if (val > 5) val = 5

    def deviceId = getDeviceId()
    def command = [
        airFlow: [
            windStep: val
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

/**
 * FanControl padrão do Hubitat – mapeia para windStrength + windStep.
 *
 * speed:
 *  - off
 *  - low
 *  - medium-low
 *  - medium
 *  - medium-high
 *  - high
 *  - auto
 */
def setSpeed(String speed) {
    logger("debug", "setSpeed(${speed})")
    speed = (speed ?: "").toLowerCase()

    if (speed == "off") {
        off()
        return
    }

    switch (speed) {
        case "low":
            setWindStrength("LOW")
            setWindStep(1)
            break

        case "medium-low":
            setWindStrength("LOW")
            setWindStep(2)
            break

        case "medium":
            setWindStrength("MID")
            setWindStep(3)
            break

        case "medium-high":
            setWindStrength("HIGH")
            setWindStep(4)
            break

        case "high":
            setWindStrength("HIGH")
            setWindStep(5)
            break

        case "auto":
            setWindStrength("AUTO")
            // não força step – deixa o AC decidir
            break

        default:
            logger("warn", "setSpeed: valor inválido '${speed}', usando 'low'")
            setWindStrength("LOW")
            setWindStep(1)
    }
}

/* ---------- Direção do vento (apenas vertical) ---------- */

def setRotateUpDown(String value) {
    logger("debug", "setRotateUpDown(${value})")
    boolean enabled = (value?.toUpperCase() == "ON")
    def deviceId = getDeviceId()
    def command = [
        windDirection: [
            rotateUpDown: enabled
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

/* ---------- Display ---------- */

def setDisplayLight(String state) {
    logger("debug", "setDisplayLight(${state})")
    String s = (state ?: "").toUpperCase()
    if (!(s in ["ON", "OFF"])) {
        logger("warn", "setDisplayLight: valor inválido '${state}', usando OFF")
        s = "OFF"
    }

    def deviceId = getDeviceId()
    def command = [
        display: [
            light: s
        ]
    ]
    parent.sendDeviceCommand(deviceId, command)
}

def toggleDisplayLight() {
    String current = (device.currentValue("displayLight") ?: "OFF").toString()
    String next = (current == "ON") ? "OFF" : "ON"
    setDisplayLight(next)
}

/* =========================================================
 *  Helpers
 * ========================================================= */

def getDeviceId() {
    return device.deviceNetworkId.replace("thinqconnect:", "")
}

def getDeviceDetails() {
    def deviceId = getDeviceId()
    return parent.state.foundDevices.find { it.id == deviceId }
}

private BigDecimal cToF(BigDecimal c) {
    return (c * 9 / 5) + 32
}

private BigDecimal fToC(BigDecimal f) {
    return (f - 32) * 5 / 9
}

/**
 * @param level Level to log at, see LOG_LEVELS for options
 * @param msg   Message to log
 */
private logger(level, msg) {
    if (level && msg) {
        Integer levelIdx = LOG_LEVELS.indexOf(level)
        Integer setLevelIdx = LOG_LEVELS.indexOf(logLevel)
        if (setLevelIdx < 0) {
            setLevelIdx = LOG_LEVELS.indexOf(DEFAULT_LOG_LEVEL)
        }
        if (levelIdx <= setLevelIdx) {
            log."${level}" "${device.displayName} ${msg}"
        }
    }
}
