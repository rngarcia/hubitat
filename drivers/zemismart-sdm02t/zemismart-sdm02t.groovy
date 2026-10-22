/**
 *  Zemismart SDM02T 2-Phase Energy Meter — Hubitat Driver
 *  Versão: 1.4.2 — Build: 2026-10-21
 *  Autor: Ricardo Nogueira Garcia
 *  Repositório: https://github.com/rngarcia/hubitat
 *  Gostou? Buy me a beer: https://www.paypal.com/donate/?business=rngarcia%40gmail.com&currency_code=USD
 *
 *  Changelog:
 *  v1.4.2 (2026-10-21) - RNG
 *    - Publicado em rngarcia/hubitat: cabeçalho padronizado, namespace "rng" e importUrl
 *  (histórico anterior no bloco de comentário abaixo)
 */
/**
 *  Zemismart SDM02T – Zigbee 2-Phase Smart Energy Meter
 *
 *  Suporta medição bifásica bidirecional (geração solar + consumo de rede)
 *  Modelo: SDM02T-TZ  |  Manufacturer: _TZE284_78ioiaml  |  Model: TS0601
 *
 *  Mapeamento de DPs alinhado à definição oficial do zigbee-herdsman-converters
 *  (modelo "SDM02V1" — "Smart energy monitor for 2P+N system").
 *  Referência: Koenkk/zigbee-herdsman-converters src/devices/tuya.ts
 *  Histórico de descoberta via sniffer EF00 (kkossev como base inicial).
 *
 *  Autor original: Ricardo Nogueira Garcia
 *  Versão:  1.4.1
 *  Data:    2026-06-23
 *
 *  Changelog:
 *    1.4.1 (2026-06-23) - Ricardo Nogueira Garcia
 *          Validado em campo: divisor de corrente /1000 confere com P=V·I·FP
 *          (L1: 110.1V × 16.586A × 0.99 = 1808W vs 1819W reportado, ~0.6%).
 *          - DP6/DP7 (pacotes empacotados V+I+P por fase, 8 bytes) agora
 *            tratados como FALLBACK por push: extraem V/I/P de L1(DP6)/L2(DP7)
 *            quando os DPs escalares (103/104/105 e 112/113/114) não chegam.
 *            Formato: [V uint16 /10][I uint24 /1000][P uint24 raw].
 *            Guard por ciclo evita evento duplicado se o escalar já atualizou.
 *          - Silenciado o aviso "DP nao mapeado" para DPs conhecidos porém
 *            não expostos (6, 7, 9, 12, 17, 18, 19, 101) — vão só p/ debug.
 *          - Preferência 'usePushFallback' (default true) liga/desliga o
 *            fallback DP6/7, permitindo zerar o auto-polling.
 *    1.4.0 (2026-06-23) - Ricardo Nogueira Garcia
 *          REMAPEAMENTO para os DPs oficiais do _TZE284_78ioiaml (SDM02V1):
 *          - DP 1   = energia importada total (forward) /100   → energy
 *          - DP 23  = energia exportada total (reverse) /100   → energyProduced
 *          - DP 29  = potência ativa total (raw)               → power
 *          - DP 32  = frequência AC /100                       → frequency
 *          - DP 50  = fator de potência total (raw)            → powerFactor
 *          - DP 102 = update_frequency (s, gravável)           → reportInterval
 *          - DP 103/112 = tensão L1/L2 /10
 *          - DP 104/113 = corrente L1/L2 /1000  (divisor oficial)
 *          - DP 105/114 = potência L1/L2 (raw, SEM divisor)
 *          - DP 108/117 = fator de potência L1/L2 (raw)
 *          - DP 109/118 = energia importada L1/L2 /100
 *          - DP 110/119 = energia exportada L1/L2 /100
 *          Mudança de comportamento de energia (conforme decidido):
 *          - 'energy' agora reporta SOMENTE a importação (DP 1), sem cálculo
 *            A+B nem soma manual. Contador crescente nativo (total_increasing).
 *          - Novo atributo 'energyProduced' reporta a exportação (DP 23).
 *          - Removidos: energyNet, updateTotalEnergy(), soma energyA+energyB.
 *          Potência total: 'power' agora vem do DP 29 (total nativo do device).
 *          powerTotal mantido como espelho derivado de powerL1+powerL2 (debug).
 *          Rótulos de fase seguem a doc oficial (L1/L2), independente da
 *          ligação física dos clamps (inversão A/B é irrelevante).
 *    1.3.9 (2026-06-22) - Throttle por threshold (Hub Load) + guards de igualdade.
 *    1.3.8 (2026-06-21) - Pacote DP07 fase A — corrente uint24.
 *    1.3.2 (2026-06-21) - DP68 corrente B real; DP72 debug.
 *    1.3.0 (2026-06-20) - Removido temperature (DP17 inválido).
 *    1.2.0 (2026-06-18) - Compatibilidade HA Energy Dashboard.
 *    1.1.0 (2026-06-18) - Mapeamento completo via sniffer.
 *    1.0.0 (2026-06-18) - Versão inicial bifásica bidirecional.
 *
 *  Licensed under the Apache License, Version 2.0
 */

