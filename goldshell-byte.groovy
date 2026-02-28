/**
 *  Goldshell BYTE Miner (MCB + CGMiner) - Hubitat Driver (Parent)
 *
 *  Author: Ricardo Nogueira Garcia
 *  Namespace: rng
 *
 *  Version: 3.2.0
 *  Date: 2026-01-26
 *
 *  Notes:
 *   - Stores Bearer token in state via setBearerToken() (avoids Hubitat preference length limits)
 *   - Creates 2 child devices (Card 0 / Card 1) and updates their metrics
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

metadata {
    definition(name: "Goldshell BYTE Miner (MCB+CGMiner)", namespace: "rng", author: "Ricardo Nogueira Garcia") {
        capability "Initialize"
        capability "Refresh"
        capability "Polling"

        command "reboot"
        command "getSetting"
        command "getDevs"
        command "getSystemInfo"

        // Token management (Option 2)
        command "setBearerToken", [[name:"token", type:"STRING", description:"JWT token ONLY (no 'Bearer ')"]]
        command "clearBearerToken"

        // Control commands (best-effort PUT /mcb/setting)
        command "setLed", [[name:"enabled", type:"BOOL", description:"true/false"]]
        command "setIdleMode", [[name:"enabled", type:"BOOL", description:"true/false"]]
        command "setCardPowerPlan", [[name:"cardId", type:"NUMBER"], [name:"level", type:"NUMBER"]]

        // system info (/mcb/status)
        attribute "deviceModel", "string"
        attribute "deviceFirmware", "string"
        attribute "deviceHardware", "string"
        attribute "mcbVersion", "string"

        // base
        attribute "online", "string"
        attribute "lastError", "string"
        attribute "lastPoll", "string"

        // flags (/mcb/setting)
        attribute "ledcontrol", "string"
        attribute "idlemode", "string"
        attribute "tempcontrol", "string"
        attribute "manual", "string"
        attribute "adjustpower", "number"
        attribute "deviceName", "string"
        attribute "panelVersion", "string"

        // totals
        attribute "totalPowerW", "number"
        attribute "totalAccepted", "number"
        attribute "totalRejected", "number"

        // raw
        attribute "rawSetting", "string"
        attribute "rawDevs", "string"
        attribute "rawSystem", "string"
    }

    preferences {
        input name: "host", type: "string", title: "Host/IP (ex: byte.lan ou 192.168.2.178)", required: true
        input name: "useHttps", type: "bool", title: "Use HTTPS (usually false)", defaultValue: false

        // cgminer endpoint sometimes needs Bearer; toggle here
        input name: "useBearerForCgminer", type: "bool", title: "Use Bearer on /mcb/cgminer (if 401)", defaultValue: false

        // child devices
        input name: "enableChildren", type: "bool", title: "Create/maintain Child Devices (Card 0/1)", defaultValue: true

        input name: "pollSeconds", type: "number", title: "Poll interval (seconds)", defaultValue: 30, range: "10..600", required: true

        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable info logging", defaultValue: true
    }
}

/* =========================
 * Lifecycle
 * ========================= */

def installed() { initialize() }

def updated() {
    logInfo "Updated (v3.2.0 2026-01-26)"
    unschedule()
    initialize()
    if (logEnable) runIn(1800, "logsOff")
}

def logsOff() {
    device.updateSetting("logEnable", [value:"false", type:"bool"])
    log.warn "Debug logging disabled"
}

def initialize() {
    if (settings.enableChildren) ensureChildren()
    schedulePolling()
    refresh()
}

def poll() { refresh() }

def refresh() {
    getSystemInfo()
    runInMillis(300, "getSetting")
    runInMillis(700, "getDevs")
}

/* =========================
 * Token management (Option 2)
 * ========================= */

def setBearerToken(String token) {
    if (!token) {
        setErr("Token vazio")
        return
    }
    String t = token.toString()
    t = t.replaceAll("\\s", "") // remove spaces/newlines
    if (t.toLowerCase().startsWith("bearer")) {
        // if user accidentally pasted "Bearer <jwt>"
        t = t.replaceFirst("(?i)^bearer\\s+", "")
        t = t.replaceAll("\\s", "")
    }
    if (t.count(".") < 2) {
        setErr("Token parece inválido (JWT normalmente tem 2 pontos). Cole só o JWT.")
        return
    }
    state.bearerToken = t
    logInfo "Bearer token saved to state (len=${t.size()})"
    setOk()
}

