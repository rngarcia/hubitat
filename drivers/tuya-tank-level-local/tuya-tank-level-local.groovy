/**
 *  Tuya Tank Level Monitor (Wi-Fi) Local — Hubitat Driver
 *  Versão: 2.3.0 — Build: 2026-10-25
 *  Autor: Ricardo Nogueira Garcia
 *  Repositório: https://github.com/rngarcia/hubitat
 *  Gostou? Buy me a beer: https://www.paypal.com/donate/?business=rngarcia%40gmail.com&currency_code=USD
 *
 *  Lê sensores ultrassônicos de nível Tuya Wi-Fi (categoria "ywcgq") direto pela
 *  rede local, porta TCP 6668, sem passar pela nuvem Tuya.
 *
 *  Pré-requisitos: IP fixo do sensor, Device ID e Local Key. A Local Key muda a cada
 *  novo pareamento com o app Tuya / Smart Life. Para descobrir ID, key e versão:
 *    pip install tinytuya && python -m tinytuya wizard
 *
 *  Protocolos suportados: 3.3, 3.4 e 3.5. O 3.5 (firmware recente) usa AES-GCM, que
 *  o sandbox do Hubitat não expõe (GCMParameterSpec é bloqueado), por isso o GCM é
 *  implementado aqui sobre AES-ECB.
 *
 *  Implementação do protocolo baseada no TinyTuya (Jason Cox, MIT License):
 *    https://github.com/jasonacox/tinytuya
 *
 *  Alturas (mesma semântica do driver cloud "Tuya Component Tank Level Monitor"):
 *    installationHeight = distância do sensor até o FUNDO do tanque
 *    liquidDepthMax     = distância do sensor até o NÍVEL MÁXIMO de água
 *    liquidDepthCm      = altura da coluna de água, medida do fundo
 *    maxWaterHeightM    = installationHeight - liquidDepthMax (altura útil)
 *    liquidDepthMaxCalc = installationHeight - coluna atual (o que liquidDepthMax
 *                         deveria valer se a caixa estivesse cheia agora)
 *
 *  Filtro de leitura (v2.3.0): com a caixa enchendo, a turbulência e a espuma fazem o
 *  ultrassom devolver leituras espalhadas. O driver guarda as amostras da janela
 *  (preferência "Janela do filtro") e publica a MEDIANA, que ignora picos isolados:
 *    - durante a variação, só publica quando a mediana anda >= "Banda morta" (%)
 *      em duas amostras seguidas, no mesmo sentido;
 *    - quando o sensor fica quieto pela duração da janela, publica a mediana exata,
 *      mesmo que a diferença seja menor que a banda morta.
 *  level e liquidDepthCm saem da mesma amostra, então ficam coerentes entre si.
 *
 *  Changelog:
 *  v2.3.0 (2026-10-25) - RNG
 *    - Filtro de mediana com janela de tempo para level/liquidDepthCm, banda morta
 *      durante a variação e publicação do valor exato quando a leitura estabiliza
 *    - Atributo opcional levelRaw (leitura bruta), desligado por padrão
 *  v2.2.0 (2026-10-22) - RNG
 *    - liquidDepthMax passa a ser tratado como distância sensor -> nível máximo,
 *      como no driver cloud; volume usa installationHeight - liquidDepthMax
 *    - Calibração: um único comando, calibSetDepthMaxFromCurrentLevel, com a fórmula
 *      installationHeight - coluna atual (os três comandos anteriores gravavam valores errados)
 *    - Novos atributos liquidDepthMaxCalc e maxWaterHeightM
 *    - DPs 7, 8, 19 e 21 confirmados em sensor real; alguns firmwares só os incluem
 *      no status depois de escritos uma vez
 *  v2.1.0 (2026-10-22) - RNG
 *    - Camada de protocolo reescrita: suporte a 3.5 (frames 6699 + AES-GCM),
 *      negociação de sessão 3.4/3.5 corrigida, frames fragmentados, CRC32 próprio
 *    - DPs corrigidos para a categoria ywcgq (estado 1, profundidade 2, nível 22)
 *    - Conexão persistente opcional com heartbeat, e reconexão com espera
 *    - Removidos: protocolo 3.1 e resolução de hostname (o sandbox não permite DNS)
 *  v2.0.1 (2026-10-21) - RNG
 *    - Publicado em rngarcia/hubitat: cabeçalho padronizado, namespace "rng" e importUrl
 *  v2.0.0 (2026-05-21) - RNG
 *    - Primeira versão local (não funcionava: protocolo 3.5 ausente e DPs errados)
 *  v1.2.0 (2026-05-21) / v1.1.2 (2026-01-19) - RNG
 *    - Versões cloud (componente do Tuya IoT Platform)
 */

import groovy.transform.Field
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import hubitat.helper.HexUtils

@Field static final String DRIVER_VERSION = "2.3.0"
@Field static final Integer FILTER_MIN_SAMPLES = 3    // abaixo disso, espera estabilizar
@Field static final Integer FILTER_MAX_SAMPLES = 25   // teto do buffer, mesmo com janela longa

// DPs da categoria ywcgq. Alguns firmwares só reportam 7/8/19/21 depois de escritos uma vez.
@Field static final String DP_STATE          = "1"   // enum: normal / lower_alarm / upper_alarm
@Field static final String DP_DEPTH          = "2"   // altura da coluna d'água, m × 100
@Field static final String DP_UPPER_LIMIT    = "7"   // max_set, %
@Field static final String DP_LOWER_LIMIT    = "8"   // mini_set, %
@Field static final String DP_INSTALL_HEIGHT = "19"  // installation_height (sensor -> fundo), m × 1000
@Field static final String DP_DEPTH_MAX      = "21"  // liquid_depth_max (sensor -> nível máximo), m × 1000
@Field static final String DP_LEVEL          = "22"  // liquid_level_percent, %
@Field static final List<Integer> CONFIG_DPS = [7, 8, 19, 21]

