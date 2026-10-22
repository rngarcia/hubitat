/**
 *  Goldshell BYTE Miner (MCB+CGMiner) — Hubitat Driver
 *  Versão: 3.5.1 — Build: 2026-10-21
 *  Autor: Ricardo Nogueira Garcia
 *  Repositório: https://github.com/rngarcia/hubitat
 *  Gostou? Buy me a beer: https://www.paypal.com/donate/?business=rngarcia%40gmail.com&currency_code=USD
 *
 *  Changelog:
 *  v3.5.1 (2026-10-21) - RNG
 *    - Publicado em rngarcia/hubitat: cabeçalho padronizado, namespace "rng" e importUrl
 *  (histórico anterior no bloco de comentário abaixo)
 */
/**
 *  Goldshell BYTE Miner (MCB + CGMiner) - Hubitat Driver (Parent)
 *
 *  Author: Ricardo Nogueira Garcia
 *  Namespace: rng
 *
 *  Version: 3.5.0
 *  Date: 2026-06-18
 *
 *  Changelog:
 *   3.5.0 (2026-06-18) - Ricardo Nogueira Garcia
 *     Otimizações de Hub Load:
 *     - schedulePolling(): substituído runEvery1Minute + pollTick por schedule() com
 *       cron direto — elimina tick desnecessário a cada minuto
 *     - refresh(): removidos runInMillis(300) e runInMillis(700) — chamadas já são
 *       asynchttpGet (assíncronas), delay artificial gerava 2 runIn extras por ciclo
 *     - pushDevsToChild(): adicionado guard sendChildEventIfChanged() em todos os
 *       sendEvent — evita 28 eventos por poll quando valores não mudam
 *     - getSetting() / pushSettingToChildren(): separado do ciclo principal; só
 *       executado a cada 10 polls (padrão ~5 min) pois setting muda raramente
 *     - Todos os sendEvent do parent com guard de igualdade via sendEvtIfChanged()
 *   3.4.0 (2026-06-04) - Ricardo Nogueira Garcia
 *     - Added EnergyMeter capability (exposes `energy` attribute in kWh)
 *     - Energy accumulated via trapezoidal integration (power × Δt) on every poll cycle
 *     - Added resetEnergy command to zero the kWh counter
 *   3.3.0 (2026-06-04) - Ricardo Nogueira Garcia
 *     - Added PowerMeter capability to parent (exposes standard `power` attribute in W)
 *     - `power` is now the canonical energy attribute (= sum of both cards)
 *     - totalPowerW kept as alias for backwards compatibility
 *
 *  Notes:
 *   - Stores Bearer token in state via setBearerToken() (avoids Hubitat preference length limits)
 *   - Creates 2 child devices (Card 0 / Card 1) and updates their metrics
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

metadata {
    definition(name: "Goldshell BYTE Miner (MCB+CGMiner)", namespace: "rng", author: "Ricardo Nogueira Garcia",
        importUrl: "https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/goldshell-byte/goldshell-byte.groovy"
    ) {
        capability "Initialize"
        capability "Refresh"
        capability "Polling"
        capability "PowerMeter"
        capability "EnergyMeter"

        command "resetEnergy"
        command "reboot"
        command "getSetting"
        command "getDevs"
        command "getSystemInfo"

        command "setBearerToken", [[name:"token", type:"STRING", description:"JWT token ONLY (no 'Bearer ')"]]
        command "clearBearerToken"

        command "setLed",          [[name:"enabled",  type:"BOOL",   description:"true/false"]]
        command "setIdleMode",     [[name:"enabled",  type:"BOOL",   description:"true/false"]]
        command "setCardPowerPlan",[[name:"cardId",   type:"NUMBER"], [name:"level", type:"NUMBER"]]

        attribute "deviceModel",    "string"
        attribute "deviceFirmware", "string"
        attribute "deviceHardware", "string"
        attribute "mcbVersion",     "string"

        attribute "online",    "string"
        attribute "lastError", "string"
        attribute "lastPoll",  "string"

        attribute "ledcontrol",   "string"
        attribute "idlemode",     "string"
        attribute "tempcontrol",  "string"
        attribute "manual",       "string"
        attribute "adjustpower",  "number"
        attribute "deviceName",   "string"
        attribute "panelVersion", "string"

        attribute "power",          "number"
        attribute "energy",         "number"
        attribute "totalPowerW",    "number"
        attribute "totalAccepted",  "number"
        attribute "totalRejected",  "number"

        attribute "rawSetting", "string"
        attribute "rawDevs",    "string"
        attribute "rawSystem",  "string"
    }

    preferences {
        input name: "host",               type: "string", title: "Host/IP (ex: byte.lan ou 192.168.2.178)", required: true
        input name: "useHttps",           type: "bool",   title: "Use HTTPS (usually false)", defaultValue: false
        input name: "useBearerForCgminer",type: "bool",   title: "Use Bearer on /mcb/cgminer (if 401)", defaultValue: false
        input name: "enableChildren",     type: "bool",   title: "Create/maintain Child Devices (Card 0/1)", defaultValue: true
        input name: "pollSeconds",        type: "number", title: "Poll interval (seconds)", defaultValue: 30, range: "10..600", required: true
        input name: "settingPollEvery",   type: "number", title: "Atualizar Setting a cada N polls (padrão 10 = ~5 min com poll 30s)", defaultValue: 10, range: "1..100"
        input name: "logEnable",          type: "bool",   title: "Enable debug logging", defaultValue: false
        input name: "txtEnable",          type: "bool",   title: "Enable info logging",  defaultValue: true
    }
}

/* =========================
 * Lifecycle
 * ========================= */

