/**
 *  Remotec ZXT-120 (rng) — Hubitat Driver
 *  Versão: 0.1.1 — Build: 2026-10-21
 *  Autor original: Bryan Copeland (djdizzyd)
 *  Modificado por: Ricardo Nogueira Garcia
 *  Repositório: https://github.com/rngarcia/hubitat
 *
 *  Changelog:
 *  v0.1.1 (2026-10-21) - RNG
 *    - Publicado em rngarcia/hubitat: cabeçalho padronizado, namespace "rng" e importUrl
 *    - Nome do driver "ZXT-120 Ricardo" -> "Remotec ZXT-120 (rng)"
 *  (histórico anterior no bloco de comentário abaixo)
 */
import groovy.transform.Field

/**
 * ZXT-120 Ricardo
 * v0.1B
 */

metadata {
    definition (name: "Remotec ZXT-120 (rng)", namespace: "rng", author: "Bryan Copeland; Ricardo Nogueira Garcia",
        importUrl: "https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/remotec-zxt-120/remotec-zxt-120.groovy"
    ) {

        capability "Actuator"
        capability "Battery"
        capability "Configuration"
        capability "Refresh"
        capability "Sensor"
        capability "TemperatureMeasurement"
        capability "Thermostat"
        capability "ThermostatMode"
        capability "ThermostatSetpoint"
        capability "ThermostatHeatingSetpoint"
        capability "ThermostatOperatingState"

        command "startLearning", [[name:"location",type:"ENUM", description:"location for IR code learning", constraints:["0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22"]]]

        attribute "learningStatus", "string"

        fingerprint mfr:"5254", prod:"0100", deviceId:"8377", inClusters:"0x20,0x27,0x31,0x40,0x43,0x44,0x70,0x72,0x80,0x86", deviceJoinName: "ZXT-120 Ricardo"
    }
    preferences {
        configParams.each { input it.value.input }
        input "logEnable", "bool", title: "Enable debug logging", defaultValue: false
    }

}

@Field static Map CMD_CLASS_VERS=[0x86:1, 0x80:1, 0x72:1, 0x70:1, 0x44:2, 0x43:2, 0x40:2, 0x31:1]
@Field static Map learingStatusCode=[0:"Idle",1:"OK",2:"Busy",4:"Failed"]
@Field static Map THERMOSTAT_MODE=[0x00:"off",0x01:"heat",0x02:"cool",0x03:"auto",0x04:"emergency heat"]
@Field static Map SET_THERMOSTAT_MODE=["off":0x00,"heat":0x01,"cool":0x02,"auto":0x03,"emergency heat":0x04]
@Field static Map THERMOSTAT_FAN_MODE=[0x00:"auto",0x01:"on"]
@Field static Map SET_THERMOSTAT_FAN_MODE=["auto":0x00,"on":0x01]
@Field static List<String> supportedThermostatFanModes=["on","auto"]
@Field static List<String> supportedThermostatModes=["off", "heat", "cool"]
@Field static Map configParams = [
        27: [input: [name: "configParam27", type: "number", title: "IR Code Number", description: "for built-in code library", defaultValue: 0], parameterSize: 2],
        28: [input: [name: "configParam28", type: "enum", title: "External IR Emitter Power", description:"", defaultValue: 0, options: [0:"Normal", (-1):"High"]], parameterSize: 1],
        32: [input: [name: "configParam32", type: "enum", title: "Surround IR Control", description:"", defaultValue: 0xFF, options: [0:"Disable", (-1):"Enabled"]], parameterSize: 1],
        33: [input: [name: "configParam33", type: "enum", title: "AC Swing Control", defaultValue: 1, options: [0:"Disabled", 1:"Auto"]], parameterSize: 1],
        37: [input: [name: "configParam37", type: "enum", title: "Temperature Offset", defaultValue: 0, options: [(-5):"-5°C", (-4):"-4°C", (-3):"-3°C", (-2):"-2°C", (-1):"-1°C",0:"0°C",1:"+1°C",2:"+2°C",3:"+3°C",4:"+4°C",5:"+5°C"]], parameterSize:1]
]