import groovy.transform.Field

static String version()   { '1.4.2' }
static String timeStamp() { '2026/06/23 22:00' }

metadata {
    definition(
        name: "Zemismart SDM02T 2-Phase Energy Meter",
        namespace: "rng",
        author: "Ricardo Nogueira Garcia",
        singleThreaded: true,
        importUrl: "https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/zemismart-sdm02t/zemismart-sdm02t.groovy"
    ) {
        capability 'EnergyMeter'
        capability 'PowerMeter'
        capability 'VoltageMeasurement'
        capability 'CurrentMeter'
        capability 'Refresh'
        capability 'Sensor'
        capability 'Health Check'

        // Fase L1
        attribute 'voltageL1',       'NUMBER'
        attribute 'currentL1',       'NUMBER'
        attribute 'powerL1',         'NUMBER'
        attribute 'energyL1',        'NUMBER'   // importada L1
        attribute 'energyProducedL1','NUMBER'   // exportada L1
        attribute 'powerFactorL1',   'NUMBER'

        // Fase L2
        attribute 'voltageL2',       'NUMBER'
        attribute 'currentL2',       'NUMBER'
        attribute 'powerL2',         'NUMBER'
        attribute 'energyL2',        'NUMBER'   // importada L2
        attribute 'energyProducedL2','NUMBER'   // exportada L2
        attribute 'powerFactorL2',   'NUMBER'

        // Totais (nativos do device)
        attribute 'energyProduced',  'NUMBER'   // DP23 — exportação total (grid export)
        attribute 'powerFactor',     'NUMBER'   // DP50 — FP total
        attribute 'powerTotal',      'NUMBER'   // espelho L1+L2 (debug/diagnóstico)
        attribute 'frequency',       'NUMBER'

        attribute 'healthStatus',   'enum', ['unknown', 'offline', 'online']
        attribute 'lastSeen',       'string'
        attribute 'reportInterval', 'NUMBER'

        command 'initialize', [[name: 'Reinicia polling e solicita dados ao device']]
        command 'setUpdateFrequency', [[name: 'Intervalo de report (s)', type: 'NUMBER', description: 'Grava o DP102 no device (5–3600s). Maior = menos Hub Load.']]
    }

    preferences {
        input name: 'logEnable',          type: 'bool',    title: '<b>Debug logging</b>',                                                      defaultValue: false
        input name: 'txtEnable',          type: 'bool',    title: '<b>Description text logging</b>',                                           defaultValue: true
        input name: 'autoPollingEnabled', type: 'bool',    title: '<b>Auto polling (60s)</b>',                                                  defaultValue: true
        input name: 'usePushFallback',    type: 'bool',    title: '<b>Fallback DP6/7 por push (V/I/P de fase sem polling)</b>',                  defaultValue: true
        input name: 'powerThreshold',     type: 'decimal', title: '<b>Threshold potência (W) — só emite se delta > valor</b>',                  defaultValue: 5.0
        input name: 'currentThreshold',   type: 'decimal', title: '<b>Threshold corrente (A) — só emite se delta > valor</b>',                  defaultValue: 0.05
        input name: 'voltageThreshold',   type: 'decimal', title: '<b>Threshold tensão (V) — só emite se delta > valor</b>',                    defaultValue: 1.0
    }
}

