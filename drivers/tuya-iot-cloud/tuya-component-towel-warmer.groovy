/**
 *  Tuya Component Towel Warmer with Power Monitor — Hubitat Driver
 *  Versão: 2.2.1 — Build: 2026-10-21
 *  Autor: Ricardo Nogueira Garcia
 *  Repositório: https://github.com/rngarcia/hubitat
 *  Gostou? Buy me a beer: https://www.paypal.com/donate/?business=rngarcia%40gmail.com&currency_code=USD
 *
 *  Changelog:
 *  v2.2.1 (2026-10-21) - RNG
 *    - Publicado em rngarcia/hubitat: cabeçalho padronizado, namespace "rng" e importUrl
 *    - Autor ajustado (versão original gerada com assistência de IA)
 *  (histórico anterior no bloco de comentário abaixo)
 */
/**
 * Tuya Component Towel Warmer (with Estimated Energy)
 *
 * Capabilities:
 *   - Switch
 *   - TemperatureMeasurement    (temp_current)
 *   - ThermostatHeatingSetpoint (temp_set)
 *   - ThermostatOperatingState  (work_state -> heating/idle)
 *   - PowerMeter                (estimado via preferência)
 *   - EnergyMeter               (acumulado kWh estimado)
 *   - Timer via countdown_set / countdown_left
 *
 * Nota sobre energia:
 *   Toalheiros Tuya desta categoria (kt) NÃO reportam DPs de energia
 *   (cur_power/cur_voltage/cur_current). A potência é estimada com base
 *   na especificação do produto, configurável em preferências.
 *
 * Requires parent driver exposing:
 *   - parent.componentOn(DeviceWrapper)
 *   - parent.componentOff(DeviceWrapper)
 *   - parent.componentSendTuyaCommand(DeviceWrapper, code, value)
 *   - parent.componentRefresh(DeviceWrapper)
 *
 * Authors:    ChatGPT (original), Ricardo Nogueira Garcia (energy estimation)
 * Version:    2.1.0
 * Date:       2025-06-04
 * Changelog:
 *  2.2.0 (2026-10-02) - Ricardo Nogueira Garcia
 *        Timer limitado a 1..6h (range real do DP countdown_set, confirmado
 *        via diagnóstico); setpoint até 80°C (range real do DP temp_set).
 *   2.1.0 - Potência padrão ajustada para 88 W (especificação do produto).
 *           Timer corrigido para máximo de 8 horas (spec do fabricante).
 *   2.0.0 - Adicionado suporte a PowerMeter e EnergyMeter estimados.
 *           Energia acumulada em kWh via loop periódico (runIn) enquanto ligado.
 *           Preferências: wattagem, intervalo de atualização, reset de energia.
 *           Comando resetEnergy para zerar o acumulador.
 */

import com.hubitat.app.DeviceWrapper
import groovy.json.JsonOutput

metadata {
    definition(name: "Tuya Component Towel Warmer with Power Monitor", namespace: "rng",
               author: "Ricardo Nogueira Garcia",
        importUrl: "https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/tuya-iot-cloud/tuya-component-towel-warmer.groovy"
    ) {
        capability "Actuator"
        capability "Sensor"
        capability "Switch"
        capability "Refresh"

        capability "ThermostatHeatingSetpoint"
        capability "ThermostatOperatingState"
        capability "TemperatureMeasurement"

        // Energia estimada
        capability "PowerMeter"   // atributo: power (W)
        capability "EnergyMeter"  // atributo: energy (kWh)

        // Timer
        attribute "countdownSet",          "string"
        attribute "countdownLeft",         "number"
        attribute "timerRemainingMinutes", "number"

        // Indicador de que a energia é estimada (não medida)
        attribute "energyEstimated", "string"

        command "setTimerHours", [[name: "Hours*", type: "NUMBER", description: "1..8"]]
        command "cancelTimer"
        command "resetEnergy"
    }

    preferences {
        input name: "logEnable",
              type: "bool",
              title: "Habilitar debug logging",
              defaultValue: true

        input name: "txtEnable",
              type: "bool",
              title: "Habilitar descriptionText logging",
              defaultValue: true

        input name: "forceCelsius",
              type: "bool",
              title: "Forçar unidade Celsius no device (temp_unit_convert=c)",
              defaultValue: true

        input name: "ratedWatts",
              type: "number",
              title: "Potência nominal do toalheiro (W) — ver especificação do produto",
              defaultValue: 88,
              range: "1..3000"

        input name: "energyUpdateMins",
              type: "enum",
              title: "Intervalo de atualização da energia estimada",
              defaultValue: "5",
              options: ["1": "1 minuto", "5": "5 minutos", "10": "10 minutos", "15": "15 minutos", "30": "30 minutos"]

        input name: "resetEnergyOnBoot",
              type: "bool",
              title: "Zerar energia acumulada ao reinstalar/atualizar driver",
              defaultValue: false
    }
}