@Field static final Integer TUYA_PORT = 6668

@Field static final Integer CMD_SESS_KEY_START  = 3
@Field static final Integer CMD_SESS_KEY_RESP   = 4
@Field static final Integer CMD_SESS_KEY_FINISH = 5
@Field static final Integer CMD_CONTROL         = 7
@Field static final Integer CMD_STATUS          = 8
@Field static final Integer CMD_HEART_BEAT      = 9
@Field static final Integer CMD_DP_QUERY        = 10
@Field static final Integer CMD_CONTROL_NEW     = 13
@Field static final Integer CMD_DP_QUERY_NEW    = 16
@Field static final Integer CMD_UPDATEDPS       = 18

// Comandos que não levam o cabeçalho "3.x" + 12 zeros no payload
@Field static final List<Integer> NO_VERSION_HEADER = [3, 4, 5, 9, 10, 16, 18]

@Field static final String PREFIX_55AA = "000055AA"
@Field static final String PREFIX_6699 = "00006699"

// Sessão por dispositivo: buffer de recepção, chave de sessão, sequência, fila
@Field static final Map<Long, Map> SESSIONS = new ConcurrentHashMap<Long, Map>()

metadata {
    definition(
        name: "Tuya Tank Level Monitor (Wi-Fi) Local",
        namespace: "rng",
        author: "Ricardo Nogueira Garcia",
        importUrl: "https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/tuya-tank-level-local/tuya-tank-level-local.groovy"
    ) {
        capability "Refresh"
        capability "Sensor"
        capability "Configuration"
        capability "PresenceSensor"   // "present" = sessão local ativa

        attribute "level",              "number"
        attribute "levelRaw",           "number"
        attribute "liquidDepthCm",      "number"
        attribute "liquidState",        "string"
        attribute "upperLimit",         "number"
        attribute "lowerLimit",         "number"
        attribute "installationHeight", "number"
        attribute "liquidDepthMax",     "number"
        attribute "liquidDepthMaxCalc", "number"
        attribute "maxWaterHeightM",    "number"
        attribute "tankCapacityLiters",      "number"
        attribute "volumeLiters",            "number"
        attribute "volumePercentOfCapacity", "number"
        attribute "tankTemplate",            "string"

        command "setUpperLimit",         [[name: "Percent", type: "NUMBER", description: "0..100"]]
        command "setLowerLimit",         [[name: "Percent", type: "NUMBER", description: "0..100"]]
        command "setInstallationHeight", [[name: "Meters",  type: "NUMBER", description: "Sensor até o fundo. Ex.: 0.680"]]
        command "setLiquidDepthMax",     [[name: "Meters",  type: "NUMBER", description: "Sensor até o nível máximo. Ex.: 0.230"]]
        // Usar com a caixa fisicamente CHEIA
        command "calibSetDepthMaxFromCurrentLevel"
        command "disconnect"
    }

    preferences {
        input name: "deviceHost", type: "text", title: "IP do sensor",
              description: "Reserve um IP fixo no roteador", required: true
        input name: "deviceId", type: "text", title: "Device ID", required: true
        input name: "localKey", type: "password", title: "Local Key (16 caracteres)",
              description: "Muda a cada novo pareamento com o app Tuya", required: true
        input name: "tuyaProtVersion", type: "enum", title: "Versão do protocolo",
              description: "Confira com: python -m tinytuya scan",
              options: ["33": "3.3", "34": "3.4", "35": "3.5"], defaultValue: "35", required: true
        input name: "persistent", type: "bool", title: "Manter conexão aberta",
              description: "Recebe as mudanças na hora (heartbeat a cada 20 s). Desligado: conecta só no polling.",
              defaultValue: true
        input name: "pollingInterval", type: "enum", title: "Intervalo de polling",
              options: ["0": "Desligado", "60": "1 minuto", "300": "5 minutos", "600": "10 minutos",
                        "900": "15 minutos", "1800": "30 minutos", "3600": "1 hora"],
              defaultValue: "300", required: true

        input name: "filterWindowSec", type: "enum", title: "Janela do filtro de nível",
              description: "Mediana das leituras desse intervalo. Também é o tempo de silêncio para publicar o valor final.",
              options: ["0": "Desligado (publica toda leitura)", "15": "15 segundos", "30": "30 segundos",
                        "60": "1 minuto", "120": "2 minutos"],
              defaultValue: "30", required: true
        input name: "levelDeadband", type: "number", title: "Banda morta durante a variação (%)",
              description: "Só publica antes de estabilizar se a mediana andar pelo menos isso. 0 = publica toda mediana nova.",
              defaultValue: 2
        input name: "publishRaw", type: "bool", title: "Publicar leitura bruta (levelRaw)",
              description: "Para diagnóstico: gera um evento por leitura, como antes do filtro", defaultValue: false

        input name: "enableVolumeCalc", type: "bool", title: "Calcular volume", defaultValue: false
        input name: "tankShape", type: "enum", title: "Formato do tanque",
              options: ["rectangular": "Retangular (C × L)", "cylVertical": "Cilíndrico vertical"],
              defaultValue: "rectangular"
        input name: "tankLengthCm",      type: "number",  title: "Comprimento (cm), retangular"
        input name: "tankWidthCm",       type: "number",  title: "Largura (cm), retangular"
        input name: "tankDiameterCm",    type: "number",  title: "Diâmetro (cm), cilíndrico"
        input name: "tankUsableHeightM", type: "decimal", title: "Altura útil (m), opcional",
              description: "Se vazio, usa installationHeight - liquidDepthMax do sensor"
        input name: "roundVolumeLiters", type: "number",  title: "Arredondar volume para múltiplos de N litros",
              defaultValue: 1

        input name: "txtEnable", type: "bool", title: "Log de descrição", defaultValue: true
        input name: "logEnable", type: "bool", title: "Log de debug (desliga sozinho em 30 min)", defaultValue: false
    }
}

