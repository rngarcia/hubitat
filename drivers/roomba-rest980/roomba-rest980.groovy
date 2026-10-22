/**
 *  Roomba (Rest980 Standalone) — Hubitat Driver
 *  Versão: 1.0.1 — Build: 2026-10-21
 *  Autor: Ricardo Nogueira Garcia
 *  Repositório: https://github.com/rngarcia/hubitat
 *  Gostou? Buy me a beer: https://www.paypal.com/donate/?business=rngarcia%40gmail.com&currency_code=USD
 *
 *  Changelog:
 *  v1.0.1 (2026-10-21) - RNG
 *    - Publicado em rngarcia/hubitat: cabeçalho padronizado, namespace "rng" e importUrl
 *    - Autor ajustado (desenvolvido com assistência de IA); conceitos do driver Roomba de Aaron Ward
 *  (histórico anterior no bloco de comentário abaixo)
 */
/**
 * Standalone Roomba (Rest980) Driver for Hubitat
 * - Does NOT require the iRobot Scheduler app
 * - Polls Rest980 for state and controls the robot via local REST endpoints
 *
 * Based on PrayerfulDrop/Aaron Ward concepts but refactored to standalone driver.
 */

import groovy.json.JsonSlurper
import java.text.SimpleDateFormat

metadata {
    definition(name: "Roomba (Rest980 Standalone)", namespace: "rng", author: "Ricardo Nogueira Garcia",
        importUrl: "https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/roomba-rest980/roomba-rest980.groovy"
    ) {
        capability "Battery"
        capability "Actuator"
        capability "Switch"
        capability "Refresh"
        capability "Initialize"

        attribute "cleanStatus", "string"      // cleaning, docking, charging, idle, error, dead, stopped
        attribute "RoombaTile", "string"       // HTML tile string for dashboards
        attribute "lastActivity", "string"
        attribute "robotName", "string"
        attribute "robotSku", "string"
        attribute "robotSoftware", "string"
        attribute "missionMinutes", "number"   // mssnM
        attribute "binStatus", "string"        // good/full/missing/unknown
        attribute "tankStatus", "string"       // good/empty/missing/unknown
        attribute "notReadyCode", "string"     // error code if any
        attribute "rawPhase", "string"         // raw cleanMissionStatus.phase

        command "start"
        command "stop"
        command "pause"
        command "resume"
        command "dock"
        command "setCarpetBoost", ["string"]     // auto/performance/eco
        command "setEdgeClean", ["string"]       // on/off
        command "setCleaningPasses", ["string"]  // auto/one/two
        command "setAlwaysFinish", ["string"]    // on/off
    }

    preferences {
        input "restIP", "text", title: "Rest980 Server IP", required: true
        input "restPort", "number", title: "Rest980 Server Port", required: true, defaultValue: 3000
        input "pollSeconds", "number", title: "Polling interval (seconds)", required: true, defaultValue: 30, range: "5..600"
        input "logEnable", "bool", title: "Enable debug logging", required: true, defaultValue: true
        input "infoEnable", "bool", title: "Enable info logging", required: true, defaultValue: true
    }
}

/* ----------------------------- Lifecycle ----------------------------- */

def installed() {
    if (infoEnable) log.info "${device.displayName} installed"
    initialize()
}

def updated() {
    if (infoEnable) log.info "${device.displayName} updated"
    unschedule()
    initialize()
    if (logEnable) runIn(1800, "logsOff") // 30 min
}

def initialize() {
    if (infoEnable) log.info "${device.displayName} initializing..."
    state.errorStartMs = state.errorStartMs ?: 0L
    state.batteryZeroStartMs = state.batteryZeroStartMs ?: 0L
    state.lastSeenMs = state.lastSeenMs ?: 0L
    state.lastStatus = state.lastStatus ?: "idle"

    // immediate refresh
    refresh()

    // periodic refresh
    def s = safeInt(pollSeconds, 30)
    schedule("0/${s} * * * * ?", "refresh")
}

