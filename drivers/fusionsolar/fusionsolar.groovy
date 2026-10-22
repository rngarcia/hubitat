/**
 *  FusionSolar OpenAPI (Cloud) — Hubitat Driver
 *  Versão: 0.2.1 — Build: 2026-10-21
 *  Autor: Ricardo Nogueira Garcia
 *  Repositório: https://github.com/rngarcia/hubitat
 *  Gostou? Buy me a beer: https://www.paypal.com/donate/?business=rngarcia%40gmail.com&currency_code=USD
 *
 *  Changelog:
 *  v0.2.1 (2026-10-21) - RNG
 *    - Publicado em rngarcia/hubitat: cabeçalho padronizado, namespace "rng" e importUrl
 *    - Namespace "fusion" -> "rng"; author corrigido (desenvolvido com assistência de IA)
 *  (histórico anterior no bloco de comentário abaixo)
 */
/*
 * FusionSolar OpenAPI — Hubitat Driver (Cloud-only)
 * Versão: 0.2.0
 * Autor: RNG + ChatGPT
 *
 * Changelog:
 *   0.2.0 (2026-06-22) - RNG
 *     - Removido statusHtml completamente (pesado no Maker API — ~1KB de HTML a cada poll)
 *     - Removido updateStatusHtml() e attribute "statusHtml"
 */

import groovy.transform.Field
@Field static final Integer DEVLIST_MIN_REFRESH_SEC = 6 * 60 * 60
@Field static final Integer DEVLIST_BACKOFF_SEC     = 30 * 60

metadata {
    definition(name: "FusionSolar OpenAPI (Cloud)", namespace: "rng", author: "Ricardo Nogueira Garcia",
        importUrl: "https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/fusionsolar/fusionsolar.groovy"
    ) {
        capability "Initialize"
        capability "Refresh"
        capability "Sensor"
        capability "PowerMeter"
        capability "EnergyMeter"

        attribute "lastUpdate",        "string"
        attribute "stationCodes",      "string"
        attribute "stationListJson",   "string"
        attribute "deviceListJson",    "string"
        attribute "lastApiStatus",     "string"
        attribute "lastFailCode",      "number"
        attribute "rateLimitUntil",    "string"
        attribute "plantDayEnergy",    "number"
        attribute "plantMonthEnergy",  "number"
        attribute "plantTotalEnergy",  "number"
        attribute "plantPowerKw",      "number"

        command "discoverStations"
        command "discoverDevices"
        command "clearToken"
        command "forcePoll"
    }
    preferences {
        input name: "cloudBaseUrl",  type: "text",     title: "Base URL (ex.: https://la5.fusionsolar.huawei.com/thirdData)", required: true
        input name: "cloudUser",     type: "text",     title: "Usuário (userName – Northbound)", required: true
        input name: "cloudPass",     type: "password", title: "Senha (systemCode – Northbound)", required: true
        input name: "prefStationCodes", type: "text",  title: "Station codes (CSV, opcional)", required: false
        input name: "prefDevIds",       type: "text",  title: "devIds (CSV, opcional)", required: false
        input name: "pollSeconds",   type: "enum",     title: "Intervalo de polling (segundos)",
              defaultValue: "60", options: ["30","60","90","120","300"]
        input name: "logEnable",     type: "bool",     title: "Debug logs", defaultValue: false
        input name: "descEnable",    type: "bool",     title: "DescriptionText logs", defaultValue: true
    }
}

def installed()  { logInfo "Instalado"; initialize() }
def updated()    { logInfo "Preferências salvas"; unschedule(); initialize() }

def initialize() {
    logDebug "initialize()"
    state.remove("token"); state.remove("cookies"); state.remove("cookieStr"); state.remove("tokenExp")
    state.remove("rateLimitUntil")
    state.devTypes = [1,10,17,38,39,41,47]
    state.devTypeIdx = 0
    scheduleNext()
    refresh()
}

def refresh()    { pollCloud() }
def forcePoll()  { pollCloud() }
def clearToken() { state.remove("token"); state.remove("tokenExp"); state.remove("cookies"); state.remove("cookieStr"); logWarn "Token e cookies limpos." }
def discoverStations() { doDiscoverStations() }
def discoverDevices()  { doDiscoverDevices() }

private void scheduleNext() {
    Integer s = (pollSeconds ?: "60") as Integer
    if (s < 30) s = 30
    logDebug "Agendando polling a cada ${s}s (runIn)"
    runIn(s, "pollCloud", [overwrite:true])
}

