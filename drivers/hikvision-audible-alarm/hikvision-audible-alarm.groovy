/**
 *  Hikvision Audible Alarm Trigger (ISAPI) — Hubitat Driver
 *  Versão: 1.5.1 — Build: 2026-10-21
 *  Autor: Ricardo Nogueira Garcia
 *  Repositório: https://github.com/rngarcia/hubitat
 *  Gostou? Buy me a beer: https://www.paypal.com/donate/?business=rngarcia%40gmail.com&currency_code=USD
 *
 *  Changelog:
 *  v1.5.1 (2026-10-21) - RNG
 *    - Publicado em rngarcia/hubitat: cabeçalho padronizado, namespace "rng" e importUrl
 *  (histórico anterior no bloco de comentário abaixo)
 */
/**
 *  Hikvision Audible Alarm Trigger (ISAPI) - Linkage via Event Trigger Notifications
 *
 *  What this driver DOES:
 *   - Configure AudioAlarm (sound/volume/repetitions)
 *   - ARM/DISARM audible warning by adding/removing "audioAlarm" linkage action on a chosen Event Trigger ID
 *     via: GET/PUT /ISAPI/Event/triggers/<ID>/notifications
 *
 *  What it CANNOT do (your device returns 403 even with admin):
 *   - Play now via /ISAPI/Event/triggers/notifications/AudioAlarm/test
 *   - Control IO inputs/outputs (403)
 *
 *  v1.5.0 (2026-01-25)
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

metadata {
    definition(
        name: "Hikvision Audible Alarm Trigger (ISAPI)",
        namespace: "rng",
        author: "Ricardo Nogueira Garcia",
        importUrl: "https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/hikvision-audible-alarm/hikvision-audible-alarm.groovy"
    ) {
        capability "Actuator"
        capability "Alarm"
        capability "Switch"
        capability "Refresh"

        command "testConnection"
        command "pulseSiren", [[name:"Seconds*", type:"NUMBER", description:"Arm duration in seconds"]]
        command "probeCommonTriggerIds"

        // AudioAlarm (profile/config)
        command "getAudioAlarmCapabilities"
        command "getAudioAlarm"
        command "setAudioAlarm"          // uses preferences

        // Trigger linkage (THIS is what makes motion -> audible)
        command "discoverTriggerIds"     // GET /ISAPI/Event/triggersCap (for you to pick the trigger ID)
        command "getTriggerNotifications"
        command "armAudible"             // ensure audioAlarm linkage is present for chosen trigger
        command "disarmAudible"          // remove audioAlarm linkage for chosen trigger

        attribute "connection", "string"        // ok/off/cred/err/unknown
        attribute "audioAlarmSupport", "string" // supported/unsupported/unknown
        attribute "audibleArmed", "string"      // on/off/unknown
        attribute "eventTriggerId", "string"

        attribute "audioAlarmId", "number"
        attribute "audioAlarmVolume", "number"
        attribute "audioAlarmTimes", "number"

        attribute "driverVersion", "string"
        attribute "lastBody", "string"
        attribute "lastStatus", "number"
    }

    preferences {
        input name: "protocol", type: "enum", title: "Protocol", options: ["http","https"], defaultValue: "http", required: true
        input name: "ip", type: "string", title: "Camera IP/Host", required: true
        input name: "port", type: "number", title: "Port (blank = 80/443)", required: false
        input name: "username", type: "string", title: "Username (Operator/Admin)", required: true
        input name: "password", type: "password", title: "Password", required: true
        input name: "ignoreSslIssues", type: "bool", title: "Ignore SSL issues (HTTPS)", defaultValue: true

        // Choose which trigger should cause audible warning (MOST IMPORTANT)
        input name: "eventTriggerId", type: "string",
            title: "Event Trigger ID to link audible warning (discover with discoverTriggerIds)",
            description: "Examples vary by firmware (e.g., VMD-1, motionDetection-1, videoMotion-1...). Use discoverTriggerIds to find yours.",
            required: true

        // AudioAlarm defaults (sound profile)
        input name: "audioAlarmIdPref", type: "number", title: "AudioAlarm sound ID (1 = Siren)", defaultValue: 1, required: true
        input name: "audioAlarmVolumePref", type: "number", title: "AudioAlarm volume (1-100)", defaultValue: 100, required: true
        input name: "audioAlarmTimesPref", type: "number", title: "AudioAlarm times (1-50)", defaultValue: 5, required: true

        input name: "infoEnable", type: "bool", title: "Enable info logging", defaultValue: true
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

def installed() {
    sendEvent(name: "driverVersion", value: "v1.5.1 (2026-10-21)")
    sendEvent(name: "connection", value: "unknown")
    sendEvent(name: "audioAlarmSupport", value: "unknown")
    sendEvent(name: "audibleArmed", value: "unknown")
    sendEvent(name: "switch", value: "off")
    sendEvent(name: "alarm", value: "off")
}

def updated() {
    sendEvent(name: "driverVersion", value: "v1.5.1 (2026-10-21)")
    sendEvent(name: "eventTriggerId", value: (settings.eventTriggerId ?: ""))
    if (logEnable) runIn(1800, "logsOff")
}

def logsOff() {
    device.updateSetting("logEnable", [value:"false", type:"bool"])
    log.warn "Debug logging disabled"
}

// ----- Capability handlers -----
// In this driver: ON = "arm audible warning linkage for chosen event trigger"
def on()  { armAudible() }
def off() { disarmAudible() }

def siren()  { armAudible() }
def strobe() { armAudible() }
def both()   { armAudible() }

def refresh() {
    getAudioAlarmCapabilities()
    runIn(1, "getAudioAlarm")
    runIn(2, "getTriggerNotifications")
}

// ----- Pulse = arm for N seconds then disarm -----
def pulseSiren(Number seconds) {
    Integer sec = Math.max(1, (seconds ?: 10) as Integer)
    if (infoEnable) log.info "Pulse audible warning linkage (ARM for ${sec}s, then DISARM)."
    armAudible()
    runIn(sec, "disarmAudible")
}

// ----- AudioAlarm profile -----
def getAudioAlarmCapabilities() {
    String path = "/ISAPI/Event/triggers/notifications/AudioAlarm/capabilities?format=json"
    if (infoEnable) log.info "GET ${path}"
    def resp = doGet(path, true, "application/json")
    handleResp("GET ${path}", resp)

    if (resp?.status == 200) {
        sendEvent(name:"audioAlarmSupport", value:"supported")
    } else if (resp?.status in [403,404]) {
        sendEvent(name:"audioAlarmSupport", value:"unsupported")
    }
}

def getAudioAlarm() {
    String path = "/ISAPI/Event/triggers/notifications/AudioAlarm?format=json"
    if (infoEnable) log.info "GET ${path}"
    def resp = doGet(path, true, "application/json")
    handleResp("GET ${path}", resp)

    if (resp?.status == 200 && resp?.body) {
        try {
            def json = new JsonSlurper().parseText(resp.body)
            def cfg = (json?.AudioAlarm ?: json)

            Integer id  = safeInt(cfg?.audioID)
            Integer vol = safeInt(cfg?.audioVolume)
            Integer tms = safeInt(cfg?.alarmTimes)

            if (id != null)  sendEvent(name:"audioAlarmId", value:id)
            if (vol != null) sendEvent(name:"audioAlarmVolume", value:vol)
            if (tms != null) sendEvent(name:"audioAlarmTimes", value:tms)

            sendEvent(name:"audioAlarmSupport", value:"supported")
        } catch (e) {
            if (logEnable) log.debug "AudioAlarm JSON parse failed: ${e}"
        }
    }
}

def setAudioAlarm() {
    Integer id  = (settings.audioAlarmIdPref ?: 1) as Integer
    Integer vol = clampInt(settings.audioAlarmVolumePref, 1, 100, 100)
    Integer tms = clampInt(settings.audioAlarmTimesPref, 1, 50, 5)

    if (infoEnable) log.info "PUT AudioAlarm profile (id=${id}, volume=${vol}, times=${tms})"

    def payload = [
        AudioAlarm: [
            audioID     : id,
            audioVolume : vol,
            alarmTimes  : tms,
            audioClass  : "alertAudio",
            alertAudioID: id,
            customAudioID: 1
        ]
    ]

    String path = "/ISAPI/Event/triggers/notifications/AudioAlarm?format=json"
    def resp = doPut(path, JsonOutput.toJson(payload), "application/json", "application/json")
    handleResp("PUT ${path} (AudioAlarm set)", resp)

    if (resp?.status == 200) {
        sendEvent(name:"audioAlarmId", value:id)
        sendEvent(name:"audioAlarmVolume", value:vol)
        sendEvent(name:"audioAlarmTimes", value:tms)
        sendEvent(name:"audioAlarmSupport", value:"supported")
    }
}

// ----- Trigger discovery / notifications -----
def discoverTriggerIds() {
    String path = "/ISAPI/Event/triggersCap"
    if (infoEnable) log.info "GET ${path} (discover trigger IDs)"
    def resp = doGet(path, true, "application/xml")
    handleResp("GET ${path}", resp)
    if (resp?.status != 200 || !resp?.body) {
        log.warn "discoverTriggerIds: no XML body (status=${resp?.status})."
        return
    }

    String xml = resp.body?.toString() ?: ""
    Set<String> found = new LinkedHashSet<>()

    // 1) XML parse: collect any tag whose name ends with "id"
    try {
        def x = new XmlSlurper(false, false).parseText(xml)
        x.'**'.each { node ->
            String nm = node.name()?.toString()
            if (!nm) return
            String nml = nm.toLowerCase()
            if (nml.endsWith("id")) {
                String v = node.text()?.toString()?.trim()
                if (v && v.size() <= 120) found << v
            }
        }
    } catch (e) {
        if (logEnable) log.debug "XML parse failed; using regex only: ${e}"
    }

    // 2) Regex: grab typical trigger-ish tokens (VMD-1, motionSomething-1, lineDetection/FieldDetection, etc.)
    def rxTok = ~/(?i)\b([A-Z]{2,12}-\d{1,3}|[a-z]+[a-z0-9]*Detection-\d{1,3}|(motion|vmd|line|field|region|tamper|intrusion)[a-z0-9_-]{0,40})\b/
    def m = (xml =~ rxTok)
    while (m.find()) {
        String v = (m.group(1) ?: "").trim()
        if (v && v.size() <= 120) found << v
    }

    // 3) Keep only "interesting" entries (reduce noise)
    def keep = found.findAll { s ->
        def t = s.toLowerCase()
        t.contains("vmd") || t.contains("motion") ||
        t.contains("linedetection") || t.contains("line") ||
        t.contains("field") || t.contains("region") ||
        t.contains("tamper") || t.contains("intrusion") ||
        t.contains("smart") || t.contains("entrance") || t.contains("exit")
    }.toList().unique()

    // Write clean list into lastBody
    String summary = keep ? keep.join("\n") : "(no trigger-like IDs detected; check debug log 'triggersCap XML head')"
    sendEvent(name: "lastBody", value: summary)
    sendEvent(name: "lastStatus", value: (resp.status ?: 0))

    log.warn "discoverTriggerIds: wrote ${keep.size()} candidate IDs into lastBody."

    if (logEnable) log.debug "triggersCap XML head: " + (xml.size() > 1200 ? xml.take(1200) + "…" : xml)
}

def getTriggerNotifications() {
    String id = settings.eventTriggerId
    if (!id) {
        log.warn "Missing eventTriggerId preference. Run discoverTriggerIds and set eventTriggerId."
        return
    }
    String path = "/ISAPI/Event/triggers/${urlEncode(id)}/notifications"
    if (infoEnable) log.info "GET ${path}"
    def resp = doGet(path, true, "application/xml")
    handleResp("GET ${path}", resp)

    if (resp?.status == 200 && resp?.body) {
        boolean has = xmlHasAudioAlarm(resp.body)
        sendEvent(name:"audibleArmed", value: has ? "on" : "off")
        sendEvent(name:"switch", value: has ? "on" : "off")
        sendEvent(name:"alarm", value: has ? "siren" : "off")
    } else {
        sendEvent(name:"audibleArmed", value:"unknown")
    }
}

// ----- ARM/DISARM by editing trigger notifications -----
def armAudible() {
    if (!settings.eventTriggerId) {
        log.warn "Missing eventTriggerId preference. Run discoverTriggerIds and set eventTriggerId."
        return
    }

    // 1) Ensure AudioAlarm profile is set (sound/volume/times)
    setAudioAlarm()

    // 2) Ensure the chosen trigger has notificationMethod=audioAlarm
    def updated = ensureAudioAlarmOnTrigger(true)
    if (updated) {
        sendEvent(name:"audibleArmed", value:"on")
        sendEvent(name:"switch", value:"on")
        sendEvent(name:"alarm", value:"siren")
    } else {
        sendEvent(name:"audibleArmed", value:"unknown")
        sendEvent(name:"switch", value:"off")
        sendEvent(name:"alarm", value:"off")
    }
}

def disarmAudible() {
    if (!settings.eventTriggerId) {
        log.warn "Missing eventTriggerId preference. Run discoverTriggerIds and set eventTriggerId."
        return
    }
    def updated = ensureAudioAlarmOnTrigger(false)
    if (updated) {
        sendEvent(name:"audibleArmed", value:"off")
        sendEvent(name:"switch", value:"off")
        sendEvent(name:"alarm", value:"off")
    } else {
        sendEvent(name:"audibleArmed", value:"unknown")
    }
}

private boolean ensureAudioAlarmOnTrigger(boolean enable) {
    String id = settings.eventTriggerId
    String getPath = "/ISAPI/Event/triggers/${urlEncode(id)}/notifications"
    String putPath = getPath

    if (infoEnable) log.info "${enable ? 'ARM' : 'DISARM'} linkage on trigger '${id}' via ${getPath}"

    def getResp = doGet(getPath, true, "application/xml")
    handleResp("GET ${getPath}", getResp)
    if (getResp?.status != 200 || !getResp?.body) {
        log.warn "Cannot read trigger notifications; status=${getResp?.status}. Cannot ${enable?'ARM':'DISARM'}."
        return false
    }

    String xmlIn = getResp.body
    boolean has = xmlHasAudioAlarm(xmlIn)
    if (enable && has) {
        if (infoEnable) log.info "Trigger already has audioAlarm linkage."
        return true
    }
    if (!enable && !has) {
        if (infoEnable) log.info "Trigger already does NOT have audioAlarm linkage."
        return true
    }

    String xmlOut = enable ? xmlAddAudioAlarm(xmlIn) : xmlRemoveAudioAlarm(xmlIn)

    def putResp = doPut(putPath, xmlOut, "application/xml", "application/xml")
    handleResp("PUT ${putPath} (Trigger notifications ${enable?'add':'remove'} audioAlarm)", putResp)

    if (putResp?.status == 200) {
        // confirm
        runIn(1, "getTriggerNotifications")
        return true
    }
    return false
}

private boolean xmlHasAudioAlarm(String xmlText) {
    try {
        def x = new XmlSlurper(false,false).parseText(xmlText)
        // Try common node names
        def methods = x.'**'.findAll { n ->
            def name = n.name()?.toString()?.toLowerCase()
            name in ["notificationmethod", "method", "notificationtype"]
        }*.text()*.toString()*.toLowerCase()

        return methods.any { it.contains("audioalarm") || it.contains("audio alarm") }
    } catch (e) {
        if (logEnable) log.debug "xmlHasAudioAlarm parse failed: ${e}"
        return xmlText.toLowerCase().contains("audioalarm")
    }
}

private String xmlAddAudioAlarm(String xmlText) {
    // Strategy:
    //  - If there are existing EventTriggerNotification entries, clone the first and only change notificationMethod
    //  - Else create a minimal list
    try {
        def x = new XmlSlurper(false,false).parseText(xmlText)
        def rootName = x.name()
        def xmlns = x.@xmlns?.toString()
        def version = x.@version?.toString()

        // Find an existing notification node (any child element with "notificationMethod" inside)
        def notif = x.'**'.find { n ->
            n.'**'.find { c -> (c.name()?.toString()?.toLowerCase() == "notificationmethod") } != null
        }

        String newEntry
        if (notif) {
            // Build a simple entry using same common fields we can see
            // We'll preserve id if exists by using (max+1) if possible; otherwise "1"
            def ids = x.'**'.findAll { it.name()?.toString()?.toLowerCase() == "id" }*.text()*.toString()
            int nextId = 1
            try {
                def nums = ids.collect { it.isInteger() ? it.toInteger() : null }.findAll { it != null }
                if (nums) nextId = (nums.max() + 1)
            } catch (ignored) {}

            newEntry = """
  <EventTriggerNotification>
    <id>${nextId}</id>
    <notificationMethod>audioAlarm</notificationMethod>
  </EventTriggerNotification>
""".trim()
        } else {
            newEntry = """
  <EventTriggerNotification>
    <id>1</id>
    <notificationMethod>audioAlarm</notificationMethod>
  </EventTriggerNotification>
""".trim()
        }

        // Insert before closing root tag
        String closing = "</${rootName}>"
        if (xmlText.contains(closing)) {
            return xmlText.replace(closing, "\n${newEntry}\n${closing}")
        }

        // Fallback if weird formatting
        return xmlText + "\n" + newEntry

    } catch (e) {
        if (logEnable) log.debug "xmlAddAudioAlarm failed; using fallback insert: ${e}"
        // crude fallback: append entry
        if (xmlText.toLowerCase().contains("</eventtriggernotificationlist")) {
            return xmlText.replaceAll("(?i)</EventTriggerNotificationList>", """
  <EventTriggerNotification>
    <id>1</id>
    <notificationMethod>audioAlarm</notificationMethod>
  </EventTriggerNotification>
</EventTriggerNotificationList>
""".trim())
        }
        // if no list wrapper exists, create minimal
        return """
<EventTriggerNotificationList version="1.0" xmlns="http://www.hikvision.com/ver10/XMLSchema">
  <EventTriggerNotification>
    <id>1</id>
    <notificationMethod>audioAlarm</notificationMethod>
  </EventTriggerNotification>
</EventTriggerNotificationList>
""".trim()
    }
}

private String xmlRemoveAudioAlarm(String xmlText) {
    // Remove any EventTriggerNotification blocks containing notificationMethod audioAlarm
    try {
        // Regex-based removal (works even if schema differs)
        def pattern = ~/(?is)\s*<EventTriggerNotification\b[^>]*>.*?<notificationMethod>\s*audioAlarm\s*<\/notificationMethod>.*?<\/EventTriggerNotification>\s*/
        String out = xmlText.replaceAll(pattern, "\n")

        // Also handle variants like "audio alarm"
        def pattern2 = ~/(?is)\s*<EventTriggerNotification\b[^>]*>.*?<notificationMethod>\s*audio\s*alarm\s*<\/notificationMethod>.*?<\/EventTriggerNotification>\s*/
        out = out.replaceAll(pattern2, "\n")

        return out
    } catch (e) {
        if (logEnable) log.debug "xmlRemoveAudioAlarm failed: ${e}"
        return xmlText
    }
}