// ── DPs (DECIMAL — alinhados à doc oficial SDM02V1) ───────────────────────────
@Field static final int DP_PHASE_L1_PKT    = 6     // pacote V+I+P fase L1 (8 bytes)
@Field static final int DP_PHASE_L2_PKT    = 7     // pacote V+I+P fase L2 (8 bytes)
@Field static final int DP_ENERGY_FWD      = 1     // energia importada total /100 (kWh)
@Field static final int DP_ENERGY_REV      = 23    // energia exportada total /100 (kWh)
@Field static final int DP_POWER_TOTAL     = 29    // potência ativa total (raw W)
@Field static final int DP_FREQUENCY       = 32    // frequência AC /100 (Hz)
@Field static final int DP_PF_TOTAL        = 50    // fator de potência total (raw %)
@Field static final int DP_UPDATE_FREQ     = 102   // intervalo de report (s, gravável)

@Field static final int DP_VOLTAGE_L1      = 103   // /10  (V)
@Field static final int DP_CURRENT_L1      = 104   // /1000 (A)
@Field static final int DP_POWER_L1        = 105   // raw  (W)
@Field static final int DP_PF_L1           = 108   // raw  (%)
@Field static final int DP_ENERGY_L1       = 109   // /100 (kWh) importada
@Field static final int DP_ENERGY_PROD_L1  = 110   // /100 (kWh) exportada

@Field static final int DP_VOLTAGE_L2      = 112   // /10  (V)
@Field static final int DP_CURRENT_L2      = 113   // /1000 (A)
@Field static final int DP_POWER_L2        = 114   // raw  (W)
@Field static final int DP_PF_L2           = 117   // raw  (%)
@Field static final int DP_ENERGY_L2       = 118   // /100 (kWh) importada
@Field static final int DP_ENERGY_PROD_L2  = 119   // /100 (kWh) exportada

// DPs conhecidos porém não expostos como atributo (vão só p/ debug, sem warn)
@Field static final List<Integer> DP_KNOWN_SILENT = [9, 12, 17, 18, 19, 101]

// ── Helpers de throttle ───────────────────────────────────────────────────────
private static BigDecimal roundTo(def value, int places) {
    return (value as BigDecimal).setScale(places, BigDecimal.ROUND_HALF_UP)
}

private boolean aboveThreshold(String attr, def newVal, def threshold) {
    def prev = device.currentValue(attr)
    if (prev == null) return true
    return Math.abs((newVal as double) - (prev as double)) > (threshold as double)
}

private boolean throttleByMinute(String key) {
    String now = new Date().format('yyyy-MM-dd HH:mm')
    String last = state["throttle_${key}"] ?: ''
    if (last == now) return false
    state["throttle_${key}"] = now
    return true
}

