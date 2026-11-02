/**
 *  Aeotec Dual Nano Switch with Power Meter — Hubitat Driver (pai)
 *  Versão: 1.0.0 — Build: 2026-11-02
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
 *  O driver nativo "Aeotec Dual Nano Switch" cria um filho por canal (Generic Component Switch), que nao tem
 *  Power Meter nem Energy Meter. O modelo ZW132 tem medicao por canal (classe Meter 0x32, via Multi Channel).
 *  Este driver consulta W e kWh de cada canal (verificado em um ZW132: valores coerentes com cada chave) e publica
 *  nos filhos, que usam o driver "Aeotec Dual Nano Switch Channel" (mesmo pacote) com Switch + Power + Energy.
 *  O Hubitat nao deixa trocar o Type de filhos de componente de sistema, entao os filhos criados pelo driver nativo
 *  precisam ser recriados com o comando recreateChildren (os IDs mudam; regras, grupos, Google Home e Maker API
 *  precisam ser reapontados).
 *
 *  VERIFICADO em duas unidades ZW132 (306 e 654): leitura de W e kWh por canal coerente com a carga, liga/desliga pelos
 *  filhos, recreateChildren e associacao do grupo 1. Um ZW140 (sem Meter) foi detectado e fica sem polling.
 *  Nada de reporte automatico e configurado por parametro (a tabela de parametros do ZW132 nao foi confirmada);
 *  a medicao e feita por consulta periodica (polling) por canal.
 *
 *  Changelog:
 *  v1.0.0 (2026-11-02) - RNG
 *    - Primeira versao publicada (consolida 0.1.0 a 0.3.1)
 *  v0.3.1 (2026-11-02) - RNG
 *    - Se o aparelho nao anuncia a classe Meter (0x32; ZW140 sem medicao), desliga o polling e nao pede leituras
 *  v0.3.0 (2026-11-02) - RNG
 *    - Filhos com Switch + Power + Energy; recreateChildren substitui os filhos do driver nativo
 *    - Atributos power1/power2/energy1/energy2 no pai
 *  v0.1.0 (2026-11-02) - RNG
 *    - Primeira versao: Switch por canal (filhos), Power/Energy por canal (filhos) e soma no pai,
 *      polling configuravel de W e kWh, comandos de diagnostico (getMeterSupport, setParam, getParam)
 */

import groovy.transform.Field

@Field String VERSION = "1.0.0"
@Field String CHILD_DRIVER = "Aeotec Dual Nano Switch Channel"
@Field List<String> LOG_LEVELS = ["error", "warn", "info", "debug", "trace"]
@Field String DEFAULT_LOG_LEVEL = LOG_LEVELS[2]
@Field List<Integer> CHANNELS = [1, 2]

metadata {
  definition (name: "Aeotec Dual Nano Switch with Power Meter", namespace: "rng", author: "Ricardo Nogueira Garcia",
        importUrl: "https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/aeotec-dual-nano-switch-power-meter/aeotec-dual-nano-switch-power-meter.groovy"
    ) {
    capability "Actuator"
    capability "Switch"
    capability "Sensor"
    capability "PowerMeter"
    capability "EnergyMeter"
    capability "Refresh"
    capability "Configuration"

    // Leituras por canal no proprio pai (os filhos criados pelo driver nativo nao aceitam troca de Type)
    attribute "power1", "number"
    attribute "power2", "number"
    attribute "energy1", "number"
    attribute "energy2", "number"

    command "recreateChildren", [[name: "Type RECREATE to confirm*", type: "STRING"]]
    command "getMeterSupport"
    command "setParam", [
      [name: "Parameter number*", type: "NUMBER"],
      [name: "Value*", type: "NUMBER"],
      [name: "Size (bytes)*", type: "ENUM", constraints: ["1", "2", "4"]]
    ]
    command "getParam", [[name: "Parameter number*", type: "NUMBER"]]
  }

  preferences {
    section { // General
      input name: "logLevel", title: "Log Level", type: "enum", options: LOG_LEVELS, defaultValue: DEFAULT_LOG_LEVEL, required: false
      input name: "logDescText", title: "Log Description Text (switch changes)", type: "bool", defaultValue: true, required: false
    }
    section { // Polling
      input name: "pollMinutes", title: "Poll power (W) every N minutes (0 = off)", type: "number", range: "0..59", defaultValue: 1, required: true
      input name: "energyPollMinutes", title: "Poll energy (kWh) every N minutes (0 = off)", type: "number", range: "0..59", defaultValue: 15, required: true
    }
  }
}