void installed() {
    sendEvent(name: "presence", value: "not present")
    updated()
}

void updated() {
    logInfo "Configurado v${DRIVER_VERSION}"
    unschedule()
    closeSocket("configuração alterada")
    session().samples = []
    session().pendingDir = null
    if (logEnable) runIn(1800, "logsOff")
    schedulePolling()
    runIn(2, "refresh")
}

void configure() {
    updated()
}

void logsOff() {
    device.updateSetting("logEnable", [value: "false", type: "bool"])
    logInfo "Log de debug desligado"
}

private void schedulePolling() {
    Integer secs = (pollingInterval ?: "300") as Integer
    switch (secs) {
        case 0:    break
        case 60:   runEvery1Minute("refresh");    break
        case 300:  runEvery5Minutes("refresh");   break
        case 600:  runEvery10Minutes("refresh");  break
        case 900:  runEvery15Minutes("refresh");  break
        case 1800: runEvery30Minutes("refresh");  break
        default:   runEvery1Hour("refresh")
    }
}

void refresh() {
    Map s = session()
    // Nem todo firmware expõe os DPs de configuração localmente: pede uma vez por sessão
    if (!s.configRead && !s.configAsked) {
        s.configAsked = true
        enqueue("updatedps")
    }
    enqueue("status")
}

void disconnect() {
    unschedule("reconnect")
    closeSocket("pedido do usuário")
}

void setUpperLimit(Number pct) {
    enqueue("set", [(DP_UPPER_LIMIT): clampInt(pct, 0, 100)])
}

void setLowerLimit(Number pct) {
    enqueue("set", [(DP_LOWER_LIMIT): clampInt(pct, 0, 100)])
}

void setInstallationHeight(Number meters) {
    enqueue("set", [(DP_INSTALL_HEIGHT): clampInt((meters ?: 0) * 1000, 100, 3000)])
}

void setLiquidDepthMax(Number meters) {
    enqueue("set", [(DP_DEPTH_MAX): clampInt((meters ?: 0) * 1000, 100, 2900)])
}

/** Com a caixa cheia: liquidDepthMax = installationHeight - coluna atual. */
void calibSetDepthMaxFromCurrentLevel() {
    BigDecimal inst = currentDecimal("installationHeight")
    Integer cm = currentInteger("liquidDepthCm")
    if (inst == null || cm == null) {
        log.warn "[${device.displayName}] Faça um Refresh antes: faltam installationHeight ou liquidDepthCm"
        return
    }
    BigDecimal newMax = inst - cm / 100G
    if (newMax <= 0G) {
        log.warn "[${device.displayName}] Resultado ${newMax} m <= 0 (instalação ${inst} m, coluna ${cm} cm). Confira installationHeight e se a caixa está cheia."
        return
    }
    logInfo "Calibrando liquidDepthMax = ${inst} m - ${cm / 100G} m = ${newMax} m"
    setLiquidDepthMax(newMax)
}

/* ── Sessão e conexão ─────────────────────────────────────────────────────── */

private Map session() {
    Long id = device.id as Long
    Map s = SESSIONS[id]
    if (s == null) {
        s = [buf: "", ready: false, connecting: false, seq: 1, queue: [], key: null,
             localNonce: null, configRead: false, failures: 0]
        SESSIONS[id] = s
    }
    return s
}

private String ver() { tuyaProtVersion ?: "35" }

private byte[] realKey() { (localKey ?: "").replaceAll("&lt;", "<").getBytes("UTF-8") }

private byte[] activeKey() {
    Map s = session()
    return (ver() != "33" && s.key != null) ? (byte[]) s.key : realKey()
}

private void enqueue(String kind, Map dps = null) {
    Map s = session()
    s.queue << [kind: kind, dps: dps]
    if (s.ready) {
        flushQueue()
    } else if (!s.connecting) {
        openSocket()
    }
}

private void openSocket() {
    Map s = session()
    if (!deviceHost || !deviceId || realKey().length != 16) {
        log.error "[${device.displayName}] Preencha IP, Device ID e uma Local Key de 16 caracteres"
        s.queue.clear()
        return
    }
    s.connecting = true
    s.ready = false
    s.key = null
    s.buf = ""
    s.seq = 1
    s.configAsked = s.queue.any { it.kind == "updatedps" }
    try {
        logDebug "Conectando a ${deviceHost}:${TUYA_PORT} (v${ver()})"
        interfaces.rawSocket.connect(deviceHost, TUYA_PORT, byteInterface: true, readDelay: 150)
    } catch (e) {
        connectionFailed("falha ao conectar: ${e.message}")
        return
    }
    if (ver() == "33") {
        sessionReady()
    } else {
        byte[] nonce = randomBytes(16)
        s.localNonce = nonce
        writeFrame(CMD_SESS_KEY_START, nonce, realKey())
    }
    runIn(10, "responseTimeout")
}