// ----- Connection test -----
def testConnection() {
    String path = "/ISAPI/System/deviceInfo"
    if (infoEnable) log.info "Testing ${path}"
    def resp = doGet(path, false, "application/xml")
    handleResp("GET ${path}", resp)
}

// ---------- HTTP helpers (Basic Auth) ----------
private String baseUri() {
    def proto = settings.protocol ?: "http"
    def host  = settings.ip
    def p     = settings.port ? (settings.port as Integer) : null
    if (!host) throw new IllegalArgumentException("Missing ip/host")
    if (p) return "${proto}://${host}:${p}"
    if (proto == "https") return "${proto}://${host}:443"
    return "${proto}://${host}:80"
}

private String basicAuth() {
    def token = "${settings.username}:${settings.password}".bytes.encodeBase64().toString()
    return "Basic ${token}"
}

private String hostHeader() {
    def proto = settings.protocol ?: "http"
    def p = settings.port ? (settings.port as Integer) : ((proto == "https") ? 443 : 80)
    return "${settings.ip}:${p}"
}

private Map doGet(String path, boolean textParser, String accept) {
    def headers = [
        "HOST"         : hostHeader(),
        "Authorization": basicAuth(),
        "Accept"       : accept
    ]
    def params = [
        uri: baseUri() + path,
        headers: headers
    ]
    if (textParser) params.textParser = true
    if ((settings.protocol ?: "http") == "https" && (settings.ignoreSslIssues == null || settings.ignoreSslIssues == true)) {
        params.ignoreSSLIssues = true
    }

    if (logEnable) log.debug "HTTP GET ${params.uri} (textParser=${textParser}, accept=${accept})"

    def out = [status: null, body: "", error: null]
    try {
        httpGet(params) { resp ->
            out.status = resp.status
            if (resp.status == 200) {
                out.body = textParser ? (resp.data?.text ?: "") : "${resp.data}"
            } else {
                out.error = "HTTP ${resp.status}"
            }
        }
    } catch (e) {
        out.error = e?.message
        out.status = extractStatusFromMessage(out.error)
    }
    return out
}