// ==================== LIFECYCLE ====================

def installed() {
  logger("debug", "installed(${VERSION})")
  state.driverInfo = [ver: VERSION]
  schedulePolling()
}

def updated() {
  logger("debug", "updated()")
  if (!state.driverInfo?.ver || state.driverInfo.ver != VERSION) {
    state.driverInfo = [ver: VERSION]
  }
  schedulePolling()
}

// O ZW132 anuncia a classe Meter (0x32) nas In Clusters; o ZW140 (sem medicao) nao
private boolean hasMeter() {
  String clusters = device.getDataValue("inClusters")
  return clusters == null || clusters.toUpperCase().contains("0X32")
}

private schedulePolling() {
  unschedule("pollPower")
  unschedule("pollEnergy")
  if (!hasMeter()) {
    logger("warn", "this device does not advertise the Meter class (0x32); probably a ZW140 without metering. Power/energy polling is off.")
    return
  }
  Integer p = (settings?.pollMinutes != null ? settings.pollMinutes : 1) as Integer
  Integer e = (settings?.energyPollMinutes != null ? settings.energyPollMinutes : 15) as Integer
  if (p > 0) {
    // cron do Hubitat (Quartz): segundos minutos horas dia-do-mes mes dia-da-semana
    schedule("0 0/${p} * ? * *", "pollPower")
    logger("info", "power polling every ${p} min")
  } else {
    logger("info", "power polling off")
  }
  if (e > 0) {
    schedule("30 0/${e} * ? * *", "pollEnergy")
    logger("info", "energy polling every ${e} min")
  } else {
    logger("info", "energy polling off")
  }
}

def pollPower() {
  if (!hasMeter()) return
  logger("debug", "pollPower()")
  sendCmds(CHANNELS.collect { Integer ep -> encap(zwave.meterV3.meterGet(scale: 2), ep) }, 400)
}

def pollEnergy() {
  if (!hasMeter()) return
  logger("debug", "pollEnergy()")
  sendCmds(CHANNELS.collect { Integer ep -> encap(zwave.meterV3.meterGet(scale: 0), ep) }, 400)
}

// ==================== HELPERS ====================

// Encapsula o comando no canal (endpoint 1 ou 2)
private String encap(cmd, Integer ep) {
  zwave.multiChannelV3.multiChannelCmdEncap(sourceEndPoint: 0, destinationEndPoint: ep).encapsulate(cmd).format()
}

// Envia uma lista de comandos a partir de um contexto onde o retorno nao vai ao hub (polling, componentes)
private void sendCmds(List<String> cmds, Integer delayMs = 500) {
  sendHubCommand(new hubitat.device.HubMultiAction(delayBetween(cmds, delayMs), hubitat.device.Protocol.ZWAVE))
}

// O DNI dos filhos tem o formato "<id do pai>:<canal>"
private Integer epOf(cd) {
  try {
    return cd.deviceNetworkId.tokenize(":")[-1].replaceAll("[^0-9]", "") as Integer
  } catch (Exception ex) {
    logger("warn", "could not find the channel of '${cd?.deviceNetworkId}'")
    return null
  }
}

// Filho do canal: DNI "<id do pai>:<canal>"
private getChild(Integer ep) {
  getChildDevices()?.find { it.deviceNetworkId.endsWith(":${ep}") }
}

