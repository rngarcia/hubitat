/**
 *  GiEX Irrigation Valve (sh1btabb) — Hubitat Driver
 *  Versão: 1.3.1 — Build: 2026-10-21
 *  Autor original: Krassimir Kossev (kkossev, Tuya Zigbee Valve v1.7.1)
 *  Modificado por: Ricardo Nogueira Garcia
 *  Repositório: https://github.com/rngarcia/hubitat
 *  Gostou? Buy me a beer: https://www.paypal.com/donate/?business=rngarcia%40gmail.com&currency_code=USD
 *
 *  Changelog:
 *  v1.3.1 (2026-10-21) - RNG
 *    - Publicado em rngarcia/hubitat: cabeçalho padronizado, namespace "rng" e importUrl
 *  (histórico anterior no bloco de comentário abaixo)
 */
/* groovylint-disable DuplicateStringLiteral, LineLength, MethodParameterTypeRequired */
/**
 *  GiEX Zigbee Irrigation Valve — _TZE200_sh1btabb / TS0601
 *
 *  Driver dedicado para a válvula de irrigação GiEX com manufacturer _TZE200_sh1btabb.
 *  Baseado em análise de sniffer de DPs realizada em 2026-06-20.
 *
 *  DPs confirmados por captura direta do dispositivo:
 *    0x01 (BOOL)     — valve switch: 0=closed, 1=open
 *    0x02 (BOOL)     — valve state report (eco do estado): 0=closed, 1=open
 *    0x65 (STRING)   — irrigationStartTime "HH:MM:SS" (hora local do hub)
 *    0x66 (STRING)   — irrigationEndTime   "HH:MM:SS" (hora local do hub)
 *    0x6A (VALUE)    — desconhecido / sem sensor de temperatura — sempre 0, ignorado
 *    0x6C (VALUE)    — battery % (0–100)
 *    0x6F (VALUE)    — waterConsumed em litros (chega ao fechar após ciclo real)
 *
 *  Atributos mantidos:
 *    valve, switch, battery, healthStatus, powerSource,
 *    irrigationStartTime, irrigationEndTime, lastIrrigationDuration,
 *    waterConsumed, irrigationDuration (timer auto-off),
 *    rtt, lastValveOpenDuration
 *
 *  Licensed under the Apache License, Version 2.0
 *
 *  ver. 1.0.0  2026-06-20  Ricardo Nogueira Garcia  — driver inicial dedicado ao _TZE200_sh1btabb
 *  ver. 1.1.0  2026-06-20  Ricardo Nogueira Garcia  — adicionado waterConsumedTotal
 *  ver. 1.2.0  2026-07-20  Ricardo Nogueira Garcia  — corrigido enrichTime: device manda hora em UTC,
 *                           não em hora local; converte UTC→local usando timezone do hub
 *  ver. 1.3.0  2026-07-20  Ricardo Nogueira Garcia  — sniffer ciclo real revelou comportamento completo:
 *                           DP 0x6F (waterConsumed) chega em tempo real ~20s incrementando durante irrigação;
 *                           DP 0x72 (lastIrrigationDuration) calculado pelo device "HH:MM:SS,d" — usado direto;
 *                           DP 0x66 manda "--:--:--" ao abrir (reset) — filtrado;
 *                           DP 0x65 (startTime) agora corrige openedAtHub com valor UTC→local do device;
 *                           waterConsumedTotal acumula por delta incremental em vez de valor final
 *                           não em hora local; converte UTC→local usando timezone do hub;
 *                           lastIrrigationTime continua usando hubTimestamp() (autoritativo) (acumulado total_increasing)
 *                           para integração com Energy Dashboard do Home Assistant;
 *                           comando resetWaterTotal() para zerar o acumulador manualmente
 *                           baseado em kkossev/Tuya Zigbee Valve v1.7.1 (lógica genérica removida)
 */

import groovy.transform.Field
import hubitat.zigbee.zcl.DataType

static String version()   { '1.3.1' }
static String timeStamp() { '2026/07/20 — v1.3.0' }

// ── Constantes Tuya ──────────────────────────────────────────────────────────
@Field static final int    CLUSTER_TUYA      = 0xEF00
@Field static final String DP_TYPE_BOOL      = '01'
@Field static final String DP_TYPE_VALUE     = '02'
@Field static final String DP_TYPE_STRING    = '03'
@Field static final String DP_TYPE_ENUM      = '04'