def installed() { initialize() }

def updated() {
    logInfo "Updated (v3.5.0 2026-06-18)"
    unschedule()
    initialize()
    if (logEnable) runIn(1800, "logsOff")
}

def logsOff() {
    device.updateSetting("logEnable", [value:"false", type:"bool"])
    log.warn "${device.displayName} Debug logging disabled"
}

def initialize() {
    if (settings.enableChildren) ensureChildren()
    state.settingTickCount = 0
    schedulePolling()
    refresh()
}

def poll() { refresh() }

def refresh() {
    // getSystemInfo raramente muda — só a cada N polls como o setting
    Integer sysEvery = Math.max(1, (settings.settingPollEvery ?: 10) as Integer)
    state.settingTickCount = ((state.settingTickCount ?: 0) as Integer) + 1
    if (state.settingTickCount >= sysEvery) {
        state.settingTickCount = 0
        getSystemInfo()
        getSetting()
    }
    // devs é o dado de alta frequência — sempre
    getDevs()
}

/* =========================
 * Token management
 * ========================= */

def setBearerToken(String token) {
    if (!token) { setErr("Token vazio"); return }
    String t = token.toString().replaceAll("\\s", "")
    if (t.toLowerCase().startsWith("bearer")) {
        t = t.replaceFirst("(?i)^bearer\\s+", "").replaceAll("\\s", "")
    }
    if (t.count(".") < 2) { setErr("Token parece inválido (JWT tem 2 pontos). Cole só o JWT."); return }
    state.bearerToken = t
    logInfo "Bearer token saved (len=${t.size()})"
    setOk()
}

def clearBearerToken() {
    state.remove("bearerToken")
    logInfo "Bearer token cleared"
    setOk()
}

def resetEnergy() {
    state.energyWh    = 0.0
    state.lastPowerW  = 0.0
    state.lastPowerTs = now()
    sendEvtIfChanged("energy", 0.0, "kWh")
    logInfo "Energy counter reset to 0 kWh"
}

private String getBearerToken() {
    String t = (state?.bearerToken ?: "").replaceAll("\\s", "")
    return t ?: null
}

/* =========================
 * API Calls
 * ========================= */

def getSystemInfo() {
    httpGetJson("/mcb/status", "handleSystem", false)
}

def getSetting() {
    httpGetJson("/mcb/setting", "handleSetting", true)
}

def getDevs() {
    httpGetJson("/mcb/cgminer?cgminercmd=devs", "handleDevs", (settings.useBearerForCgminer == true))
}

def reboot() {
    def tok = getBearerToken()
    if (!tok) { setErr("Reboot requer Bearer token. Use setBearerToken(token)."); return }

    def proto = (settings.useHttps ? "https" : "http")
    def url   = "${proto}://${settings.host}/mcb/restart"
    def params = [
        uri: url,
        headers: ["Accept": "application/json", "Authorization": "Bearer ${tok}".toString()],
        timeout: 15
    ]
    logInfo "Reboot: PUT ${url}"
    try { asynchttpPut("handleReboot", params) }
    catch (e) { setErr("Reboot call failed: ${e}".take(240)) }
}