private String tsNow() {
    String tzId = (location?.timeZone?.ID ?: "UTC")
    def sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    sdf.setTimeZone(java.util.TimeZone.getTimeZone(tzId))
    return sdf.format(new Date())
}
private void logDebug(msg){ if (logEnable)  log.debug "[FusionSolar] ${msg}" }
private void logInfo(msg) { if (descEnable) log.info  "[FusionSolar] ${msg}" }
private void logWarn(msg) { log.warn  "[FusionSolar] ${msg}" }

private String base() {
    String b = cloudBaseUrl?.trim()
    if (!b) return null
    return b.endsWith("/thirdData") ? b : (b.endsWith("/") ? (b + "thirdData") : (b + "/thirdData"))
}

private Map makeHeaders() {
    Map h = ["accept":"application/json", "Content-Type":"application/json", "X-Requested-With":"XMLHttpRequest"]
    if (state.token)     h["XSRF-TOKEN"] = state.token
    if (state.cookieStr) h["Cookie"]     = state.cookieStr
    String b = base()
    if (b) h["Referer"] = b + "/"
    return h
}

private boolean tokenValid() {
    Long exp = state.tokenExp as Long
    return (state.token && exp && now() < exp)
}

private void login() {
    String b = base()
    if (!b) { logWarn "Base URL ausente"; return }
    Map req = [ uri:"${b}/login", contentType:"application/json", requestContentType:"application/json",
                body:[ userName:cloudUser, systemCode:cloudPass ] ]
    try {
        state.remove("token"); state.remove("cookies"); state.remove("cookieStr"); state.remove("tokenExp")
        httpPost(req) { resp ->
            if (resp?.status == 200) {
                String token = null
                resp?.headers?.each { h ->
                    String k = (h?.name ?: h?.key)?.toString()
                    String v = h?.value?.toString()
                    if (k?.equalsIgnoreCase("XSRF-TOKEN")) token = v
                }
                List<String> cookies = []
                resp?.headers?.findAll { (it?.name ?: it?.key)?.toString()?.equalsIgnoreCase("Set-Cookie") }?.each { h ->
                    def nv = (h?.value ?: "").toString().tokenize(";")?.first()
                    if (nv) cookies << nv
                }
                if (token) {
                    state.token = token
                    if (!cookies.any { it.toLowerCase().startsWith("xsrf-token=") }) cookies << "XSRF-TOKEN=${token}"
                    state.cookies = cookies
                    state.cookieStr = cookies.join("; ")
                    state.tokenExp = now() + (25*60*1000)
                    sendEvent(name:"lastApiStatus", value:"login ok @ ${tsNow()}")
                    logInfo "Login ok; token e cookies obtidos."
                } else {
                    logWarn "Login ok, mas token não veio no header"
                }
            } else {
                logWarn "Login falhou: HTTP ${resp?.status}"
                sendEvent(name:"lastApiStatus", value:"login http ${resp?.status}")
            }
        }
    } catch (ex) {
        logWarn "Erro no login: ${ex}"
        sendEvent(name:"lastApiStatus", value:"login erro: ${ex}")
    }
}

private Map apiPost(String path, Map body, boolean allowRetry=true) {
    String b = base()
    if (!b) { logWarn "Base URL inválida"; return [ok:false, status:-1, data:null] }
    if (!tokenValid()) login()
    Map req = [ uri:"${b}/${path}", contentType:"application/json", requestContentType:"application/json",
                headers:makeHeaders(), body:body ?: [:] ]
    logDebug "POST ${req.uri} body=${req.body} hdr=${req.headers.keySet()}"
    try {
        Map result = [ok:false, status:null, data:null, failCode:null, raw:null]
        httpPost(req) { r ->
            result.status = r?.status
            def jd = r?.data
            result.raw = jd
            def fc = jd?.failCode
            result.failCode = fc
            if (fc == 305) {
                logWarn "Token expirado (305); relogin…"
                state.tokenExp = 0L
                if (allowRetry) { login(); result = apiPost(path, body, false) }
            } else if (fc == 401) {
                logWarn "401 para ${path}: ${jd?.message ?: jd?.data}"
                result.ok = false
            } else if (fc == 407) {
                logWarn "Rate limit (407) em ${path}"
                Integer s = (pollSeconds ?: "60") as Integer
                long until = now() + (s * 1000L * 2)
                state.rateLimitUntil = until
                sendEvent(name:"rateLimitUntil", value:tsFromMillis(until))
                result.ok = false
            } else if (fc != null && fc != 0) {
                logWarn "OpenAPI erro em ${path}: failCode=${fc}, msg=${jd?.data}"
                result.ok = false
            } else if (jd == null) {
                logWarn "Resposta sem JSON em ${path}"
                result.ok = false
            } else {
                result.ok = true
                result.data = jd?.data
            }
        }
        return result
    } catch (ex) {
        logWarn "HTTP erro em ${path}: ${ex}"
        return [ok:false, status:-1, data:null]
    }
}