// DPs do dispositivo
@Field static final String DP_SWITCH         = '01'   // BOOL  — abrir/fechar
@Field static final String DP_VALVE_STATE    = '02'   // BOOL  — report de estado
@Field static final String DP_START_TIME     = '65'   // STRING "HH:MM:SS"
@Field static final String DP_END_TIME       = '66'   // STRING "HH:MM:SS"
@Field static final String DP_UNKNOWN_6A     = '6A'   // VALUE — ignorado (sempre 0)
@Field static final String DP_BATTERY        = '6C'   // VALUE — bateria %
@Field static final String DP_WATER_CONSUMED = '6F'   // VALUE — litros consumidos
@Field static final String DP_IRRIGATION_TIMER  = '68'   // VALUE — timer auto-off em segundos (report do device)

// Temporização
@Field static final int DEBOUNCE_MS          = 300
@Field static final int DIGITAL_CLEAR_MS     = 3000
@Field static final int REFRESH_CLEAR_MS     = 6000
@Field static final int CMD_TIMEOUT_SEC      = 8
@Field static final int HEALTH_ABSENT_THRESH = 3      // ciclos de 3h sem mensagem → offline
@Field static final int MAX_PING_MS          = 10000

// ── Metadados ────────────────────────────────────────────────────────────────
metadata {
    definition(
        name: "GiEX Irrigation Valve (sh1btabb)",
        namespace: "rng",
        author: "Krassimir Kossev; Ricardo Nogueira Garcia",
        singleThreaded: true,
        importUrl: "https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/giex-irrigation-valve/giex-irrigation-valve.groovy"
    ) {
        capability 'Actuator'
        capability 'Valve'          // open(), close(), valve
        capability 'Switch'         // on(), off(), switch  (compatibilidade Alexa/Google/HomeKit)
        capability 'Refresh'
        capability 'Configuration'
        capability 'HealthCheck'
        capability 'Battery'        // battery
        capability 'PowerSource'    // powerSource

        // Atributos de irrigação
        attribute 'irrigationStartTime',    'string'   // "yyyy-MM-dd HH:mm:ss"
        attribute 'irrigationEndTime',      'string'   // "yyyy-MM-dd HH:mm:ss"
        attribute 'lastIrrigationDuration', 'string'   // calculado: "00:05:12"
        attribute 'waterConsumed',          'number'   // litros em tempo real durante irrigação (atualiza ~20s)
        attribute 'waterConsumedTotal',     'number'   // litros acumulados desde a instalação (total_increasing — Energy Dashboard)
        attribute 'irrigationDuration',     'number'   // timer auto-off em segundos (0 = desligado)
        attribute 'lastValveOpenDuration',  'number'   // segundos (se reportado)
        attribute 'lastIrrigationTime',     'string'   // "yyyy-MM-dd HH:mm:ss" — timestamp do último fechamento com data+hora do hub

        // Diagnóstico
        attribute 'healthStatus', 'enum', ['offline', 'online']
        attribute 'rtt',          'number'

        // Comandos extras
        command 'setIrrigationTimer', [
            [name: 'Duração (segundos)', type: 'NUMBER',
             description: 'Timer de auto-desligamento em segundos. 0 = desabilitado. Máx: 86400.']
        ]
        command 'ping'
        command 'resetWaterTotal', [[name: 'Zerar o acumulador waterConsumedTotal (ex: troca de device)']]
    }

    preferences {
        input name: 'txtEnable',    type: 'bool',   title: '<b>Log de eventos</b>',
              description: 'Exibe valores nos logs do Hubitat',        defaultValue: true
        input name: 'logEnable',    type: 'bool',   title: '<b>Log de debug</b>',
              description: 'Log detalhado para troubleshooting',        defaultValue: false
        input name: 'autoOffTimer', type: 'number', title: '<b>Timer de auto-desligamento (segundos)</b>',
              description: 'Fechar automaticamente após N segundos. 0 = desabilitado.',
              defaultValue: 0, range: '0..86400', required: false
    }
}

// ── Lifecycle ────────────────────────────────────────────────────────────────

void installed() {
    log.info "${device.displayName} instalado (v${version()})"
    initState()
    runIn(3, 'configure')
}

