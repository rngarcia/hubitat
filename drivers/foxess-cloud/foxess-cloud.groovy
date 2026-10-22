/**
 *  FoxESS Cloud OpenAPI — Hubitat Driver
 *  Versão: 0.1.1 — Build: 2026-10-21
 *  Autor: Ricardo Nogueira Garcia
 *  Repositório: https://github.com/rngarcia/hubitat
 *  Gostou? Buy me a beer: https://www.paypal.com/donate/?business=rngarcia%40gmail.com&currency_code=USD
 *
 *  Changelog:
 *  v0.1.1 (2026-10-21) - RNG
 *    - Publicado em rngarcia/hubitat: cabeçalho padronizado, namespace "rng" e importUrl
 *  (histórico anterior no bloco de comentário abaixo)
 */
/**
 *  FoxESS Cloud OpenAPI — driver Hubitat
 *
 *  Autor: Ricardo Nogueira Garcia
 *  Referência: autenticação (assinatura MD5), endpoints e mapeamento de variáveis baseados na
 *              integração Home Assistant "foxess-ha" (macxq e colaboradores)
 *              https://github.com/macxq/foxess-ha
 *
 *  Lê o inversor pela FoxESS Cloud OpenAPI, com a mesma chave de API usada no Home Assistant.
 *  Expõe powerSource (mains/battery) e gridStatus (online/offline) a partir do runningState
 *  (163 = on-grid, 164 = off-grid/EPS), para automações de falta de energia
 *  (ex.: desligar o aquecedor de água quando a casa passa para a bateria).
 *
 *  ATENÇÃO — limite da OpenAPI: 1.440 chamadas/dia por chave, compartilhado com o Home Assistant.
 *  Com o intervalo padrão (5 min) este driver usa ~500 chamadas/dia.
 *
 *  Changelog:
 *  v0.1.0 (2026-09-27) - Versão inicial: real/query (tempo real), report/query (energia do dia),
 *                        device/detail (status/firmware), battery/soc/get (minSoc/minSocOnGrid);
 *                        powerSource/gridStatus pelo runningState; detecção de dado velho;
 *                        contador diário de chamadas; fail-safe opcional (nuvem fora = queda).
 */

import groovy.json.JsonOutput
import groovy.transform.Field
import java.math.RoundingMode
import java.security.MessageDigest
import java.text.SimpleDateFormat

@Field static final String DRIVER_VERSION = "0.1.1"
@Field static final String BASE_URI = "https://www.foxesscloud.com"
@Field static final String PATH_REAL = "/op/v0/device/real/query"
@Field static final String PATH_REPORT = "/op/v0/device/report/query"
@Field static final String PATH_DETAIL = "/op/v0/device/detail"
@Field static final String PATH_BATTERY = "/op/v0/device/battery/soc/get"
@Field static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

@Field static final Map RUN_STATES = [
    "160": "self-test", "161": "waiting", "162": "checking", "163": "on-grid",
    "164": "off-grid", "165": "fault", "166": "permanent-fault", "167": "standby",
    "168": "upgrading", "169": "fct", "170": "illegal"
]

@Field static final Map REPORT_MAP = [
    "PVEnergyTotal"       : "pvEnergyToday",
    "generation"          : "generationToday",
    "loads"               : "loadsEnergyToday",
    "chargeEnergyToTal"   : "batChargeToday",
    "dischargeEnergyToTal": "batDischargeToday",
    "feedin"              : "feedinToday",
    "gridConsumption"     : "gridConsumptionToday"
]

// Atributos cuja mudança sempre vale uma linha de log info (quando txtEnable)
@Field static final List TXT_ATTRS = ["battery", "deviceStatus", "cloudStatus", "runningState", "minSoc", "minSocOnGrid"]