// ── Parse principal ───────────────────────────────────────────────────────────
void parse(String description) {
    // lastSeen throttle por minuto — evita sendEvent a cada pacote Zigbee
    String now = new Date().format('yyyy-MM-dd HH:mm')
    if ((state.lastSeenMinute ?: '') != now) {
        state.lastSeenMinute = now
        sendEvent(name: 'lastSeen', value: now + ':00')
        setHealthStatus('online')
        runIn(600, 'markOffline', [overwrite: true])
    }

    if (logEnable) log.debug "${device.displayName} parse: ${description?.take(120)}"

    if (!description?.contains('EF00') && !description?.contains('ef00')) {
        if (description?.contains('attrId: 0001') || description?.contains('attrId:0001')) setHealthStatus('online')
        return
    }

    Map descMap = [:]
    try { descMap = zigbee.parseDescriptionAsMap(description) }
    catch (e) { log.warn "${device.displayName} parseDescriptionAsMap falhou: ${e.message}"; return }

    List rawData = descMap?.data
    if (!rawData || rawData.size() < 7) return

    List<Integer> raw = rawData.collect { it instanceof Integer ? it : Integer.parseInt(it.toString(), 16) }
    setHealthStatus('online')

    int dpId   = raw[2]
    int dpType = raw[3]
    int dpLen  = (raw[4] << 8) | raw[5]

    if (raw.size() < 6 + dpLen) {
        if (logEnable) log.debug "${device.displayName} payload incompleto dp=${dpId}"
        return
    }

    List<Integer> val = raw.subList(6, 6 + dpLen)
    if (logEnable) log.debug "${device.displayName} DP ${dpId} (0x${String.format('%02X',dpId)}) type=${dpType} len=${dpLen} val=${val.collect{String.format('%02X',it)}.join(' ')}"

    processDP(dpId, dpType, dpLen, val)
}