private Map doPut(String path, String body, String contentType, String accept) {
    def headers = [
        "HOST"         : hostHeader(),
        "Authorization": basicAuth(),
        "Content-Type" : contentType,
        "Accept"       : accept
    ]
    def params = [
        uri: baseUri() + path,
        headers: headers,
        body: body,
        requestContentType: contentType
    ]
    if ((settings.protocol ?: "http") == "https" && (settings.ignoreSslIssues == null || settings.ignoreSslIssues == true)) {
        params.ignoreSSLIssues = true
    }

    if (logEnable) log.debug "HTTP PUT ${params.uri} (contentType=${contentType})"

    def out = [status: null, body: "", error: null]
    try {
        httpPut(params) { resp ->
            out.status = resp.status
            try {
                out.body = resp.data?.text ?: resp.data?.toString() ?: ""
            } catch (ignored) {
                out.body = resp.data?.toString() ?: ""
            }
            if (resp.status != 200) out.error = "HTTP ${resp.status}"
        }
    } catch (e) {
        out.error = e?.message
        out.status = extractStatusFromMessage(out.error)
    }
    return out
}

private void handleResp(String label, Map resp) {
    if (resp == null) return

    sendEvent(name:"lastStatus", value: (resp.status ?: 0))

    String body = (resp.body ?: "")
    if (body.size() > 8000) body = body.take(8000) + "…"
    sendEvent(name:"lastBody", value: body)

    if (resp.status == 200) {
        sendEvent(name:"connection", value:"ok")
        if (infoEnable) log.info "${label} OK"
        if (logEnable && resp.body) log.debug "${label} BODY: ${resp.body}"
        return
    }

    if ((resp.error ?: "").contains("code: 401") || resp.status == 401) {
        sendEvent(name:"connection", value:"cred")
        log.warn "${label} auth failed (401)"
    } else if ((resp.error ?: "").contains("code: 403") || resp.status == 403) {
        sendEvent(name:"connection", value:"err")
        log.warn "${label} forbidden/restricted (403)"
    } else if ((resp.error ?: "").toLowerCase().contains("connect timed out") ||
               (resp.error ?: "").toLowerCase().contains("no route to host") ||
               (resp.error ?: "").toLowerCase().contains("connection refused")) {
        sendEvent(name:"connection", value:"off")
        log.warn "${label} camera offline/timeout"
    } else {
        sendEvent(name:"connection", value:"err")
        log.warn "${label} failed: ${resp.status} ${resp.error ?: ''}"
    }
}