/* =========================================================
 *  LIFECYCLE
 * ========================================================= */

void installed() {
    initialize()
}

void updated() {
    if (logEnable) runIn(1800, "logsOff")
    initialize()
}

void initialize() {
    if (device.currentValue("thermostatOperatingState") == null) {
        sendEvent(name: "thermostatOperatingState", value: "idle")
    }

    // Inicializa atributos de energia se ainda não existirem
    if (device.currentValue("power") == null) {
        sendEvent(name: "power", value: 0, unit: "W")
    }
    if (device.currentValue("energy") == null || settings.resetEnergyOnBoot) {
        sendEvent(name: "energy", value: 0, unit: "kWh",
                  descriptionText: "${device.displayName} energia acumulada zerada")
        state.energyAccumWh = 0.0
    }

    sendEvent(name: "energyEstimated", value: "sim — baseada em ${ratedWatts ?: 88} W nominal")

    // Retomar loop de energia se o switch já estiver ligado
    if (device.currentValue("switch") == "on") {
        scheduleEnergyUpdate()
    }
}

void logsOff() {
    device.updateSetting("logEnable", [value: "false", type: "bool"])
    log.info "Debug logging disabled"
}

/* =========================================================
 *  PARSE — chamado pelo driver pai
 * ========================================================= */

void parse(Object description) {
    List<Map> events = []

    if (description instanceof List) {
        events = (List<Map>) description
    } else if (description instanceof Map) {
        events = [(Map) description]
    } else {
        if (logEnable) log.debug "parse(): tipo não suportado. Raw=${description}"
        return
    }

    events.each { Map e ->
        if (!e?.name) return

        String n = e.name as String
        def v = e.value

        if (txtEnable) log.info "${device.displayName} evento ${n}=${v}"

        // Detectar mudança de switch para controlar o loop de energia
        if (n == "switch") {
            if (v == "on") {
                startEnergyTracking()
            } else {
                stopEnergyTracking()
            }
        }

        // countdown_left em minutos → timerRemainingMinutes
        if (n == "countdownLeft") {
            Integer left = safeInt(v)
            if (left != null) {
                sendEvent(
                    name: "timerRemainingMinutes",
                    value: left,
                    unit: "min",
                    descriptionText: "${device.displayName} timer restante: ${left} min"
                )
            }
        }

        sendEvent(e)
    }
}

/* =========================================================
 *  SWITCH
 * ========================================================= */

void on() {
    parent?.componentOn((DeviceWrapper) device)
}

void off() {
    parent?.componentOff((DeviceWrapper) device)
}

/* =========================================================
 *  HEATING SETPOINT (temp_set)
 * ========================================================= */

void setHeatingSetpoint(BigDecimal temp) {
    if (temp == null) return

    Integer t = temp.setScale(0, BigDecimal.ROUND_HALF_UP).intValue()
    t = clampToStep(t, 40, 80, 5)

    if (txtEnable) log.info "${device.displayName} setHeatingSetpoint ${t}°C"

    if (settings.forceCelsius != false) {
        parent?.componentSendTuyaCommand((DeviceWrapper) device, "temp_unit_convert", "c")
    }

    parent?.componentSendTuyaCommand((DeviceWrapper) device, "temp_set", t)

    if ((device.currentValue("switch") ?: "") != "on") {
        on()
    }
}

/* =========================================================
 *  TIMER (countdown_set / countdown_left)
 * ========================================================= */

void setTimerHours(BigDecimal hours) {
    Integer h = hours == null ? null : hours.setScale(0, BigDecimal.ROUND_HALF_UP).intValue()
    if (h == null) return
    if (h < 1) h = 1
    if (h > 8) h = 8

    String v = "${h}h"
    if (txtEnable) log.info "${device.displayName} setTimerHours ${v}"

    parent?.componentSendTuyaCommand((DeviceWrapper) device, "countdown_set", v)

    if ((device.currentValue("switch") ?: "") != "on") on()
}