// ── Processamento de cada DP ──────────────────────────────────────────────────
void processDP(int dpId, int dpType, int dpLen, List<Integer> val) {
    float pThr = (settings?.powerThreshold   ?: 5.0f)  as float
    float iThr = (settings?.currentThreshold ?: 0.05f) as float
    float vThr = (settings?.voltageThreshold ?: 1.0f)  as float

    switch (dpId) {

        // ── Energia total (nativa do device) ─────────────────────────────────
        case DP_ENERGY_FWD:
            // Importação total — contador crescente nativo. Vai direto em 'energy'.
            BigDecimal eImp = roundTo(toUInt32(val) / 100.0f, 2)
            float prev = (device.currentValue('energy') ?: -1) as float
            if (eImp != prev) {
                sendEvent(name: 'energy', value: eImp, unit: 'kWh',
                          descriptionText: "energy (importada) = ${eImp} kWh")
                if (txtEnable) log.info "${device.displayName} Energia importada: ${eImp} kWh"
            }
            break

        case DP_ENERGY_REV:
            // Exportação total — contador crescente nativo. Vai em 'energyProduced'.
            BigDecimal eExp = roundTo(toUInt32(val) / 100.0f, 2)
            float prevE = (device.currentValue('energyProduced') ?: -1) as float
            if (eExp != prevE) {
                sendEvent(name: 'energyProduced', value: eExp, unit: 'kWh',
                          descriptionText: "energyProduced (exportada) = ${eExp} kWh")
                if (txtEnable) log.info "${device.displayName} Energia exportada: ${eExp} kWh"
            }
            break

        // ── Potência / FP / frequência totais ────────────────────────────────
        case DP_POWER_TOTAL:
            // Potência ativa total nativa (assinada: neg = exportando).
            BigDecimal pTot = roundTo(toInt32Signed(val), 0)
            if (aboveThreshold('power', pTot, pThr)) {
                sendEvent(name: 'power', value: pTot, unit: 'W',
                          descriptionText: "power = ${pTot} W (total, neg=exportando)")
                if (txtEnable) log.info "${device.displayName} Potência total: ${pTot} W"
            }
            break

        case DP_FREQUENCY:
            if (throttleByMinute('frequency')) {
                sendMeasurement('frequency', roundTo(toUInt32(val) / 100.0f, 2), 'Hz', 'Frequência')
            }
            break

        case DP_PF_TOTAL:
            if (throttleByMinute('powerFactor')) {
                sendMeasurement('powerFactor', toUInt32(val), '%', 'Fator Potência total')
            }
            break

        case DP_UPDATE_FREQ:
            int ri = toUInt32(val)
            sendEvent(name: 'reportInterval', value: ri, unit: 's')
            if (logEnable) log.debug "${device.displayName} Intervalo de report (DP102): ${ri}s"
            break

        // ── Fase L1 ──────────────────────────────────────────────────────────
        case DP_VOLTAGE_L1:
            float vL1 = toUInt32(val) / 10.0f
            if (vL1 > 0 && vL1 < 300 && aboveThreshold('voltageL1', vL1, vThr)) {
                sendMeasurement('voltageL1', roundTo(vL1, 1), 'V', 'Tensão L1')
                sendEvent(name: 'voltage', value: roundTo(vL1, 1), unit: 'V', descriptionText: "voltage = ${roundTo(vL1,1)} V (L1)")
            }
            break

        case DP_CURRENT_L1:
            // divisor oficial /1000
            BigDecimal iL1 = roundTo(toInt32Signed(val) / 1000.0f, 3)
            if (aboveThreshold('currentL1', iL1, iThr)) {
                sendMeasurement('currentL1', iL1, 'A', 'Corrente L1')
                sendEvent(name: 'amperage', value: iL1, unit: 'A', descriptionText: "amperage = ${iL1} A (L1)")
            }
            break

        case DP_POWER_L1:
            // raw, SEM divisor (assinada)
            BigDecimal pL1 = roundTo(toInt32Signed(val), 0)
            if (aboveThreshold('powerL1', pL1, pThr)) {
                sendMeasurement('powerL1', pL1, 'W', 'Potência L1')
                updatePowerMirror()
            }
            break

        case DP_PF_L1:
            if (throttleByMinute('powerFactorL1')) {
                sendMeasurement('powerFactorL1', toUInt32(val), '%', 'Fator Potência L1')
            }
            break

        case DP_ENERGY_L1:
            sendMeasurement('energyL1', roundTo(toUInt32(val) / 100.0f, 2), 'kWh', 'Energia importada L1')
            break

        case DP_ENERGY_PROD_L1:
            sendMeasurement('energyProducedL1', roundTo(toUInt32(val) / 100.0f, 2), 'kWh', 'Energia exportada L1')
            break

        // ── Fase L2 ──────────────────────────────────────────────────────────
        case DP_VOLTAGE_L2:
            float vL2 = toUInt32(val) / 10.0f
            if (vL2 > 0 && vL2 < 300 && aboveThreshold('voltageL2', vL2, vThr)) {
                sendMeasurement('voltageL2', roundTo(vL2, 1), 'V', 'Tensão L2')
            }
            break

        case DP_CURRENT_L2:
            BigDecimal iL2 = roundTo(toInt32Signed(val) / 1000.0f, 3)
            if (aboveThreshold('currentL2', iL2, iThr)) {
                sendMeasurement('currentL2', iL2, 'A', 'Corrente L2')
            }
            break

        case DP_POWER_L2:
            BigDecimal pL2 = roundTo(toInt32Signed(val), 0)
            if (aboveThreshold('powerL2', pL2, pThr)) {
                sendMeasurement('powerL2', pL2, 'W', 'Potência L2')
                updatePowerMirror()
            }
            break

        case DP_PF_L2:
            if (throttleByMinute('powerFactorL2')) {
                sendMeasurement('powerFactorL2', toUInt32(val), '%', 'Fator Potência L2')
            }
            break

        case DP_ENERGY_L2:
            sendMeasurement('energyL2', roundTo(toUInt32(val) / 100.0f, 2), 'kWh', 'Energia importada L2')
            break

        case DP_ENERGY_PROD_L2:
            sendMeasurement('energyProducedL2', roundTo(toUInt32(val) / 100.0f, 2), 'kWh', 'Energia exportada L2')
            break

        // ── Fallback: pacotes empacotados por fase (push puro) ───────────────
        case DP_PHASE_L1_PKT:
            if (settings?.usePushFallback != false) processPhasePacket('L1', dpLen, val, vThr, iThr, pThr)
            else if (logEnable) log.debug "${device.displayName} DP6 (pacote L1) ignorado (fallback off)"
            break

        case DP_PHASE_L2_PKT:
            if (settings?.usePushFallback != false) processPhasePacket('L2', dpLen, val, vThr, iThr, pThr)
            else if (logEnable) log.debug "${device.displayName} DP7 (pacote L2) ignorado (fallback off)"
            break

        default:
            if (dpId in DP_KNOWN_SILENT) {
                if (logEnable) log.debug "${device.displayName} DP conhecido (nao exposto): ${dpId} val=${val.collect{String.format('%02X',it)}.join(' ')}"
            } else if (logEnable) {
                log.debug "${device.displayName} DP nao mapeado: ${dpId} (0x${String.format('%02X',dpId)}) val=${val.collect{String.format('%02X',it)}.join(' ')}"
            }
            break
    }
}