metadata {
    definition(name: "FoxESS Cloud OpenAPI", namespace: "rng", author: "Ricardo Nogueira Garcia",
        importUrl: "https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/foxess-cloud/foxess-cloud.groovy"
    ) {
        capability "Sensor"
        capability "Refresh"
        capability "Battery"
        capability "PowerSource"
        capability "PowerMeter"
        capability "EnergyMeter"
        capability "VoltageMeasurement"
        capability "TemperatureMeasurement"

        // Estado da rede / nuvem
        attribute "gridStatus", "string"          // online / offline / unknown
        attribute "runningState", "string"        // ex.: "163: on-grid"
        attribute "runningStateCode", "number"
        attribute "deviceStatus", "string"        // on-line / in-alarm / off-line
        attribute "cloudStatus", "string"         // ok / stale / error
        attribute "lastUpdate", "string"
        attribute "dataTime", "string"
        attribute "dataAgeMin", "number"
        attribute "apiCallsToday", "number"

        // Potências (kW)
        attribute "pvPower", "number"
        attribute "loadsPower", "number"
        attribute "generationPower", "number"
        attribute "gridConsumptionPower", "number"
        attribute "feedinPower", "number"
        attribute "batChargePower", "number"
        attribute "batDischargePower", "number"
        attribute "epsPower", "number"

        // Rede elétrica
        attribute "voltageL2", "number"
        attribute "currentL1", "number"
        attribute "currentL2", "number"
        attribute "frequency", "number"

        // Bateria / temperaturas
        attribute "soh", "number"
        attribute "batteryTemperature", "number"
        attribute "ambientTemperature", "number"
        attribute "minSoc", "number"
        attribute "minSocOnGrid", "number"

        // Energia do dia (kWh)
        attribute "pvEnergyToday", "number"
        attribute "generationToday", "number"
        attribute "loadsEnergyToday", "number"
        attribute "batChargeToday", "number"
        attribute "batDischargeToday", "number"
        attribute "feedinToday", "number"
        attribute "gridConsumptionToday", "number"

        command "refreshAll"
    }

    preferences {
        input "apiKey", "password", title: "API Key (FoxCloud OpenPlatform)", required: true
        input "deviceSN", "text", title: "SN do inversor", required: true
        input "pollInterval", "enum", title: "Intervalo do tempo real (min)",
              options: ["2", "5", "10", "15"], defaultValue: "5"
        input "reportInterval", "enum", title: "Intervalo da energia do dia (min)",
              options: ["15", "30", "60"], defaultValue: "15"
        input "staleMinutes", "number", title: "Dado da nuvem considerado velho após (min)", defaultValue: 15
        input "cloudFailAsOutage", "bool",
              title: "Fail-safe: tratar nuvem inacessível como falta de energia",
              description: "Se ligado, após N falhas seguidas o powerSource vai para 'battery'.",
              defaultValue: false
        input "cloudFailThreshold", "number", title: "Falhas seguidas para acionar o fail-safe", defaultValue: 3
        input "logEnable", "bool", title: "Debug logging (desliga sozinho em 30 min)", defaultValue: true
        input "txtEnable", "bool", title: "Description text logging", defaultValue: true
    }
}

// ───────────────────────── Ciclo de vida ─────────────────────────

void installed() {
    log.info "${device.displayName}: driver instalado (v${DRIVER_VERSION})"
    initialize()
}

void updated() {
    log.info "${device.displayName}: preferências salvas (v${DRIVER_VERSION})"
    unschedule()
    if (logEnable) runIn(1800, "logsOff")
    initialize()
}

void uninstalled() {
    unschedule()
}

void initialize() {
    state.failCount = 0
    state.lastDetail = 0
    state.lastReport = 0
    state.lastBattery = 0

    Integer n = (pollInterval ?: "5") as Integer
    Integer sec = new Random().nextInt(50) + 5   // espalha o horário para não bater no :00
    schedule("${sec} */${n} * ? * *", "poll")
    log.info "${device.displayName}: polling a cada ${n} min (segundo ${sec})"

    runIn(5, "refreshAll")
}