def handleReboot(resp, data) {
    if (!resp) { setErr("Reboot: no response"); return }
    if (resp.status >= 200 && resp.status < 300) { logInfo "Reboot OK (HTTP ${resp.status})"; setOk() }
    else setErr("Reboot failed HTTP ${resp.status}: ${safeBodySnippet(resp)}")
}

/* =========================
 * Control commands
 * ========================= */

def setLed(Boolean enabled)     { updateSettingAndPut([ledcontrol: (enabled == true)]) }
def setIdleMode(Boolean enabled) { updateSettingAndPut([idlemode:   (enabled == true)]) }

def setCardPowerPlan(Number cardId, Number level) {
    Integer cid = (cardId == null ? null : cardId as Integer)
    Integer lvl = (level  == null ? null : level  as Integer)
    if (cid == null || lvl == null) { setErr("setCardPowerPlan requires cardId and level"); return }
    updateSettingAndPut([_powerPlanUpdate: [cardId: cid, level: lvl]])
}

private void updateSettingAndPut(Map changes) {
    def tok = getBearerToken()
    if (!tok) { setErr("This command requires Bearer token saved via setBearerToken()"); return }

    Map patch = [:]
    if (changes.containsKey("ledcontrol")) patch.ledcontrol = (changes.ledcontrol == true)
    if (changes.containsKey("idlemode"))   patch.idlemode   = (changes.idlemode   == true)

    if (changes._powerPlanUpdate instanceof Map) {
        if (!(state.lastSetting instanceof Map)) {
            setErr("No cached setting yet. Click Refresh and try again.")
            return
        }
        Integer cid      = (changes._powerPlanUpdate.cardId as Integer)
        Integer lvl      = (changes._powerPlanUpdate.level  as Integer)
        def cpbs         = (state.lastSetting?.cpbs instanceof List) ? state.lastSetting.cpbs : null
        def card         = cpbs?.find { (it instanceof Map) && (it.id as Integer) == cid }
        Integer algoSel  = (card?.algo_select != null) ? (card.algo_select as Integer) : 0
        patch.cpbs       = [[ id: cid, algo_select: algoSel, mode: [[ select: lvl ]] ]]
    }

    if (patch.isEmpty()) { setErr("Nothing to update"); return }
    httpPutJson("/mcb/setting", patch, "handlePutSetting", tok)
}

def handlePutSetting(resp, data) {
    if (!resp) { setErr("PUT setting: no response"); return }
    if (resp.status >= 200 && resp.status < 300) {
        logInfo "PUT setting OK (HTTP ${resp.status})"
        setOk()
        getSetting()
        runInMillis(600, "getDevs")
    } else {
        setErr("PUT setting failed HTTP ${resp.status}: ${safeBodySnippet(resp)}")
    }
}

/* =========================
 * Handlers
 * ========================= */

def handleSystem(resp, data) {
    handleCommon(resp, "system")
    if (!isOk(resp)) return
    def obj = resp.getJson()
    if (!(obj instanceof Map)) return

    sendEvtIfChanged("rawSystem",       JsonOutput.toJson(obj).take(900))
    sendEvtIfChanged("deviceModel",     obj.model?.toString())
    sendEvtIfChanged("deviceFirmware",  obj.firmware?.toString())
    sendEvtIfChanged("deviceHardware",  obj.hardware?.toString())
    sendEvtIfChanged("mcbVersion",      obj.mcbversion?.toString())
    setOk()
}

def handleSetting(resp, data) {
    handleCommon(resp, "setting")
    if (!isOk(resp)) return
    def obj = resp.getJson()
    if (!(obj instanceof Map)) return

    state.lastSetting = obj

    sendEvtIfChanged("rawSetting",   JsonOutput.toJson(obj).take(900))
    sendEvtIfChanged("ledcontrol",   "${obj.ledcontrol}")
    sendEvtIfChanged("idlemode",     "${obj.idlemode}")
    sendEvtIfChanged("tempcontrol",  "${obj.tempcontrol}")
    sendEvtIfChanged("manual",       "${obj.manual}")
    if (obj.adjustpower != null) sendEvtIfChanged("adjustpower", toNum(obj.adjustpower), "number")
    if (obj.name)    sendEvtIfChanged("deviceName",   obj.name.toString())
    if (obj.version) sendEvtIfChanged("panelVersion", obj.version.toString())

    if (settings.enableChildren) {
        ensureChildren()
        pushSettingToChildren(obj)
    }
    setOk()
}