// ── Fallback: extrai V/I/P de pacote empacotado DP6(L1)/DP7(L2) ───────────────
// Formato (8 bytes): [V uint16 BE /10][I uint24 BE /1000][P uint24 BE raw]
// Usado como fonte por push quando os DPs escalares (103+/112+) não chegam.
// Os mesmos guards de threshold dos escalares se aplicam, evitando duplicar
// eventos quando ambos (pacote e escalar) chegam no mesmo ciclo.
void processPhasePacket(String phase, int dpLen, List<Integer> val, float vThr, float iThr, float pThr) {
    if (dpLen != 8) {
        if (logEnable) log.debug "${device.displayName} pacote ${phase} com tamanho inesperado (${dpLen})"
        return
    }
    int vRaw = (val[0] << 8) | val[1]
    int iRaw = (val[2] << 16) | (val[3] << 8) | val[4]
    int pRaw = (val[5] << 16) | (val[6] << 8) | val[7]

    BigDecimal v = roundTo(vRaw / 10.0f, 1)
    BigDecimal i = roundTo(iRaw / 1000.0f, 3)
    BigDecimal p = roundTo(pRaw, 0)

    if (v > 0 && v < 300 && aboveThreshold("voltage${phase}", v, vThr)) {
        sendMeasurement("voltage${phase}", v, 'V', "Tensão ${phase} (pkt)")
        if (phase == 'L1') sendEvent(name: 'voltage', value: v, unit: 'V', descriptionText: "voltage = ${v} V (${phase})")
    }
    if (aboveThreshold("current${phase}", i, iThr)) {
        sendMeasurement("current${phase}", i, 'A', "Corrente ${phase} (pkt)")
        if (phase == 'L1') sendEvent(name: 'amperage', value: i, unit: 'A', descriptionText: "amperage = ${i} A (${phase})")
    }
    if (aboveThreshold("power${phase}", p, pThr)) {
        sendMeasurement("power${phase}", p, 'W', "Potência ${phase} (pkt)")
        updatePowerMirror()
    }
}

// ── Espelho de potência L1+L2 (diagnóstico) ───────────────────────────────────
// 'power' oficial vem do DP29 (total nativo). Este espelho ajuda a comparar
// a soma das fases com o total reportado pelo device.
void updatePowerMirror() {
    float pL1 = (device.currentValue('powerL1') ?: 0) as float
    float pL2 = (device.currentValue('powerL2') ?: 0) as float
    BigDecimal total = roundTo(pL1 + pL2, 0)
    float prev = (device.currentValue('powerTotal') ?: -99999) as float
    if (total == prev) return
    sendEvent(name: 'powerTotal', value: total, unit: 'W',
              descriptionText: "powerTotal (L1+L2) = ${total} W")
}

