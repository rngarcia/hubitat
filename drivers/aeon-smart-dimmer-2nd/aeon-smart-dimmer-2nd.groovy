/**
 *  Aeon Smart Dimmer 2nd Edition — Hubitat Driver
 *  Versão: 1.0.1 — Build: 2026-10-21
 *  Autor original: SmartThings (zwave-metering-dimmer.groovy, Apache 2.0)
 *  Modificado por: Ricardo Nogueira Garcia
 *  Repositório: https://github.com/rngarcia/hubitat
 *
 *  Changelog:
 *  v1.0.1 (2026-10-21) - RNG
 *    - Publicado em rngarcia/hubitat: cabeçalho padronizado, namespace "rng" e importUrl
 *  (histórico anterior no bloco de comentário abaixo)
 */
/**
 *  Aeon Labs / Aeotec Smart Dimmer (2nd Edition) - DSC25-ZWUS
 *
 *  Original work: Copyright 2014/2015 SmartThings (zwave-metering-dimmer.groovy, Apache 2.0)
 *  Ported and modified for Hubitat Elevation by: Ricardo Nogueira Garcia
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
 *  IMPORTANTE - DIFERENTE DOS DRIVERS DE SWITCH ANTERIORES:
 *  Este e o equipamento real, confirmado pela etiqueta fisica: Aeon Labs Smart Dimmer (2nd Edition),
 *  Model DSC25-ZWUS, FCC ID XBADSC25 (908.42MHz). Ao contrario do Smart Switch 6 e do Smart Switch
 *  Gen5 (que sao reles simples on/off, onde o Switch Multilevel controla so o LED), este e um DIMMER
 *  DE VERDADE - o comando Switch Multilevel (0x26) e o controle PRINCIPAL de brilho/liga-desliga, nao
 *  um efeito colateral a ser evitado. O bug de on/off original vinha de tentar tratar esse device como
 *  switch binario simples.
 *
 *  CHANGELOG
 *  v1.0.0 (2026-07-27) - Ricardo Nogueira Garcia
 *    - Port do driver aberto SmartThings zwave-metering-dimmer.groovy (Apache 2.0) para Hubitat
 *    - on()/off()/setLevel() usam Basic Set + Switch Multilevel Get, conforme comportamento nativo
 *      do device (documentado no driver original SmartThings)
 *    - configure() aplica parametro 80 = 2 (Basic Report) e parametros de report de energia (101, 111),
 *      especificos para dispositivos Aeon Labs (mfr 0086), conforme driver original
 *    - Fingerprint generico por inClusters (0x26,0x32), ja que o model ID exato do DSC25 nao esta
 *      documentado publicamente (dispositivo descontinuado ha varios anos) - o fingerprint por
 *      manufacturer (0086) tambem incluido como reforco
 */

import groovy.transform.Field

@Field String VERSION = "1.0.0"
@Field List<String> LOG_LEVELS = ["error", "warn", "info", "debug", "trace"]
@Field String DEFAULT_LOG_LEVEL = LOG_LEVELS[1]

metadata {
  definition (name: "Aeon Smart Dimmer 2nd Edition", namespace: "rng", author: "SmartThings; Ricardo Nogueira Garcia",
        importUrl: "https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/aeon-smart-dimmer-2nd/aeon-smart-dimmer-2nd.groovy"
    ) {
    capability "Actuator"
    capability "Switch"
    capability "SwitchLevel"
    capability "Sensor"
    capability "Power Meter"
    capability "Energy Meter"
    capability "Refresh"
    capability "Polling"
    capability "Configuration"

    command "reset"

    // Fingerprint generico - o model ID exato do DSC25 nao e publicamente documentado
    fingerprint inClusters: "0x26,0x32", deviceJoinName: "Aeon Smart Dimmer 2nd Edition"
    fingerprint mfr: "0086", prod: "0103", deviceJoinName: "Aeon Smart Dimmer 2nd Edition" // US
    fingerprint mfr: "0086", prod: "0003", deviceJoinName: "Aeon Smart Dimmer 2nd Edition" // EU
  }

  preferences {
    section { // General
      input name: "logLevel", title: "Log Level", type: "enum", options: LOG_LEVELS, defaultValue: DEFAULT_LOG_LEVEL, required: false
      input name: "logDescText", title: "Log Description Text", type: "bool", defaultValue: true, required: false
    }
    section { // Configuration (parametros genericos Aeon Labs, aplicaveis ao DSC25)
      input name: "param80", title: "Load Change Notification (param 80)", description: "Recomendado: Basic Report, para o Hubitat sempre saber o estado real", type: "enum", options: [[0:"Send Nothing (Disabled)"],[1:"Send HAIL Command"],[2:"Send BASIC Report Command"]], defaultValue: 2, required: true
      input name: "reportWatts", title: "Report Watts (param 101 - bit 4)", type: "bool", defaultValue: true, required: true
      input name: "reportKwh", title: "Report Accumulated kWh (param 101 - bit 8)", type: "bool", defaultValue: true, required: true
      input name: "param111", title: "Report Interval - segundos (param 111)", type: "enum", options: [[30:"30s"],[60:"1m"],[120:"2m"],[300:"5m"],[600:"10m"],[900:"15m"]], defaultValue: 300, required: true
    }
  }
}

