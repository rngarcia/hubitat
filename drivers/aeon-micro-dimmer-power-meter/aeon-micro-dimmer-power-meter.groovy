/**
 *  Aeon Micro Dimmer with Power Meter — Hubitat Driver
 *  Versão: 1.0.1 — Build: 2026-11-02
 *  Autor: Ricardo Nogueira Garcia
 *  Repositório: https://github.com/rngarcia/hubitat
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  POR QUE ESTE DRIVER EXISTE
 *  O driver nativo "Aeon Micro Dimmer" do Hubitat expoe apenas Switch e Level. O equipamento, porem, anuncia a
 *  classe de comando Meter (0x32) e responde a MeterGet com potencia (W) e energia acumulada (kWh). Este driver
 *  expoe esses valores como Power Meter / Energy Meter.
 *
 *  O QUE FOI VERIFICADO (2 unidades com a mesma identificacao: Z-Wave manufacturer 0x0086, product type 0x0003,
 *  product id 0x0013; a maior parte dos testes de reporte automatico foi feita em uma delas)
 *  - MeterGet scale 2 (W) e scale 0 (kWh) respondem com valores coerentes com a carga.
 *  - Os parametros 80, 90, 91, 92, 101, 102, 111 e 112 sao aceitos e voltam com o valor gravado.
 *  - Energia (kWh): o reporte automatico respeita o intervalo do parametro 112.
 *  - Potencia (W): o reporte automatico NAO e regular (apareceu de forma esporadica, mesmo com o parametro 111
 *    em 300 s). Por isso o driver consulta a potencia periodicamente (preferencia "Poll power every N minutes").
 *  - Parametro 80 = 2 (Basic Report): o Hubitat passa a saber quando o dimmer e acionado na parede.
 *  Os numeros de parametro seguem a familia Aeon "Micro"; nao foram confirmados no manual do modelo.
 *
 *  Changelog:
 *  v1.0.1 (2026-11-02) - RNG
 *    - Log Level padrao passa de warn para info, para a leitura de volta dos parametros (ConfigurationReport)
 *      e as linhas "was turned on/off" aparecerem sem precisar mexer nas preferencias
 *  v1.0.0 (2026-11-02) - RNG
 *    - Primeira versao: Switch, SwitchLevel, PowerMeter, EnergyMeter, Refresh, Configuration
 *    - Configure grava a associacao com o hub e os parametros de reporte, e le cada um de volta
 *    - Polling de potencia configuravel (padrao 1 min; 0 desliga); kWh por reporte automatico
 *    - Salvar as preferencias reaplica a configuracao no dispositivo
 */

import groovy.transform.Field

@Field String VERSION = "1.0.1"
@Field List<String> LOG_LEVELS = ["error", "warn", "info", "debug", "trace"]
@Field String DEFAULT_LOG_LEVEL = LOG_LEVELS[2]

metadata {
  definition (name: "Aeon Micro Dimmer with Power Meter", namespace: "rng", author: "Ricardo Nogueira Garcia",
        importUrl: "https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/aeon-micro-dimmer-power-meter/aeon-micro-dimmer-power-meter.groovy"
    ) {
    capability "Actuator"
    capability "Switch"
    capability "SwitchLevel"
    capability "Sensor"
    capability "PowerMeter"
    capability "EnergyMeter"
    capability "Refresh"
    capability "Configuration"

    command "setParam", [
      [name: "Parameter number*", type: "NUMBER"],
      [name: "Value*", type: "NUMBER"],
      [name: "Size (bytes)*", type: "ENUM", constraints: ["1", "2", "4"]]
    ]
    command "getParam", [[name: "Parameter number*", type: "NUMBER"]]

    // Identificacao lida do dispositivo: Device Id 19 (0x0013), Device Type 3 (0x0003), Manufacturer 134 (0x0086)
    fingerprint mfr: "0086", prod: "0003", deviceId: "0013", inClusters: "0x26,0x32,0x27,0x2C,0x2B,0x70,0x85,0x72,0x86", outClusters: "0x82", deviceJoinName: "Aeon Micro Dimmer with Power Meter"
  }

  preferences {
    section { // General
      input name: "logLevel", title: "Log Level", type: "enum", options: LOG_LEVELS, defaultValue: DEFAULT_LOG_LEVEL, required: false
      input name: "logDescText", title: "Log Description Text (switch and level changes)", type: "bool", defaultValue: true, required: false
    }
    section { // Power polling
      input name: "pollMinutes", title: "Poll power (W) every N minutes (0 = off)", description: "Automatic power reports from this device are irregular, so the driver asks for it.", type: "number", range: "0..59", defaultValue: 1, required: true
    }
    section { // Device configuration (applied on Save and on Configure)
      input name: "notifyPhysical", title: "Report wall switch changes to the hub (param 80 = Basic Report)", type: "bool", defaultValue: true, required: false
      input name: "wattThreshold", title: "Report power when it changes by at least (W) (param 91)", type: "number", range: "1..1000", defaultValue: 2, required: true
      input name: "wattPercent", title: "or by at least (%) (param 92)", type: "number", range: "1..100", defaultValue: 5, required: true
      input name: "powerInterval", title: "Power report interval, seconds (param 111)", type: "number", range: "5..86400", defaultValue: 300, required: true
      input name: "energyInterval", title: "Energy (kWh) report interval, seconds (param 112)", type: "number", range: "5..86400", defaultValue: 900, required: true
    }
  }
}