void logsOff() {
    log.warn "${device.displayName}: debug logging desligado"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

// ───────────────────────── Comandos ─────────────────────────

void refresh() {
    poll()
}

void refreshAll() {
    state.lastDetail = 0
    state.lastReport = 0
    state.lastBattery = 0
    poll()
}

void poll() {
    if (!apiKey || !deviceSN) {
        log.warn "${device.displayName}: configure API Key e SN nas preferências"
        return
    }
    apiPost(PATH_REAL, [sn: deviceSN.trim()], "handleReal", [:])
}

// ───────────────────────── Tempo real ─────────────────────────

void handleReal(resp, Map data) {
    def json = parseResp(resp, "real/query", true)
    if (json == null) return

    def res = json.result
    def first = (res instanceof List) ? (res ? res[0] : null) : res
    if (!first || !first.datas) {
        registerFailure("real/query sem 'datas' na resposta", true)
        return
    }

    Map v = [:]
    first.datas.each { d -> v[d.variable?.toString()] = d.value }
    logDebug "real/query: ${v.size()} variáveis"
    registerSuccess()

    // Idade do dado na nuvem
    boolean stale = false
    String t = first.time?.toString()
    if (t) {
        emit("dataTime", t)
        Long age = dataAgeMinutes(t)
        if (age != null) {
            emit("dataAgeMin", age, "min")
            stale = age > ((staleMinutes ?: 15) as Integer)
        }
    }
    emit("cloudStatus", stale ? "stale" : "ok")
    if (stale) log.warn "${device.displayName}: dado da nuvem velho (${device.currentValue('dataAgeMin')} min) — gridStatus mantido"

    processReal(v, stale)
    scheduleSecondary()
}

private void processReal(Map v, boolean stale) {
    // Bateria
    BigDecimal soc = num(v["SoC"])
    if (soc != null) emit("battery", rnd(soc, 0), "%")
    emit("soh", rnd(num(v["SOH"]), 0), "%")
    emit("batteryTemperature", rnd(num(v["batTemperature"] ?: v["batTemperature_1"]), 1), "°C")

    // Potências (kW)
    BigDecimal pv = num(v["pvPower"])
    emit("pvPower", rnd(pv, 3), "kW")
    if (pv != null) emit("power", rnd(pv * 1000, 0), "W")
    emit("loadsPower", rnd(num(v["loadsPower"]), 3), "kW")
    emit("generationPower", rnd(num(v["generationPower"]), 3), "kW")
    emit("gridConsumptionPower", rnd(num(v["gridConsumptionPower"]), 3), "kW")
    emit("feedinPower", rnd(num(v["feedinPower"]), 3), "kW")
    emit("batChargePower", rnd(num(v["batChargePower"]), 3), "kW")
    emit("batDischargePower", rnd(num(v["batDischargePower"]), 3), "kW")
    emit("epsPower", rnd(num(v["epsPower"]), 3), "kW")

    // Rede elétrica (split-phase: R = L1, S = L2)
    emit("voltage", rnd(num(v["RVolt"]), 1), "V")
    emit("voltageL2", rnd(num(v["SVolt"]), 1), "V")
    emit("currentL1", rnd(num(v["RCurrent"]), 2), "A")
    emit("currentL2", rnd(num(v["SCurrent"]), 2), "A")
    emit("frequency", rnd(num(v["RFreq"]), 2), "Hz")

    // Temperaturas
    emit("temperature", rnd(num(v["invTemperation"]), 1), "°C")
    emit("ambientTemperature", rnd(num(v["ambientTemperation"]), 1), "°C")

    // Estado de operação → rede presente ou não
    String code = v["runningState"]?.toString()?.trim()
    if (code) {
        emit("runningStateCode", code.isInteger() ? code.toInteger() : null)
        emit("runningState", "${code}: ${RUN_STATES[code] ?: 'unknown code'}")
        if (!stale) {
            if (code == "163") setGrid("online", "runningState 163 (on-grid)")
            else if (code == "164") setGrid("offline", "runningState 164 (off-grid/EPS)")
        }
    }
}

// ───────────────────────── Chamadas secundárias ─────────────────────────

private void scheduleSecondary() {
    Long t = now()
    Integer delay = 2   // OpenAPI exige >= 1 s entre chamadas
    if (t - ((state.lastDetail ?: 0) as Long) >= 15 * 60000L) {
        runIn(delay, "fetchDetail"); delay += 2
    }
    Integer rep = (reportInterval ?: "15") as Integer
    if (t - ((state.lastReport ?: 0) as Long) >= rep * 60000L) {
        runIn(delay, "fetchReport"); delay += 2
    }
    if (t - ((state.lastBattery ?: 0) as Long) >= 60 * 60000L) {
        runIn(delay, "fetchBatterySettings")
    }
}

void fetchDetail() {
    state.lastDetail = now()
    apiGet(PATH_DETAIL, [sn: deviceSN.trim()], "handleDetail")
}

void handleDetail(resp, Map data) {
    def json = parseResp(resp, "device/detail", false)
    if (json == null) return
    def r = json.result
    Integer st = num(r?.status)?.intValue()
    String s = [1: "on-line", 2: "in-alarm", 3: "off-line"][st] ?: "unknown"
    emit("deviceStatus", s)
    ["deviceType", "stationName", "moduleSN", "masterVersion", "slaveVersion", "managerVersion", "hasBattery"].each { k ->
        if (r?.get(k) != null) updateDataValue(k, r.get(k).toString())
    }
    logDebug "device/detail: status=${s}"
}

void fetchReport() {
    state.lastReport = now()
    TimeZone tz = location.timeZone
    Date d = new Date()
    Integer y = d.format("yyyy", tz) as Integer
    Integer m = d.format("M", tz) as Integer
    Integer day = d.format("d", tz) as Integer
    Map body = [
        sn       : deviceSN.trim(),
        year     : y,
        month    : m,
        dimension: "month",
        variables: REPORT_MAP.keySet() as List
    ]
    apiPost(PATH_REPORT, body, "handleReport", [day: day])
}

void handleReport(resp, Map data) {
    def json = parseResp(resp, "report/query", false)
    if (json == null) return
    Integer day = (data?.day ?: 1) as Integer
    json.result?.each { item ->
        String var = item["variable"]?.toString()
        String attr = REPORT_MAP[var]
        if (!attr) return
        def vals = item["values"]   // usar [] — .values em Map devolveria values()
        BigDecimal val = (vals instanceof List && vals.size() >= day) ? num(vals[day - 1]) : null
        if (val == null) val = 0
        emit(attr, rnd(val, 3), "kWh")
        if (var == "PVEnergyTotal") emit("energy", rnd(val, 3), "kWh")
    }
    logDebug "report/query: energia do dia ${day} atualizada"
}

void fetchBatterySettings() {
    state.lastBattery = now()
    apiGet(PATH_BATTERY, [sn: deviceSN.trim()], "handleBatterySettings")
}

void handleBatterySettings(resp, Map data) {
    def json = parseResp(resp, "battery/soc/get", false)
    if (json == null) return
    emit("minSoc", num(json.result?.minSoc), "%")
    emit("minSocOnGrid", num(json.result?.minSocOnGrid), "%")
}

// ───────────────────────── HTTP / autenticação ─────────────────────────

private void apiPost(String path, Map body, String callback, Map data) {
    Map params = [
        uri               : BASE_URI,
        path              : path,
        headers           : authHeaders(path),
        requestContentType: "application/json",
        contentType       : "application/json",
        body              : body,
        timeout           : 60
    ]
    countCall()
    logDebug "POST ${path} ${JsonOutput.toJson(body)}"
    try {
        asynchttpPost(callback, params, data)
    } catch (e) {
        registerFailure("POST ${path}: ${e.message}", path == PATH_REAL)
    }
}

private void apiGet(String path, Map query, String callback) {
    Map params = [
        uri        : BASE_URI,
        path       : path,
        query      : query,
        headers    : authHeaders(path),
        contentType: "application/json",
        timeout    : 60
    ]
    countCall()
    logDebug "GET ${path} ${query}"
    try {
        asynchttpGet(callback, params, [:])
    } catch (e) {
        registerFailure("GET ${path}: ${e.message}", false)
    }
}

// Assinatura: md5 de  path + "\r\n" + token + "\r\n" + timestamp  — com "\r\n" LITERAL
// (barra invertida + r + barra invertida + n), exatamente como a integração do HA faz.
private Map authHeaders(String path) {
    String key = apiKey.trim()
    String ts = now().toString()
    String raw = "${path}\\r\\n${key}\\r\\n${ts}"
    return [
        token       : key,
        lang        : "en",
        timestamp   : ts,
        signature   : md5(raw),
        "User-Agent": UA
    ]
}

private String md5(String s) {
    MessageDigest md = MessageDigest.getInstance("MD5")
    return md.digest(s.getBytes("UTF-8")).encodeHex().toString()
}

private def parseResp(resp, String what, boolean primary) {
    if (resp.hasError()) {
        registerFailure("${what}: HTTP ${resp.status} ${resp.getErrorMessage()}", primary)
        return null
    }
    def json
    try {
        json = resp.getJson()
    } catch (e) {
        registerFailure("${what}: resposta não é JSON", primary)
        return null
    }
    if (json?.errno != 0) {
        String hint = (json?.errno == 40400) ? " (limite diário de chamadas da OpenAPI atingido)" : ""
        registerFailure("${what}: errno ${json?.errno} ${json?.msg}${hint}", primary)
        return null
    }
    return json
}

private void countCall() {
    String today = new Date().format("yyyy-MM-dd", location.timeZone)
    if (state.apiDate != today) {
        state.apiDate = today
        state.apiCalls = 0
    }
    state.apiCalls = ((state.apiCalls ?: 0) as Integer) + 1
    sendEvent(name: "apiCallsToday", value: state.apiCalls)
}

// ───────────────────────── Estado da rede / falhas ─────────────────────────

private void setGrid(String g, String reason) {
    String prev = device.currentValue("gridStatus")
    String ps = (g == "online") ? "mains" : ((g == "offline") ? "battery" : "unknown")
    sendEvent(name: "gridStatus", value: g, descriptionText: "${device.displayName} rede ${g} — ${reason}")
    sendEvent(name: "powerSource", value: ps, descriptionText: "${device.displayName} powerSource ${ps}")
    if (prev != g) {
        String msg = "${device.displayName}: rede elétrica ${g == 'online' ? 'PRESENTE' : 'AUSENTE'} — ${reason} → powerSource=${ps}"
        if (g == "offline") log.warn msg else log.info msg
    }
}

private void registerSuccess() {
    if ((state.failCount ?: 0) > 0) log.info "${device.displayName}: nuvem respondeu de novo após ${state.failCount} falha(s)"
    state.failCount = 0
    sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))
}