private Integer extractStatusFromMessage(String msg) {
    if (!msg) return null
    def m = (msg =~ /status code:\s*(\d{3})/)
    if (m.find()) return (m.group(1) as Integer)
    return null
}

private Integer safeInt(def v) {
    try {
        if (v == null) return null
        if (v instanceof Number) return ((Number)v).intValue()
        return Integer.parseInt(v.toString())
    } catch (ignored) { return null }
}

private Integer clampInt(def v, int min, int max, int defVal) {
    Integer i = safeInt(v)
    if (i == null) i = defVal
    return Math.max(min, Math.min(max, i))
}

private String urlEncode(String s) {
    return java.net.URLEncoder.encode(s ?: "", "UTF-8")
}

def probeCommonTriggerIds() {
    def candidates = [
        "VMD-1",
        "MotionDetection-1", "motionDetection-1",
        "LineDetect-1", "lineDetect-1",
        "LineDetection-1", "lineDetection-1",
        "RegionEntrance-1", "regionEntrance-1",
        "RegionExiting-1", "regionExiting-1",
        "TamperDetection-1", "tamperDetection-1"
    ]

    List<String> ok = []
    List<String> bad = []

    candidates.each { id ->
        String path = "/ISAPI/Event/triggers/${java.net.URLEncoder.encode(id, 'UTF-8')}/notifications"
        def resp = doGet(path, true, "application/xml")
        if (resp?.status == 200) ok << id
        else bad << "${id}:${resp?.status ?: 'ERR'}"
        if (logEnable) log.debug "probe ${id} => ${resp?.status}"
    }

    String summary =
        (ok ? "OK (use um destes como eventTriggerId):\n" + ok.join("\n") : "Nenhum candidate deu 200.\n") +
        "\nTestados (status):\n" + bad.join(", ")

    sendEvent(name:"lastBody", value: summary.take(8000))
    sendEvent(name:"lastStatus", value: 200)
    log.warn "probeCommonTriggerIds finalizado. Veja lastBody."
}