// Cria so os filhos que faltam (driver "Aeotec Dual Nano Switch Channel"); nao mexe nos existentes
def createChildren() {
  CHANNELS.each { Integer ep ->
    if (!getChild(ep)) {
      String dni = "${device.id}:${ep}"
      logger("info", "creating child ${dni}")
      addChildDevice("rng", CHILD_DRIVER, dni, [name: "Aeotec Dual Nano Switch:${ep}", label: "${device.displayName} ${ep}", isComponent: true])
    }
  }
}

// Apaga e recria cada filho com o driver "Aeotec Dual Nano Switch Channel", mantendo DNI, nome e label.
// ATENCAO: o ID do dispositivo muda; regras, grupos, Google Home e Maker API perdem a referencia e a sala some.
def recreateChildren(confirm) {
  if ((confirm as String)?.trim()?.toUpperCase() != "RECREATE") {
    logger("warn", "recreateChildren: not confirmed. Type RECREATE in the parameter to proceed.")
    return
  }
  Map record = [:]
  CHANNELS.each { Integer ep ->
    def old = getChild(ep)
    String dni = old?.deviceNetworkId ?: "${device.id}:${ep}"
    String name = old?.name ?: "Aeotec Dual Nano Switch:${ep}"
    String label = old?.label ?: "${device.displayName} ${ep}"
    record["ep${ep}"] = [oldId: old?.id, dni: dni, label: label, room: old?.roomName, type: old?.typeName]
    if (old) {
      logger("warn", "deleting child ${dni} (old id ${old.id}, '${label}', room ${old.roomName})")
      deleteChildDevice(dni)
    }
    def created = addChildDevice("rng", CHILD_DRIVER, dni, [name: name, label: label, isComponent: true])
    record["ep${ep}"].newId = created?.id
    logger("info", "created child ${dni} '${label}' (new id ${created?.id}); set its room to '${record["ep${ep}"].room}'")
  }
  state.migration = record
  runIn(3, "refresh")
}

// Manda eventos ao filho; atributos que o driver do filho nao declara (filho ainda no driver nativo) sao ignorados
private void childEvent(child, Map evt) {
  if (child && child.hasAttribute(evt.name as String)) {
    child.parse([evt])
  }
}

// ==================== COMMANDS (pai: todos os canais) ====================

def on() {
  logger("debug", "on()")
  sendCmds(CHANNELS.collectMany { Integer ep -> [encap(zwave.switchBinaryV1.switchBinarySet(switchValue: 0xFF), ep), encap(zwave.switchBinaryV1.switchBinaryGet(), ep)] })
}

def off() {
  logger("debug", "off()")
  sendCmds(CHANNELS.collectMany { Integer ep -> [encap(zwave.switchBinaryV1.switchBinarySet(switchValue: 0x00), ep), encap(zwave.switchBinaryV1.switchBinaryGet(), ep)] })
}

def refresh() {
  logger("debug", "refresh()")
  sendCmds(CHANNELS.collectMany { Integer ep ->
    List<String> c = [encap(zwave.switchBinaryV1.switchBinaryGet(), ep)]
    if (hasMeter()) {
      c << encap(zwave.meterV3.meterGet(scale: 2), ep)
      c << encap(zwave.meterV3.meterGet(scale: 0), ep)
    }
    c
  }, 600)
}

def configure() {
  logger("info", "configure(): hub association (group 1) and refresh. No report parameters are changed.")
  createChildren()
  sendCmds([
    zwave.associationV2.associationSet(groupingIdentifier: 1, nodeId: [zwaveHubNodeId]).format(),
    zwave.associationV2.associationGet(groupingIdentifier: 1).format()
  ], 600)
  runIn(5, "refresh")
}

def getMeterSupport() {
  logger("debug", "getMeterSupport()")
  sendCmds(([1, 2, 0]).collect { Integer ep -> ep ? encap(zwave.meterV3.meterSupportedGet(), ep) : zwave.meterV3.meterSupportedGet().format() }, 700)
}

