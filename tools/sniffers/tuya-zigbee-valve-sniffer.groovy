/**
 *  Tuya Zigbee Valve — DP Sniffer v1.2.0
 *
 *  ver. 1.0.0 2026-06-20 Ricardo Nogueira Garcia - versão inicial
 *  ver. 1.1.0 2026-06-20 Ricardo Nogueira Garcia - corrigido HubAction bug e parser
 *  ver. 1.2.0 2026-06-20 Ricardo Nogueira Garcia - parser corrigido para offset seq=2B real;
 *                         queryAllDPs envia cmd 0x03 e também força leitura DP a DP (0x00);
 *                         adicionado comando forceReportAll para forçar reports individuais
 */

import groovy.transform.Field

static String version() { '1.2.0' }

@Field static final int CLUSTER_TUYA = 0xEF00

@Field static final Map DP_NAMES = [
    '01': 'switch',
    '02': 'valve_state / timerState',
    '03': 'flow_state_%',
    '05': 'measuredValue_water',
    '06': 'unknown_06',
    '07': 'battery_% / countdown',
    '08': 'battery_state',
    '09': 'accumulated_usage_time_sec',
    '0A': 'weather_delay',
    '0B': 'timer_time_left_sec',
    '0C': 'work_state',
    '0D': 'smart_weather',
    '0F': 'last_valve_open_duration_sec',
    '65': 'irrigationStartTime',
    '66': 'irrigationEndTime',
    '67': 'cycleIrrigationNumTimes',
    '68': 'irrigationTarget',
    '69': 'cycleIrrigationInterval_min',
    '6C': 'battery_%',
    '6F': 'waterConsumed_litres',
    '72': 'lastIrrigationDuration_string',
]

@Field static final Map DP_TYPE_NAMES = [
    '01': 'BOOL',
    '02': 'VALUE(4B)',
    '03': 'STRING',
    '04': 'ENUM',
    '05': 'BITMAP',
]

// DPs a tentar ler individualmente no forceReportAll
// cobre toda a faixa conhecida da GiEX e valvulas TS0601
@Field static final List<Integer> PROBE_DPS = [
    0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A,
    0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11,
    0x65, 0x66, 0x67, 0x68, 0x69, 0x6A, 0x6B, 0x6C, 0x6D, 0x6E, 0x6F,
    0x70, 0x71, 0x72, 0x73
]

metadata {
    definition(
        name: 'Tuya Zigbee Valve SNIFFER',
        namespace: 'rgr_diag',
        author: 'Ricardo Nogueira Garcia',
        singleThreaded: true
    ) {
        capability 'Refresh'
        capability 'Valve'

        command 'openValve'
        command 'closeValve'
        command 'queryAllDPs',   [[name: 'Envia cmd 0x03 (queryAll Tuya)']]
        command 'forceReportAll',[[name: 'Sonda DPs 0x01-0x11 + 0x65-0x73 um a um (SETDATA com valor 0)']]

        attribute 'lastSniffed', 'string'
    }

    preferences {
        input name: 'logEnable', type: 'bool', title: '<b>Debug logging</b>', defaultValue: true
        input name: 'openDP',    type: 'enum',
              title: '<b>DP para abrir/fechar</b>',
              options: ['01': 'DP 01 (TS0601 genérico)', '02': 'DP 02 (GiEX sh1btabb)'],
              defaultValue: '01'
    }
}

// ── Comandos ────────────────────────────────────────────────────────────────

void refresh()    { queryAllDPs() }
void open()       { openValve() }
void close()      { closeValve() }

void openValve() {
    String dp = settings?.openDP ?: '01'
    log.warn "${device.displayName} [SNIFFER] >>> ABRIR válvula DP ${dp}=01"
    sendRawZigbee(buildTuyaCmd(dp, '01', '01'))
}

void closeValve() {
    String dp = settings?.openDP ?: '01'
    log.warn "${device.displayName} [SNIFFER] >>> FECHAR válvula DP ${dp}=00"
    sendRawZigbee(buildTuyaCmd(dp, '01', '00'))
}

void queryAllDPs() {
    log.warn "${device.displayName} [SNIFFER] >>> queryAllTuyaDP (cmd 0x03)"
    List<String> cmds = zigbee.command(CLUSTER_TUYA, 0x03, '0000')
    sendRawZigbee(cmds)
}

/**
 * Sonda cada DP individualmente mandando um SETDATA (cmd 0x00) com valor zero.
 * Muitos dispositivos respondem com o valor atual ao receber um set, mesmo
 * quando o queryAll (cmd 0x03) não funciona.
 * Os DPs são enviados com 300ms de intervalo para não sobrecarregar.
 */
void forceReportAll() {
    log.warn "${device.displayName} [SNIFFER] >>> forceReportAll — sondando ${PROBE_DPS.size()} DPs"
    List<String> cmds = []
    PROBE_DPS.each { dp ->
        String dpHex = String.format('%02X', dp)
        // Tenta como BOOL (01) primeiro — se o DP não existir, o device simplesmente ignora
        cmds += buildTuyaCmd(dpHex, '01', '00')
    }
    // Envia tudo de uma vez; o Hubitat gerencia o espaçamento
    sendRawZigbee(cmds)
}

// ── Parser ──────────────────────────────────────────────────────────────────

