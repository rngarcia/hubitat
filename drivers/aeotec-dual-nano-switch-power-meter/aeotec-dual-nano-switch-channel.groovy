/**
 *  Aeotec Dual Nano Switch Channel — Hubitat Driver (filho)
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
 *  Driver de componente: um canal do Aeotec Dual Nano Switch (Switch + Power Meter + Energy Meter).
 *  Quem fala Z-Wave e o pai ("Aeotec Dual Nano Switch with Power Meter"); este filho recebe os valores e
 *  repassa on / off / refresh ao pai.
 *
 *  Changelog:
 *  v1.0.0 (2026-11-02) - RNG
 *    - Primeira versao publicada
 *  v0.3.0 (2026-11-02) - RNG
 *    - Volta a ter Switch + Power + Energy no mesmo filho (substitui o Generic Component Switch)
 */

metadata {
  definition (name: "Aeotec Dual Nano Switch Channel", namespace: "rng", author: "Ricardo Nogueira Garcia", component: true,
        importUrl: "https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/aeotec-dual-nano-switch-power-meter/aeotec-dual-nano-switch-channel.groovy"
    ) {
    capability "Actuator"
    capability "Switch"
    capability "Sensor"
    capability "PowerMeter"
    capability "EnergyMeter"
    capability "Refresh"
  }
}

void on() {
  parent?.componentOn(device)
}

void off() {
  parent?.componentOff(device)
}

void refresh() {
  parent?.componentRefresh(device)
}

// Eventos enviados pelo pai: cd.parse([[name: "power", value: 12.3, unit: "W"]])
void parse(String description) {
  log.warn "parse(String) is not used by a component driver: ${description}"
}

void parse(List<Map> events) {
  events.each { Map evt ->
    if (device.hasAttribute(evt.name as String)) {
      sendEvent(evt)
    }
  }
}
