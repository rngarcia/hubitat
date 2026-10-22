/**
 *  Zemismart SDM02T – DP Sniffer (Ferramenta de diagnóstico)
 *
 *  Propósito: capturar todos os Data Points (DPs) brutos enviados pelo device
 *             via cluster EF00 (Tuya MCU). NÃO interpreta os valores —
 *             apenas loga tudo para identificar o mapeamento de DPs.
 *
 *  Instrução de uso:
 *    1. Instale este driver no device
 *    2. Habilite "Debug logging" nas preferências
 *    3. Abra o log do Hubitat (filtrado pelo device)
 *    4. Clique em "Refresh" no device ou aguarde reports automáticos
 *    5. Copie TODAS as linhas de log com "DP_SNIFFER" e envie para análise
 *
 *  Autor original: Ricardo Nogueira Garcia
 *  Versão: 1.0.0  –  2026-06-18
 */

metadata {
    definition(
        name: 'Zemismart SDM02T Sniffer',
        namespace: 'rng',
        author: 'Ricardo Nogueira Garcia',
        importUrl: ''
    ) {
        capability 'Refresh'
        capability 'Sensor'

        // Atributos raw para inspecionar no device page
        attribute 'lastDP',    'number'
        attribute 'lastType',  'string'
        attribute 'lastValue', 'string'
        attribute 'lastRaw',   'string'
        attribute 'dpLog',     'string'

        command 'sendTuyaMagic'
    }

    preferences {
        input name: 'logEnable',  type: 'bool', title: '<b>Debug logging</b>',  defaultValue: true
        input name: 'txtEnable',  type: 'bool', title: '<b>Description text logging</b>', defaultValue: true
    }
}

// ── Constantes Tuya EF00 ─────────────────────────────────────────────────────
@groovy.transform.Field static final int    CLUSTER_TUYA = 0xEF00
@groovy.transform.Field static final int    SETDATA      = 0x00
@groovy.transform.Field static final String DP_TYPE_BOOL   = '01'
@groovy.transform.Field static final String DP_TYPE_VALUE  = '02'
@groovy.transform.Field static final String DP_TYPE_STRING = '03'
@groovy.transform.Field static final String DP_TYPE_ENUM   = '04'
@groovy.transform.Field static final String DP_TYPE_BITMAP = '05'

@groovy.transform.Field static final Map DP_TYPE_NAMES = [
    '01': 'BOOL/RAW',
    '02': 'VALUE(int32)',
    '03': 'STRING',
    '04': 'ENUM',
    '05': 'BITMAP'
]

// ── Parse principal ───────────────────────────────────────────────────────────
void parse(String description) {
    if (logEnable) { log.debug "${device.displayName} parse() raw: ${description}" }

    // Tenta extrair catchall do cluster EF00
    if (description?.contains('cluster: EF00') || description?.contains('clusterId: EF00') ||
        description?.contains('0xEF00') || description?.contains('cluster: ef00') ) {
        parseTuyaCluster(description)
        return
    }

    // Fallback: tenta via parseDescriptionAsMap
    Map descMap = [:]
    try { descMap = zigbee.parseDescriptionAsMap(description) } catch (e) { return }

    if (descMap?.clusterInt == CLUSTER_TUYA || descMap?.cluster == 'EF00') {
        parseTuyaCluster(description)
    } else {
        if (logEnable) { log.debug "${device.displayName} [NON-TUYA] cluster=${descMap?.cluster} attrId=${descMap?.attrId} value=${descMap?.value}" }
    }
}