// ==================== LIFECYCLE ====================

def installed() {
  logger("debug", "installed(${VERSION})")
  state.driverInfo = [ver: VERSION]
  initialize()
}

def initialize() {
  logger("debug", "initialize()")
}

def updated() {
  logger("debug", "updated()")
  if (!state.driverInfo?.ver || state.driverInfo.ver != VERSION) {
    state.driverInfo = [ver: VERSION]
  }
  unschedule()
  configure()
}

// ==================== COMMANDS ====================

// Neste dispositivo, ao contrario do SS6/Gen5, o Switch Multilevel E o controle correto -
// nao deve ser evitado. Basic Set liga/desliga, Switch Multilevel Get confirma o nivel real.
def on() {
  logger("debug", "on()")
  cmdSequence([
    zwave.basicV1.basicSet(value: 0xFF),
    zwave.switchMultilevelV1.switchMultilevelGet()
  ], 3000)
}

def off() {
  logger("debug", "off()")
  cmdSequence([
    zwave.basicV1.basicSet(value: 0x00),
    zwave.switchMultilevelV1.switchMultilevelGet()
  ], 3000)
}

def setLevel(level, duration = null) {
  logger("debug", "setLevel(${level})")
  Integer lvl = Math.min(level as Integer, 99)
  cmdSequence([
    zwave.basicV1.basicSet(value: lvl),
    zwave.switchMultilevelV1.switchMultilevelGet()
  ], 3000)
}

def poll() {
  refresh()
}

def refresh() {
  logger("debug", "refresh()")
  cmdSequence([
    zwave.switchMultilevelV1.switchMultilevelGet(),
    zwave.meterV3.meterGet(scale: 0), // kWh
    zwave.meterV3.meterGet(scale: 2)  // W
  ], 1000)
}

def reset() {
  logger("debug", "reset()")
  sendEvent(name: "power", value: 0, unit: "W")
  sendEvent(name: "energy", value: 0, unit: "kWh")
  cmdSequence([
    zwave.meterV3.meterReset(),
    zwave.meterV3.meterGet(scale: 0)
  ])
}

def configure() {
  logger("debug", "configure()")
  def cmds = []

  cmds << zwave.configurationV1.configurationSet(parameterNumber: 80, size: 1, scaledConfigurationValue: param80.toInteger())

  Integer reportGroup = 0
  reportGroup += (reportWatts ? 4 : 0)
  reportGroup += (reportKwh ? 8 : 0)
  cmds << zwave.configurationV1.configurationSet(parameterNumber: 101, size: 4, scaledConfigurationValue: reportGroup)
  cmds << zwave.configurationV1.configurationSet(parameterNumber: 111, size: 4, scaledConfigurationValue: param111.toInteger())

  if (!getDataValue("MSR")) {
    cmds << zwave.manufacturerSpecificV2.manufacturerSpecificGet()
  }

  cmdSequence(cmds, 400)
}

// ==================== PARSE / EVENTS ====================

def parse(String description) {
  logger("debug", "parse() - description: ${description}")
  def result = []
  def cmd = zwave.parse(description, getCommandClassVersions())
  if (cmd) {
    result = zwaveEvent(cmd)
  } else {
    logger("warn", "parse() - Nao parseado: ${description}")
  }
  result
}

def zwaveEvent(hubitat.zwave.commands.basicv1.BasicReport cmd) {
  logger("debug", "BasicReport - value: ${cmd.value}")
  dimmerEvent(cmd.value)
}

def zwaveEvent(hubitat.zwave.commands.basicv1.BasicSet cmd) {
  logger("debug", "BasicSet - value: ${cmd.value}")
  dimmerEvent(cmd.value)
}