private String tsFromMillis(long ms) {
    String tzId = (location?.timeZone?.ID ?: "UTC")
    def sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    sdf.setTimeZone(java.util.TimeZone.getTimeZone(tzId))
    return sdf.format(new Date(ms))
}

private void doDiscoverStations() {
    def res = apiPost("stations", [pageNo:1])
    if (!res.ok) { logWarn "stations falhou, tentando getStationList…"; res = apiPost("getStationList", [:]) }
    if (res.ok) {
        List list = []
        def data = res.data
        if (data?.list instanceof List) list = data.list
        else if (data instanceof List)  list = data
        def stations = list.collect { st ->
            [ stationCode:(st.stationCode ?: st.plantCode)?.toString(),
              stationName:(st.stationName ?: st.plantName)?.toString(),
              address:(st.stationAddr ?: st.plantAddress)?.toString(),
              capacity:st.capacity ]
        }.findAll { it.stationCode }
        sendEvent(name:"stationListJson", value:groovy.json.JsonOutput.toJson(stations))
        if (stations) {
            def codes = stations*.stationCode
            sendEvent(name:"stationCodes", value:codes.join(","))
            logInfo "Plantas encontradas (${codes.size()}): ${codes}"
        } else {
            logWarn "Nenhuma planta visível para o usuário de API."
        }
    }
}

private List<String> resolveStationCodesForPolling() {
    List<String> codes = (prefStationCodes ?: device.currentValue("stationCodes") ?: "")
        .split(/\s*,\s*/).findAll{ it }.collect{ it.toString() }
    if (!codes) { doDiscoverStations(); codes = (device.currentValue("stationCodes") ?: "").split(/\s*,\s*/).findAll{ it }.collect{ it.toString() } }
    return codes
}

private void doDiscoverDevices() {
    Long nextAt = (state.devListNextAt as Long) ?: 0L
    if (now() < nextAt) { logDebug "getDevList pulado: próximo após ${tsFromMillis(nextAt)}"; return }
    List<String> codes = resolveStationCodesForPolling()
    if (!codes) { logWarn "Sem stationCodes para getDevList"; return }
    def res = apiPost("getDevList", [stationCodes:codes.join(",")])
    if (res.ok) {
        def devices = (res.data instanceof List) ? res.data : []
        def out = devices.collect { d ->
            [devId:d.id, name:d.devName, typeId:d.devTypeId, station:d.stationCode, sn:d.esnCode,
             invType:d.invType, lat:d.latitude, lon:d.longitude]
        }
        sendEvent(name:"deviceListJson", value:groovy.json.JsonOutput.toJson(out))
        logInfo "Dispositivos: ${out.size()} encontrados."
        state.devListNextAt = now() + (DEVLIST_MIN_REFRESH_SEC * 1000L)
    } else {
        state.devListNextAt = now() + (((res.failCode as Integer) == 407 ? DEVLIST_BACKOFF_SEC : DEVLIST_MIN_REFRESH_SEC) * 1000L)
        logWarn "getDevList falhou — próximo após ${tsFromMillis(state.devListNextAt as Long)}"
    }
}