void parseTuyaCluster(String description) {
    Map descMap = [:]
    try { descMap = zigbee.parseDescriptionAsMap(description) } catch (e) {
        log.warn "${device.displayName} Falha ao parsear: ${description}"
        return
    }

    if (logEnable) { log.debug "${device.displayName} [EF00 raw descMap] ${descMap}" }

    // Tenta extrair DPs do campo 'data'
    String data = descMap?.data?.join('') ?: ''

    // command 01 = report, 02 = response
    String cmd = descMap?.command
    if (logEnable) { log.debug "${device.displayName} [EF00] command=${cmd} data=${data}" }

    if (data.length() < 14) {
        log.warn "${device.displayName} [EF00] data muito curta (${data.length()} chars): ${data}"
        return
    }

    // Formato: [seq 2B][cmd 1B][seq2 2B][dpId 1B][dpType 1B][len 2B][value Len*B]
    // Offset em nibbles (cada byte = 2 chars hex):
    // byte 0-1 = seq (4 chars), byte 2 = cmd (2 chars), byte 3-4 = seq2 (4 chars)
    // byte 5 = dpId (2 chars), byte 6 = dpType (2 chars), byte 7-8 = length (4 chars)
    // byte 9.. = value

    try {
        // O parseDescriptionAsMap às vezes já expõe dp, dpType, fnCmd, etc.
        // Tentamos ler diretamente
        int dpId    = -1
        int dpType  = -1
        int dpLen   = -1
        String dpValueHex = ''

        // Verifica se descMap já tem os campos parseados pelo HE
        if (descMap?.dp != null) {
            dpId   = descMap.dp as int
            dpType = descMap.dpType as int
            dpLen  = descMap.fnCmdLength as int ?: 0
            dpValueHex = descMap.fnCmd ?: ''
        } else {
            // Parse manual do payload raw
            // data é a lista de bytes como strings hex, juntados
            // Estrutura EF00: [seq MSB][seq LSB][status][seq2 MSB][seq2 LSB][dp][dpType][len MSB][len LSB][value...]
            List<String> byteList = []
            for (int i = 0; i < data.length() - 1; i += 2) {
                byteList << data.substring(i, i + 2)
            }
            if (byteList.size() < 7) {
                log.warn "${device.displayName} payload insuficiente: ${byteList}"
                return
            }
            // Pula os primeiros 5 bytes (seq + status + seq2)
            dpId       = Integer.parseInt(byteList[5], 16)
            dpType     = Integer.parseInt(byteList[6], 16)
            int lenMSB = Integer.parseInt(byteList[7], 16)
            int lenLSB = Integer.parseInt(byteList[8], 16)
            dpLen      = (lenMSB << 8) | lenLSB
            dpValueHex = byteList.drop(9).take(dpLen).join('')
        }

        if (dpId < 0) {
            log.warn "${device.displayName} dpId não encontrado"
            return
        }

        // Interpreta o valor conforme o tipo
        String typeName = DP_TYPE_NAMES[String.format('%02X', dpType)] ?: "UNKNOWN(${dpType})"
        long   rawLong  = dpValueHex ? Long.parseLong(dpValueHex, 16) : 0L

        String interpretado = interpretDP(dpId, dpType, dpValueHex, rawLong)

        // Loga em destaque para fácil identificação
        log.info "${device.displayName} ╔══ DP_SNIFFER ═══════════════════════════════╗"
        log.info "${device.displayName} ║  DP ID   : ${dpId} (0x${String.format('%02X', dpId)})"
        log.info "${device.displayName} ║  Tipo    : ${typeName}"
        log.info "${device.displayName} ║  Hex raw : ${dpValueHex}"
        log.info "${device.displayName} ║  Int raw : ${rawLong}"
        log.info "${device.displayName} ║  Interp. : ${interpretado}"
        log.info "${device.displayName} ╚══════════════════════════════════════════════╝"

        // Atualiza atributos no device page para consulta rápida
        sendEvent(name: 'lastDP',    value: dpId)
        sendEvent(name: 'lastType',  value: typeName)
        sendEvent(name: 'lastValue', value: rawLong.toString())
        sendEvent(name: 'lastRaw',   value: dpValueHex)

        // Acumula log de DPs únicos no atributo dpLog
        String atual = device.currentValue('dpLog') ?: ''
        String entrada = "dp${dpId}:${rawLong}"
        if (!atual.contains("dp${dpId}:")) {
            sendEvent(name: 'dpLog', value: (atual + " | ${entrada}").take(1000))
        }

    } catch (ex) {
        log.error "${device.displayName} Erro ao parsear DP: ${ex.message}"
        log.error "${device.displayName} data era: ${data}"
    }
}