def handleDevs(resp, data) {
    handleCommon(resp, "devs")
    if (!isOk(resp)) return
    def obj = resp.getJson()
    if (!(obj instanceof Map)) return

    sendEvtIfChanged("rawDevs", JsonOutput.toJson(obj).take(900))

    Map<Integer, Map> byId = [:]
    obj.minfos?.each { m ->
        def algo = m.name?.toString()
        m.infos?.each { inf ->
            Integer id = (inf.id as Integer)
            inf._algoName = algo
            byId[id] = inf
        }
    }

    if (settings.enableChildren) {
        ensureChildren()
        pushDevsToChild(0, byId[0])
        pushDevsToChild(1, byId[1])
    }

    // totals
    def p0 = toNum(byId[0]?.power)
    def p1 = toNum(byId[1]?.power)
    if (p0 != null || p1 != null) {
        def totalW = (p0 ?: 0) + (p1 ?: 0)
        sendEvtIfChanged("power",       totalW, "number", "W")
        sendEvtIfChanged("totalPowerW", totalW, "number", "W")
        accumulateEnergy(totalW as double)
    }

    def a0 = toNum(byId[0]?.accepted)
    def a1 = toNum(byId[1]?.accepted)
    sendEvtIfChanged("totalAccepted", ((a0 ?: 0) + (a1 ?: 0)))

    def r0 = toNum(byId[0]?.rejected)
    def r1 = toNum(byId[1]?.rejected)
    sendEvtIfChanged("totalRejected", ((r0 ?: 0) + (r1 ?: 0)))

    setOk()
}

/* =========================
 * Child devices
 * ========================= */

private String childDni(int cardId) { return "${device.deviceNetworkId}-card${cardId}" }

private void ensureChildren() {
    if (!(settings.enableChildren == true)) return
    createOrUpdateChild(0, "Card 0")
    createOrUpdateChild(1, "Card 1")
}

private void createOrUpdateChild(int cardId, String labelSuffix) {
    String dni   = childDni(cardId)
    def child    = getChildDevice(dni)
    if (!child) {
        logInfo "Creating child device ${dni}"
        child = addChildDevice("rng", "Goldshell BYTE Miner Card", dni,
                    [label: "${device.displayName} ${labelSuffix}", isComponent: true])
        child?.updateDataValue("cardId", "${cardId}")
        child?.sendEvent(name:"cardId", value: cardId)
    } else {
        if (!child.getDataValue("cardId")) child.updateDataValue("cardId", "${cardId}")
        child.sendEvent(name:"cardId", value: cardId)
    }
}

private void pushSettingToChildren(Map setting) {
    def cpbs = setting?.cpbs
    if (!(cpbs instanceof List)) return
    cpbs.each { c ->
        if (!(c instanceof Map)) return
        Integer cid = (c.id != null) ? (c.id as Integer) : null
        if (cid == null) return
        def child = getChildDevice(childDni(cid))
        if (!child) return
        def algoSel = (c.algo_select != null) ? (c.algo_select as Integer) : 0
        def modes   = c.mode
        if (modes instanceof List && !modes.isEmpty()) {
            if (algoSel < 0 || algoSel >= modes.size()) algoSel = 0
            def mode = modes[algoSel]
            if (mode instanceof Map) {
                if (mode.algo   != null) sendChildEventIfChanged(child, "algo",      mode.algo.toString())
                if (mode.select != null) sendChildEventIfChanged(child, "powerPlan", toNum(mode.select))
            }
        }
        sendChildEventIfChanged(child, "online",    "true")
        sendChildEventIfChanged(child, "lastError", "")
    }
}