void updated() {
    log.info "${device.displayName} preferências salvas — debug=${logEnable} txt=${txtEnable} autoOff=${settings?.autoOffTimer}s"
    if (logEnable) { runIn(86400, 'logsOff') } else { unschedule('logsOff') }
    scheduleHealthCheck()
    // Sincroniza o atributo irrigationDuration com a preferência atual
    Integer timer = safeInt(settings?.autoOffTimer, 0)
    sendEvent(name: 'irrigationDuration', value: timer, unit: 's', type: 'digital')
}

void configure() {
    logInfo 'configure()...'
    List<String> cmds = []
    // Tuya magic — necessário para TS0601
    cmds += zigbee.readAttribute(0x0000, [0x0004, 0x0000, 0x0001, 0x0005, 0x0007, 0xfffe], [:], 150)
    cmds += zigbee.writeAttribute(0x0000, 0xffde, 0x20, 0x0d, [:], 50)
    sendZigbee(cmds)
    runIn(3, 'refresh')
}

void logsOff() {
    log.warn "${device.displayName} debug logging desligado automaticamente após 24h"
    device.updateSetting('logEnable', [value: 'false', type: 'bool'])
}

// ── Comandos de válvula ──────────────────────────────────────────────────────

void on()    { open() }
void off()   { close() }

void open() {
    logDebug 'open()'
    setState('isDigital', true)
    scheduleCommandTimeout()
    // Registra o timestamp de abertura no hub (não depende do DP 0x65 do device)
    String openedAt = hubTimestamp()
    setState('openedAtHub', openedAt)
    sendEvent(name: 'irrigationStartTime', value: openedAt, type: 'digital')
    sendEvent(name: 'irrigationEndTime',   value: '—', type: 'digital')
    sendEvent(name: 'waterConsumed',       value: 0,   type: 'digital')
    logInfo "irrigationStartTime (hub) = ${openedAt}" 
    List<String> cmds = buildTuyaCmd(DP_SWITCH, DP_TYPE_BOOL, '01')
    // Se timer de auto-off configurado, envia junto após 500ms
    Integer timer = safeInt(settings?.autoOffTimer, 0)
    if (timer > 0) {
        String timerHex = zigbee.convertToHexString(timer, 8)
        cmds += buildTuyaCmd('68', DP_TYPE_VALUE, timerHex)   // DP 0x68 = irrigationTarget
    }
    sendZigbee(cmds)
    runInMillis(DIGITAL_CLEAR_MS, 'clearDigital')
}

void close() {
    logDebug 'close()'
    setState('isDigital', true)
    scheduleCommandTimeout()
    // Captura o horário de fechamento para cálculo de duração
    setState('closeRequestedAt', now())
    sendZigbee(buildTuyaCmd(DP_SWITCH, DP_TYPE_BOOL, '00'))
    runInMillis(DIGITAL_CLEAR_MS, 'clearDigital')
}

void resetWaterTotal() {
    setState('waterConsumedTotal', 0L)
    sendEvent(name: 'waterConsumedTotal', value: 0, unit: 'L', type: 'digital')
    log.warn "${device.displayName} waterConsumedTotal zerado manualmente"
}

void setIrrigationTimer(BigDecimal seconds) {
    Integer s = safeInt(seconds, 0)
    if (s < 0 || s > 86400) { log.warn "${device.displayName} timer deve ser 0–86400 segundos"; return }
    logInfo "setIrrigationTimer: ${s}s"
    device.updateSetting('autoOffTimer', [value: s, type: 'number'])
    sendEvent(name: 'irrigationDuration', value: s, unit: 's', type: 'digital')
}

// ── Refresh / Ping ───────────────────────────────────────────────────────────

void refresh() {
    logDebug 'refresh()'
    setState('isRefresh', true)
    scheduleCommandTimeout()
    // TS0601: cmd 0x03 solicita report de todos os DPs
    // (este device não suporta o cmd, mas a resposta vem de qualquer forma pelo heartbeat)
    // Força leitura de bateria via cluster padrão como fallback
    List<String> cmds = []
    cmds += zigbee.command(CLUSTER_TUYA, 0x03, '0000')
    cmds += zigbee.readAttribute(0x0001, [0x0020, 0x0021], [:], 200)
    sendZigbee(cmds)
    runInMillis(REFRESH_CLEAR_MS, 'clearRefresh')
}

void ping() {
    logInfo 'ping...'
    setState('pingTime', now())
    scheduleCommandTimeout()
    sendZigbee(zigbee.readAttribute(0x0000, 0x0001, [:], 0))
}