// Tentativas de interpretação heurística para auxiliar identificação
String interpretDP(int dp, int type, String hex, long raw) {
    List<String> guesses = []

    // Heurísticas comuns para medidores bifásicos Tuya
    if (type == 0x02) {
        // VALUE (int32 big-endian)
        // Tensão: valor típico 2200–2400 → /10 = 220–240 V
        float divV = raw / 10.0
        if (divV >= 100 && divV <= 260)  { guesses << "≈ Tensão? ${divV} V (/10)" }

        // Potência: valor típico 0–50000 → /10 = 0–5000 W
        float divP10 = raw / 10.0
        if (divP10 >= 0 && divP10 <= 15000) { guesses << "≈ Potência? ${divP10} W (/10)" }

        // Corrente: valor típico 0–120000 → /1000 = 0–120 A
        float divA = raw / 1000.0
        if (divA >= 0 && divA <= 120)    { guesses << "≈ Corrente? ${divA} A (/1000)" }

        // Energia: valor típico 0–9999999 → /100 = kWh
        float divE = raw / 100.0
        if (divE >= 0 && divE <= 999999) { guesses << "≈ Energia? ${divE} kWh (/100)" }

        // Frequência: valor típico 5000 → /100 = 50 Hz
        float divF = raw / 100.0
        if (divF >= 45 && divF <= 65)    { guesses << "≈ Freq? ${divF} Hz (/100)" }

        // Fator de potência: /100 = %
        float divFP = raw / 100.0
        if (divFP >= 0 && divFP <= 100)  { guesses << "≈ FP? ${divFP} % (/100)" }
    } else if (type == 0x01) {
        guesses << (raw == 0 ? 'BOOL: false/off' : 'BOOL: true/on')
    } else if (type == 0x04) {
        guesses << "ENUM: ${raw}"
    }

    return guesses ? guesses.join(' | ') : "(sem heurística)"
}

// ── Refresh ──────────────────────────────────────────────────────────────────
def refresh() {
    if (logEnable) { log.debug "${device.displayName} refresh() → enviando query Tuya" }
    List<String> cmds = []
    // Tuya query all DPs
    cmds += zigbee.command(CLUSTER_TUYA, 0x03, '000000')
    sendZigbeeCommands(cmds)
}

// ── Magic packet (necessário para alguns devices Tuya) ────────────────────────
def sendTuyaMagic() {
    List<String> cmds = []
    cmds += "he raw 0x${device.deviceNetworkId} 0 0 0x0000 {10 00 00 04 00 00 00 02 00 00 00 00 00 00 00 00 00} {0x0000}"
    sendZigbeeCommands(cmds)
    log.info "${device.displayName} Magic packet enviado"
}

// ── Installed / Updated ───────────────────────────────────────────────────────
def installed() {
    log.info "${device.displayName} instalado — aguardando DPs..."
    sendEvent(name: 'dpLog', value: '')
    runIn(3, sendTuyaMagic)
    runIn(6, refresh)
}

def updated() {
    log.info "${device.displayName} preferências salvas"
    if (logEnable) { runIn(1800, 'logsOff') }
}

def logsOff() {
    device.updateSetting('logEnable', [value:'false', type:'bool'])
    log.info "${device.displayName} debug logging desabilitado automaticamente"
}

// ── Helper ────────────────────────────────────────────────────────────────────
void sendZigbeeCommands(List<String> cmds) {
    if (cmds) {
        if (logEnable) { log.debug "${device.displayName} sendZigbeeCommands: ${cmds}" }
        sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
    }
}