def setParam(param, value, size) {
  logger("info", "setParam: parameter ${param} = ${value} (${size} byte(s))")
  sendCmds([
    zwave.configurationV1.configurationSet(parameterNumber: param as Integer, size: size as Integer, scaledConfigurationValue: value as Integer).format(),
    zwave.configurationV1.configurationGet(parameterNumber: param as Integer).format()
  ])
}

def getParam(param) {
  sendCmds([zwave.configurationV1.configurationGet(parameterNumber: param as Integer).format()])
}

// ==================== COMMANDS (filhos) ====================

def componentOn(cd) {
  Integer ep = epOf(cd)
  if (ep == null) return
  logger("debug", "componentOn(${cd.displayName}) channel ${ep}")
  sendCmds([encap(zwave.switchBinaryV1.switchBinarySet(switchValue: 0xFF), ep), encap(zwave.switchBinaryV1.switchBinaryGet(), ep)])
}

def componentOff(cd) {
  Integer ep = epOf(cd)
  if (ep == null) return
  logger("debug", "componentOff(${cd.displayName}) channel ${ep}")
  sendCmds([encap(zwave.switchBinaryV1.switchBinarySet(switchValue: 0x00), ep), encap(zwave.switchBinaryV1.switchBinaryGet(), ep)])
}

def componentRefresh(cd) {
  Integer ep = epOf(cd)
  if (ep == null) return
  logger("debug", "componentRefresh(${cd.displayName}) channel ${ep}")
  List<String> c = [encap(zwave.switchBinaryV1.switchBinaryGet(), ep)]
  if (hasMeter()) {
    c << encap(zwave.meterV3.meterGet(scale: 2), ep)
    c << encap(zwave.meterV3.meterGet(scale: 0), ep)
  }
  sendCmds(c, 600)
}

// ==================== PARSE ====================

def parse(String description) {
  logger("trace", "parse: ${description}")
  def cmd = zwave.parse(description, [0x20: 1, 0x25: 1, 0x32: 3, 0x60: 3, 0x70: 1, 0x85: 2])
  if (cmd) {
    zwaveEvent(cmd)
  } else {
    logger("warn", "parse: could not interpret '${description}'")
  }
  return null
}

def zwaveEvent(hubitat.zwave.commands.multichannelv3.MultiChannelCmdEncap cmd) {
  def inner = cmd.encapsulatedCommand([0x20: 1, 0x25: 1, 0x32: 3, 0x70: 1])
  if (inner) {
    handleCmd(inner, cmd.sourceEndPoint as Integer)
  } else {
    logger("warn", "could not interpret encapsulated command: ${cmd}")
  }
}

def zwaveEvent(hubitat.zwave.Command cmd) {
  handleCmd(cmd, 0)
}

private void handleCmd(cmd, Integer ep) {
  if (cmd instanceof hubitat.zwave.commands.meterv3.MeterReport) {
    meterReport(cmd, ep)
  } else if (cmd instanceof hubitat.zwave.commands.switchbinaryv1.SwitchBinaryReport) {
    switchReport(cmd.value, ep)
  } else if (cmd instanceof hubitat.zwave.commands.basicv1.BasicReport || cmd instanceof hubitat.zwave.commands.basicv1.BasicSet) {
    switchReport(cmd.value, ep)
  } else if (cmd instanceof hubitat.zwave.commands.meterv3.MeterSupportedReport) {
    logger("info", "MeterSupportedReport ep=${ep}: ${cmd}")
  } else if (cmd instanceof hubitat.zwave.commands.configurationv1.ConfigurationReport) {
    logger("info", "ConfigurationReport: parameter ${cmd.parameterNumber} = ${cmd.scaledConfigurationValue} (size ${cmd.size})")
  } else if (cmd instanceof hubitat.zwave.commands.associationv2.AssociationReport) {
    logger("info", "AssociationReport: group ${cmd.groupingIdentifier} nodes ${cmd.nodeId} (hub is ${zwaveHubNodeId})")
  } else {
    logger("debug", "unhandled command (ep=${ep}): ${cmd}")
  }
}