void logsOff(){
    log.warn "debug logging disabled..."
    device.updateSetting("logEnable",[value:"false",type:"bool"])
}

void configure() {
    if (!state.initialized) initializeVars()
    runIn(5, "pollDeviceData")
}

void initializeVars() {
    // first run only
    state.each{k -> v
        state.remove("$k")
    }
    sendEvent(name:"supportedThermostatModes", value: supportedThermostatModes.toString().replaceAll(/"/,""), isStateChange:true)
    sendEvent(name:"supportedThermostatFanModes", value: supportedThermostatFanModes.toString().replaceAll(/"/,""), isStateChange:true)
    state.initialized=true
    runIn(5, refresh)
}

void installed() {
    if (logEnable) log.debug "installed()..."
    initializeVars()
}

void updated() {
    log.info "updated..."
    log.warn "debug logging is: ${logEnable == true}"
    unschedule()
    if (logEnable) runIn(1800,logsOff)
    runConfigs()
}

void runConfigs() {
    List<hubitat.zwave.Command> cmds=[]
    configParams.each { param, data ->
        if (settings[data.input.name]) {
            cmds.addAll(configCmd(param, data.parameterSize, settings[data.input.name]))
        }
    }
    sendToDevice(cmds)
}

List<hubitat.zwave.Command> pollConfigs() {
    List<hubitat.zwave.Command> cmds=[]
    configParams.each { param, data ->
        if (settings[data.input.name]) {
            cmds.add(zwave.configurationV1.configurationGet(parameterNumber: param.toInteger()))
        }
    }
    return cmds
}

List<hubitat.zwave.Command> configCmd(parameterNumber, size, scaledConfigurationValue) {
    List<hubitat.zwave.Command> cmds = []
    int intval=scaledConfigurationValue.toInteger()
    if (size==1) {
        if (intval < 0) intval = 256 + intval
        cmds.add(zwave.configurationV1.configurationSet(parameterNumber: parameterNumber.toInteger(), size: size.toInteger(), configurationValue: [intval]))
    } else {
        cmds.add(zwave.configurationV1.configurationSet(parameterNumber: parameterNumber.toInteger(), size: size.toInteger(), scaledConfigurationValue: intval))
    }
    cmds.add(zwave.configurationV1.configurationGet(parameterNumber: parameterNumber.toInteger()))
    return cmds
}

void startLearning(location) {
    List<hubitat.zwave.Command> cmds=[]
    cmds.add(zwave.configurationV1.configurationSet(parameterNumber: 25, size:1, configurationValue: [location.toInteger()]))
    cmds.add(zwave.configurationV1.configurationGet(parameterNumber: 26))
}

void zwaveEvent(hubitat.zwave.commands.configurationv1.ConfigurationReport cmd) {
    int scaledValue
    cmd.configurationValue.reverse().eachWithIndex { v, index -> scaledValue=scaledValue | v << (8*index) }
    if(configParams[cmd.parameterNumber.toInteger()]) {
        Map configParam=configParams[cmd.parameterNumber.toInteger()]
        if (configParam.parameterSize==1 && configParam.input.range) {
            if (configParam.input.range.matches("-(.*)")) {
                if (scaledValue > 127) {
                    scaledValue = scaledValue - 256
                }
            }
        }
        device.updateSetting(configParam.input.name, [value: "${scaledValue}", type: configParam.input.type])
    } else {
        if (cmd.parameterNumber.toInteger()==26) {
            eventProcess(name: "learningStatus", value: learingStatusCode[cmd.configurationValue[0]])
        }
    }
}

void eventProcess(Map evt) {
    if (device.currentValue(evt.name).toString() != evt.value.toString()) {
        evt.isStateChange=true
        sendEvent(evt)
    }
}

void pollDeviceData() {
    List<hubitat.zwave.Command> cmds = []
    cmds.add(zwave.batteryV1.batteryGet())
    cmds.add(zwave.versionV1.versionGet())
    cmds.addAll(pollConfigs())
    sendToDevice(cmds)
}

void refresh() {
    List<hubitat.zwave.Command> cmds=[]
    cmds.add(zwave.batteryV1.batteryGet())
    cmds.add(zwave.sensorMultilevelV1.sensorMultilevelGet())
    cmds.add(zwave.thermostatModeV2.thermostatModeGet())
    cmds.add(zwave.thermostatSetpointV2.thermostatSetpointGet(setpointType: 1))
    cmds.add(zwave.thermostatSetpointV2.thermostatSetpointGet(setpointType: 2))
    cmds.add(zwave.configurationV1.configurationGet(parameterNumber: 26))
    sendToDevice(cmds)
}

void parse(String description) {
    if (logEnable) log.debug "parse:${description}"
    hubitat.zwave.Command cmd = zwave.parse(description, CMD_CLASS_VERS)
    if (cmd) {
        zwaveEvent(cmd)
    }
}

void zwaveEvent(hubitat.zwave.commands.versionv1.VersionReport cmd) {
    if (logEnable) log.debug "version1 report: ${cmd}"
    device.updateDataValue("firmwareVersion", "${cmd.applicationVersion}.${cmd.applicationSubVersion}")
    device.updateDataValue("protocolVersion", "${cmd.zWaveProtocolVersion}.${cmd.zWaveProtocolSubVersion}")
}

void sendToDevice(List<hubitat.zwave.Command> cmds) {
    sendHubCommand(new hubitat.device.HubMultiAction(commands(cmds), hubitat.device.Protocol.ZWAVE))
}

void sendToDevice(hubitat.zwave.Command cmd) {
    sendHubCommand(new hubitat.device.HubAction(cmd.format(), hubitat.device.Protocol.ZWAVE))
}

void sendToDevice(String cmd) {
    sendHubCommand(new hubitat.device.HubAction(cmd.format(), hubitat.device.Protocol.ZWAVE))
}

List<String> commands(List<hubitat.zwave.Command> cmds, Long delay=200) {
    return delayBetween(cmds.collect{ it.format() }, delay)
}

void zwaveEvent(hubitat.zwave.Command cmd) {
    if (logEnable) log.debug "skip:${cmd}"
}

void zwaveEvent(hubitat.zwave.commands.batteryv1.BatteryReport cmd) {
    if (logEnable) log.debug "got battery report: ${cmd.batteryLevel}"
    Map evt = [name: "battery", unit: "%"]
    if (cmd.batteryLevel == 0xFF) {
        evt.descriptionText = "${device.displayName} has a low battery"
        evt.value = "1"
    } else {
        evt.descriptionText = "${device.displayName} battery is ${cmd.batteryLevel}%"
        evt.value = "${cmd.batteryLevel}"
    }
    if (txtEnable) log.info evt.descriptionText
    eventProcess(evt)
}

void zwaveEvent(hubitat.zwave.commands.sensormultilevelv1.SensorMultilevelReport cmd) {
    if (cmd.sensorType.toInteger() == 1) {
        String scale = cmd.scale == 1 ? "F" : "C"
        BigDecimal sensorValue=0
        if (logEnable) log.debug "got temp: ${cmd.scaledSensorValue}"
        if (scale==getTemperatureScale()) {
            sensorValue=cmd.scaledSensorValue
        } else if (scale=="C" && getTemperatureScale()=="F") {
            sensorValue=celsiusToFahrenheit(cmd.scaledSensorValue)
        } else if (scale=="F" && getTemperatureScale()=="C") {
            sensorValue=fahrenheitToCelsius(cmd.scaledSensorValue)
        }
        eventProcess(name: "temperature", value: sensorValue, unit: getTemperatureScale())
    }
}

void setpointCalc(String newmode, String unit, value) {
    String mode="cool"
    if (device.currentValue("thermostatMode")=="heat" || device.currentValue("thermostatMode")=="emergency heat") {
        state.lastMode="heat"
        mode="heat"
    } else if (device.currentValue("thermostatMode")=="cool") {
        state.lastMode="cool"
        mode="cool"
    } else if (state.lastMode) {
        mode=state.lastMode
    }
    if (newmode==mode) {
        eventProcess(name: "thermostatSetpoint", value: value)
    }
}