def clearBearerToken() {
    state.remove("bearerToken")
    logInfo "Bearer token cleared"
    setOk()
}

private String getBearerToken() {
    String t = (state?.bearerToken ?: "")
    t = t.replaceAll("\\s", "")
    return t ? t : null
}

/* =========================
 * API Calls
 * ========================= */

def getSystemInfo() {
    httpGetJson("/mcb/status", "handleSystem", false)
}

def getSetting() {
    // usually requires Bearer
    httpGetJson("/mcb/setting", "handleSetting", true)
}

def getDevs() {
    httpGetJson("/mcb/cgminer?cgminercmd=devs", "handleDevs", (settings.useBearerForCgminer == true))
}

def reboot() {
    def tok = getBearerToken()
    if (!tok) {
        setErr("Reboot requer Bearer token salvo. Use setBearerToken(token).")
        return
    }

    def proto = (settings.useHttps ? "https" : "http")
    def url = "${proto}://${settings.host}/mcb/restart"

    def params = [
        uri: url,
        headers: [
            "Accept": "application/json",
            "Authorization": "Bearer ${tok}".toString()
        ],
        timeout: 15
    ]

    logInfo "Reboot: PUT ${url}"
    try {
        asynchttpPut("handleReboot", params)
    } catch (e) {
        setErr("Reboot call failed: ${e}".take(240))
    }
}

def handleReboot(resp, data) {
    if (!resp) { setErr("Reboot: no response"); return }
    if (resp.status >= 200 && resp.status < 300) {
        logInfo "Reboot OK (HTTP ${resp.status})"
        setOk()
    } else {
        setErr("Reboot failed HTTP ${resp.status}: ${safeBodySnippet(resp)}")
    }
}

/* =========================
 * Control commands (best-effort PUT /mcb/setting)
 * ========================= */

def setLed(Boolean enabled) {
    updateSettingAndPut([ledcontrol: (enabled == true)])
}

def setIdleMode(Boolean enabled) {
    updateSettingAndPut([idlemode: (enabled == true)])
}

def setCardPowerPlan(Number cardId, Number level) {
    Integer cid = (cardId == null ? null : cardId as Integer)
    Integer lvl = (level == null ? null : level as Integer)
    if (cid == null || lvl == null) {
        setErr("setCardPowerPlan requires cardId and level")
        return
    }
    updateSettingAndPut([_powerPlanUpdate: [cardId: cid, level: lvl]])
}

private void updateSettingAndPut(Map changes) {
    def tok = getBearerToken()
    if (!tok) {
        setErr("This command requires Bearer token saved via setBearerToken()")
        return
    }

    // PATCH pequeno (evita 408 por payload grande)
    Map patch = [:]

    if (changes.containsKey("ledcontrol")) patch.ledcontrol = (changes.ledcontrol == true)
    if (changes.containsKey("idlemode")) patch.idlemode = (changes.idlemode == true)

    // powerplan: vamos mandar um patch menor (pode precisar ajuste depois)
    if (changes._powerPlanUpdate instanceof Map) {
        if (!(state.lastSetting instanceof Map)) {
            setErr("No cached setting yet. Click Refresh and try again.")
            return
        }

        Integer cid = (changes._powerPlanUpdate.cardId as Integer)
        Integer lvl = (changes._powerPlanUpdate.level as Integer)

        // pegar algo_select atual para este card (do cache)
        def cpbs = (state.lastSetting?.cpbs instanceof List) ? state.lastSetting.cpbs : null
        def card = cpbs?.find { (it instanceof Map) && (it.id as Integer) == cid }
        Integer algoSel = (card?.algo_select != null) ? (card.algo_select as Integer) : 0

        // PATCH pro card específico
        patch.cpbs = [[
            id: cid,
            algo_select: algoSel,
            mode: [[ select: lvl ]],   // alguns firmwares aceitam assim (modo atual = index 0)
        ]]
    }

    if (patch.isEmpty()) {
        setErr("Nothing to update")
        return
    }

    httpPutJson("/mcb/setting", patch, "handlePutSetting", tok)
}