void cancelTimer() {
    if (txtEnable) log.info "${device.displayName} cancelTimer"
    parent?.componentSendTuyaCommand((DeviceWrapper) device, "countdown_set", "cancel")
}

/* =========================================================
 *  REFRESH
 * ========================================================= */

void refresh() {
    if (txtEnable) log.info "${device.displayName} refresh"
    parent?.componentRefresh((DeviceWrapper) device)
}

/* =========================================================
 *  ENERGIA ESTIMADA
 * ========================================================= */

/**
 * Chamado ao detectar switch=on.
 * Registra horário de início e agenda o loop periódico.
 */
private void startEnergyTracking() {
    state.energyStartMs = now()
    state.energyAccumWh = state.energyAccumWh ?: 0.0

    Integer watts = (settings.ratedWatts ?: 88) as Integer
    sendEvent(name: "power", value: watts, unit: "W",
              descriptionText: "${device.displayName} ligado — potência estimada: ${watts} W")

    if (logEnable) log.debug "Iniciando rastreamento de energia: ${watts} W"
    scheduleEnergyUpdate()
}

/**
 * Chamado ao detectar switch=off.
 * Faz um acúmulo final e para o loop.
 */
private void stopEnergyTracking() {
    accumulateEnergy()
    unschedule("energyUpdateTask")
    state.energyStartMs = null

    sendEvent(name: "power", value: 0, unit: "W",
              descriptionText: "${device.displayName} desligado — potência: 0 W")

    if (logEnable) log.debug "Rastreamento de energia pausado (switch off)"
}

/**
 * Agenda o próximo tick do loop conforme preferência.
 */
private void scheduleEnergyUpdate() {
    Integer mins = (settings.energyUpdateMins ?: "5") as Integer
    runIn(mins * 60, "energyUpdateTask")
}

/**
 * Tick periódico: acumula energia e se reagenda.
 */
void energyUpdateTask() {
    if (device.currentValue("switch") != "on") return
    accumulateEnergy()
    scheduleEnergyUpdate()
}

/**
 * Calcula Wh desde a última marca de tempo, soma ao acumulador,
 * converte para kWh e emite o evento.
 */
private void accumulateEnergy() {
    Long startMs = state.energyStartMs as Long
    if (startMs == null) return

    Long nowMs = now()
    Double elapsedHours = (nowMs - startMs) / 3_600_000.0
    Integer watts = (settings.ratedWatts ?: 88) as Integer
    Double addedWh = watts * elapsedHours

    state.energyAccumWh = (state.energyAccumWh ?: 0.0) + addedWh
    state.energyStartMs = nowMs  // reset da janela

    Double kWh = (state.energyAccumWh / 1000.0).round(4)

    if (logEnable) log.debug "Energia acumulada: +${addedWh.round(4)} Wh → total ${state.energyAccumWh.round(2)} Wh (${kWh} kWh)"

    sendEvent(
        name: "energy",
        value: kWh,
        unit: "kWh",
        descriptionText: "${device.displayName} energia estimada: ${kWh} kWh"
    )
}

/**
 * Zera o acumulador de energia.
 */
void resetEnergy() {
    state.energyAccumWh = 0.0
    state.energyStartMs = (device.currentValue("switch") == "on") ? now() : null

    sendEvent(name: "energy", value: 0, unit: "kWh",
              descriptionText: "${device.displayName} energia acumulada zerada pelo usuário")

    if (txtEnable) log.info "${device.displayName} energia zerada"
}

/* =========================================================
 *  UTILS
 * ========================================================= */

private Integer safeInt(def v) {
    if (v == null) return null
    if (v instanceof Number) return (v as Number).intValue()
    String s = v.toString()
    if (s.isInteger()) return s.toInteger()
    return null
}

private Integer clampToStep(Integer val, Integer min, Integer max, Integer step) {
    Integer v = val
    if (v < min) v = min
    if (v > max) v = max

    Integer base = ((v - min) / step) * step + min
    Integer next = base + step

    if (next <= max && Math.abs(next - v) < Math.abs(base - v)) return next
    return base
}