private void sessionReady() {
    Map s = session()
    s.ready = true
    s.connecting = false
    s.failures = 0
    if (device.currentValue("presence") != "present") sendEvent(name: "presence", value: "present")
    logDebug "Sessão pronta"
    flushQueue()
    if (persistent != false) runIn(20, "heartbeat")
}

private void flushQueue() {
    Map s = session()
    while (s.queue) {
        Map item = s.queue.remove(0)
        sendRequest(item.kind as String, item.dps as Map)
    }
    runIn(10, "responseTimeout")
}

void heartbeat() {
    Map s = session()
    if (!s.ready) return
    sendRequest("hb")
    runIn(10, "responseTimeout")
    runIn(20, "heartbeat")
}

void responseTimeout() {
    if (session().connecting) {
        log.warn "[${device.displayName}] O sensor não completou a sessão. Confira a Local Key e a versão do protocolo."
    }
    connectionFailed("sem resposta do sensor")
}

void reconnect() {
    if (!session().ready && !session().connecting) refresh()
}

private void connectionFailed(String reason) {
    Map s = session()
    s.failures = (s.failures ?: 0) + 1
    closeSocket(reason)
    if (persistent != false) {
        Integer wait = Math.min(30 * (s.failures as Integer), 300)
        logDebug "Nova tentativa em ${wait}s"
        runIn(wait, "reconnect")
    }
}

private void closeSocket(String reason) {
    Map s = session()
    unschedule("heartbeat")
    unschedule("responseTimeout")
    if (s.ready || s.connecting) logDebug "Fechando conexão: ${reason}"
    s.ready = false
    s.connecting = false
    s.key = null
    s.buf = ""
    try { interfaces.rawSocket.close() } catch (ignored) { }
    if (device.currentValue("presence") != "not present") {
        sendEvent(name: "presence", value: "not present", descriptionText: reason)
    }
}

void socketStatus(String msg) {
    logDebug "socketStatus: ${msg}"
    if (msg.contains("error") || msg.contains("disconnect") || msg.contains("Broken pipe")) {
        if (session().ready || session().connecting) connectionFailed(msg)
    }
}

/* ── Envio ─────────────────────────────────────────────────────────────────── */

private void sendRequest(String kind, Map dps = null) {
    String v = ver()
    String devId = deviceId
    String ts = (now().intdiv(1000L)).toString()
    Integer cmd
    Map body

    switch (kind) {
        case "status":
            if (v == "33") {
                if (devId.length() == 22) {
                    // Dispositivos "device22" respondem ao CONTROL_NEW com a lista de DPs
                    cmd = CMD_CONTROL_NEW
                    body = [devId: devId, uid: devId, t: ts, dps: [(DP_STATE): null, (DP_DEPTH): null, (DP_LEVEL): null]]
                } else {
                    cmd = CMD_DP_QUERY
                    body = [gwId: devId, devId: devId, uid: devId, t: ts]
                }
            } else {
                cmd = CMD_DP_QUERY_NEW
                body = [:]
            }
            break
        case "updatedps":
            cmd = CMD_UPDATEDPS
            body = [dpId: CONFIG_DPS]
            break
        case "hb":
            cmd = CMD_HEART_BEAT
            body = [gwId: devId, devId: devId]
            break
        default:
            if (v == "33") {
                cmd = CMD_CONTROL
                body = [devId: devId, uid: devId, t: ts, dps: dps]
            } else {
                cmd = CMD_CONTROL_NEW
                body = [protocol: 5, t: ts as Long, data: [dps: dps]]
            }
    }
    String json = JsonOutput.toJson(body)
    logDebug "→ cmd ${cmd} ${json}"
    writeFrame(cmd, json.getBytes("UTF-8"), activeKey())
}

private void writeFrame(Integer cmd, byte[] json, byte[] key) {
    Map s = session()
    Integer seq = s.seq as Integer
    s.seq = seq + 1
    byte[] frame = buildFrame(ver(), cmd, seq, json, key, randomBytes(12))
    try {
        interfaces.rawSocket.sendMessage(HexUtils.byteArrayToHexString(frame))
    } catch (e) {
        connectionFailed("erro de envio: ${e.message}")
    }
}

/**
 * Monta um frame completo. 3.3: 55AA + CRC32; 3.4: 55AA + HMAC-SHA256;
 * 3.5: 6699 + AES-GCM, com o cabeçalho a partir do byte 4 como dado autenticado.
 */
static byte[] buildFrame(String v, Integer cmd, Integer seq, byte[] payload, byte[] key, byte[] iv) {
    byte[] header = concat("${v[0]}.${v[1]}".getBytes("UTF-8"), new byte[12])
    boolean withHeader = !(cmd in NO_VERSION_HEADER)
    if (v == "35") {
        byte[] plain = withHeader ? concat(header, payload) : payload
        byte[] head = concat(hex(PREFIX_6699), concat([0, 0] as byte[],
                      concat(int4(seq), concat(int4(cmd), int4(12 + plain.length + 16)))))
        byte[] aad = slice(head, 4, head.length)
        List gcm = gcmCrypt(key, iv, plain, aad, true)
        return concat(head, concat(iv, concat((byte[]) gcm[0], concat((byte[]) gcm[1], hex("00009966")))))
    }
    byte[] body
    if (v == "34") {
        body = aesEcb(key, withHeader ? concat(header, payload) : payload, true)
    } else {
        body = aesEcb(key, payload, true)
        if (withHeader) body = concat(header, body)
    }
    Integer trailer = (v == "34") ? 36 : 8
    byte[] head = concat(hex(PREFIX_55AA), concat(int4(seq), concat(int4(cmd), int4(body.length + trailer))))
    byte[] data = concat(head, body)
    byte[] check = (v == "34") ? hmacSha256(key, data) : int4((int) crc32(data))
    return concat(data, concat(check, hex("0000AA55")))
}