// ── Comando: gravar intervalo de report no device (DP102) ─────────────────────
def setUpdateFrequency(BigDecimal seconds) {
    int s = Math.max(5, Math.min(3600, (seconds ?: 30) as int))
    log.info "${device.displayName} Gravando update_frequency = ${s}s no device (DP102)"
    // DP102, tipo VALUE(0x02), len 4
    String hexVal = String.format('%08X', s)
    sendZigbeeCommands(sendTuyaDP(DP_UPDATE_FREQ, '02', hexVal))
}

// Monta comando Tuya EF00 set para um DP (datatype VALUE)
List<String> sendTuyaDP(int dp, String dpTypeHex, String valueHex) {
    String dpHex  = String.format('%02X', dp)
    int lenBytes  = valueHex.length() / 2
    String lenHex = String.format('%04X', lenBytes)
    int seq = (state.tuyaSeq = ((state.tuyaSeq ?: 0) + 1) & 0xFFFF)
    String seqHex = String.format('%04X', seq)
    String payload = seqHex + dpHex + dpTypeHex + lenHex + valueHex
    return zigbee.command(0xEF00, 0x00, payload)
}

// ── Helpers ───────────────────────────────────────────────────────────────────
void sendMeasurement(String name, def value, String unit, String label) {
    sendEvent(name: name, value: value, unit: unit, descriptionText: "${label}: ${value} ${unit}")
    if (txtEnable && name != 'powerTotal') log.info "${device.displayName} ${label}: ${value} ${unit}"
}

int toUInt32(List<Integer> b) {
    long v = 0L
    b.each { v = (v << 8) | (it & 0xFF) }
    return (int)(v & 0xFFFFFFFFL)
}

int toInt32Signed(List<Integer> b) {
    long raw = 0L
    b.each { raw = (raw << 8) | (it & 0xFF) }
    if (b.size() == 4 && (b[0] & 0x80)) raw = raw - 0x100000000L
    return (int) raw
}

// ── Health Status ─────────────────────────────────────────────────────────────
void setHealthStatus(String status) {
    if (device.currentValue('healthStatus') != status) sendEvent(name: 'healthStatus', value: status)
}

void markOffline() {
    setHealthStatus('offline')
    log.warn "${device.displayName} Sem comunicação há 10 minutos → offline"
}

// ── Refresh / Initialize ──────────────────────────────────────────────────────
def refresh() {
    if (logEnable) log.debug "${device.displayName} refresh()"
    sendZigbeeCommands(zigbee.command(0xEF00, 0x03, '000000'))
}

def initialize() {
    log.info "${device.displayName} initialize()"
    unschedule()
    state.clear()
    if (autoPollingEnabled) {
        runEvery1Minute('refresh')
        log.info "${device.displayName} Auto polling ativado (60s)"
    } else {
        log.info "${device.displayName} Auto polling desativado — usando Zigbee push"
    }
    sendTuyaMagic()
    runIn(3, 'refresh')
}

def updated() {
    log.info "${device.displayName} Preferências salvas"
    initialize()
    if (logEnable) runIn(1800, 'logsOff')
}

def installed() {
    log.info "${device.displayName} Instalado"
    sendEvent(name: 'healthStatus', value: 'unknown')
    runIn(5, 'initialize')
}

void logsOff() {
    device.updateSetting('logEnable', [value: 'false', type: 'bool'])
    log.info "${device.displayName} Debug logging desabilitado"
}

void sendTuyaMagic() {
    sendZigbeeCommands(["he raw 0x${device.deviceNetworkId} 0 0 0x0000 {10 00 00 04 00 00 00 02 00 00 00 00 00 00 00 00 00} {0x0000}"])
}

void sendZigbeeCommands(List<String> cmds) {
    if (cmds) {
        if (logEnable) log.debug "${device.displayName} cmds: ${cmds}"
        sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
    }
}