// ── Parser principal ─────────────────────────────────────────────────────────

void parse(String description) {
    if (description == null) { return }
    updateHealth()
    unschedule('cmdTimeout')

    Map descMap = [:]
    try { descMap = zigbee.parseDescriptionAsMap(description) }
    catch (e) { logDebug "parseDescriptionAsMap exception: ${e}"; return }

    String cluster = descMap.cluster ?: descMap.clusterId ?: ''

    switch (cluster) {
        case 'EF00': parseTuya(descMap);  break
        case '0001': parsePowerCluster(descMap); break
        case '0000':
            if (descMap.attrId == '0001') {
                // ping response
                Long pingTime = (getState('pingTime') ?: 0L) as long
                if (pingTime > 0) {
                    Integer rtt = (int)(now() - pingTime)
                    if (rtt < MAX_PING_MS) {
                        sendEvent(name: 'rtt', value: rtt, unit: 'ms', type: 'digital')
                        logInfo "rtt = ${rtt} ms"
                    }
                }
            }
            break
        default:
            logDebug "cluster não tratado: ${cluster} attrId=${descMap.attrId} value=${descMap.value}"
    }
}

// ── Parser Tuya EF00 ─────────────────────────────────────────────────────────

void parseTuya(Map descMap) {
    List<String> data = descMap.data
    if (data == null || data.size() < 7) {
        // ZCL Default Response (cmd 0x0B) e ACKs chegam como 2 bytes — ignorar silenciosamente
        if (data != null && data.size() > 0) { logDebug "EF00 ACK/response curto ignorado: ${data}" }
        return
    }
    logDebug "EF00 data (${data.size()}B): ${data}"

    // Formato confirmado pelo sniffer: seq(2B) + dp(1B) + type(1B) + len(2B) + value(lenB)
    Integer idx = 2   // pula seq
    while (idx + 3 < data.size()) {
        Integer dp   = hexToInt(data[idx])
        Integer type = hexToInt(data[idx + 1])
        Integer len  = (hexToInt(data[idx + 2]) << 8) | hexToInt(data[idx + 3])
        if ((type as int) < 1 || (type as int) > 5 || (len as int) < 0 || (len as int) > 256) { break }
        Integer end = idx + 4 + len - 1
        if (end >= data.size()) { break }

        List<String> valBytes = data[(idx + 4)..end]
        handleDP(dp, type, len, valBytes)
        idx += 4 + len
    }
}