// ==================== LIFECYCLE ====================

def installed() {
  logger("debug", "installed(${VERSION})")
  state.driverInfo = [ver: VERSION]
  schedulePolling()
  runIn(10, "applyConfiguration")
}

def updated() {
  logger("debug", "updated()")
  if (!state.driverInfo?.ver || state.driverInfo.ver != VERSION) {
    state.driverInfo = [ver: VERSION]
  }
  schedulePolling()
  applyConfiguration()
}

private schedulePolling() {
  unschedule("pollPower")
  Integer n = (settings?.pollMinutes != null ? settings.pollMinutes : 1) as Integer
  if (n > 0) {
    // cron do Hubitat (Quartz): segundos minutos horas dia-do-mes mes dia-da-semana
    schedule("0 0/${n} * ? * *", "pollPower")
    logger("info", "power polling every ${n} min")
  } else {
    logger("info", "power polling off")
  }
}

// Consulta so a potencia (W). A energia (kWh) vem pelo reporte automatico (parametro 112).
def pollPower() {
  logger("debug", "pollPower()")
  sendHubCommand(new hubitat.device.HubAction(zwave.meterV3.meterGet(scale: 2).format(), hubitat.device.Protocol.ZWAVE))
}

// ==================== COMMANDS ====================

def on() {
  logger("debug", "on()")
  delayBetween([
    zwave.switchMultilevelV3.switchMultilevelSet(value: 0xFF).format(),
    zwave.switchMultilevelV3.switchMultilevelGet().format()
  ], 500)
}

def off() {
  logger("debug", "off()")
  delayBetween([
    zwave.switchMultilevelV3.switchMultilevelSet(value: 0x00).format(),
    zwave.switchMultilevelV3.switchMultilevelGet().format()
  ], 500)
}

def setLevel(level, duration = null) {
  logger("debug", "setLevel(${level})")
  Integer lvl = Math.max(0, Math.min(99, level as Integer))
  delayBetween([
    zwave.switchMultilevelV3.switchMultilevelSet(value: lvl).format(),
    zwave.switchMultilevelV3.switchMultilevelGet().format()
  ], 500)
}

def refresh() {
  logger("debug", "refresh()")
  delayBetween([
    zwave.switchMultilevelV3.switchMultilevelGet().format(),
    zwave.meterV3.meterGet(scale: 2).format(),  // 2 = W
    zwave.meterV3.meterGet(scale: 0).format()   // 0 = kWh
  ], 800)
}

def configure() {
  logger("info", "configure(): hub association and meter report parameters")
  return configureCmds()
}

// Usado por updated()/installed(), que nao podem devolver comandos ao hub
def applyConfiguration() {
  sendHubCommand(new hubitat.device.HubMultiAction(configureCmds(), hubitat.device.Protocol.ZWAVE))
}

/*
 * Parametros (familia Aeon "Micro"; lidos de volta para conferir o que o equipamento aceitou):
 *   80  (1 byte)  0 = nada, 1 = Hail, 2 = Basic Report quando a carga muda de estado
 *   90  (1 byte)  1 = habilita os limiares dos parametros 91 e 92
 *   91  (2 bytes) variacao minima em W para reportar
 *   92  (1 byte)  variacao minima em % para reportar
 *   101 (4 bytes) 4 = o grupo 1 envia Meter Report de W
 *   102 (4 bytes) 8 = o grupo 2 envia Meter Report de kWh
 *   111 (4 bytes) intervalo do grupo 1, em segundos
 *   112 (4 bytes) intervalo do grupo 2, em segundos
 */
private List configureCmds() {
  Integer wt = Math.max(1, (settings?.wattThreshold ?: 2) as Integer)
  Integer wp = Math.max(1, (settings?.wattPercent ?: 5) as Integer)
  Integer pi = Math.max(5, (settings?.powerInterval ?: 300) as Integer)
  Integer ei = Math.max(5, (settings?.energyInterval ?: 900) as Integer)
  Integer p80 = (settings?.notifyPhysical == false) ? 0 : 2

  List<String> cmds = []
  cmds << zwave.associationV1.associationSet(groupingIdentifier: 1, nodeId: [zwaveHubNodeId]).format()
  cmds << zwave.configurationV1.configurationSet(parameterNumber: 80,  size: 1, scaledConfigurationValue: p80).format()
  cmds << zwave.configurationV1.configurationSet(parameterNumber: 90,  size: 1, scaledConfigurationValue: 1).format()
  cmds << zwave.configurationV1.configurationSet(parameterNumber: 91,  size: 2, scaledConfigurationValue: wt).format()
  cmds << zwave.configurationV1.configurationSet(parameterNumber: 92,  size: 1, scaledConfigurationValue: wp).format()
  cmds << zwave.configurationV1.configurationSet(parameterNumber: 101, size: 4, scaledConfigurationValue: 4).format()
  cmds << zwave.configurationV1.configurationSet(parameterNumber: 102, size: 4, scaledConfigurationValue: 8).format()
  cmds << zwave.configurationV1.configurationSet(parameterNumber: 111, size: 4, scaledConfigurationValue: pi).format()
  cmds << zwave.configurationV1.configurationSet(parameterNumber: 112, size: 4, scaledConfigurationValue: ei).format()
  [80, 90, 91, 92, 101, 102, 111, 112].each { Integer p ->
    cmds << zwave.configurationV1.configurationGet(parameterNumber: p).format()
  }
  return delayBetween(cmds, 600)
}