def zwaveEvent(hubitat.zwave.commands.switchmultilevelv3.SwitchMultilevelReport cmd) {
  logger("debug", "SwitchMultilevelReport - value: ${cmd.value}")
  dimmerEvent(cmd.value)
}

private dimmerEvent(rawValue) {
  def result = []
  def value = (rawValue ? "on" : "off")
  def isStateChange = (device.currentValue("switch") != value)
  result << createEvent(name: "switch", value: value, descriptionText: "${device.displayName} is ${value}", isStateChange: isStateChange)
  if (rawValue) {
    Integer level = (rawValue == 99 ? 100 : rawValue)
    result << createEvent(name: "level", value: level, unit: "%")
  }
  result
}

def zwaveEvent(hubitat.zwave.commands.meterv3.MeterReport cmd) {
  logger("debug", "MeterReport - scale: ${cmd.scale}, value: ${cmd.scaledMeterValue}")
  def result = []
  if (cmd.meterType == 1) { // electric
    switch (cmd.scale) {
      case 0: // kWh
        result << createEvent(name: "energy", value: cmd.scaledMeterValue, unit: "kWh")
        break
      case 2: // Watts
        result << createEvent(name: "power", value: Math.round(cmd.scaledMeterValue), unit: "W")
        break
    }
  }
  result
}

def zwaveEvent(hubitat.zwave.commands.manufacturerspecificv2.ManufacturerSpecificReport cmd) {
  String msr = String.format("%04X-%04X-%04X", cmd.manufacturerId, cmd.productTypeId, cmd.productId)
  logger("info", "ManufacturerSpecificReport - MSR: ${msr}")
  updateDataValue("MSR", msr)
  []
}

def zwaveEvent(hubitat.zwave.commands.configurationv1.ConfigurationReport cmd) {
  logger("debug", "ConfigurationReport - param ${cmd.parameterNumber}: ${cmd.scaledConfigurationValue}")
  []
}

def zwaveEvent(hubitat.zwave.commands.securityv1.SecurityMessageEncapsulation cmd) {
  def encapsulatedCommand = cmd.encapsulatedCommand(getCommandClassVersions())
  if (encapsulatedCommand) {
    zwaveEvent(encapsulatedCommand)
  } else {
    logger("warn", "Nao foi possivel extrair comando seguro de: ${cmd}")
    []
  }
}

def zwaveEvent(hubitat.zwave.commands.crc16encapv1.Crc16Encap cmd) {
  def encapsulatedCommand = zwave.getCommand(cmd.commandClass, cmd.command, cmd.data)
  if (encapsulatedCommand) {
    zwaveEvent(encapsulatedCommand)
  } else {
    logger("warn", "Nao foi possivel extrair comando CRC16 de: ${cmd}")
    []
  }
}

def zwaveEvent(hubitat.zwave.Command cmd) {
  logger("warn", "zwaveEvent(Command) - Nao tratado: ${cmd}")
  []
}

// ==================== HELPERS ====================

private cmd(hubitat.zwave.Command cmd) {
  if (getDataValue("zwaveSecurePairingComplete") == "true" && getDataValue("S2") == null) {
    zwave.securityV1.securityMessageEncapsulation().encapsulate(cmd).format()
  } else if (getDataValue("zwaveSecurePairingComplete") == "true") {
    zwaveSecureEncap(cmd)
  } else {
    cmd.format()
  }
}

private cmdSequence(Collection commands, Integer delayBetweenArgs = 250) {
  delayBetween(commands.collect { cmd(it) }, delayBetweenArgs)
}

private getCommandClassVersions() {
  return [
    0x20: 1, // Basic
    0x26: 3, // Switch Multilevel
    0x32: 3, // Meter
    0x56: 1, // Crc16Encap
    0x70: 1, // Configuration
    0x72: 2, // Manufacturer Specific
    0x85: 2, // Association
    0x98: 1  // Security
  ]
}

private logger(level, msg) {
  if (level && msg) {
    Integer levelIdx = LOG_LEVELS.indexOf(level)
    Integer setLevelIdx = LOG_LEVELS.indexOf(logLevel)
    if (setLevelIdx < 0) { setLevelIdx = LOG_LEVELS.indexOf(DEFAULT_LOG_LEVEL) }
    if (levelIdx <= setLevelIdx) {
      log."${level}" "${device.displayName} ${msg}"
    }
  }
}