void parse(String description) {
    if (description == null) { return }
    if (logEnable) { log.debug "${device.displayName} [SNIFFER] RAW: ${description}" }

    Map descMap = [:]
    try { descMap = zigbee.parseDescriptionAsMap(description) }
    catch (e) { log.debug "${device.displayName} [SNIFFER] parse exception: ${e}"; return }

    String cluster = descMap.cluster ?: descMap.clusterId ?: ''
    if (cluster == 'EF00') { parseTuyaEF00(descMap); return }

    log.info "${device.displayName} [SNIFFER] OTHER cluster=${cluster} cmd=${descMap.command} attrId=${descMap.attrId} value=${descMap.value} data=${descMap.data}"
}

/**
 * Formato real confirmado pelos logs:
 *   data[0..1] = seq (2 bytes, ignorado)
 *   data[2]    = dp
 *   data[3]    = type
 *   data[4..5] = len (BE)
 *   data[6..]  = value (len bytes)
 *
 * Múltiplos DPs podem vir concatenados após o primeiro.
 * Após o primeiro DP (offset 6+len), os seguintes NÃO repetem o seq —
 * começam direto em dp/type/len/value (offset de 4 bytes por DP adicional).
 */
void parseTuyaEF00(Map descMap) {
    List<String> data = descMap.data
    if (data == null || data.size() < 2) {
        if (logEnable) { log.debug "${device.displayName} [SNIFFER] EF00 payload muito curto: ${data}" }
        return
    }

    if (logEnable) { log.debug "${device.displayName} [SNIFFER] EF00 data (${data.size()}B): ${data}" }

    // Primeiro DP: offset 2 (pula seq de 2 bytes)
    int idx = 2
    boolean first = true

    while (idx + 3 < data.size()) {
        int dp   = hx(data[idx])
        int type = hx(data[idx + 1])
        int lenH = hx(data[idx + 2])
        int lenL = hx(data[idx + 3])
        int len  = (lenH << 8) | lenL

        // Validação de sanidade
        if (type < 1 || type > 5 || len < 0 || len > 128) {
            if (first) {
                // Talvez não tenha seq — tenta offset 0
                if (logEnable) { log.debug "${device.displayName} [SNIFFER] offset=2 inválido, tentando offset=0" }
                parseFromOffset(data, 0)
                return
            }
            break
        }

        int end = idx + 4 + len - 1
        if (end >= data.size()) { break }

        List<String> valBytes = data[(idx + 4)..end]
        logDP(dp, type, len, valBytes)

        idx += 4 + len
        first = false
    }
}

void parseFromOffset(List<String> data, int startIdx) {
    int idx = startIdx
    while (idx + 3 < data.size()) {
        int dp   = hx(data[idx])
        int type = hx(data[idx + 1])
        int len  = (hx(data[idx + 2]) << 8) | hx(data[idx + 3])

        if (type < 1 || type > 5 || len < 0 || len > 128) { break }
        int end = idx + 4 + len - 1
        if (end >= data.size()) { break }

        logDP(dp, type, len, data[(idx + 4)..end])
        idx += 4 + len
    }
}

void logDP(int dp, int type, int len, List<String> valBytes) {
    String valHex  = valBytes.join('')
    long   valInt  = hexBytesToLong(valBytes)
    String valStr  = bytesToAscii(valBytes)
    String dpHex   = String.format('%02X', dp)
    String typeStr = DP_TYPE_NAMES[String.format('%02X', type)] ?: "type=0x${String.format('%02X', type)}"
    String dpName  = DP_NAMES[dpHex] ?: '???'

    log.warn "${device.displayName} [SNIFFER] ◀ dp=0x${dpHex}(${dp}) [${dpName}] | ${typeStr} | len=${len} | hex=${valHex} | int=${valInt} | ascii='${valStr}'"
    sendEvent(name: 'lastSniffed', value: "dp=0x${dpHex} [${dpName}] = ${valInt} (0x${valHex})")
}

// ── Helpers ─────────────────────────────────────────────────────────────────

int hx(String h) { Integer.parseInt(h ?: '00', 16) }

long hexBytesToLong(List<String> bytes) {
    long r = 0; bytes.each { r = (r << 8) | Integer.parseInt(it, 16) }; return r
}

String bytesToAscii(List<String> bytes) {
    try { return bytes.collect { b -> int c = Integer.parseInt(b,16); (c>=32&&c<127)?((char)c).toString():'.' }.join('') }
    catch (e) { return '?' }
}

String getPACKET_ID() { zigbee.convertToHexString(new Random().nextInt(65536), 4) }

List<String> buildTuyaCmd(String dp, String dpType, String fncmd) {
    return zigbee.command(
        CLUSTER_TUYA, 0x00, [:], 300,
        getPACKET_ID() + dp + dpType +
        zigbee.convertToHexString((int)(fncmd.length() / 2), 4) + fncmd
    )
}

void sendRawZigbee(List<String> cmds) {
    if (!cmds) { return }
    hubitat.device.HubMultiAction actions = new hubitat.device.HubMultiAction()
    cmds.each { cmd ->
        if (cmd instanceof List) { cmd.each { actions.add(new hubitat.device.HubAction(it.toString(), hubitat.device.Protocol.ZIGBEE)) } }
        else { actions.add(new hubitat.device.HubAction(cmd.toString(), hubitat.device.Protocol.ZIGBEE)) }
    }
    sendHubCommand(actions)
}

void installed()  { log.info "${device.displayName} [SNIFFER] v${version()} installed" }
void updated()    { log.info "${device.displayName} [SNIFFER] v${version()} updated — logEnable=${logEnable}" }
void initialize() { log.info "${device.displayName} [SNIFFER] v${version()} initialize" }