private void registerFailure(String msg, boolean primary) {
    log.warn "${device.displayName}: falha na nuvem — ${msg}"
    if (!primary) return
    state.failCount = ((state.failCount ?: 0) as Integer) + 1
    emit("cloudStatus", "error")
    Integer thr = (cloudFailThreshold ?: 3) as Integer
    if (cloudFailAsOutage && state.failCount >= thr && device.currentValue("gridStatus") != "offline") {
        setGrid("offline", "fail-safe: ${state.failCount} falhas seguidas na nuvem")
    }
}

// ───────────────────────── Utilitários ─────────────────────────

private Long dataAgeMinutes(String t) {
    try {
        String dt = t.take(19)                       // "yyyy-MM-dd HH:mm:ss"
        def mo = (t =~ /([+-]\d{4})\s*$/)             // offset no fim, ex.: "GMT+0000"
        Date d
        if (mo.find()) {
            d = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z").parse("${dt} ${mo.group(1)}")
        } else {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
            f.setTimeZone(location.timeZone)
            d = f.parse(dt)
        }
        return ((now() - d.time) / 60000L) as Long
    } catch (e) {
        logDebug "não consegui interpretar o horário do dado '${t}': ${e.message}"
        return null
    }
}

private BigDecimal num(def x) {
    if (x == null) return null
    try {
        return new BigDecimal(x.toString().trim())
    } catch (e) {
        return null
    }
}

private BigDecimal rnd(BigDecimal x, int scale) {
    return x?.setScale(scale, RoundingMode.HALF_UP)
}

private void emit(String name, def value, String unit = null) {
    if (value == null) return
    Map evt = [name: name, value: value]
    if (unit) evt.unit = unit
    evt.descriptionText = "${device.displayName} ${name} é ${value}${unit ? ' ' + unit : ''}"
    if (txtEnable && name in TXT_ATTRS && device.currentValue(name)?.toString() != value.toString()) {
        log.info evt.descriptionText
    }
    sendEvent(evt)
}

private void logDebug(String msg) {
    if (logEnable) log.debug "${device.displayName}: ${msg}"
}