private void pushDevsToChild(int cardId, Map inf) {
    if (!inf) return
    def child = getChildDevice(childDni(cardId))
    if (!child) return

    // Guard em cada sendEvent — só emite se valor mudou
    // Evita ~28 eventos por poll quando miner está estável
    sendChildEventIfChanged(child, "algo",        inf._algoName)
    sendChildEventIfChanged(child, "valid",       toNum(inf.valid))
    sendChildEventIfChanged(child, "hashrate",    toNum(inf.hashrate))
    sendChildEventIfChanged(child, "avHashrate",  toNum(inf.av_hashrate))
    sendChildEventIfChanged(child, "accepted",    toNum(inf.accepted))
    sendChildEventIfChanged(child, "rejected",    toNum(inf.rejected))
    sendChildEventIfChanged(child, "hwErrors",    toNum(inf.hwerrors))
    sendChildEventIfChanged(child, "hwErrRatio",  toNum(inf.hwerr_ration))
    sendChildEventIfChanged(child, "powerPlan",   toNum(inf.powerplan))
    sendChildEventIfChanged(child, "minerStatus", toNum(inf.minerstatus))
    sendChildEventIfChanged(child, "nonce",       toNum(inf.nonces))

    def rpm = parseRpm(inf.fanspeed?.toString())
    if (rpm != null) sendChildEventIfChanged(child, "fanRpm", rpm)

    def temps = parseTemps(inf.temp?.toString())
    if (temps.t1 != null) sendChildEventIfChanged(child, "tempA", temps.t1)
    if (temps.t2 != null) sendChildEventIfChanged(child, "tempB", temps.t2)

    def pw = toNum(inf.power)
    if (pw != null) sendChildEventIfChanged(child, "powerW", pw)

    sendChildEventIfChanged(child, "online",    "true")
    sendChildEventIfChanged(child, "lastError", "")
}

/* -------------------------------------------------------
 * Guard de igualdade para child devices
 * Evita sendEvent quando valor não mudou — principal causa
 * de eventos desnecessários no Maker API
 * ------------------------------------------------------- */
private void sendChildEventIfChanged(def child, String name, def value, String unit = null) {
    if (child.currentValue(name)?.toString() == value?.toString()) return
    Map evt = [name: name, value: value]
    if (unit) evt.unit = unit
    child.sendEvent(evt)
}

/* Component methods called by children */
def componentRefresh(cd)                          { getDevs() }
def componentSetPowerPlan(cd, Number level) {
    Integer cid = (cd?.getDataValue("cardId") ?: cd?.currentValue("cardId")) as Integer
    setCardPowerPlan(cid, level)
}
def componentReboot(cd) { reboot() }

/* =========================
 * HTTP helpers
 * ========================= */

private void httpGetJson(String path, String cbMethod, boolean includeBearer) {
    def proto = (settings.useHttps ? "https" : "http")
    def url   = "${proto}://${settings.host}${path}"
    Map headers = ["Accept": "application/json, text/plain, */*"]

    if (includeBearer) {
        def tok = getBearerToken()
        if (!tok) { setErr("Missing Bearer token for ${path}. Use setBearerToken(token)."); return }
        headers["Authorization"] = "Bearer ${tok}".toString()
    }

    def params = [uri: url, headers: headers, timeout: 15]
    logDebug "GET ${url}"
    try { asynchttpGet(cbMethod, params) }
    catch (e) { setErr("HTTP GET failed ${path}: ${e}".take(240)) }
}

private void httpPutJson(String path, Map body, String cbMethod, String tok) {
    def proto    = (settings.useHttps ? "https" : "http")
    def url      = "${proto}://${settings.host}${path}"
    def jsonBody = JsonOutput.toJson(body)
    Map headers  = [
        "Accept":        "application/json, text/plain, */*",
        "Content-Type":  "application/json",
        "Authorization": "Bearer ${tok}".toString(),
        "Connection":    "close"
    ]
    def params = [uri: url, headers: headers, body: jsonBody, timeout: 45]
    logInfo "PUT ${url} bodyLen=${jsonBody.size()} body=${jsonBody.take(220)}"
    try { asynchttpPut(cbMethod, params) }
    catch (e) { setErr("HTTP PUT failed ${path}: ${e}".take(240)) }
}

private boolean isOk(resp) {
    return (resp && resp.status >= 200 && resp.status < 300)
}

