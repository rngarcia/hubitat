/**
 *  Tholz SmartHeat — Hubitat Driver (TCP)
 *  Versão: 1.7.1 — Build: 2026-10-21
 *  Autor original: VH / TRATO
 *  Modificado por: Ricardo Nogueira Garcia
 *
 *  Gostou? Buy me a beer: https://www.paypal.com/donate/?business=rngarcia%40gmail.com&currency_code=USD
 *
 *  Changelog:
 *  v1.7.1 (2026-10-21) - RNG
 *    - importUrl aponta para o monorepo rngarcia/hubitat (sem mudança de lógica)
 *    - Link de apoio (PayPal) no cabeçalho
 *
 *  v1.7 (2026-10-19) - RNG
 *    - Fix setHeatMode: não envia mais on:true junto com o opMode. O campo "on" do
 *      heat0 é o acionamento da bomba solar; mandar on:true forçava a bomba ligada
 *      (cortada só pelo F17 após 360 min), inclusive à noite, a cada troca de Mode.
 *      Agora envia só opMode (e on:false apenas no modo Off).
 *    - Energia do Apoio Solar passa a ser contada pelo heat0.on (bomba de fato
 *      ligada), não mais por opMode==1 — o solar fica sempre em Automático.
 *      Demais apoios seguem por opMode.
 *    - importUrl no metadata (atualização pelo botão "Import" do Hubitat)
 *
 *  v1.6 (2026-10-16) - RNG
 *    - Fix guard de igualdade nos child devices de energia: Generic Component Switch
 *      não declara power/energy, então cd.currentValue() sempre retornava null e
 *      todo frame recebido gerava eventos power/energy repetidos (flood no Maker API).
 *      Último valor publicado agora fica em cache no state do pai.
 *    - Log "Conectado." após connect bem-sucedido
 *    - Removido prefixo duplicado "Tholz TCP: Tholz TCP:" no log de conexão
 *
 *  v1.5 (2026-06-22) - RNG
 *    - Removido statusHtml completamente (pesado no Maker API)
 *    - Guards de igualdade em publishAllEnergyAttrs() — só emite se valor mudou
 *    - Throttle de temperatura — só emite se delta > 0.5°C vs último valor
 *    - Fix bug intervalo de refresh: refreshLoop agora sempre lê settings diretamente
 *    - state.refreshInterval removido (causava ignorar novo valor das preferências)
 *    - publishAllEnergyAttrs() só chamado quando estado do apoio muda ou em refresh
 *      periódico com guard de igualdade
 *
 *  v1.4 (2026-06-04) - RNG
 *    - Monitoramento de consumo energético por apoio
 *    - Child devices dos apoios com power e energy
 *    - Comandos resetApoioEnergy
 *
 *  v1.3 (2025-11-16) - RNG/ChatGPT
 *    - Conexão via rawSocket (porta 4000)
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

@Field static final String DRIVER_VERSION    = "1.7"
@Field static final String DRIVER_BUILD_DATE = "2026-10-19"
@Field static final List<String> HEAT_MODE_OPTIONS = ['Off','Ligado','Automático','Econômico']

metadata {
    definition(name: "Tholz SmartHeat", namespace: "rng", author: "VH/TRATO; Ricardo Nogueira Garcia",
               importUrl: "https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/tholz-smartheat/tholz-smartheat.groovy") {
        capability "Initialize"
        capability "Refresh"
        capability "Sensor"
        capability "PushableButton"
        capability "Actuator"

        command "reconnect"
        command "heatOn"
        command "heatOff"
        command "setHeatMode",   [[name:"mode*", type:"ENUM", constraints:["Off","Ligado","Automático","Econômico"]]]
        command "setHeatSetpoint", [[name:"sp(°C)*", type:"NUMBER"]]
        command "heatUp1C"
        command "heatDown1C"
        command "toggleHeatMode"
        command "showDriverInfo"

        command "setApoioSolar",            [[name:"estado*", type:"ENUM", constraints:["Ligado","Desligado"]]]
        command "setApoioGas",              [[name:"estado*", type:"ENUM", constraints:["Ligado","Desligado"]]]
        command "setApoioEletrico",         [[name:"estado*", type:"ENUM", constraints:["Ligado","Desligado"]]]
        command "setRecirculacaoBarrilete", [[name:"estado*", type:"ENUM", constraints:["Ligado","Desligado"]]]

        command "resetApoioSolarEnergy"
        command "resetApoioGasEnergy"
        command "resetApoioEletricoEnergy"
        command "resetRecirculacaoBarrileteEnergy"

        attribute "heat0Mode",    "string"
        attribute "heat0On",      "string"
        attribute "heat0Setpoint","number"

        attribute "apoioSolar",             "string"
        attribute "apoioGas",               "string"
        attribute "apoioEletrico",          "string"
        attribute "recirculacaoBarrilete",  "string"

        attribute "apoioSolarPower",             "number"
        attribute "apoioSolarEnergy",            "number"
        attribute "apoioGasPower",               "number"
        attribute "apoioGasEnergy",              "number"
        attribute "apoioEletricoPower",          "number"
        attribute "apoioEletricoEnergy",         "number"
        attribute "recirculacaoBarriletePower",  "number"
        attribute "recirculacaoBarrileteEnergy", "number"

        attribute "supportedHeatModes", "string"

        attribute "tempColetor",      "number"
        attribute "tempReservatorio", "number"
        attribute "tempConsumo",      "number"
        attribute "temperature",      "number"
        attribute "tempRecirculacao", "number"

        attribute "deviceId",       "number"
        attribute "fwMain",         "string"
        attribute "fwSec",          "string"
        attribute "driverVersion",  "string"
        attribute "driverBuildDate","string"
    }

    preferences {
        input name: "device_IP_address", type: "string",  title: "IP do dispositivo Tholz", required: true
        input name: "device_port",       type: "number",  title: "Porta TCP", defaultValue: 4000, range: "1..65535", required: true
        input name: "autoRefreshSecs",   type: "number",  title: "Atualizar a cada (s)", defaultValue: 300, range: "5..3600"
        input name: "logEnable",         type: "bool",    title: "Habilitar debug logging", defaultValue: false
        input name: "tempThreshold",     type: "decimal", title: "Threshold temperatura (°C) — só emite se delta > valor", defaultValue: 0.5

        input name: "sectionEnergy", type: "paragraph", element: "paragraph",
              title: "<b>Potência dos Apoios (para cálculo de consumo)</b>",
              description: "Informe a potência nominal de cada apoio."

        input name: "powerApoioSolar",    type: "number", title: "Potência — Apoio Solar (W)",        defaultValue: 0,    range: "0..50000"
        input name: "powerApoioGas",      type: "number", title: "Potência — Apoio a Gás (W)",        defaultValue: 0,    range: "0..50000"
        input name: "powerApoioEletrico", type: "number", title: "Potência — Apoio Elétrico (W)",     defaultValue: 3000, range: "0..50000"
        input name: "powerRecirculacao",  type: "number", title: "Potência — Recirculação (W)",       defaultValue: 0,    range: "0..50000"
    }
}

// ── Mapa de apoios de energia ─────────────────────────────────────────────────
@Field static final List APOIOS_ENERGY_MAP = [
    [stateKey:"solar",        attrOn:"apoioSolar",            attrPwr:"apoioSolarPower",            attrEng:"apoioSolarEnergy",            prefPwr:"powerApoioSolar",    childSuffix:"apoio-solar"],
    [stateKey:"gas",          attrOn:"apoioGas",              attrPwr:"apoioGasPower",              attrEng:"apoioGasEnergy",              prefPwr:"powerApoioGas",      childSuffix:"apoio-gas"],
    [stateKey:"eletrico",     attrOn:"apoioEletrico",         attrPwr:"apoioEletricoPower",         attrEng:"apoioEletricoEnergy",         prefPwr:"powerApoioEletrico", childSuffix:"apoio-eletrico"],
    [stateKey:"recirculacao", attrOn:"recirculacaoBarrilete", attrPwr:"recirculacaoBarriletePower", attrEng:"recirculacaoBarrileteEnergy", prefPwr:"powerRecirculacao",  childSuffix:"recirculacao-barrilete"]
]

@Field Map OP_MODE_LABELS_DEFAULT = [0:"Off", 1:"Ligado", 2:"Automático", 3:"Econômico"]

// ── Lifecycle ─────────────────────────────────────────────────────────────────
def installed() {
    logInfo "Installed v${DRIVER_VERSION}"
    sendEvent(name:"numberOfButtons", value:20)
    sendDriverInfo()
    publishSupportedHeatModes()
    ["apoioSolar","apoioGas","apoioEletrico","recirculacaoBarrilete"].each { sendEvent(name:it, value:"off") }
    initEnergyState()
}

def updated() {
    logInfo "Updated v${DRIVER_VERSION}"
    sendEvent(name:"numberOfButtons", value:20)
    unschedule()
    state.remove("rxBuf")
    sendDriverInfo()
    publishSupportedHeatModes()
    initialize()
}

def initialize() {
    logInfo "Initializing v${DRIVER_VERSION}"
    sendDriverInfo()
    connectSocket()
    scheduleRefresh()
    ["apoioSolar","apoioGas","apoioEletrico","recirculacaoBarrilete"].each {
        if (device.currentValue(it) == null) sendEvent(name:it, value:"off")
    }
    initEnergyState()
}

// ── Scheduling ────────────────────────────────────────────────────────────────
def scheduleRefresh() {
    unschedule("refreshLoop")
    runIn(2, "refreshLoop")
    logInfo "Agendado refreshLoop a cada ${autoRefreshSecs ?: 300} segundos."
}

def refreshLoop() {
    try { refresh() } catch (e) { logWarn "Erro em refreshLoop: ${e}" }
    // Sempre lê settings diretamente — evita bug de state antigo ignorar novo valor
    Integer s = (settings?.autoRefreshSecs ?: 300) as Integer
    if (s < 5) s = 5
    runIn(s, "refreshLoop")
}

def reconnect() {
    logWarn "Reconnecting..."
    interfaces?.rawSocket?.close()
    runIn(2, "connectSocket")
}

def showDriverInfo() { sendDriverInfo() }

private void connectSocket() {
    try {
        logInfo "Conectando em ${device_IP_address}:${device_port} ..."
        interfaces.rawSocket.connect(device_IP_address, (int) device_port)
        state.lastRxAt = now()
        logInfo "Conectado."
    } catch (e) {
        logWarn "Falha ao conectar: ${e.message}"
        runIn(10, "connectSocket")
    }
}

def uninstalled() {
    try { interfaces?.rawSocket?.close() } catch (ex) {}
}

def refresh() { getDevice() }

def getDevice() { sendJson([command: "getDevice"]) }

// ── Push ──────────────────────────────────────────────────────────────────────
def push(pushed) {
    if (pushed == null) return
    logInfo "Push ${pushed}"
    sendEvent(name:"pushed", value: pushed, isStateChange: true)
    switch (pushed) {
        case "3":  heatOn();                  break
        case "4":  heatOff();                 break
        case "5":  heatUp1C();                break
        case "6":  heatDown1C();              break
        case "13": setHeatMode("Automático"); break
        case "14": setHeatMode("Ligado");     break
        case "15": setHeatMode("Off");        break
        case "18": toggleHeatMode();          break
        case "19": setOutputById(12, true);   break
        case "20": setOutputById(12, false);  break
        default: logWarn "Push ${pushed}: botão não mapeado"
    }
}

def toggleHeatMode() {
    String cur = device.currentValue("heat0Mode") ?: "Off"
    String next = cur.equalsIgnoreCase("Automático") ? "Ligado" : "Automático"
    setHeatMode(next)
}

private void publishSupportedHeatModes() {
    try { sendEvent(name:"supportedHeatModes", value: groovy.json.JsonOutput.toJson(HEAT_MODE_OPTIONS)) }
    catch (e) { logWarn "Falha ao publicar supportedHeatModes: ${e}" }
}

// ── Comandos de apoio ─────────────────────────────────────────────────────────
def setApoioSolar(String estado)            { setHeatOpModeByChannel(0, mapLigadoDesligado(estado)) }
def setApoioGas(String estado)              { setHeatOpModeByChannel(1, mapLigadoDesligado(estado)) }
def setApoioEletrico(String estado)         { setHeatOpModeByChannel(2, mapLigadoDesligado(estado)) }
def setRecirculacaoBarrilete(String estado) { setHeatOpModeByChannel(3, mapLigadoDesligado(estado)) }

private Integer mapLigadoDesligado(String s) {
    String x = (s ?: "").trim().toLowerCase()
    if (x in ["ligado","on","1","true"])       return 1
    if (x in ["desligado","off","0","false"])  return 0
    logWarn "Valor inválido para estado='${s}', assumindo Desligado(0)"
    return 0
}

private void setHeatOpModeByChannel(Integer heatIdx, Integer opVal) {
    if (heatIdx == null || opVal == null) return
    String key = "heat${heatIdx}"
    sendJson([command:"setDevice", argument:[heatings:[(key):[opMode:opVal, on:(opVal==1)]]]])
    switch (heatIdx) {
        case 0: updateApoioState("solar",        "apoioSolar",            opVal==1); break
        case 1: updateApoioState("gas",          "apoioGas",              opVal==1); break
        case 2: updateApoioState("eletrico",     "apoioEletrico",         opVal==1); break
        case 3: updateApoioState("recirculacao", "recirculacaoBarrilete", opVal==1); break
    }
    runIn(1, "getDevice")
}

private void updateApoioState(String stateKey, String attrName, boolean nowOn) {
    boolean wasOn = (device.currentValue(attrName) == "on")
    sendEvent(name:attrName, value: nowOn ? "on" : "off")
    if (nowOn != wasOn) trackEnergyTransition(stateKey, nowOn)
}

// ── Heatings ──────────────────────────────────────────────────────────────────
def heatOn()  { setHeatingOnOff(true) }
def heatOff() { setHeatingOnOff(false) }

private void setHeatingOnOff(boolean onVal) {
    Map last  = (state?.lastDevice ?: [:]) as Map
    Map heat0 = ((last.heatings ?: [:]) as Map)?.heat0 as Map
    Integer curMode = (heat0?.opMode instanceof Number) ? (heat0.opMode as Integer) : null
    Integer newMode = !onVal ? 0 : ((curMode == null || curMode == 0) ? 1 : curMode)
    sendJson([command:"setDevice", argument:[heatings:[heat0:[on:onVal, opMode:newMode]]]])
    sendEvent(name:"heat0On",   value: onVal ? "on" : "off")
    if (newMode != null) sendEvent(name:"heat0Mode", value: opModeLabel(newMode, null))
    runIn(1, "getDevice")
}

def setHeatMode(String mode) {
    Integer idx = opModeIndexFromLabel(mode)
    if (idx == null) { log.warn "setHeatMode: modo inválido '${mode}'"; return }
    // v1.7: só muda o modo. "on" do heat0 = acionamento da bomba solar; não forçar.
    Map h0 = (idx == 0) ? [opMode:idx, on:false] : [opMode:idx]
    sendJson([command:"setDevice", argument:[heatings:[heat0:h0]]])
    sendEvent(name:"heat0Mode", value: opModeLabel(idx, null))
    if (idx == 0) sendEvent(name:"heat0On", value:"off")
    runIn(1, "getDevice")
}

def setHeatSetpoint(Number spC) {
    if (spC == null) return
    Integer raw = rawFromC(spC)
    sendJson([command:"setDevice", argument:[heatings:[heat0:[sp:raw]]]])
    sendEvent(name:"heat0Setpoint", value: (spC as BigDecimal), unit:"°C")
    runIn(1, "getDevice")
}

def heatUp1C()   { adjustSetpoint(+1.0G) }
def heatDown1C() { adjustSetpoint(-1.0G) }

private void adjustSetpoint(BigDecimal delta) {
    Map heat0 = ((((state?.lastDevice ?: [:]) as Map).heatings ?: [:]) as Map)?.heat0 as Map
    BigDecimal cur = cFromRaw(heat0?.sp) ?: 0.0G
    BigDecimal min = cFromRaw(heat0?.minSp)
    BigDecimal max = cFromRaw(heat0?.maxSp)
    BigDecimal target = cur + delta
    if (min != null) target = [target, min].max()
    if (max != null) target = [target, max].min()
    setHeatSetpoint(target)
}

// ── Output por ID ─────────────────────────────────────────────────────────────
private void setOutputById(Integer targetId, Boolean onVal) {
    Map outs = (((state?.lastDevice ?: [:]) as Map)?.outputs ?: [:]) as Map
    Map argOutputs = [:]
    outs?.each { k, v -> if (v instanceof Map && v.id == targetId) argOutputs[k as String] = [on:onVal] }
    if (argOutputs.isEmpty()) {
        String key = guessOutputKeyForId(targetId, outs)
        if (key) argOutputs[key] = [on:onVal]
    }
    if (argOutputs.isEmpty()) { logWarn "Não foi possível mapear saída id=${targetId}"; return }
    sendJson([command:"setDevice", argument:[outputs:argOutputs]])
}

private String guessOutputKeyForId(Integer idVal, Map outs) {
    String key = outs?.find { it?.value instanceof Map && it?.value?.id == idVal }?.key
    if (key) return key as String
    if (idVal == 1)  return "out1"
    if (idVal == 3)  return "out3"
    if (idVal == 0)  return "out0"
    return null
}

// ── Socket ────────────────────────────────────────────────────────────────────
private void sendJson(Map obj) {
    String js = groovy.json.JsonOutput.toJson(obj)
    if (logEnable) log.debug "TX: ${js}"
    try { interfaces.rawSocket.sendMessage(js + "\n") }
    catch (e) { logWarn "Falha ao enviar (${e}); reconectando..."; reconnect() }
}

def parse(String description) {
    if (logEnable) log.debug "RX (String): ${description}"
    String chunk = looksLikeHex(description) ? hexToUtf8(description) : description
    state.rxBuf = (state.rxBuf ?: "") + (chunk ?: "")
    processBuffer()
}

private boolean looksLikeHex(String s) {
    return s && (s ==~ /[0-9A-Fa-f]+/) && (s.length() % 2 == 0)
}

private String hexToUtf8(String hex) {
    byte[] bytes = new byte[hex.length() / 2]
    for (int i = 0; i < hex.length(); i += 2) bytes[i/2] = (byte) Integer.parseInt(hex.substring(i, i+2), 16)
    return new String(bytes, "UTF-8")
}

private void processBuffer() {
    String buf = state.rxBuf ?: ""
    if (!buf) return
    int level = 0, start = -1
    List<String> frames = []
    for (int i = 0; i < buf.length(); i++) {
        char c = buf.charAt(i)
        if (c == '{') { if (level == 0) start = i; level++ }
        else if (c == '}') { level--; if (level == 0 && start >= 0) { frames << buf.substring(start, i+1); start = -1 } }
    }
    state.rxBuf = (level == 0) ? "" : (start >= 0 ? buf.substring(start) : buf)
    frames.each { handleJsonFrame(it) }
}

private void handleJsonFrame(String jsonText) {
    if (logEnable) log.debug "JSON frame: ${jsonText}"
    Map obj
    try { obj = (Map) new groovy.json.JsonSlurper().parseText(jsonText) } catch (e) { logWarn "Falha JSON: ${e}"; return }
    if (!obj) return
    Map resp = (obj.command && obj.response instanceof Map) ? (Map) obj.response : obj
    if (resp) { state.lastDevice = resp; updateFromResponse(resp) }
}

private void updateFromResponse(Map resp) {
    if (resp?.id != null)    sendEvent(name:"deviceId", value:(resp.id as Long))
    if (resp?.firmware)      sendEvent(name:"fwMain",   value:resp.firmware as String)
    if (resp?.firmwareSec)   sendEvent(name:"fwSec",    value:resp.firmwareSec as String)

    // Outputs
    Map outs = (resp.outputs ?: [:]) as Map
    if (outs) {
        outs.each { key, node ->
            if (!(node instanceof Map)) return
            Integer idVal = (node.id ?: -1) as Integer
            Boolean isOn  = (node.on == true)
            String dni = "${device.id}-${key}"
            def cd = getChildDevice(dni)
            if (!cd) {
                String nice = nameForOutputId(idVal) ?: "Output ${idVal}"
                cd = addChildDevice("hubitat", "Generic Component Switch", dni,
                    [label:"${device.displayName} - ${nice}", isComponent:true, componentName:key, componentLabel:nice])
                cd.updateDataValue("outputKey", key)
                cd.updateDataValue("outputId", "${idVal}")
            }
            componentUpdate(cd, isOn ? "on" : "off")
        }
        state.lastDevice = (state.lastDevice ?: [:]) + [outputs:outs]
    }

    // Heatings
    Map heats = (resp.heatings ?: [:]) as Map
    Map heat0 = heats?.heat0 as Map
    if (heat0) publishHeat0(heat0)

    Map heat3 = heats?.heat3 as Map
    if (heat3?.t4 != null) {
        BigDecimal tRecirc = cFromRaw(heat3.t4)
        if (tRecirc != null) sendTempIfChanged("tempRecirculacao", tRecirc)
    }

    // Estados dos apoios
    [0:"solar", 1:"gas", 2:"eletrico", 3:"recirculacao"].each { idx, sk ->
        String heatKey = "heat${idx}"
        Map hx = heats?."${heatKey}" as Map
        if (hx == null) return
        Integer op = (hx?.opMode instanceof Number) ? (hx.opMode as Integer) : null
        if (op == null) return
        String attrName = APOIOS_ENERGY_MAP.find { it.stateKey == sk }?.attrOn
        if (!attrName) return
        // v1.7: solar (heat0) fica sempre em Automático; a bomba é o campo "on"
        boolean nowOn = (idx == 0) ? (hx.on == true) : (op == 1)
        boolean wasOn = (device.currentValue(attrName) == "on")
        sendEvent(name:attrName, value: nowOn ? "on" : "off")
        if (nowOn != wasOn) trackEnergyTransition(sk, nowOn)
    }

    publishAllEnergyAttrs()
}

// ── Temperaturas com throttle ─────────────────────────────────────────────────
private void sendTempIfChanged(String attrName, BigDecimal newVal) {
    double threshold = (settings?.tempThreshold ?: 0.5) as double
    def prev = device.currentValue(attrName)
    if (prev != null && Math.abs((newVal as double) - (prev as double)) < threshold) return
    sendEvent(name:attrName, value:newVal, unit:"°C")
}

// ── Heat0 ─────────────────────────────────────────────────────────────────────
private void publishHeat0(Map h) {
    if (!(h instanceof Map)) return
    Integer typeVal = (h.type instanceof Number) ? (h.type as Integer) : null
    Integer op      = (h.opMode instanceof Number) ? (h.opMode as Integer) : null
    Boolean on      = (h.on == true)
    BigDecimal sp   = cFromRaw(h.sp)
    BigDecimal t1   = cFromRaw(h.t1)
    BigDecimal t2   = cFromRaw(h.t2)
    BigDecimal t3   = cFromRaw(h.t3)
    BigDecimal t4   = cFromRaw(h.t4)

    BigDecimal tMain = t3 ?: t2 ?: t1
    if (tMain == null) {
        int maxIdx = -1
        h.each { k, v ->
            def m = (k instanceof String) ? (k =~ /^t(\d+)$/) : null
            if (m && m.matches()) {
                int idx = Integer.parseInt(m[0][1])
                BigDecimal tv = cFromRaw(v)
                if (tv != null && idx > maxIdx) { maxIdx = idx; tMain = tv }
            }
        }
    }

    if (tMain != null) sendTempIfChanged("temperature",      tMain)
    if (t1 != null)    sendTempIfChanged("tempColetor",      t1)
    if (t2 != null)    sendTempIfChanged("tempReservatorio", t2)
    if (t3 != null)    sendTempIfChanged("tempConsumo",      t3)
    else if (tMain != null) sendTempIfChanged("tempConsumo", tMain)

    if (op != null) {
        String newMode = opModeLabel(op, typeVal)
        if (device.currentValue("heat0Mode") != newMode) sendEvent(name:"heat0Mode", value:newMode)
    }
    String onStr = on ? "on" : "off"
    if (device.currentValue("heat0On") != onStr) sendEvent(name:"heat0On", value:onStr)
    if (sp != null) {
        def prev = device.currentValue("heat0Setpoint")
        if (prev == null || Math.abs((sp as double) - (prev as double)) >= 0.5) {
            sendEvent(name:"heat0Setpoint", value:sp, unit:"°C")
        }
    }
}

// ── Energia ───────────────────────────────────────────────────────────────────
private void initEnergyState() {
    APOIOS_ENERGY_MAP.each { ap ->
        String sk = ap.stateKey
        if (state["energy_${sk}"] == null)     state["energy_${sk}"]     = 0.0
        if (state["energyOnAt_${sk}"] == null) state["energyOnAt_${sk}"] = 0L
    }
    publishAllEnergyAttrs()
}

private void trackEnergyTransition(String stateKey, boolean nowOn) {
    long ts = now()
    String onAtKey = "energyOnAt_${stateKey}"
    String accKey  = "energy_${stateKey}"
    if (!nowOn) {
        long onAt = (state[onAtKey] ?: 0L) as Long
        if (onAt > 0L) {
            double elapsedH = (ts - onAt) / 3_600_000.0
            double watts    = getPowerForApoio(stateKey)
            state[accKey]   = ((state[accKey] ?: 0.0) as double) + (watts * elapsedH / 1000.0)
        }
        state[onAtKey] = 0L
    } else {
        state[onAtKey] = ts
    }
    publishAllEnergyAttrs()
}

private double getPowerForApoio(String stateKey) {
    switch (stateKey) {
        case "solar":        return (settings?.powerApoioSolar    ?: 0) as double
        case "gas":          return (settings?.powerApoioGas      ?: 0) as double
        case "eletrico":     return (settings?.powerApoioEletrico ?: 3000) as double
        case "recirculacao": return (settings?.powerRecirculacao  ?: 0) as double
    }
    return 0.0
}

// Guard de igualdade — só emite evento se valor mudou (evita flood no Maker API)
private void publishAllEnergyAttrs() {
    APOIOS_ENERGY_MAP.each { ap ->
        String sk    = ap.stateKey
        double watts = getPowerForApoio(sk)
        boolean isOn = (device.currentValue(ap.attrOn) == "on")

        double accumulated = (state["energy_${sk}"] ?: 0.0) as double
        long onAt = (state["energyOnAt_${sk}"] ?: 0L) as Long
        double livePeriod = (isOn && onAt > 0L) ? ((now() - onAt) / 3_600_000.0 * watts / 1000.0) : 0.0
        double totalKwh   = accumulated + livePeriod
        double instPower  = isOn ? watts : 0.0

        // Guard: só emite se mudou
        def prevPwr = device.currentValue(ap.attrPwr)
        def prevEng = device.currentValue(ap.attrEng)

        double pwrRounded = instPower.round(1)
        double engRounded = totalKwh.round(4)

        if (prevPwr == null || Math.abs((prevPwr as double) - pwrRounded) >= 0.1) {
            sendEvent(name:ap.attrPwr, value:pwrRounded, unit:"W",   descriptionText:"${device.displayName} ${sk} power ${pwrRounded}W")
        }
        if (prevEng == null || Math.abs((prevEng as double) - engRounded) >= 0.0001) {
            sendEvent(name:ap.attrEng, value:engRounded, unit:"kWh", descriptionText:"${device.displayName} ${sk} energy ${engRounded}kWh")
        }

        updateApoioEnergyChild(ap, pwrRounded, engRounded)
    }
}

private void updateApoioEnergyChild(Map ap, double power, double energy) {
    String dni = "${device.id}-energy-${ap.childSuffix}"
    def cd = getChildDevice(dni)
    if (!cd) {
        String label = "${device.displayName} — ${childEnergyLabel(ap.stateKey)}"
        try {
            cd = addChildDevice("hubitat", "Generic Component Switch", dni,
                [label:label, isComponent:true, componentName:"energy-${ap.childSuffix}", componentLabel:childEnergyLabel(ap.stateKey)])
            logInfo "Child de energia criado: ${label}"
        } catch (ex) { logWarn "Falha ao criar child de energia '${label}': ${ex}"; return }
    }
    try {
        // Sincroniza o estado do switch com o estado do apoio no pai
        boolean isOn = (device.currentValue(ap.attrOn) == "on")
        String  newSw = isOn ? "on" : "off"
        def prevSw = cd.currentValue("switch")
        if (prevSw != newSw) cd.sendEvent(name:"switch", value:newSw)

        // Generic Component Switch não declara power/energy: cd.currentValue() volta null
        // e o guard nunca segura. Guarda o último valor publicado no state do pai.
        String kP = "childPwr_${ap.stateKey}"
        String kE = "childEng_${ap.stateKey}"
        def prevPwr = state[kP]
        def prevEng = state[kE]
        if (prevPwr == null || Math.abs((prevPwr as double) - power) >= 0.1) {
            cd.sendEvent(name:"power", value:power, unit:"W")
            state[kP] = power
        }
        if (prevEng == null || Math.abs((prevEng as double) - energy) >= 0.0001) {
            cd.sendEvent(name:"energy", value:energy, unit:"kWh")
            state[kE] = energy
        }
    } catch (ex) { logWarn "Erro ao publicar energia no child ${dni}: ${ex}" }
}

private String childEnergyLabel(String stateKey) {
    switch (stateKey) {
        case "solar":        return "Apoio Solar"
        case "gas":          return "Apoio a Gás"
        case "eletrico":     return "Apoio Elétrico"
        case "recirculacao": return "Recirculação Barrilete"
    }
    return stateKey
}

// ── Reset de energia ──────────────────────────────────────────────────────────
def resetApoioSolarEnergy()            { resetApoioEnergy("solar") }
def resetApoioGasEnergy()              { resetApoioEnergy("gas") }
def resetApoioEletricoEnergy()         { resetApoioEnergy("eletrico") }
def resetRecirculacaoBarrileteEnergy() { resetApoioEnergy("recirculacao") }

private void resetApoioEnergy(String stateKey) {
    logInfo "Resetando energia: ${stateKey}"
    state["energy_${stateKey}"] = 0.0
    String attrOn = APOIOS_ENERGY_MAP.find { it.stateKey == stateKey }?.attrOn
    state["energyOnAt_${stateKey}"] = (attrOn && device.currentValue(attrOn) == "on") ? now() : 0L
    publishAllEnergyAttrs()
}

// ── Component handlers ────────────────────────────────────────────────────────
def componentOn(cd) {
    String dni = cd.deviceNetworkId ?: ""
    if (dni.contains("-energy-")) { routeApoioSwitch(dni, "Ligado"); return }
    String outKey = cd.getDataValue("outputKey")
    if (outKey) { sendJson([command:"setDevice", argument:[outputs:[(outKey):[on:true]]]]); runIn(1,"getDevice") }
}

def componentOff(cd) {
    String dni = cd.deviceNetworkId ?: ""
    if (dni.contains("-energy-")) { routeApoioSwitch(dni, "Desligado"); return }
    String outKey = cd.getDataValue("outputKey")
    if (outKey) { sendJson([command:"setDevice", argument:[outputs:[(outKey):[on:false]]]]); runIn(1,"getDevice") }
}

private void routeApoioSwitch(String dni, String estado) {
    logInfo "Child apoio switch: ${dni} -> ${estado}"
    if      (dni.endsWith("-apoio-solar"))            { setApoioSolar(estado) }
    else if (dni.endsWith("-apoio-gas"))              { setApoioGas(estado) }
    else if (dni.endsWith("-apoio-eletrico"))         { setApoioEletrico(estado) }
    else if (dni.endsWith("-recirculacao-barrilete")) { setRecirculacaoBarrilete(estado) }
    else { logWarn "routeApoioSwitch: DNI não reconhecido: ${dni}" }
}

private void componentUpdate(def cd, String ev) {
    try { cd.parse([[name:"switch", value:ev, descriptionText:"${cd.displayName} was turned ${ev}"]]) }
    catch (ignored) { cd.sendEvent(name:"switch", value:ev) }
}


// ── Socket status ─────────────────────────────────────────────────────────────
void socketStatus(String message) {
    logWarn "socketStatus: ${message}"
    runIn(5, "connectSocket")
}

// ── Helpers ───────────────────────────────────────────────────────────────────
private void sendDriverInfo() {
    sendEvent(name:"driverVersion",   value:DRIVER_VERSION)
    sendEvent(name:"driverBuildDate", value:DRIVER_BUILD_DATE)
    if (logEnable) log.debug "Driver info -> version=${DRIVER_VERSION}, build=${DRIVER_BUILD_DATE}"
}

private void logInfo(msg) { log.info "Tholz TCP: ${msg}" }
private void logWarn(msg) { log.warn "Tholz TCP: ${msg}" }

private BigDecimal cFromRaw(def v) {
    if (v == null) return null
    if ((v instanceof Number) && v.intValue() == 0xAAAA) return null
    try { return (v as BigDecimal) / 10.0G } catch (ignored) { return null }
}

private Integer rawFromC(def c) {
    if (c == null) return null
    return Math.round((c as BigDecimal) * 10.0G) as Integer
}

private String opModeLabel(Integer opMode, Integer typeVal) {
    return OP_MODE_LABELS_DEFAULT.get(opMode, "Modo ${opMode}")
}

private Integer opModeIndexFromLabel(String label) {
    String x = (label ?: "").trim().toLowerCase()
    switch (x) {
        case "off": case "desligado": return 0
        case "on":  case "ligado":    return 1
        case "auto": case "automático": case "automatico": return 2
        case "econômico": case "economico": return 3
    }
    try { return Integer.parseInt(x) } catch (ignored) { return null }
}

private String nameForOutputId(Integer idVal) {
    switch (idVal) {
        case 0: return "Filtro"
        case 1: return "Apoio Elétrico"
        case 2: return "Apoio a Gás"
        case 3: return "Recirculação"
        case 4: return "Borbulhador"
        case 5: return "Circulação"
    }
    return null
}