/* ── Recepção ──────────────────────────────────────────────────────────────── */

void parse(String hexData) {
    Map s = session()
    s.buf = (s.buf ?: "") + hexData.toUpperCase()
    while (true) {
        Map frame = extractFrame(s)
        if (frame == null) break
        handleFrame(frame)
    }
}

/** Retira do buffer o próximo frame completo, ou null se ainda faltam bytes. */
private Map extractFrame(Map s) {
    String buf = s.buf
    int start = findPrefix(buf)
    if (start < 0) {
        // Guarda o fim do buffer: o prefixo pode ter chegado partido
        s.buf = buf.length() > 6 ? buf.substring(buf.length() - 6) : buf
        return null
    }
    buf = buf.substring(start)
    s.buf = buf
    byte[] raw = HexUtils.hexStringToByteArray(buf)
    Map out = decodeFrame(ver(), raw, activeKey())
    if (out == null) return null
    s.buf = buf.substring((out.consumed as Integer) * 2)
    return out
}

private static int findPrefix(String buf) {
    for (int i = 0; i + 8 <= buf.length(); i += 2) {
        String p = buf.substring(i, i + 8)
        if (p == PREFIX_55AA || p == PREFIX_6699) return i
    }
    return -1
}

/**
 * Decodifica um frame no início de raw. Retorna [consumed, cmd, payload] ou null se incompleto.
 * payload sai já decifrado, sem retcode e sem o cabeçalho "3.x".
 */
static Map decodeFrame(String v, byte[] raw, byte[] key) {
    if (raw.length < 18) return null
    if (raw[2] == (byte) 0x66) {
        int len = uint4(raw, 14)
        if (len < 28 || len > 8192) return [consumed: 4, cmd: -1, error: "tamanho inválido"]
        int total = 18 + len + 4
        if (raw.length < total) return null
        int cmd = uint4(raw, 10)
        byte[] aad = slice(raw, 4, 18)
        byte[] iv = slice(raw, 18, 30)
        byte[] ct = slice(raw, 30, 18 + len - 16)
        byte[] tag = slice(raw, 18 + len - 16, 18 + len)
        List gcm = gcmCrypt(key, iv, ct, aad, false)
        if (!sameBytes((byte[]) gcm[1], tag)) return [consumed: total, cmd: cmd, error: "tag GCM inválida"]
        byte[] plain = (byte[]) gcm[0]
        if (plain.length >= 4) plain = slice(plain, 4, plain.length)   // retcode
        return [consumed: total, cmd: cmd, payload: stripVersionHeader(plain)]
    }
    int len = uint4(raw, 12)
    int trailer = (v == "34") ? 36 : 8
    if (len < trailer || len > 8192) return [consumed: 4, cmd: -1, error: "tamanho inválido"]
    int total = 16 + len
    if (raw.length < total) return null
    int cmd = uint4(raw, 8)
    int start = 20   // cabeçalho + retcode
    int end = total - trailer
    if (end <= start) return [consumed: total, cmd: cmd, payload: new byte[0]]
    byte[] body = slice(raw, start, end)
    if (v == "34") {
        byte[] expected = hmacSha256(key, slice(raw, 0, end))
        if (!sameBytes(expected, slice(raw, end, end + 32))) {
            return [consumed: total, cmd: cmd, error: "HMAC inválido"]
        }
    }
    body = stripVersionHeader(body)
    if (body.length > 0 && body[0] != (byte) '{') {
        try {
            body = stripVersionHeader(aesEcb(key, body, false))
        } catch (e) {
            return [consumed: total, cmd: cmd, error: "falha ao decifrar"]
        }
    }
    return [consumed: total, cmd: cmd, payload: body]
}

private void handleFrame(Map frame) {
    Map s = session()
    Integer cmd = frame.cmd as Integer
    if (frame.error) {
        log.warn "[${device.displayName}] Frame cmd ${cmd} descartado: ${frame.error}. Confira a Local Key e a versão."
        return
    }
    byte[] payload = (byte[]) frame.payload

    if (cmd == CMD_SESS_KEY_RESP) {
        finishNegotiation(payload)
        return
    }
    unschedule("responseTimeout")
    s.failures = 0
    if (cmd == CMD_HEART_BEAT) return

    String text = new String(payload, "UTF-8").trim()
    logDebug "← cmd ${cmd} ${text}"
    if (!text.startsWith("{")) {
        if (text.contains("data unvalid")) log.warn "[${device.displayName}] O sensor recusou a consulta: ${text}"
        closeIfOneShot()
        return
    }
    Map json
    try {
        json = (Map) new JsonSlurper().parseText(text)
    } catch (e) {
        logDebug "JSON inválido: ${e.message}"
        return
    }
    Map dps = (json.dps ?: json.data?.dps) as Map
    if (dps) processDps(dps)
    closeIfOneShot()
}

private void closeIfOneShot() {
    if (persistent == false && !session().queue) runIn(3, "disconnect")
}