private void handleCommon(resp, String what) {
    sendEvtIfChanged("lastPoll", new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))
    if (!resp) { setErr("${what}: no response"); return }
    if (resp.status == 401 || resp.status == 403) {
        setErr("${what}: unauthorized (HTTP ${resp.status})")
        sendEvtIfChanged("online", "false")
        getChildDevices()?.each {
            it.sendEvent(name:"online",    value:"false")
            it.sendEvent(name:"lastError", value:"unauthorized")
        }
    }
}

/* -------------------------------------------------------
 * Guard de igualdade para parent device
 * ------------------------------------------------------- */
private void sendEvtIfChanged(String name, def value, String type = "string", String unit = null) {
    if (device.currentValue(name)?.toString() == value?.toString()) return
    Map evt = [name: name, value: value]
    if (unit) evt.unit = unit
    sendEvent(evt)
}

private BigDecimal toNum(def v) {
    if (v == null) return null
    try { return new BigDecimal(v.toString()) } catch (ignored) { return null }
}

private Integer parseRpm(String s) {
    if (!s) return null
    def m = (s =~ /(?i)([0-9]+)\s*rpm/)
    if (m.find()) return m.group(1).toInteger()
    return null
}

private Map parseTemps(String s) {
    if (!s) return [t1:null, t2:null]
    def m = (s =~ /(?i)([0-9]+(?:\.[0-9]+)?)\s*°?C\s*\/\s*([0-9]+(?:\.[0-9]+)?)\s*°?C/)
    if (m.find()) return [t1: new BigDecimal(m.group(1)), t2: new BigDecimal(m.group(2))]
    def m2 = (s =~ /(?i)([0-9]+(?:\.[0-9]+)?)\s*°?C/)
    if (m2.find()) return [t1: new BigDecimal(m2.group(1)), t2: null]
    return [t1:null, t2:null]
}

private void accumulateEnergy(double currentW) {
    long   tsNow     = now()
    long   tsLast    = (state.lastPowerTs ?: tsNow) as long
    double prevW     = (state.lastPowerW  ?: 0.0)   as double
    double accWh     = (state.energyWh    ?: 0.0)   as double

    double deltaHours = (tsNow - tsLast) / 3_600_000.0
    if (deltaHours > 0 && deltaHours < 1.0) {
        accWh += ((prevW + currentW) / 2.0) * deltaHours
    }

    state.lastPowerW  = currentW
    state.lastPowerTs = tsNow
    state.energyWh    = accWh

    def kWh = (accWh / 1000.0).round(4)
    sendEvtIfChanged("energy", kWh, "number", "kWh")
    logDebug "Energy: ${kWh} kWh (acc ${accWh.round(2)} Wh, Δt=${(deltaHours*60).round(1)} min, ${currentW} W)"
}

private String safeBodySnippet(resp) {
    try { return resp?.data?.toString()?.take(180) ?: "" } catch (ignored) { return "" }
}

private void setOk() {
    sendEvtIfChanged("online",    "true")
    sendEvtIfChanged("lastError", "")
}

private void setErr(String msg) {
    sendEvtIfChanged("lastError", msg.take(240))
    log.warn "${device.displayName}: ${msg}"
    getChildDevices()?.each { it.sendEvent(name:"lastError", value: msg.take(240)) }
}

private void logDebug(msg) { if (logEnable) log.debug "${device.displayName}: ${msg}" }
private void logInfo(msg)  { if (txtEnable) log.info  "${device.displayName}: ${msg}" }

/* =========================
 * Polling scheduler
 * Usa schedule() com cron direto em vez de runEvery1Minute + pollTick.
 * Elimina tick desnecessário a cada minuto e a comparação de timestamp.
 * ========================= */

private void schedulePolling() {
    unschedule("refresh")
    unschedule("pollTick")   // limpa schedule antigo se existir

    Integer s = Math.max(10, Math.min(600, (settings.pollSeconds ?: 30) as Integer))
    logInfo "Polling agendado a cada ${s}s"

    if (s < 60) {
        // cron a cada N segundos (Hubitat suporta até 10s mínimo)
        schedule("0/${s} * * * * ?", "refresh")
    } else if (s < 3600) {
        Integer mins = Math.round(s / 60f) as Integer
        if (mins < 1) mins = 1
        schedule("0 0/${mins} * * * ?", "refresh")
    } else {
        // fallback para 1 hora
        schedule("0 0 * * * ?", "refresh")
    }
}