private void meterReport(cmd, Integer ep) {
  logger("debug", "MeterReport ep=${ep}: meterType=${cmd.meterType} scale=${cmd.scale} value=${cmd.scaledMeterValue} previous=${cmd.scaledPreviousMeterValue} delta=${cmd.deltaTime}s")
  def child = ep in CHANNELS ? getChild(ep) : null
  if (cmd.scale == 2) {
    BigDecimal watts = (cmd.scaledMeterValue as BigDecimal).setScale(1, java.math.RoundingMode.HALF_UP)
    if (ep in CHANNELS) {
      childEvent(child, [name: "power", value: watts, unit: "W", descriptionText: "${child?.displayName} power is ${watts} W"])
      storeMeter("power", ep, watts)
      updateTotals()
    } else {
      logger("debug", "power on endpoint ${ep}: ${watts} W (not used; the parent shows the sum of the channels)")
    }
  } else if (cmd.scale == 0) {
    BigDecimal kwh = cmd.scaledMeterValue as BigDecimal
    if (ep in CHANNELS) {
      childEvent(child, [name: "energy", value: kwh, unit: "kWh", descriptionText: "${child?.displayName} energy is ${kwh} kWh"])
      storeMeter("energy", ep, kwh)
      updateTotals()
    } else {
      logger("debug", "energy on endpoint ${ep}: ${kwh} kWh (not used; the parent shows the sum of the channels)")
    }
  } else {
    logger("warn", "MeterReport with unexpected scale ${cmd.scale} on endpoint ${ep}")
  }
}

private void storeMeter(String attr, Integer ep, BigDecimal value) {
  Map m = (state.meters ?: [:]) as Map
  m["${attr}${ep}"] = value.toString()
  state.meters = m
  String unit = attr == "power" ? "W" : "kWh"
  sendEvent(name: "${attr}${ep}", value: value, unit: unit, descriptionText: "${device.displayName} channel ${ep} ${attr} is ${value} ${unit}")
}

// Pai = soma dos canais que ja reportaram
private void updateTotals() {
  Map m = (state.meters ?: [:]) as Map
  List<BigDecimal> w = CHANNELS.findAll { m["power${it}"] != null }.collect { m["power${it}"] as BigDecimal }
  List<BigDecimal> e = CHANNELS.findAll { m["energy${it}"] != null }.collect { m["energy${it}"] as BigDecimal }
  if (w) {
    BigDecimal total = w.sum() as BigDecimal
    sendEvent(name: "power", value: total, unit: "W", descriptionText: "${device.displayName} power is ${total} W")
  }
  if (e) {
    BigDecimal total = e.sum() as BigDecimal
    sendEvent(name: "energy", value: total, unit: "kWh", descriptionText: "${device.displayName} energy is ${total} kWh")
  }
}

private void switchReport(rawValue, Integer ep) {
  if (!(ep in CHANNELS)) {
    logger("debug", "switch report on endpoint ${ep} ignored")
    return
  }
  String v = rawValue ? "on" : "off"
  def child = getChild(ep)
  if (child) {
    String desc = "${child.displayName} was turned ${v}"
    if (logDescText != false && child.currentValue("switch") != v) {
      logger("info", desc)
    }
    child.parse([[name: "switch", value: v, descriptionText: desc]])
  } else {
    logger("warn", "no child for channel ${ep}; run createChildren")
  }
  Map sw = (state.sw ?: [:]) as Map
  sw["ep${ep}"] = v
  state.sw = sw
  String parentValue = sw.values().any { it == "on" } ? "on" : "off"
  sendEvent(name: "switch", value: parentValue, descriptionText: "${device.displayName} was turned ${parentValue}")
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