private void finishNegotiation(byte[] payload) {
    Map s = session()
    byte[] localNonce = (byte[]) s.localNonce
    if (payload.length < 48 || localNonce == null) {
        connectionFailed("resposta de sessão inválida")
        return
    }
    byte[] remoteNonce = slice(payload, 0, 16)
    byte[] proof = slice(payload, 16, 48)
    if (!sameBytes(proof, hmacSha256(realKey(), localNonce))) {
        connectionFailed("Local Key não confere")
        log.error "[${device.displayName}] A Local Key não confere com a do sensor. Ela muda a cada novo pareamento."
        return
    }
    writeFrame(CMD_SESS_KEY_FINISH, hmacSha256(realKey(), remoteNonce), realKey())
    s.key = sessionKey(ver(), realKey(), localNonce, remoteNonce)
    sessionReady()
}

/** Chave de sessão: XOR dos nonces cifrado com a Local Key (ECB no 3.4, GCM no 3.5). */
static byte[] sessionKey(String v, byte[] realKey, byte[] localNonce, byte[] remoteNonce) {
    byte[] x = new byte[16]
    for (int i = 0; i < 16; i++) x[i] = (byte) (localNonce[i] ^ remoteNonce[i])
    if (v == "34") return aesEcb(realKey, x, true, false)
    return (byte[]) gcmCrypt(realKey, slice(localNonce, 0, 12), x, new byte[0], true)[0]
}

private void processDps(Map dps) {
    boolean touched = false
    Integer rawPct = null
    Integer rawCm = null
    dps.each { k, v ->
        switch (k as String) {
            case DP_LEVEL:
                rawPct = clampInt(v, 0, 100)
                break
            case DP_DEPTH:
                rawCm = clampInt(v, 0, 100000)
                break
            case DP_STATE:
                if (emit("liquidState", v as String, null)) logDesc "Estado: ${v}"
                break
            case DP_UPPER_LIMIT:
                session().configRead = true
                if (emit("upperLimit", clampInt(v, 0, 100), "%")) logDesc "Limite superior: ${v}%"
                break
            case DP_LOWER_LIMIT:
                session().configRead = true
                if (emit("lowerLimit", clampInt(v, 0, 100), "%")) logDesc "Limite inferior: ${v}%"
                break
            case DP_INSTALL_HEIGHT:
                session().configRead = true
                BigDecimal ih = (v as BigDecimal) / 1000G
                if (emit("installationHeight", ih, "m")) logDesc "Altura de instalação: ${ih} m"
                touched = true
                break
            case DP_DEPTH_MAX:
                session().configRead = true
                BigDecimal dm = (v as BigDecimal) / 1000G
                if (emit("liquidDepthMax", dm, "m")) logDesc "Profundidade máxima: ${dm} m"
                touched = true
                break
            default:
                logDebug "DP sem mapeamento: ${k} = ${v}"
        }
    }
    if (touched) {
        recalcDerived()
        recalcVolume()
    }
    if (rawPct != null || rawCm != null) addSample(rawPct, rawCm)
}

/* ── Filtro de nível ───────────────────────────────────────────────────────── */

private Integer filterWindow() {
    try { return (filterWindowSec ?: "30") as Integer } catch (ignored) { return 30 }
}

/** Guarda a leitura bruta e decide se publica agora ou espera estabilizar. */
private void addSample(Integer pct, Integer cm) {
    Map s = session()
    // Uma mensagem pode trazer só o DP 22 ou só o DP 2: completa com o último bruto
    if (pct == null) pct = s.rawLevel as Integer
    if (cm == null) cm = s.rawCm as Integer
    s.rawLevel = pct
    s.rawCm = cm
    if (pct == null) return
    if (publishRaw) emit("levelRaw", pct, "%")

    Integer win = filterWindow()
    if (win <= 0) {
        publishLevel(pct, cm)
        return
    }
    long t = now()
    List buf = ((s.samples ?: []) as List).findAll { (t - (it.t as Long)) <= win * 1000L }
    buf << [t: t, level: pct, cm: cm]
    while (buf.size() > FILTER_MAX_SAMPLES) buf.remove(0)
    s.samples = buf

    if (buf.size() >= FILTER_MIN_SAMPLES) publishFiltered(false)
    // Sem leitura nova durante a janela inteira = estabilizou: publica o valor exato
    runIn(win, "filterSettled")
}

void filterSettled() {
    publishFiltered(true)
}

/** Publica a mediana do buffer. Antes de estabilizar, respeita a banda morta. */
private void publishFiltered(boolean settled) {
    List buf = session().samples as List
    if (!buf) return
    List sorted = buf.sort(false) { it.level as Integer }
    Map med = (Map) sorted[(sorted.size() - 1).intdiv(2)]
    Integer pct = med.level as Integer
    Integer cm = med.cm as Integer
    Map s = session()
    if (!settled) {
        Integer cur = currentInteger("level")
        Integer band = clampInt(levelDeadband ?: 0, 0, 100)
        if (cur != null) {
            if (Math.abs(pct - cur) < band) {
                s.pendingDir = null
                return
            }
            // Confirmação: duas medianas seguidas fora da banda, no mesmo sentido
            Integer dir = pct > cur ? 1 : -1
            if (s.pendingDir != dir) {
                s.pendingDir = dir
                return
            }
        }
    }
    s.pendingDir = null
    if (settled) logDebug "Leitura estável: mediana ${pct}% de ${buf.size()} amostra(s)"
    publishLevel(pct, cm)
}