void handleDP(Integer dp, Integer type, Integer len, List<String> valBytes) {
    String dpHex  = String.format('%02X', dp)
    Long   valInt = bytesToLong(valBytes)
    String valStr = bytesToAscii(valBytes)
    logDebug "DP 0x${dpHex} type=${type} len=${len} int=${valInt} ascii='${valStr}'"

    switch (dpHex) {
        case DP_SWITCH:       // 0x01 — switch principal
        case DP_VALVE_STATE:  // 0x02 — eco de estado
            handleValveSwitch(valInt == 1)
            break

        case DP_START_TIME:   // 0x65 — "HH:MM:SS" UTC, chega ao abrir
            String enrichedStart = enrichTime(valStr)
            setState('lastStartTime', enrichedStart)
            // Sobrescreve o timestamp do hub com o valor do device (mais preciso para o start)
            setState('openedAtHub', enrichedStart)
            sendEvent(name: 'irrigationStartTime', value: enrichedStart, type: 'physical')
            logInfo "irrigationStartTime (device UTC→local) = ${enrichedStart}"
            break

        case DP_END_TIME:     // 0x66 — "HH:MM:SS" UTC ao fechar, ou "--:--:--" ao abrir (reset)
            if (valStr == '--:--:--') {
                // Device reseta o end time ao abrir — ignora silenciosamente
                logDebug "DP 0x66 reset (--:--:--) ao abrir — ignorado"
                sendEvent(name: 'irrigationEndTime', value: '—', type: 'digital')
                break
            }
            String enrichedEnd = enrichTime(valStr)
            String closedAtHub = hubTimestamp()
            sendEvent(name: 'irrigationEndTime',  value: enrichedEnd, type: 'physical')
            sendEvent(name: 'lastIrrigationTime', value: closedAtHub, type: 'physical')
            logInfo "irrigationEndTime (device UTC→local) = ${enrichedEnd}"
            logInfo "lastIrrigationTime (hub) = ${closedAtHub}"
            setState('openedAtHub', null)   // limpa para o próximo ciclo
            break

        case DP_BATTERY:      // 0x6C — bateria %
            Integer pct = (valInt as Integer)
            // Pilhas novas = 100% esperado; aceita qualquer valor 0–100
            pct = Math.max(0, Math.min(100, pct))
            sendEvent(name: 'battery', value: pct, unit: '%', type: 'physical', isStateChange: true)
            logInfo "battery = ${pct}%"
            break

        case DP_WATER_CONSUMED: // 0x6F — litros em tempo real (~20s durante irrigação, incrementa 1L)
            // Atualiza waterConsumed em tempo real (ex: 0→1→2→3→4→5 durante o ciclo)
            sendEvent(name: 'waterConsumed', value: valInt, unit: 'L', type: 'physical')
            logInfo "waterConsumed (tempo real) = ${valInt} L"
            // Acumula no total apenas quando o valor sobe (evita somar 0 do reset ao abrir)
            Long prevCycle = (getState('lastWaterCycle') ?: 0L) as Long
            if (valInt > prevCycle) {
                Long delta = valInt - prevCycle
                Long prevTotal = (getState('waterConsumedTotal') ?: 0L) as Long
                Long newTotal  = prevTotal + delta
                setState('waterConsumedTotal', newTotal)
                setState('lastWaterCycle', valInt)
                sendEvent(name: 'waterConsumedTotal', value: newTotal, unit: 'L', type: 'physical')
                logInfo "waterConsumedTotal = ${newTotal} L (+${delta}L)"
            } else if (valInt == 0) {
                // Reset ao abrir novo ciclo — zera o contador de ciclo
                setState('lastWaterCycle', 0L)
            }
            break

        case DP_UNKNOWN_6A:   // 0x6A — sempre 0, sem sensor, ignora
            logDebug "DP 0x6A (desconhecido) = ${valInt} — ignorado"
            break

        case DP_IRRIGATION_TIMER: // 0x68 — device confirma o timer configurado (segundos)
            logInfo "irrigationDuration (report do device) = ${valInt}s"
            sendEvent(name: 'irrigationDuration', value: valInt, unit: 's', type: 'physical')
            break

        case DP_CYCLE_TIMES:     // 0x67 — cycleIrrigationNumTimes (sempre 0 neste modelo)
            logDebug "DP 0x67 cycleIrrigationNumTimes = ${valInt} — ignorado"
            break

        case DP_LAST_DURATION:   // 0x72 — "HH:MM:SS,d" duração calculada pelo device (chega ao fechar)
            // Formato do device: "00:01:57,5" — usa direto, mais preciso que o cálculo do hub
            logInfo "lastIrrigationDuration (device) = ${valStr}"
            sendEvent(name: 'lastIrrigationDuration', value: valStr, type: 'physical')
            break

        default:
            logDebug "DP não tratado: 0x${dpHex} type=${type} int=${valInt} ascii='${valStr}'"
            break
    }
}

// ── Lógica de estado da válvula ───────────────────────────────────────────────

void handleValveSwitch(boolean isOpen) {
    boolean debounce  = (getState('debounce') ?: false) as boolean
    boolean isDigital = (getState('isDigital') ?: false) as boolean
    boolean isRefresh = (getState('isRefresh') ?: false) as boolean
    String  lastVal   = (getState('lastValve') ?: 'unknown') as String
    String  newVal    = isOpen ? 'open' : 'closed'

    if (debounce && newVal == lastVal) {
        logDebug "debounce: ignorando evento duplicado (${newVal})"
        runInMillis(DEBOUNCE_MS, 'clearDebounce')
        return
    }

    String eventType = isDigital ? 'digital' : 'physical'
    String desc = isRefresh ? "${device.displayName} is ${newVal} (Refresh)" :
                              "${device.displayName} is ${newVal} [${eventType}]"

    setState('debounce', true)
    setState('lastValve', newVal)
    runInMillis(DEBOUNCE_MS, 'clearDebounce')

    if (txtEnable) { log.info desc }
    sendEvent(name: 'valve',  value: newVal,           descriptionText: desc, type: eventType)
    sendEvent(name: 'switch', value: isOpen ? 'on' : 'off', descriptionText: desc, type: eventType)
    sendEvent(name: 'powerSource', value: 'battery', type: 'digital')
}

// ── Power cluster (fallback de bateria) ─────────────────────────────────────