private void pollCloud() {
    try {
        Long rl = state.rateLimitUntil as Long
        if (rl && now() < rl) { logWarn "Pulando poll por rate limit até ${tsFromMillis(rl)}"; scheduleNext(); return }

        List<String> codes = resolveStationCodesForPolling()
        if (codes) {
            def kpi = apiPost("getStationRealKpi", [stationCodes:codes.join(",")])
            if (kpi.ok) applyStationKpi(kpi.data)
            else if (kpi.failCode == 401) logWarn "Sem permissão para getStationRealKpi."
        } else {
            logWarn "Sem plantas para polling. Use discoverStations ou informe Station codes nas Preferences."
        }

        List<Map> devs = []
        String devJson = device.currentValue("deviceListJson")
        if (!devJson || devJson == "null") doDiscoverDevices()
        devJson = device.currentValue("deviceListJson")
        if (devJson && devJson != "null") {
            try { devs = new groovy.json.JsonSlurper().parseText(devJson) as List<Map> } catch(ignore){}
        }

        if (!devs?.isEmpty()) {
            List<String> idsInPref = (prefDevIds ?: "").split(/\s*,\s*/).findAll{ it }.collect{ it.toString() }
            if (idsInPref) {
                Integer idx = (state.devTypeIdx ?: 0) as Integer
                int typeId = (state.devTypes as List<Integer>)[idx % (state.devTypes.size())]
                state.devTypeIdx = (idx + 1) % (state.devTypes.size())
                def res = apiPost("getDevRealKpi", [devIds:idsInPref.join(","), devTypeId:typeId])
                if (res.ok) applyDeviceKpi(res.data)
            } else {
                Map<Integer,List<String>> byType = [:].withDefault{[]}
                devs.each { d -> if (d.typeId != null) byType[d.typeId as Integer] << d.devId?.toString() }
                if (!byType.isEmpty()) {
                    Integer idx = (state.devTypeIdx ?: 0) as Integer
                    List<Integer> types = new ArrayList<Integer>(byType.keySet())
                    int typeId = types[idx % types.size()]
                    state.devTypeIdx = (idx + 1) % (types.size())
                    def res = apiPost("getDevRealKpi", [devIds:(byType[typeId] as List<String>).join(","), devTypeId:typeId])
                    if (res.ok) applyDeviceKpi(res.data)
                }
            }
        }

        sendEvent(name:"lastUpdate",    value:tsNow())
        sendEvent(name:"lastApiStatus", value:"ok @ ${tsNow()}")

    } catch (ex) {
        logWarn "Erro no pollCloud: ${ex}"
        sendEvent(name:"lastApiStatus", value:"erro: ${ex}")
    } finally {
        scheduleNext()
    }
}

private void applyStationKpi(def data) {
    List list = (data instanceof List) ? data : (data ? [data] : [])
    if (!list) return
    def s   = list[0]
    def map = s?.dataItemMap ?: [:]
    BigDecimal day = safeNum(map?.day_power)
    BigDecimal mon = safeNum(map?.month_power)
    BigDecimal tot = safeNum(map?.total_power)
    BigDecimal pkw = safeNum(s?.realTimePower ?: map?.realTimePower)
    BigDecimal pW  = (pkw != null) ? (pkw * 1000G) : null

    if (day != null) { sendEvent(name:"energy", value:day, unit:"kWh"); sendEvent(name:"plantDayEnergy", value:day, unit:"kWh") }
    if (mon != null) sendEvent(name:"plantMonthEnergy", value:mon, unit:"kWh")
    if (tot != null) sendEvent(name:"plantTotalEnergy", value:tot, unit:"kWh")
    if (pW  != null) { sendEvent(name:"power", value:pW, unit:"W"); sendEvent(name:"plantPowerKw", value:pkw, unit:"kW") }
    logInfo "Planta: P=${pW?:'-'} W | Dia=${day?:'-'} kWh | Mês=${mon?:'-'} kWh | Total=${tot?:'-'} kWh"
}

private void applyDeviceKpi(def data) {
    List list = (data instanceof List) ? data : (data ? [data] : [])
    if (!list) return
    BigDecimal sumW = 0G
    boolean hasAny = false
    list.each { d ->
        def map = d?.dataItemMap ?: [:]
        if (map?.active_power != null && map.active_power != "N/A") {
            hasAny = true
            try {
                BigDecimal val = (map.active_power as BigDecimal)
                sumW += (val.abs() <= 5000) ? (val * 1000G) : val
            } catch(ignore){}
        }
    }
    if (hasAny) {
        sendEvent(name:"power", value:sumW, unit:"W")
        sendEvent(name:"plantPowerKw", value:(sumW/1000G), unit:"kW")
        logDebug "Potência agregada por dispositivos: ${sumW} W"
    }
}

private BigDecimal safeNum(def v) {
    if (v == null) return null
    if (v instanceof Number) return v as BigDecimal
    if (v == "N/A") return null
    try { return (v as BigDecimal) } catch(ex) { return null }
}