private void publishLevel(Integer pct, Integer cm) {
    boolean touched = false
    if (pct != null && emit("level", pct, "%")) {
        logDesc "Nível: ${pct}%"
        touched = true
    }
    if (cm != null && emit("liquidDepthCm", cm, "cm")) {
        logDesc "Coluna d'água: ${cm} cm"
        touched = true
    }
    if (touched) {
        recalcDerived()
        recalcVolume()
    }
}

private void recalcDerived() {
    BigDecimal inst = currentDecimal("installationHeight")
    BigDecimal max = currentDecimal("liquidDepthMax")
    Integer cm = currentInteger("liquidDepthCm")
    if (inst != null && cm != null) {
        BigDecimal calc = [inst - cm / 100G, 0G].max().setScale(3, BigDecimal.ROUND_HALF_UP)
        emit("liquidDepthMaxCalc", calc, "m")
    }
    if (inst != null && max != null && inst > max) {
        emit("maxWaterHeightM", (inst - max).setScale(3, BigDecimal.ROUND_HALF_UP), "m")
    }
}

/** Altura útil: preferência manual, ou installationHeight - liquidDepthMax. */
private BigDecimal usableHeight() {
    if (tankUsableHeightM != null) return tankUsableHeightM as BigDecimal
    BigDecimal inst = currentDecimal("installationHeight")
    BigDecimal max = currentDecimal("liquidDepthMax")
    if (inst == null || max == null || inst <= max) return null
    return (inst - max).setScale(3, BigDecimal.ROUND_HALF_UP)
}

/* ── Volume ────────────────────────────────────────────────────────────────── */

void recalcVolume() {
    if (enableVolumeCalc) {
        Integer depthCm = currentInteger("liquidDepthCm")
        BigDecimal height = usableHeight()
        BigDecimal area = areaM2()
        if (depthCm != null && height && area) {
            BigDecimal depthM = [[depthCm / 100G, 0G].max(), height].min()
            BigDecimal capL = area * height * 1000G
            BigDecimal volL = area * depthM * 1000G
            Integer step = clampInt(roundVolumeLiters ?: 1, 1, 100000)
            BigDecimal volRounded = ((volL / step).setScale(0, BigDecimal.ROUND_HALF_UP)) * step
            emit("tankCapacityLiters", capL.setScale(0, BigDecimal.ROUND_HALF_UP), "L")
            emit("volumeLiters", volRounded.setScale(0, BigDecimal.ROUND_HALF_UP), "L")
            emit("volumePercentOfCapacity", (volL / capL * 100G).setScale(1, BigDecimal.ROUND_HALF_UP), "%")
        }
    }
    updateTemplate()
}

private BigDecimal areaM2() {
    if (tankShape == "cylVertical") {
        if (!tankDiameterCm) return null
        BigDecimal r = (tankDiameterCm as BigDecimal) / 200G
        return (Math.PI as BigDecimal) * r * r
    }
    if (!tankLengthCm || !tankWidthCm) return null
    return (tankLengthCm as BigDecimal) * (tankWidthCm as BigDecimal) / 10000G
}

private void updateTemplate() {
    def lvl = latest("level")
    def depth = latest("liquidDepthCm")
    def vol = latest("volumeLiters")
    def cap = latest("tankCapacityLiters")
    String st = latest("liquidState") ?: "--"
    String t = "${lvl ?: '--'}% • ${depth ?: '--'} cm"
    if (enableVolumeCalc) t += " • ${vol ?: '--'} L / ${cap ?: '--'} L"
    emit("tankTemplate", t + " • ${st}", null)
}

/* ── Criptografia ──────────────────────────────────────────────────────────── */

static byte[] aesEcb(byte[] key, byte[] data, boolean encrypt, boolean pkcs7 = true) {
    Cipher c = Cipher.getInstance(pkcs7 ? "AES/ECB/PKCS5Padding" : "AES/ECB/NoPadding")
    c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"))
    return c.doFinal(data)
}

/**
 * AES-GCM (tag de 128 bits) montado sobre AES-ECB: CTR a partir de J0+1 e GHASH em GF(2^128).
 * Retorna [saída, tag]. Na decifragem, a tag devolvida deve ser comparada com a recebida.
 */
static List gcmCrypt(byte[] key, byte[] iv, byte[] input, byte[] aad, boolean encrypt) {
    Cipher c = Cipher.getInstance("AES/ECB/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"))
    long[] h = toLongs(c.doFinal(new byte[16]), 0)
    byte[] j0 = new byte[16]
    for (int i = 0; i < 12; i++) j0[i] = iv[i]
    j0[15] = 1
    byte[] counter = slice(j0, 0, 16)
    byte[] out = new byte[input.length]
    for (int off = 0; off < input.length; off += 16) {
        incrementCounter(counter)
        byte[] ks = c.doFinal(counter)
        int n = Math.min(16, input.length - off)
        for (int i = 0; i < n; i++) out[off + i] = (byte) (input[off + i] ^ ks[i])
    }
    byte[] cipherText = encrypt ? out : input
    long[] x = [0L, 0L] as long[]
    x = ghashBlocks(x, h, aad)
    x = ghashBlocks(x, h, cipherText)
    long[] lens = [((long) aad.length) * 8L, ((long) cipherText.length) * 8L] as long[]
    x = gfMul([x[0] ^ lens[0], x[1] ^ lens[1]] as long[], h)
    byte[] ekj0 = c.doFinal(j0)
    byte[] s = fromLongs(x)
    byte[] tag = new byte[16]
    for (int i = 0; i < 16; i++) tag[i] = (byte) (s[i] ^ ekj0[i])
    return [out, tag]
}