def setParam(param, value, size) {
  logger("info", "setParam: parameter ${param} = ${value} (${size} byte(s))")
  delayBetween([
    zwave.configurationV1.configurationSet(
      parameterNumber: param as Integer,
      size: size as Integer,
      scaledConfigurationValue: value as Integer).format(),
    zwave.configurationV1.configurationGet(parameterNumber: param as Integer).format()
  ], 500)
}

def getParam(param) {
  zwave.configurationV1.configurationGet(parameterNumber: param as Integer).format()
}

// ==================== PARSE ====================

def parse(String description) {
  logger("trace", "parse: ${description}")
  def cmd = zwave.parse(description, [0x20: 1, 0x26: 3, 0x32: 3, 0x70: 1])
  if (cmd) {
    zwaveEvent(cmd)
  } else {
    logger("warn", "parse: could not interpret '${description}'")
  }
  return null
}

def zwaveEvent(hubitat.zwave.commands.meterv3.MeterReport cmd) {
  logger("debug", "MeterReport: meterType=${cmd.meterType} scale=${cmd.scale} value=${cmd.scaledMeterValue} previous=${cmd.scaledPreviousMeterValue} delta=${cmd.deltaTime}s")
  if (cmd.scale == 0) {
    sendEvent(name: "energy", value: cmd.scaledMeterValue, unit: "kWh", descriptionText: "${device.displayName} energy is ${cmd.scaledMeterValue} kWh")
  } else if (cmd.scale == 2) {
    // uma casa decimal: o equipamento reporta 3 e isso so geraria eventos a cada leitura
    BigDecimal watts = (cmd.scaledMeterValue as BigDecimal).setScale(1, java.math.RoundingMode.HALF_UP)
    sendEvent(name: "power", value: watts, unit: "W", descriptionText: "${device.displayName} power is ${watts} W")
  } else {
    logger("warn", "MeterReport with unexpected scale: ${cmd.scale}")
  }
}

def zwaveEvent(hubitat.zwave.commands.switchmultilevelv3.SwitchMultilevelReport cmd) {
  levelEvents(cmd.value)
}

def zwaveEvent(hubitat.zwave.commands.switchmultilevelv3.SwitchMultilevelSet cmd) {
  levelEvents(cmd.value)
}

def zwaveEvent(hubitat.zwave.commands.basicv1.BasicReport cmd) {
  levelEvents(cmd.value)
}

def zwaveEvent(hubitat.zwave.commands.basicv1.BasicSet cmd) {
  levelEvents(cmd.value)
}

private levelEvents(rawValue) {
  String sw = rawValue ? "on" : "off"
  String desc = "${device.displayName} was turned ${sw}"
  if (logDescText != false && device.currentValue("switch") != sw) {
    logger("info", desc)
  }
  sendEvent(name: "switch", value: sw, descriptionText: desc)
  if (rawValue) {
    Integer lvl = (rawValue as Integer) == 255 ? 100 : Math.min(rawValue as Integer, 99)
    if (logDescText != false && (device.currentValue("level") as Integer) != lvl) {
      logger("info", "${device.displayName} level is ${lvl}%")
    }
    sendEvent(name: "level", value: lvl, unit: "%", descriptionText: "${device.displayName} level is ${lvl}%")
  }
}

def zwaveEvent(hubitat.zwave.commands.configurationv1.ConfigurationReport cmd) {
  logger("info", "ConfigurationReport: parameter ${cmd.parameterNumber} = ${cmd.scaledConfigurationValue} (size ${cmd.size})")
}

def zwaveEvent(hubitat.zwave.Command cmd) {
  logger("debug", "unhandled command: ${cmd}")
}

// ==================== LOGGING ====================

private logger(String level, String msg) {
  Integer levelIdx = LOG_LEVELS.indexOf(level)
  Integer setLevelIdx = LOG_LEVELS.indexOf(settings?.logLevel ?: DEFAULT_LOG_LEVEL)
  if (setLevelIdx < 0) { setLevelIdx = LOG_LEVELS.indexOf(DEFAULT_LOG_LEVEL) }
  if (levelIdx >= 0 && levelIdx <= setLevelIdx) {
    log."${level}"(msg)
  }
}