def logsOff() {
    log.warn "Debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

/* ----------------------------- Commands ----------------------------- */

def on() { start() }
def off() { stop() }

def start()  { action("/api/local/action/start",  true) }
def stop()   { action("/api/local/action/stop",   true) }
def pause()  { action("/api/local/action/pause",  true) }
def resume() { action("/api/local/action/resume", true) }
def dock()   { action("/api/local/action/dock",   true) }

def setCarpetBoost(String mode) {
    if (!mode) return
    action("/api/local/config/carpetBoost/${mode}", false)
}
def setEdgeClean(String mode) {
    if (!mode) return
    action("/api/local/config/edgeClean/${mode}", false)
}
def setCleaningPasses(String mode) {
    if (!mode) return
    action("/api/local/config/cleaningPasses/${mode}", false)
}
def setAlwaysFinish(String mode) {
    if (!mode) return
    action("/api/local/config/alwaysFinish/${mode}", false)
}

def refresh() {
    def resp = executeAction("/api/local/info/state")
    if (!resp?.data) return

    state.lastSeenMs = now()

    // Rest980 usually returns parsed JSON already as resp.data
    def data = resp.data

    // Basic identity
    safeSend("robotName", data?.name?.toString())
    safeSend("robotSku", data?.sku?.toString())
    safeSend("robotSoftware", data?.softwareVer?.toString())

    // Battery
    def bat = safeInt(data?.batPct, null)
    if (bat != null) sendEvent(name: "battery", value: bat, unit: "%")

    // Mission
    def mssnM = safeInt(data?.cleanMissionStatus?.mssnM, 0)
    sendEvent(name: "missionMinutes", value: mssnM)

    // Bin/Tank
    updateBinTank(data)

    // State machine
    def phase = data?.cleanMissionStatus?.phase?.toString()
    safeSend("rawPhase", phase)

    def notReady = data?.cleanMissionStatus?.notReady?.toString()
    safeSend("notReadyCode", notReady ?: "")

    def status = computeStatus(phase, notReady, bat, mssnM)
    safeSend("cleanStatus", status)

    // Switch state
    if (status == "cleaning") {
        sendEvent(name: "switch", value: "on", isStateChange: true)
    } else {
        sendEvent(name: "switch", value: "off", isStateChange: true)
    }

    // Last activity (any successful refresh updates)
    sendEvent(name: "lastActivity", value: fmtNow(), isStateChange: true)

    // Tile
    roombaTile(status, bat ?: 0, mssnM)

    state.lastStatus = status
}

/* ----------------------------- Internals ----------------------------- */

private void action(String path, boolean refreshAfter) {
    def resp = executeAction(path)
    if (resp == null && infoEnable) log.warn "Action failed: ${path}"
    if (refreshAfter) runIn(2, "refresh")
}

private Map executeAction(String path) {
    if (!restIP || !restPort) {
        log.warn "Rest980 settings missing (restIP/restPort)"
        return null
    }

    def params = [
        uri: "http://${restIP}:${restPort}",
        path: path,
        contentType: "application/json",
        timeout: 10
    ]

    try {
        Map result = null
        httpGet(params) { r ->
            // Hubitat gives r.data as parsed object if JSON
            result = [status: r.status, data: r.data, headers: r.headers]
        }
        return result
    } catch (e) {
        if (logEnable) log.error "Rest980 error calling ${path}: ${e}"
        return null
    }
}

private void updateBinTank(def data) {
    // Roomba
    if (data?.bin != null) {
        def bin = "unknown"
        if (data.bin.present == false) bin = "missing"
        else if (data.bin.full == true) bin = "full"
        else bin = "good"
        safeSend("binStatus", bin)
        safeSend("tankStatus", "unknown")
        return
    }

    // Braava-ish
    if (data?.tankLvl != null) {
        def tank = "unknown"
        try {
            if (data?.detectedPad?.toString()?.contains("Wet")) {
                if (data?.mopReady?.tankPresent == false) tank = "missing"
                else if (safeInt(data.tankLvl, 0) == 0) tank = "empty"
                else tank = "good"
            }
        } catch (ignored) { }
        safeSend("tankStatus", tank)
        safeSend("binStatus", "unknown")
        return
    }

    safeSend("binStatus", "unknown")
    safeSend("tankStatus", "unknown")
}

private String computeStatus(String phase, String notReady, Integer batPct, Integer mssnM) {
    // Map phases similar to original app logic
    // "run" => cleaning
    // "charge" => charging
    // "hmMidMsn"/"hmPostMsn"/"hmUsrDock" => docking (unless battery truly dead)
    // "stop" => idle or error depending on notReady
    def status = "idle"

    switch (phase) {
        case "run":
            status = "cleaning"
            clearErrorTimers()
            break

        case "charge":
            status = "charging"
            clearErrorTimers()
            break

        case "hmMidMsn":
        case "hmPostMsn":
        case "hmUsrDock":
            status = computeDockOrDead(batPct)
            break

        case "stop":
            if (safeInt(notReady, 0) > 0) {
                status = computeErrorWithDelay()
            } else {
                status = "idle"
                clearErrorTimers()
            }
            break

        default:
            status = "idle"
            break
    }

    return status
}

private String computeDockOrDead(Integer batPct) {
    // replicate original “battery died” heuristic: if batPct hits 0 for >10 min => dead
    if (batPct != null && batPct == 0) {
        if (!state.batteryZeroStartMs || state.batteryZeroStartMs == 0L) {
            state.batteryZeroStartMs = now()
            return "docking"
        }
        def mins = ((now() - state.batteryZeroStartMs) / 60000.0).round(0) as int
        if (logEnable) log.debug "Battery at 0% for ~${mins} min"
        return (mins > 10) ? "dead" : "docking"
    } else {
        state.batteryZeroStartMs = 0L
        clearErrorTimers()
        return "docking"
    }
}

private String computeErrorWithDelay() {
    // original app waited ~5 minutes before declaring "error"
    if (!state.errorStartMs || state.errorStartMs == 0L) {
        state.errorStartMs = now()
        return state.lastStatus ?: "idle"
    }
    def mins = ((now() - state.errorStartMs) / 60000.0).round(0) as int
    if (logEnable) log.debug "Possible error for ~${mins} min"
    return (mins > 5) ? "error" : (state.lastStatus ?: "idle")
}

private void clearErrorTimers() {
    state.errorStartMs = 0L
}

private void roombaTile(String cleaning, Integer batterylevel, Integer cleaningTime) {
    def img = "roomba-stop.png"
    def msg = (cleaning ?: "idle").capitalize()

    switch(cleaning) {
        case "cleaning":
            img = "roomba-clean.png"
            break
        case "charging":
            img = "roomba-charge.png"
            break
        case "docking":
            img = "roombadock.png"
            break
        case "dead":
            img = "roomba-dead.png"
            msg = "Battery Died"
            break
        case "error":
            img = "roomba-error.png"
            break
        default:
            img = "roomba-stop.png"
            break
    }

    def url = "https://raw.githubusercontent.com/PrayerfulDrop/Hubitat/master/Roomba/support/${img}"
    def html = "<center><img width=70px height=70px vspace=5px src=${url}><br><font style='font-size:13px'>"
    if (cleaning in ["docking","cleaning"]) html += "${msg} - ${cleaningTime}min<br>Battery: ${batterylevel}%"
    else html += "${msg}<br>Battery: ${batterylevel}%"
    html += "</font></center>"

    sendEvent(name: "RoombaTile", value: html, displayed: true)
    if (logEnable) log.debug "RoombaTile updated: ${msg}"
}

/* ----------------------------- Helpers ----------------------------- */

private void safeSend(String attr, def val) {
    if (val == null) return
    sendEvent(name: attr, value: val.toString())
}

private Integer safeInt(def v, Integer fallback) {
    try {
        if (v == null) return fallback
        return v.toString().isInteger() ? v.toString().toInteger() : fallback
    } catch (ignored) {
        return fallback
    }
}

private String fmtNow() {
    def sdf = new SimpleDateFormat("MM/dd/yyyy hh:mm:ss aa")
    return sdf.format(new Date())
}