private static long[] ghashBlocks(long[] x, long[] h, byte[] data) {
    for (int off = 0; off < data.length; off += 16) {
        byte[] block = new byte[16]
        int n = Math.min(16, data.length - off)
        for (int i = 0; i < n; i++) block[i] = data[off + i]
        long[] b = toLongs(block, 0)
        x = gfMul([x[0] ^ b[0], x[1] ^ b[1]] as long[], h)
    }
    return x
}

/** Multiplicação em GF(2^128) com o polinômio do GCM (NIST SP 800-38D, algoritmo 1). */
private static long[] gfMul(long[] x, long[] y) {
    long zHi = 0L, zLo = 0L
    long vHi = y[0], vLo = y[1]
    long r = 0xE1L << 56
    for (int i = 0; i < 128; i++) {
        long bit = (i < 64) ? ((x[0] >>> (63 - i)) & 1L) : ((x[1] >>> (127 - i)) & 1L)
        if (bit == 1L) { zHi ^= vHi; zLo ^= vLo }
        boolean lsb = (vLo & 1L) == 1L
        vLo = (vLo >>> 1) | (vHi << 63)
        vHi = vHi >>> 1
        if (lsb) vHi ^= r
    }
    return [zHi, zLo] as long[]
}

private static void incrementCounter(byte[] counter) {
    for (int i = 15; i >= 12; i--) {
        counter[i] = (byte) (counter[i] + 1)
        if (counter[i] != 0) break
    }
}

private static long[] toLongs(byte[] b, int off) {
    long hi = 0L, lo = 0L
    for (int i = 0; i < 8; i++) {
        hi = (hi << 8) | (b[off + i] & 0xFF)
        lo = (lo << 8) | (b[off + 8 + i] & 0xFF)
    }
    return [hi, lo] as long[]
}

private static byte[] fromLongs(long[] v) {
    byte[] out = new byte[16]
    for (int i = 0; i < 8; i++) {
        out[i] = (byte) (v[0] >>> (56 - 8 * i))
        out[8 + i] = (byte) (v[1] >>> (56 - 8 * i))
    }
    return out
}

static byte[] hmacSha256(byte[] key, byte[] data) {
    Mac mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}

static long crc32(byte[] data) {
    long crc = 0xFFFFFFFFL
    for (byte b : data) {
        crc ^= (b & 0xFF)
        for (int k = 0; k < 8; k++) crc = ((crc & 1L) != 0L) ? ((crc >>> 1) ^ 0xEDB88320L) : (crc >>> 1)
    }
    return crc ^ 0xFFFFFFFFL
}

/* ── Utilitários ───────────────────────────────────────────────────────────── */

private static byte[] stripVersionHeader(byte[] b) {
    if (b.length >= 15 && b[0] == (byte) '3' && b[1] == (byte) '.' && b[3] != (byte) '{') {
        return slice(b, 15, b.length)
    }
    return b
}

private static byte[] randomBytes(int n) {
    byte[] b = new byte[n]
    new Random().nextBytes(b)
    return b
}

private static byte[] concat(byte[] a, byte[] b) {
    byte[] out = new byte[a.length + b.length]
    for (int i = 0; i < a.length; i++) out[i] = a[i]
    for (int i = 0; i < b.length; i++) out[a.length + i] = b[i]
    return out
}

// java.util.Arrays e System.arraycopy não são permitidos no sandbox do Hubitat
private static byte[] slice(byte[] b, int from, int to) {
    byte[] out = new byte[to - from]
    for (int i = from; i < to; i++) out[i - from] = b[i]
    return out
}

private static boolean sameBytes(byte[] a, byte[] b) {
    if (a.length != b.length) return false
    int diff = 0
    for (int i = 0; i < a.length; i++) diff |= (a[i] ^ b[i])
    return diff == 0
}

private static byte[] hex(String s) { HexUtils.hexStringToByteArray(s) }

private static byte[] int4(int v) {
    return [(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v] as byte[]
}

private static int uint4(byte[] b, int off) {
    return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF)
}

private static Integer clampInt(def v, int min, int max) {
    Integer n
    try { n = (v as BigDecimal).setScale(0, BigDecimal.ROUND_HALF_UP).intValue() } catch (ignored) { return min }
    return Math.max(min, Math.min(max, n))
}

private Integer currentInteger(String attr) {
    def v = latest(attr)
    return (v == null) ? null : clampInt(v, Integer.MIN_VALUE, Integer.MAX_VALUE)
}

private BigDecimal currentDecimal(String attr) {
    def v = latest(attr)
    try { return (v == null) ? null : (v as BigDecimal) } catch (ignored) { return null }
}

private def latest(String name) {
    return (state.latest?.containsKey(name)) ? state.latest[name] : device.currentValue(name)
}

private boolean emit(String name, Object value, String unit) {
    if (latest(name)?.toString() == value?.toString()) return false
    // currentValue() não enxerga eventos enviados na mesma execução
    if (state.latest == null) state.latest = [:]
    state.latest[name] = value?.toString()
    Map evt = [name: name, value: value]
    if (unit) evt.unit = unit
    sendEvent(evt)
    return true
}

private void logDebug(String msg) { if (logEnable) log.debug "[${device.displayName}] ${msg}" }
private void logInfo(String msg)  { log.info "[${device.displayName}] ${msg}" }
private void logDesc(String msg)  { if (txtEnable) log.info "[${device.displayName}] ${msg}" }