def handlePutSetting(resp, data) {
    if (!resp) { setErr("PUT setting: no response"); return }
    if (resp.status >= 200 && resp.status < 300) {
        logInfo "PUT setting OK (HTTP ${resp.status})"
        setOk()
        // Refresh to reflect applied changes
        runInMillis(350, "getSetting")
        runInMillis(900, "getDevs")
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

    sendEvent(name:"rawSystem", value: JsonOutput.toJson(obj).take(900))
    if (obj.model)      sendEvent(name:"deviceModel", value: obj.model.toString())
    if (obj.firmware)   sendEvent(name:"deviceFirmware", value: obj.firmware.toString())
    if (obj.hardware)   sendEvent(name:"deviceHardware", value: obj.hardware.toString())
    if (obj.mcbversion) sendEvent(name:"mcbVersion", value: obj.mcbversion.toString())

    setOk()
}

def handleSetting(resp, data) {
    handleCommon(resp, "setting")
    if (!isOk(resp)) return

    def obj = resp.getJson()
    if (!(obj instanceof Map)) return

    // cache for PUT updates
    state.lastSetting = obj

    sendEvent(name:"rawSetting", value: JsonOutput.toJson(obj).take(900))
    sendEvent(name:"ledcontrol", value: "${obj.ledcontrol}")
    sendEvent(name:"idlemode", value: "${obj.idlemode}")
    sendEvent(name:"tempcontrol", value: "${obj.tempcontrol}")
    sendEvent(name:"manual", value: "${obj.manual}")

    if (obj.adjustpower != null) sendEvent(name:"adjustpower", value: toNum(obj.adjustpower))
    if (obj.name)    sendEvent(name:"deviceName", value: obj.name.toString())
    if (obj.version) sendEvent(name:"panelVersion", value: obj.version.toString())

    // Keep children alive + send static settings
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

    sendEvent(name:"rawDevs", value: JsonOutput.toJson(obj).take(900))

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
    if (p0 != null || p1 != null) sendEvent(name:"totalPowerW", value: ((p0 ?: 0) + (p1 ?: 0)))

    def a0 = toNum(byId[0]?.accepted)
    def a1 = toNum(byId[1]?.accepted)
    sendEvent(name:"totalAccepted", value: ((a0 ?: 0) + (a1 ?: 0)))

    def r0 = toNum(byId[0]?.rejected)
    def r1 = toNum(byId[1]?.rejected)
    sendEvent(name:"totalRejected", value: ((r0 ?: 0) + (r1 ?: 0)))

    setOk()
}

/* =========================
 * Child devices
 * ========================= */

private String childDni(int cardId) {
    return "${device.deviceNetworkId}-card${cardId}"
}

private void ensureChildren() {
    if (!(settings.enableChildren == true)) return
    createOrUpdateChild(0, "Card 0")
    createOrUpdateChild(1, "Card 1")
}

private void createOrUpdateChild(int cardId, String labelSuffix) {
    String dni = childDni(cardId)
    def child = getChildDevice(dni)
    if (!child) {
        logInfo "Creating child device ${dni}"
        child = addChildDevice("rng", "Goldshell BYTE Miner Card", dni, [label: "${device.displayName} ${labelSuffix}", isComponent: true])
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
        def modes = c.mode
        if (modes instanceof List && !modes.isEmpty()) {
            if (algoSel < 0 || algoSel >= modes.size()) algoSel = 0
            def mode = modes[algoSel]
            if (mode instanceof Map) {
                if (mode.algo != null) child.sendEvent(name:"algo", value: mode.algo.toString())
                if (mode.select != null) child.sendEvent(name:"powerPlan", value: toNum(mode.select))
            }
        }
        child.sendEvent(name:"online", value: "true")
        child.sendEvent(name:"lastError", value: "")
    }
}

private void pushDevsToChild(int cardId, Map inf) {
    if (!inf) return
    def child = getChildDevice(childDni(cardId))
    if (!child) return

    child.sendEvent(name:"algo", value: inf._algoName)
    child.sendEvent(name:"valid", value: toNum(inf.valid))
    child.sendEvent(name:"hashrate", value: toNum(inf.hashrate))
    child.sendEvent(name:"avHashrate", value: toNum(inf.av_hashrate))
    child.sendEvent(name:"accepted", value: toNum(inf.accepted))
    child.sendEvent(name:"rejected", value: toNum(inf.rejected))
    child.sendEvent(name:"hwErrors", value: toNum(inf.hwerrors))
    child.sendEvent(name:"hwErrRatio", value: toNum(inf.hwerr_ration))
    child.sendEvent(name:"powerPlan", value: toNum(inf.powerplan))
    child.sendEvent(name:"minerStatus", value: toNum(inf.minerstatus))
    child.sendEvent(name:"nonce", value: toNum(inf.nonces))

    def rpm = parseRpm(inf.fanspeed?.toString())
    if (rpm != null) child.sendEvent(name:"fanRpm", value: rpm)

    def temps = parseTemps(inf.temp?.toString())
    if (temps.t1 != null) child.sendEvent(name:"tempA", value: temps.t1)
    if (temps.t2 != null) child.sendEvent(name:"tempB", value: temps.t2)

    def pw = toNum(inf.power)
    if (pw != null) child.sendEvent(name:"powerW", value: pw)

    child.sendEvent(name:"online", value: "true")
    child.sendEvent(name:"lastError", value: "")
}

/* Component methods called by children */
def componentRefresh(cd) { getDevs() }

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
    def url = "${proto}://${settings.host}${path}"

    Map headers = ["Accept": "application/json, text/plain, */*"]

    if (includeBearer) {
        def tok = getBearerToken()
        if (!tok) {
            setErr("Missing Bearer token for ${path}. Use setBearerToken(token).")
            return
        }
        headers["Authorization"] = "Bearer ${tok}".toString()
    }

    def params = [ uri: url, headers: headers, timeout: 15 ]

    logDebug "GET ${url}"
    try {
        asynchttpGet(cbMethod, params)
    } catch (e) {
        setErr("HTTP GET failed ${path}: ${e}".take(240))
    }
}