void parsePowerCluster(Map descMap) {
    if (descMap.attrId == '0021') {   // BatteryPercentageRemaining (0–200, divide por 2)
        Integer raw = hexToInt(descMap.value ?: '00')
        Integer pct = Math.min(100, raw.intdiv(2))
        logDebug "cluster 0001 attrId 0021 → battery ${pct}% (raw=${raw})"
        // Só usa o cluster padrão se ainda não temos valor do DP 0x6C
        if (device.currentValue('battery') == null) {
            sendEvent(name: 'battery', value: pct, unit: '%', type: 'physical')
            logInfo "battery (cluster 0001) = ${pct}%"
        }
    }
}

// ── Health check ─────────────────────────────────────────────────────────────

void updateHealth() {
    setState('absentCount', 0)
    if (device.currentValue('healthStatus') != 'online') {
        sendEvent(name: 'healthStatus', value: 'online',   type: 'digital')
        sendEvent(name: 'powerSource',  value: 'battery',  type: 'digital')
        logInfo 'online'
    }
}

void scheduleHealthCheck() {
    unschedule('healthCheck')
    Random rnd = new Random()
    // A cada 3 horas, horário aleatório para não sincronizar todos os devices
    schedule("${rnd.nextInt(59)} ${rnd.nextInt(59)} 0/3 * * ? *", 'healthCheck')
    logDebug 'health check agendado (3h)'
}

void healthCheck() {
    Integer absent = (getState('absentCount') ?: 0) as int
    if (absent >= HEALTH_ABSENT_THRESH) {
        if (device.currentValue('healthStatus') != 'offline') {
            log.warn "${device.displayName} offline (sem mensagens por ~${absent * 3}h)"
            sendEvent(name: 'healthStatus', value: 'offline', type: 'digital')
            sendEvent(name: 'powerSource',  value: 'unknown', type: 'digital')
            // Força bateria 0 quando offline (padrão do driver original)
            if ((safeInt(device.currentValue('battery'), 1)) > 0) {
                sendEvent(name: 'battery', value: 0, unit: '%', type: 'digital')
            }
        }
    } else {
        logDebug "health OK (absentCount=${absent})"
    }
    setState('absentCount', absent + 1)
}

// ── Timeout de comando ───────────────────────────────────────────────────────

void scheduleCommandTimeout() { runIn(CMD_TIMEOUT_SEC, 'cmdTimeout') }

void cmdTimeout() {
    log.warn "${device.displayName} sem resposta (device adormecido ou offline?)"
    sendEvent(name: 'rtt', value: 'timeout', type: 'digital')
}

// ── Helpers de estado ────────────────────────────────────────────────────────

void initState() {
    state.clear()
    // Preserva o total acumulado se já existia (não zera no reinit)
    Long prevTotal = (state.states?.waterConsumedTotal ?: 0L) as Long
    state.states = [
        isDigital:          false,
        isRefresh:          false,
        debounce:           false,
        lastValve:          'unknown',
        absentCount:        0,
        pingTime:           0L,
        closeRequestedAt:   0L,
        lastStartTime:      '',
        waterConsumedTotal: prevTotal
    ]
    state.driverVersion = version()
    logInfo 'estado inicializado'
}

void setState(String key, Object val) {
    if (state.states == null) { state.states = [:] }
    state.states[key] = val
}

Object getState(String key) {
    return state.states?."${key}"
}

void clearDigital()  { setState('isDigital', false) }
void clearDebounce() { setState('debounce',  false) }
void clearRefresh()  { setState('isRefresh', false) }

// ── Helpers de tempo ─────────────────────────────────────────────────────────

/**
 * Enriquece "HH:MM:SS" (já no fuso do hub, enviado pelo time-sync Tuya cmd 0x24)
 * com a data local do hub. Trata limite de meia-noite.
 */
/**
 * Retorna o timestamp atual do hub formatado como "yyyy-MM-dd HH:mm:ss" no timezone local.
 */
String hubTimestamp() {
    Calendar now = Calendar.getInstance(location.timeZone)
    return String.format('%04d-%02d-%02d %02d:%02d:%02d',
        now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH),
        now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), now.get(Calendar.SECOND))
}