private void httpPutJson(String path, Map body, String cbMethod, String tok) {
    def proto = (settings.useHttps ? "https" : "http")
    def url = "${proto}://${settings.host}${path}"

    def jsonBody = JsonOutput.toJson(body)

    Map headers = [
        "Accept": "application/json, text/plain, */*",
        "Content-Type": "application/json",
        "Authorization": "Bearer ${tok}".toString(),
        "Connection": "close"
    ]

    def params = [
        uri: url,
        headers: headers,
        body: jsonBody,
        timeout: 45
    ]

    logInfo "PUT ${url} bodyLen=${jsonBody.size()} body=${jsonBody.take(220)}"
    try {
        asynchttpPut(cbMethod, params)
    } catch (e) {
        setErr("HTTP PUT failed ${path}: ${e}".take(240))
    }
}

private boolean isOk(resp) {
    return (resp && resp.status >= 200 && resp.status < 300)
}

private void handleCommon(resp, String what) {
    sendEvent(name:"lastPoll", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))
    if (!resp) { setErr("${what}: no response"); return }
    if (resp.status == 401 || resp.status == 403) {
        setErr("${what}: unauthorized (HTTP ${resp.status})")
        sendEvent(name:"online", value:"false")
        getChildDevices()?.each { it.sendEvent(name:"online", value:"false"); it.sendEvent(name:"lastError", value:"unauthorized") }
    }
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
    if (m.find()) {
        return [t1: new BigDecimal(m.group(1)), t2: new BigDecimal(m.group(2))]
    }
    def m2 = (s =~ /(?i)([0-9]+(?:\.[0-9]+)?)\s*°?C/)
    if (m2.find()) return [t1: new BigDecimal(m2.group(1)), t2: null]
    return [t1:null, t2:null]
}

private Map deepCloneMap(Map m) {
    def s = JsonOutput.toJson(m)
    return (Map) new JsonSlurper().parseText(s)
}

private String safeBodySnippet(resp) {
    try { return resp?.data?.toString()?.take(180) ?: "" } catch (ignored) { return "" }
}

private void setOk() {
    sendEvent(name:"online", value:"true")
    sendEvent(name:"lastError", value:"")
}

private void setErr(String msg) {
    sendEvent(name:"lastError", value: msg.take(240))
    log.warn "${device.displayName}: ${msg}"
    getChildDevices()?.each { it.sendEvent(name:"lastError", value: msg.take(240)) }
}

private void logDebug(msg) { if (logEnable) log.debug "${device.displayName}: ${msg}" }
private void logInfo(msg)  { if (txtEnable) log.info  "${device.displayName}: ${msg}" }

/* =========================
 * Polling scheduler
 * ========================= */

private void schedulePolling() {
    def s = (settings.pollSeconds ?: 30) as Integer
    s = Math.max(10, Math.min(600, s))
    state.pollEvery = s
    state.lastTick = 0L
    runEvery1Minute("pollTick")
}

def pollTick() {
    if ((now() - (state.lastTick ?: 0L)) >= (state.pollEvery * 1000L)) {
        state.lastTick = now()
        refresh()
    }
}