/**
 * Converte "HH:MM:SS" enviado pelo device (em UTC — confirmado por sniffer:
 * device reportou 06:07 quando o hub local marcava 19:08 em America/Sao_Paulo UTC-3,
 * diferença de 13h que não bate com local, mas bate com UTC do dia anterior no ciclo de 24h)
 *
 * Estratégia: monta o timestamp UTC com a data de hoje ou ontem (a que resultar
 * no instante mais próximo do now()), depois converte para o timezone local do hub.
 */
String enrichTime(String timeOnly) {
    if (!timeOnly || timeOnly.length() < 8) { return timeOnly ?: '' }
    try {
        Integer hh = timeOnly.substring(0, 2).toInteger()
        Integer mm = timeOnly.substring(3, 5).toInteger()
        Integer ss = timeOnly.substring(6, 8).toInteger()

        // Monta o instante em UTC
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone('UTC'))
        utc.set(Calendar.HOUR_OF_DAY, hh)
        utc.set(Calendar.MINUTE, mm)
        utc.set(Calendar.SECOND, ss)
        utc.set(Calendar.MILLISECOND, 0)

        // Escolhe data UTC mais próxima do now()
        Long now = now()
        Long diffToday = Math.abs(utc.getTimeInMillis() - now)
        utc.add(Calendar.DATE, -1)
        Long diffYest  = Math.abs(utc.getTimeInMillis() - now)
        if (diffToday <= diffYest) { utc.add(Calendar.DATE, 1) }  // hoje era melhor

        // Converte para timezone local do hub
        Calendar local = Calendar.getInstance(location.timeZone)
        local.setTimeInMillis(utc.getTimeInMillis())

        return String.format('%04d-%02d-%02d %02d:%02d:%02d',
            local.get(Calendar.YEAR), local.get(Calendar.MONTH) + 1,
            local.get(Calendar.DAY_OF_MONTH),
            local.get(Calendar.HOUR_OF_DAY), local.get(Calendar.MINUTE),
            local.get(Calendar.SECOND))
    } catch (e) {
        logDebug "enrichTime exception: ${e}"
        return timeOnly
    }
}

/**
 * Calcula a duração entre dois timestamps "yyyy-MM-dd HH:mm:ss".
 * Retorna "HH:MM:SS" ou null se falhar.
 */
String calcDuration(String start, String end) {
    try {
        def fmt = new java.text.SimpleDateFormat('yyyy-MM-dd HH:mm:ss')
        fmt.setTimeZone(location.timeZone)
        Long diffSec = (fmt.parse(end).time - fmt.parse(start).time) / 1000L
        if (diffSec < 0) { diffSec += 86400L }  // cruzou meia-noite
        Long h = diffSec / 3600L
        Long m = (diffSec % 3600L) / 60L
        Long s = diffSec % 60L
        return String.format('%02d:%02d:%02d', h, m, s)
    } catch (e) {
        logDebug "calcDuration exception: ${e}"
        return null
    }
}

// ── Helpers Zigbee / Tuya ────────────────────────────────────────────────────

String packetId() { zigbee.convertToHexString(new Random().nextInt(65536), 4) }

List<String> buildTuyaCmd(String dp, String dpType, String value) {
    return zigbee.command(
        CLUSTER_TUYA, 0x00, [:], 200,
        packetId() + dp + dpType +
        zigbee.convertToHexString((int)(value.length() / 2), 4) + value
    )
}

void sendZigbee(List<String> cmds) {
    if (!cmds) { return }
    hubitat.device.HubMultiAction actions = new hubitat.device.HubMultiAction()
    cmds.each { actions.add(new hubitat.device.HubAction(it.toString(), hubitat.device.Protocol.ZIGBEE)) }
    sendHubCommand(actions)
}

// ── Helpers genéricos ────────────────────────────────────────────────────────

Integer hexToInt(String h) {
    return Integer.parseInt(h ?: '00', 16)
}

Integer safeInt(val, Integer defVal) {
    try { return "${val}".toInteger() } catch (e) { return defVal }
}

Long bytesToLong(List<String> b) {
    Long r = 0L
    b.each { r = (r << 8) | hexToInt(it) }
    return r
}

String bytesToAscii(List<String> b) {
    try {
        return b.collect { String hex ->
            Integer c = hexToInt(hex)
            (c >= 32 && c < 127) ? ((char) c).toString() : '.'
        }.join('')
    } catch (e) { return '?' }
}

void logDebug(String msg) { if (logEnable)  { log.debug "${device.displayName} ${msg}" } }
void logInfo(String msg)  { if (txtEnable)  { log.info  "${device.displayName} ${msg}" } }