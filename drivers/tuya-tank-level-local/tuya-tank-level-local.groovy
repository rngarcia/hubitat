/**
 *  Tuya Tank Level Monitor (Wi-Fi) Local — Hubitat Driver
 *  Versão: 2.0.1 — Build: 2026-10-21
 *  Autor: Ricardo Nogueira Garcia
 *  Repositório: https://github.com/rngarcia/hubitat
 *  Gostou? Buy me a beer: https://www.paypal.com/donate/?business=rngarcia%40gmail.com&currency_code=USD
 *
 *  Changelog:
 *  v2.0.1 (2026-10-21) - RNG
 *    - Publicado em rngarcia/hubitat: cabeçalho padronizado, namespace "rng" e importUrl
 *  (histórico anterior no bloco de comentário abaixo)
 */
/**
 * ╔══════════════════════════════════════════════════════════════════════════════╗
 * ║   Tuya Tank Level Monitor (Wi-Fi) — Local Protocol Driver (Hubitat)        ║
 * ╚══════════════════════════════════════════════════════════════════════════════╝
 *
 *  Comunicação DIRETA com o dispositivo via rede local (sem Tuya Cloud).
 *  Utiliza o protocolo local Tuya na porta 6668, com criptografia AES-128-ECB.
 *  Compatível com versões de protocolo 3.1, 3.3 e 3.4.
 *
 * ──────────────────────────────────────────────────────────────────────────────
 *  PRÉ-REQUISITOS
 * ──────────────────────────────────────────────────────────────────────────────
 *  Para comunicação local, você precisa de três valores do dispositivo.
 *  Use o utilitário TinyTuya para obtê-los:
 *    • IP ou hostname do dispositivo
 *    • Device ID  (devId / gwId)
 *    • Local Key  (chave AES de 16 bytes, atribuída no momento do pareamento)
 *
 *  Instalação do TinyTuya:
 *    pip install tinytuya
 *    python -m tinytuya wizard
 *    Referência: https://github.com/jasonacox/tinytuya
 *
 *  ATENÇÃO: A Local Key muda toda vez que o dispositivo é re-pareado com o
 *           aplicativo Tuya / Smart Life. Se a comunicação parar de funcionar,
 *           re-execute o wizard do TinyTuya e atualize a chave nas preferências.
 *
 * ──────────────────────────────────────────────────────────────────────────────
 *  MAPEAMENTO DPS (Data Points)
 * ──────────────────────────────────────────────────────────────────────────────
 *  Os DPS abaixo são os típicos desta classe de dispositivo.
 *  Se o seu firmware usar números diferentes, ajuste as constantes DPS_* abaixo.
 *
 *    DPS  1  / liquid_level_percent  (0..100)      → level (%)
 *    DPS  3  / liquid_depth          (m, scale 2)  → liquidDepthCm (cm)
 *    DPS  5  / liquid_state          (enum)        → liquidState
 *    DPS 101 / max_set               (0..100)      → upperLimit (%)
 *    DPS 102 / mini_set              (0..100)      → lowerLimit (%)
 *    DPS 104 / installation_height   (m, scale 3)  → installationHeight (m)
 *    DPS 106 / liquid_depth_max      (m, scale 3)  → liquidDepthMax (m)
 *
 * ──────────────────────────────────────────────────────────────────────────────
 *  CÁLCULO DE VOLUME (opcional)
 * ──────────────────────────────────────────────────────────────────────────────
 *  Suporta tanques retangulares e cilíndricos verticais.
 *  Gera os atributos: volumeLiters, tankCapacityLiters,
 *                     volumePercentOfCapacity, tankTemplate
 *
 * ──────────────────────────────────────────────────────────────────────────────
 *  COMANDOS DE CALIBRAÇÃO (escrevem DPS no dispositivo)
 * ──────────────────────────────────────────────────────────────────────────────
 *  setUpperLimit / setLowerLimit / setInstallationHeight / setLiquidDepthMax
 *  calibSetUpperFromCurrentLevel / calibSetLowerFromCurrentLevel
 *  calibSetDepthMaxFromCurrentDepth / calibSetDepthMaxFromInstallationHeight
 *  calibSetInstallationHeightFromDepthMax
 *
 * ──────────────────────────────────────────────────────────────────────────────
 *  VERSÃO E HISTÓRICO
 * ──────────────────────────────────────────────────────────────────────────────
 *  Version: 2.0.0 (2026-05-21)
 *
 *  Changelog:
 *    2.0.0 (2026-05-21) - Reescrita completa como driver standalone com protocolo
 *                         local Tuya (sem cloud / sem parent driver). Adiciona
 *                         conexão rawSocket na porta 6668, criptografia AES-128-ECB,
 *                         suporte a versões 3.1 / 3.3 / 3.4 (incluindo negociação
 *                         de chave de sessão para 3.4), polling configurável,
 *                         capability PresenceSensor, suporte a hostname,
 *                         auto-reconnect, heartbeat e lógica de retry.
 *                         Cálculo de volume, comandos de calibração e template
 *                         de dashboard mantidos da v1.x.
 *    1.2.0 (2026-05-21) - (cloud/child) Polling configurável.
 *    1.1.2 (2026-01-19) - (cloud/child) Lançamento público inicial.
 *
 *  Implementação do protocolo baseada no trabalho de Ivar Holand:
 *    https://github.com/ivarho/hubitatappndevice (Apache 2.0 License)
 */

// ──────────────────────────────────────────────────────────────────────────────
// IMPORTS
// Bibliotecas necessárias para criptografia, JSON, sockets e helpers do Hubitat.
// ──────────────────────────────────────────────────────────────────────────────
import groovy.transform.Field
import groovy.json.JsonSlurper
import groovy.json.JsonBuilder
import groovy.json.JsonOutput
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import hubitat.device.HubAction
import hubitat.device.Protocol
import hubitat.helper.HexUtils

// ──────────────────────────────────────────────────────────────────────────────
// CONSTANTES GLOBAIS — IDENTIFICAÇÃO DO DRIVER
// Usadas em logs de ciclo de vida (installed / updated).
// ──────────────────────────────────────────────────────────────────────────────
@Field static final String DRIVER_VERSION = "2.0.1"
@Field static final String DRIVER_DATE    = "2026-05-21"

// ──────────────────────────────────────────────────────────────────────────────
// CONSTANTES GLOBAIS — MAPEAMENTO DE DPS
// Cada constante mapeia um atributo Hubitat ao número de DPS do protocolo Tuya.
// Se o seu dispositivo usar números diferentes, altere apenas aqui.
// ──────────────────────────────────────────────────────────────────────────────
@Field static final String DPS_LEVEL          = "1"    // Nível em % (0..100)
@Field static final String DPS_DEPTH          = "3"    // Profundidade em metros (scale 2, ex: 125 = 1,25m)
@Field static final String DPS_STATE          = "5"    // Estado: normal / lower_alarm / upper_alarm
@Field static final String DPS_UPPER_LIMIT    = "101"  // Limite superior de alarme (%)
@Field static final String DPS_LOWER_LIMIT    = "102"  // Limite inferior de alarme (%)
@Field static final String DPS_INSTALL_HEIGHT = "104"  // Altura de instalação do sensor (scale 3, ex: 3000 = 3,000m)
@Field static final String DPS_DEPTH_MAX      = "106"  // Profundidade máxima configurada (scale 3)

// ──────────────────────────────────────────────────────────────────────────────
// CONSTANTES GLOBAIS — PROTOCOLO TUYA LOCAL
// Definem a estrutura de frame, porta, timeouts e número máximo de tentativas.
// O prefixo/sufixo são os "magic bytes" que delimitam cada frame Tuya.
// ──────────────────────────────────────────────────────────────────────────────
@Field static final Integer TUYA_PORT        = 6668          // Porta TCP do protocolo local
@Field static final String  TUYA_PREFIX      = "000055AA"    // Início de frame (magic bytes)
@Field static final String  TUYA_SUFFIX      = "0000AA55"    // Fim de frame (magic bytes)
@Field static final Integer TUYA_HEADER_LEN  = 16            // Tamanho do cabeçalho em bytes
@Field static final Integer SEND_TIMEOUT_MS  = 2000          // Timeout de resposta (ms)
@Field static final Integer MAX_RETRIES      = 3             // Tentativas antes de fechar o socket

// ──────────────────────────────────────────────────────────────────────────────
// CONSTANTES GLOBAIS — TIPOS DE FRAME (Frame Type IDs)
// O byte de tipo no cabeçalho Tuya indica a natureza de cada mensagem:
//   KEY_START / KEY_RESP / KEY_FINAL → negociação de chave de sessão (v3.4)
//   CONTROL / CONTROL_NEW           → comando de escrita de DPS enviado ao device
//   STATUS_RESP                     → resposta a um comando de escrita
//   HEART_BEAT                      → keepalive bidirecional
//   DP_QUERY / DP_QUERY_NEW         → consulta de status (resposta ao refresh)
// ──────────────────────────────────────────────────────────────────────────────
@Field static final Integer FT_KEY_START    = 3
@Field static final Integer FT_KEY_RESP     = 4
@Field static final Integer FT_KEY_FINAL    = 5
@Field static final Integer FT_CONTROL      = 7
@Field static final Integer FT_STATUS_RESP  = 8
@Field static final Integer FT_HEARTBEAT    = 9
@Field static final Integer FT_DP_QUERY     = 10
@Field static final Integer FT_CONTROL_NEW  = 13
@Field static final Integer FT_DP_QUERY_NEW = 16

// ──────────────────────────────────────────────────────────────────────────────
// CAMPOS ESTÁTICOS — COMANDO PENDENTE
// Armazenam o último comando enviado para retry automático em caso de timeout.
// @Field static garante persistência entre invocações do mesmo device handler.
// ──────────────────────────────────────────────────────────────────────────────
@Field static String fPendingCommand = ""
@Field static Map    fPendingMessage = [:]

/* ══════════════════════════════════════════════════════════════════════════════
 *  METADATA — Definição do device, capabilities, atributos e preferências
 *
 *  capabilities:
 *    Refresh        → comando refresh() para consulta manual de status
 *    Sensor         → indica que o device reporta leituras
 *    Configuration  → comando configure() para reconfigurar schedules
 *    PresenceSensor → "present" quando o socket está conectado e respondendo
 *
 *  Atributos divididos em grupos:
 *    • Sensor principal: level, liquidDepthCm, liquidState
 *    • Configuração do device: upperLimit, lowerLimit, installationHeight, liquidDepthMax
 *    • Volume: tankCapacityLiters, volumeLiters, volumePercentOfCapacity, tankTemplate
 * ══════════════════════════════════════════════════════════════════════════════ */
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
        capability "PresenceSensor"   // "present" = socket conectado e respondendo

        // ── Atributos do sensor principal ─────────────────────────────────────
        attribute "level",             "number"  // Nível do líquido em % (0..100)
        attribute "liquidDepthCm",     "number"  // Profundidade em cm (normalizado pelo driver)
        attribute "liquidState",       "string"  // Estado: normal / lower_alarm / upper_alarm

        // ── Configurações do device (espelhadas localmente, graváveis via comandos) ─
        attribute "upperLimit",        "number"  // Limite superior de alarme (%)
        attribute "lowerLimit",        "number"  // Limite inferior de alarme (%)
        attribute "installationHeight","number"  // Altura de instalação do sensor (m)
        attribute "liquidDepthMax",    "number"  // Profundidade máxima configurada (m)

        // ── Atributos de volume (calculados localmente pelo driver) ────────────
        attribute "tankCapacityLiters",      "number"  // Capacidade total do tanque (L)
        attribute "volumeLiters",            "number"  // Volume atual (L)
        attribute "volumePercentOfCapacity", "number"  // Volume atual em % da capacidade
        attribute "tankTemplate",            "string"  // String resumida para tiles de dashboard

        // ── Comandos de escrita de DPS no device ──────────────────────────────
        command "setUpperLimit",         [[name:"Percent", type:"NUMBER", description:"0..100"]]
        command "setLowerLimit",         [[name:"Percent", type:"NUMBER", description:"0..100"]]
        command "setInstallationHeight", [[name:"Meters",  type:"NUMBER", description:"Ex.: 3.000"]]
        command "setLiquidDepthMax",     [[name:"Meters",  type:"NUMBER", description:"Ex.: 2.900"]]

        // ── Comandos de calibração (derivam valores dos atributos atuais) ──────
        command "calibSetUpperFromCurrentLevel"           // max_set ← level atual
        command "calibSetLowerFromCurrentLevel"           // mini_set ← level atual
        command "calibSetDepthMaxFromCurrentDepth"        // liquid_depth_max ← liquidDepthCm atual
        command "calibSetDepthMaxFromInstallationHeight"  // liquid_depth_max ← installationHeight atual
        command "calibSetInstallationHeightFromDepthMax"  // installation_height ← liquidDepthMax atual

        // ── Gerenciamento de conexão ───────────────────────────────────────────
        command "disconnect"   // Fecha o socket TCP manualmente
    }

    preferences {
        // ── Seção: Conexão com o dispositivo ──────────────────────────────────
        // Estas três configurações são obrigatórias e obtidas via TinyTuya.
        input name: "deviceHost", type: "text",
              title: "IP ou hostname do dispositivo",
              description: "Ex.: 192.168.1.100 ou tank-sensor.local — use IP fixo para estabilidade",
              required: true

        input name: "deviceId", type: "text",
              title: "Device ID (devId)",
              description: "ID único do device Tuya. Obter com: python -m tinytuya wizard",
              required: true

        input name: "localKey", type: "password",
              title: "Local Key (16 caracteres)",
              description: "Chave AES-128. ATENÇÃO: muda a cada re-pareamento com o app Tuya!",
              required: true

        input name: "tuyaProtVersion", type: "enum",
              title: "Versão do protocolo",
              description: "Verificar com TinyTuya. v3.3 é o mais comum em devices Wi-Fi recentes.",
              options: ["31":"3.1", "33":"3.3", "34":"3.4"],
              defaultValue: "33", required: true

        // ── Seção: Comportamento da conexão ───────────────────────────────────
        // auto-reconnect mantém uma conexão persistente que se reconecta a cada ciclo.
        // heartbeat é recomendado apenas para protocolo 3.4, onde reduz overhead de sessão.
        input name: "autoReconnect", type: "bool",
              title: "Auto-reconectar quando o socket for fechado",
              description: "Mantém conexão persistente; reconecta a cada ciclo de ~30s.",
              defaultValue: true

        input name: "heartbeatEnable", type: "bool",
              title: "Usar heartbeat para manter conexão ativa (recomendado para v3.4)",
              description: "Envia keepalive a cada 20s. Reduz reestabelecimentos de sessão no v3.4.",
              defaultValue: false

        // ── Seção: Polling periódico ───────────────────────────────────────────
        // O polling envia um "status request" ao dispositivo no intervalo configurado.
        // Com auto-reconnect ativo, o device também envia pushes espontâneos — nesse
        // caso é possível usar intervalos longos (5..30 min) e ainda ter dados frescos.
        input name: "pollingEnable", type: "bool",
              title: "Habilitar polling periódico (requisição de status)",
              defaultValue: true

        input name: "pollingInterval", type: "enum",
              title: "Intervalo de polling",
              description: "Mínimo de 30s para não sobrecarregar o device.",
              options: [
                  "30"  : "30 segundos",
                  "60"  : "1 minuto",
                  "120" : "2 minutos",
                  "300" : "5 minutos",
                  "600" : "10 minutos",
                  "900" : "15 minutos",
                  "1800": "30 minutos",
                  "3600": "1 hora"
              ],
              defaultValue: "300", required: true

        // ── Seção: Cálculo de volume ───────────────────────────────────────────
        // Calcula volume corrente e capacidade total a partir das dimensões do tanque.
        // A altura utilizável é determinada na ordem: preferência manual >
        //   liquidDepthMax (DPS 106) > installationHeight (DPS 104).
        input name: "enableVolumeCalc", type: "bool",
              title: "Habilitar cálculo de volume",
              defaultValue: true

        input name: "tankShape", type: "enum",
              title: "Formato do tanque",
              options: [
                  "rectangular":"Retangular (C × L × A)",
                  "cylVertical" :"Cilíndrico vertical (diâmetro × A)"
              ],
              defaultValue: "rectangular", required: true

        // Dimensões para tanque retangular
        input name: "tankLengthCm",   type: "number", title: "Comprimento do tanque (cm) — retangular",   required: false
        input name: "tankWidthCm",    type: "number", title: "Largura do tanque (cm) — retangular",       required: false

        // Dimensão para tanque cilíndrico vertical
        input name: "tankDiameterCm", type: "number", title: "Diâmetro do tanque (cm) — cilíndrico",     required: false

        // Sobrescreve a altura usada para calcular capacidade (opcional).
        // Se não informado, usa liquidDepthMax ou installationHeight do próprio device.
        input name: "tankUsableHeightM", type: "number",
              title: "Altura utilizável (m) — sobrescreve valor do device (opcional)",
              required: false

        // Controla o arredondamento do volume exibido (ex.: 10 → exibe múltiplos de 10L)
        input name: "roundVolumeLiters", type: "number",
              title: "Arredondar volume para o múltiplo mais próximo de N litros (ex.: 1, 5, 10)",
              defaultValue: 1

        // ── Seção: Logging ────────────────────────────────────────────────────
        input name: "txtEnable", type: "bool",
              title: "Habilitar log de descrição (descriptionText)",
              defaultValue: true

        input name: "logEnable", type: "bool",
              title: "Habilitar log de debug (desativa automaticamente após 30 min)",
              defaultValue: true
    }
}

/* ══════════════════════════════════════════════════════════════════════════════
 *  CICLO DE VIDA DO DRIVER
 *
 *  installed() → chamado uma única vez ao criar o device no Hubitat.
 *  updated()   → chamado toda vez que as preferências são salvas.
 *                Cancela todos os schedules anteriores, reinicia a sessão Tuya,
 *                registra os novos schedules de polling e aciona um refresh.
 *  configure() → exposto na UI do Hubitat; reinicia sessão e schedules.
 *  logsOff()   → desativa debug após 30 min (privado: não aparece na lista de comandos).
 * ══════════════════════════════════════════════════════════════════════════════ */
void installed() {
    logInfo "Instalado v${DRIVER_VERSION} (${DRIVER_DATE})"
    // Inicia como "not present" até que o socket confirme comunicação
    sendEvent(name: "presence", value: "not present")
    updated()
}

void updated() {
    logInfo "Atualizado v${DRIVER_VERSION} (${DRIVER_DATE})"
    // Limpa todos os schedules pendentes para evitar duplicação
    unschedule()
    // Agenda auto-desligamento do debug logging em 30 min
    if (settings?.logEnable) runIn(1800, "logsOff")
    // Reinicia estado da sessão Tuya (necessário p/ nova chave, IP, versão, etc.)
    tuyaSessionReset()
    // Registra o schedule de polling com o novo intervalo
    schedulePolling()
    // Aguarda 2s antes do primeiro refresh para o hub estabilizar
    runIn(2, "refresh")
}

void configure() {
    logDebug "configure()"
    tuyaSessionReset()
    schedulePolling()
    // Recalcula volume com os dados já em memória (sem precisar de novo refresh)
    recalcVolume()
}

/**
 * Desativa o log de debug após o timer de 30 min.
 * Declarado como private para não aparecer na lista de comandos do Hubitat.
 */
private void logsOff() {
    device.updateSetting("logEnable", [value: "false", type: "bool"])
    logInfo "Log de debug desativado"
}

/* ══════════════════════════════════════════════════════════════════════════════
 *  POLLING PERIÓDICO
 *
 *  schedulePolling() → registra o Cron/runEvery apropriado baseado no intervalo.
 *                      Intervalos < 60s usam expressão Cron (scheduler nativo);
 *                      intervalos em minutos usam os helpers runEvery* do Hubitat.
 *                      Floor de 30s para não sobrecarregar a API local do device.
 *  pollDevice()      → alvo do scheduler; delega para refresh().
 * ══════════════════════════════════════════════════════════════════════════════ */
private void schedulePolling() {
    unschedule("pollDevice")
    if (!settings?.pollingEnable) {
        logInfo "Polling desabilitado"
        return
    }
    Integer secs = (settings?.pollingInterval ?: "300") as Integer
    // Garante mínimo de 30s para não martelar o device
    if (secs < 30) secs = 30

    if (secs < 60) {
        // Expressão Cron para intervalos sub-minuto (ex.: "0/30 * * * * ?")
        schedule("0/${secs} * * * * ?", "pollDevice")
    } else {
        Integer mins = (secs / 60) as Integer
        switch (mins) {
            case 1:  runEvery1Minute("pollDevice");   break
            case 5:  runEvery5Minutes("pollDevice");  break
            case 10: runEvery10Minutes("pollDevice"); break
            case 15: runEvery15Minutes("pollDevice"); break
            case 30: runEvery30Minutes("pollDevice"); break
            default: runEvery1Hour("pollDevice");     break
        }
    }
    logInfo "Polling agendado a cada ${secs}s"
}

/** Alvo do scheduler de polling. Delega para refresh(). */
void pollDevice() {
    logDebug "pollDevice() — gatilho agendado"
    refresh()
}

/* ══════════════════════════════════════════════════════════════════════════════
 *  COMANDOS PÚBLICOS
 *
 *  refresh()    → envia requisição de status (DP_QUERY) ao device.
 *  disconnect() → fecha o socket TCP manualmente (útil para debug).
 * ══════════════════════════════════════════════════════════════════════════════ */
void refresh() {
    logDebug "refresh() — solicitando status ao device"
    tuyaSend("status", [:])
}

void disconnect() {
    logDebug "disconnect() — solicitado pelo usuário"
    unschedule("heartbeat")
    socketClose()
}

/* ══════════════════════════════════════════════════════════════════════════════
 *  COMANDOS DE ESCRITA DE DPS
 *
 *  Cada comando converte o valor para o formato raw exigido pelo protocolo Tuya
 *  e envia via tuyaSend("set", ...).
 *
 *  Regras de escala:
 *    • Percentuais (upperLimit / lowerLimit): inteiro 0..100, sem escala.
 *    • Alturas em metros (installationHeight / liquidDepthMax): scale=3,
 *      ou seja, 1,500 m → raw 1500. Hardware limita: 100..3000 / 100..2900.
 * ══════════════════════════════════════════════════════════════════════════════ */

/** Define o limite superior de alarme (DPS 101, max_set) */
void setUpperLimit(Number pct) {
    Integer v = safeIntClamped(pct, 0, 100, "setUpperLimit")
    logDebug "setUpperLimit(${pct}) → DPS ${DPS_UPPER_LIMIT} = ${v}"
    tuyaSend("set", [(DPS_UPPER_LIMIT): v])
}

/** Define o limite inferior de alarme (DPS 102, mini_set) */
void setLowerLimit(Number pct) {
    Integer v = safeIntClamped(pct, 0, 100, "setLowerLimit")
    logDebug "setLowerLimit(${pct}) → DPS ${DPS_LOWER_LIMIT} = ${v}"
    tuyaSend("set", [(DPS_LOWER_LIMIT): v])
}

/**
 * Define a altura de instalação do sensor (DPS 104, installation_height).
 * scale=3 → raw = metros × 1000. Faixa válida: 0,100..3,000 m (raw 100..3000).
 */
void setInstallationHeight(Number meters) {
    Integer raw = metersToScaledInt(meters, 3, 100, 3000)
    logDebug "setInstallationHeight(${meters}m) → DPS ${DPS_INSTALL_HEIGHT} raw=${raw}"
    tuyaSend("set", [(DPS_INSTALL_HEIGHT): raw])
}

/**
 * Define a profundidade máxima do tanque (DPS 106, liquid_depth_max).
 * scale=3 → raw = metros × 1000. Faixa válida: 0,100..2,900 m (raw 100..2900).
 */
void setLiquidDepthMax(Number meters) {
    Integer raw = metersToScaledInt(meters, 3, 100, 2900)
    logDebug "setLiquidDepthMax(${meters}m) → DPS ${DPS_DEPTH_MAX} raw=${raw}"
    tuyaSend("set", [(DPS_DEPTH_MAX): raw])
}

/* ══════════════════════════════════════════════════════════════════════════════
 *  COMANDOS DE CALIBRAÇÃO
 *
 *  Derivam o valor a ser gravado no device a partir dos atributos já lidos,
 *  evitando que o usuário precise digitar o valor manualmente.
 *  Todos verificam se o atributo fonte está disponível antes de prosseguir.
 * ══════════════════════════════════════════════════════════════════════════════ */

/** Grava max_set com o nível atual lido → define o ponto de "tanque cheio" */
void calibSetUpperFromCurrentLevel() {
    Integer lvl = currentInteger("level")
    if (lvl == null) { log.warn "[${device.displayName}] Sem level atual para calibrar limite superior."; return }
    setUpperLimit(lvl)
}

/** Grava mini_set com o nível atual lido → define o ponto de "tanque vazio" */
void calibSetLowerFromCurrentLevel() {
    Integer lvl = currentInteger("level")
    if (lvl == null) { log.warn "[${device.displayName}] Sem level atual para calibrar limite inferior."; return }
    setLowerLimit(lvl)
}

/** Grava liquid_depth_max com a profundidade atual lida (liquidDepthCm → metros) */
void calibSetDepthMaxFromCurrentDepth() {
    Integer cm = currentInteger("liquidDepthCm")
    if (cm == null) { log.warn "[${device.displayName}] Sem liquidDepthCm atual para calibrar."; return }
    setLiquidDepthMax((cm as BigDecimal) / 100G)
}

/** Copia installationHeight para liquid_depth_max (útil quando tanque está cheio) */
void calibSetDepthMaxFromInstallationHeight() {
    BigDecimal h = currentDecimal("installationHeight")
    if (h == null) { log.warn "[${device.displayName}] Sem installationHeight atual para calibrar."; return }
    setLiquidDepthMax(h)
}

/** Copia liquidDepthMax para installation_height (sincronização inversa) */
void calibSetInstallationHeightFromDepthMax() {
    BigDecimal mx = currentDecimal("liquidDepthMax")
    if (mx == null) { log.warn "[${device.displayName}] Sem liquidDepthMax atual para calibrar."; return }
    setInstallationHeight(mx)
}

/* ══════════════════════════════════════════════════════════════════════════════
 *  SOCKET TCP — GERENCIAMENTO DE CONEXÃO
 *
 *  socketConnect() → abre o socket TCP para o device na porta 6668.
 *                    Resolve hostname → IP antes de conectar.
 *  socketStatus()  → callback do Hubitat para eventos do socket (erro, desconexão).
 *                    Dispara auto-reconexão se configurado.
 *  socketWrite()   → serializa byte[] para hex e envia pelo socket.
 *  socketClose()   → fecha o socket e limpa estado da sessão.
 *  resolveHost()   → converte hostname para IP via DNS (InetAddress),
 *                    retorna o hostname original como fallback se falhar.
 * ══════════════════════════════════════════════════════════════════════════════ */

/** Abre a conexão TCP com o device. Retorna true em caso de sucesso. */
private boolean socketConnect() {
    String host = resolveHost()
    if (!host) {
        log.error "[${device.displayName}] Não foi possível resolver o host: ${settings.deviceHost}"
        return false
    }
    logDebug "Conectando a ${host}:${TUYA_PORT}"
    try {
        // byteInterface: true → recebe dados como hex string em parse()
        // readDelay: 150ms → janela de acumulação de fragmentos TCP antes de entregar ao parse()
        interfaces.rawSocket.connect(host, TUYA_PORT,
            byteInterface: true,
            readDelay: 150)
        return true
    } catch (java.net.NoRouteToHostException e) {
        log.error "[${device.displayName}] Sem rota para o host ${host}: ${e.message}"
    } catch (java.net.SocketTimeoutException e) {
        log.error "[${device.displayName}] Timeout ao conectar em ${host}: ${e.message}"
    } catch (e) {
        log.error "[${device.displayName}] Erro ao conectar socket: ${e}"
    }
    return false
}

/**
 * Callback invocado pelo Hubitat quando há um evento no socket (erro ou desconexão).
 * "Broken pipe" → erro de escrita → fecha imediatamente.
 * "disconnect"  → socket foi fechado pelo device ou pela rede → tenta reconectar
 *                 se autoReconnect estiver habilitado.
 */
void socketStatus(String msg) {
    logDebug "socketStatus: ${msg}"

    if (msg.contains("Broken pipe") || msg.contains("send error")) {
        unschedule("heartbeat")
        socketClose()
        return
    }

    if (msg.contains("disconnect")) {
        unschedule("heartbeat")
        boolean doReconnect = (settings?.autoReconnect != false)
        socketClose(doReconnect)
        if (doReconnect) {
            logDebug "Auto-reconectando..."
            boolean ok = tuyaGetSession()
            if (!ok) sendEvent(name: "presence", value: "not present")
            state.HaveSession = ok
        }
    }
}

/** Serializa o payload byte[] para hex string e envia pelo socket raw. */
private void socketWrite(byte[] message) {
    String hex = HexUtils.byteArrayToHexString(message)
    logDebug "socketWrite → ${hex}"
    try {
        interfaces.rawSocket.sendMessage(hex)
    } catch (e) {
        log.error "[${device.displayName}] Erro ao escrever no socket: ${e}"
        socketClose()
    }
}

/**
 * Fecha o socket TCP e limpa o estado de sessão.
 * Se willReconnect=false, emite presence "not present" imediatamente.
 */
private void socketClose(boolean willReconnect = false) {
    logDebug "socketClose (willReconnect=${willReconnect})"
    unschedule("sendTimeout")
    if (!willReconnect) sendEvent(name: "presence", value: "not present")
    // Limpa estado da sessão para forçar nova negociação no próximo connect
    state.session_step = "step1"
    state.HaveSession  = false
    state.sessionKey   = null
    try { interfaces.rawSocket.close() } catch (ignored) {}
}

/**
 * Resolve hostname para IP usando DNS (InetAddress).
 * Se o valor já for um IP (regex IPv4), retorna diretamente.
 * Em caso de falha de DNS, retorna o hostname original — a pilha TCP
 * tentará resolver por conta própria (mDNS / etc.).
 */
private String resolveHost() {
    String h = (settings?.deviceHost ?: "").trim()
    if (!h) return null
    // Verifica se já é um endereço IPv4 válido
    if (h ==~ /^\d{1,3}(\.\d{1,3}){3}$/) return h
    // Tenta resolução DNS
    try {
     //   String ip = InetAddress.getByName(h).getHostAddress()
        logDebug "Hostname '${h}' resolvido para ${ip}"
        return ip
    } catch (e) {
        log.warn "[${device.displayName}] Falha ao resolver DNS de '${h}': ${e.message} — usando hostname diretamente"
        return h
    }
}

/* ══════════════════════════════════════════════════════════════════════════════
 *  GERENCIAMENTO DE SESSÃO TUYA
 *
 *  tuyaSessionReset() → limpa todo o estado da sessão (chamado em updated/configure).
 *  tuyaGetSession()   → abre o socket e, para v3.4, inicia negociação de chave.
 *                       Retorna true quando a sessão está pronta para uso.
 *                       Para v3.4, retorna false imediatamente pois a negociação
 *                       é assíncrona (completada em parse() ao receber KEY_RESP).
 *  heartbeat()        → envia keepalive e agenda reconexão forçada em 30s
 *                       (caso o ack não chegue, socketStatus detecta o timeout).
 *  tuyaSend()         → ponto de entrada único para envio de comandos. Abre sessão
 *                       se necessário, serializa o payload e arma timer de retry.
 *  sendAll()          → reenvio do comando pendente (chamado após sessão pronta em v3.4).
 *  sendTimeout()      → disparado pelo timer; decrementa retries até fechar o socket.
 * ══════════════════════════════════════════════════════════════════════════════ */

/** Zera todo o estado de sessão. Chamado ao salvar preferências. */
private void tuyaSessionReset() {
    state.HaveSession  = false
    state.session_step = "step1"
    state.sessionKey   = null
    state.statePayload = [:]
    state.retry        = MAX_RETRIES
    state.Msgseq       = 1
}

/**
 * Abre o socket e inicia a sessão Tuya.
 *   v3.1 / v3.3 → sem handshake; sessão pronta imediatamente após connect.
 *   v3.4        → envia KEY_START; sessão ficará pronta quando parse() receber
 *                 KEY_RESP e completar a negociação de chave de sessão.
 * Retorna true apenas quando a sessão está imediatamente disponível (v3.1/3.3).
 */
private boolean tuyaGetSession() {
    if (!socketConnect()) return false

    String ver = settings?.tuyaProtVersion ?: "33"
    if (ver == "34") {
        // v3.4: envia step 1 da negociação de chave — resposta assíncrona em parse()
        state.session_step = "step1"
        socketWrite(buildKeyStartMessage())
        return false  // sessão ainda não está pronta
    }

    // v3.1 / v3.3: sem handshake, sessão pronta imediatamente
    state.HaveSession = true
    sendEvent(name: "presence", value: "present")
    return true
}

/**
 * Envia um heartbeat ao device e agenda reconexão forçada em 30s.
 * Se o ack do heartbeat chegar (handleFrame FT_HEARTBEAT), o timer é cancelado
 * e um novo heartbeat é agendado em 18s. Se não chegar, socketStatus fecha o socket.
 */
void heartbeat() {
    logDebug "heartbeat()"
    tuyaSend("hb")
    runIn(30, "socketStatus", [data: "disconnect: ciclo de reconexão forçado"])
}

/**
 * Ponto de entrada único para envio de qualquer comando ao device.
 * Abre sessão automaticamente se não houver uma ativa.
 * Arma um timer de SEND_TIMEOUT_MS para retry automático.
 */
private void tuyaSend(String command, Map data = null) {
    // Guarda comando pendente para retry em caso de timeout ou reconexão v3.4
    fPendingCommand = command
    fPendingMessage = data ?: [:]

    if (!state.HaveSession) {
        logDebug "Sem sessão ativa — abrindo conexão"
        state.HaveSession = tuyaGetSession()
    }

    if (state.HaveSession) {
        socketWrite(buildPayload(command, data))
        runInMillis(SEND_TIMEOUT_MS, "sendTimeout")
    }
    // Se HaveSession ainda false (v3.4 aguardando KEY_RESP), sendAll() será
    // chamado pelo handleFrame quando a negociação completar.
}

/** Reenvia o comando pendente. Chamado após negociação de sessão v3.4 ou em retry. */
void sendAll() {
    if (fPendingCommand) tuyaSend(fPendingCommand, fPendingMessage)
}

/**
 * Timer de resposta. Decrementa retries e reenvia ou fecha o socket.
 * Chamado automaticamente pelo Hubitat após SEND_TIMEOUT_MS sem resposta.
 */
void sendTimeout() {
    Integer retries = (state.retry ?: 0) as Integer
    if (retries > 0) {
        log.warn "[${device.displayName}] Sem resposta do device, tentando novamente... (${retries} restante(s))"
        state.retry = retries - 1
        sendAll()
    } else {
        log.error "[${device.displayName}] Sem resposta após ${MAX_RETRIES} tentativas — fechando socket"
        socketClose()
    }
}

/* ══════════════════════════════════════════════════════════════════════════════
 *  PARSER DE DADOS RECEBIDOS
 *
 *  parse(String rawHex) → callback do Hubitat ao receber dados do socket.
 *                         O Hubitat entrega os bytes como string hexadecimal.
 *                         Um único TCP segment pode conter múltiplos frames Tuya
 *                         (cada um delimitado por TUYA_PREFIX/TUYA_SUFFIX),
 *                         então parseAllFrames() itera sobre todos.
 *
 *  parseAllFrames()     → localiza todos os frames no hex recebido, usando o
 *                         magic prefix "000055AA" como marcador de início.
 *                         Proteção contra loop infinito via guard counter.
 *
 *  decodeFrame()        → decodifica um frame individual:
 *                         1. Extrai tipo e tamanho do cabeçalho (bytes 11 e 15)
 *                         2. Determina offset e tamanho do payload conforme versão
 *                         3. Detecta se o payload é plaintext (começa com '{') ou cifrado
 *                         4. Decifra se necessário e extrai o JSON com os DPS
 *                         5. Retorna Map com: tipo, texto raw, mapa dps, resposta completa
 *
 *  handleFrame()        → despacha cada frame decodificado para o handler correto:
 *                         FT_KEY_RESP     → completa negociação de sessão v3.4
 *                         FT_HEARTBEAT    → renova timer de keepalive
 *                         FT_STATUS_RESP / FT_DP_QUERY* → processa DPS via processDps()
 * ══════════════════════════════════════════════════════════════════════════════ */

/**
 * Callback do Hubitat: recebe hex bruto do socket e distribui frames para handleFrame().
 * Cancela o timer de sendTimeout e reseta o contador de retries a cada resposta recebida.
 */
void parse(String rawHex) {
    logDebug "parse() hex recebido: ${rawHex}"
    unschedule("sendTimeout")
    state.retry = MAX_RETRIES

    List<Map> frames = parseAllFrames(rawHex)
    frames.each { Map frame -> handleFrame(frame) }
}

/**
 * Localiza e decodifica todos os frames Tuya presentes no hex recebido.
 * Um único pacote TCP pode conter múltiplos frames concatenados.
 * Guard counter (50) previne loop infinito em dados malformados.
 */
private List<Map> parseAllFrames(String hex) {
    List<Map> results = []
    int pos = 0, guard = 50

    while (guard-- > 0) {
        int idx = hex.indexOf(TUYA_PREFIX, pos)
        if (idx == -1) break
        pos = idx + 1  // avança para encontrar próximo frame

        byte[] raw = HexUtils.hexStringToByteArray(hex)
        Map frame  = decodeFrame(raw, (idx / 2) as Integer)
        if (frame) results << frame
    }
    return results
}

/**
 * Decodifica um único frame Tuya a partir do offset sofIndex (em bytes).
 *
 * Estrutura de um frame Tuya (bytes):
 *   [0..3]   Prefixo 0x000055AA
 *   [4..7]   Reservado / versão (ignorado)
 *   [8..11]  Número de sequência
 *   [11]     Tipo do frame (FT_*)
 *   [12..15] Comprimento do payload (incluindo checksum e sufixo)
 *   [16..]   Payload (cifrado ou plaintext)
 *   [end-4]  CRC32 (v3.1/v3.3) ou HMAC-SHA256 últimos 32B (v3.4)
 *   [end]    Sufixo 0x0000AA55
 *
 * Retorna Map com: type, raw (texto decifrado), dps (Map), full (Map completo)
 * ou null para frames ignorados / inválidos.
 */
private Map decodeFrame(byte[] raw, Integer sofIndex) {
    if (raw.size() < sofIndex + 20) return null

    Integer frameType   = Byte.toUnsignedInt(raw[sofIndex + 11])
    Integer frameLength = Byte.toUnsignedInt(raw[sofIndex + 15])
    String  ver         = settings?.tuyaProtVersion ?: "33"

    logDebug "decodeFrame sofIndex=${sofIndex} tipo=${frameType} len=${frameLength}"

    // CONTROL/CONTROL_NEW são confirmações de escrita — sem payload útil, ignorar
    if (frameType in [FT_CONTROL, FT_CONTROL_NEW]) return null

    // Escolhe a chave de decifragem:
    //   sessionKey → presente apenas em v3.4 após negociação bem-sucedida
    //   localKey   → usada em v3.1/v3.3 e durante a negociação v3.4
    byte[] useKey = (state.sessionKey != null)
        ? (state.sessionKey as byte[])
        : getRealLocalKey()

    // Tamanho do checksum varia por versão:
    //   v3.1 / v3.3 → CRC32 = 4 bytes
    //   v3.4        → HMAC-SHA256 = 32 bytes
    Integer checksumSize  = (ver == "34") ? 32 : 4
    Integer payloadStart, payloadLength

    // Calcula offset e tamanho do payload dependendo do tipo de frame e versão
    switch (frameType) {
        case FT_KEY_RESP:
            // KEY_RESP sempre usa a localKey pura, não a sessionKey ainda em negociação
            payloadStart  = 20
            payloadLength = frameLength - checksumSize - 8
            useKey = getRealLocalKey()
            break
        case FT_STATUS_RESP:
            // Versões têm cabeçalhos de tamanhos diferentes antes do payload JSON
            if (ver == "31") {
                payloadStart  = 39   // 20B header + 16B MD5 + 3B versão
                payloadLength = frameLength - checksumSize - 31
            } else if (ver == "33") {
                payloadStart  = 35   // 20B header + 12B zeros + 3B versão
                payloadLength = frameLength - checksumSize - 19 - 4
            } else {
                payloadStart  = 20
                payloadLength = frameLength - checksumSize - 8
            }
            break
        default:
            payloadStart  = 20
            payloadLength = frameLength - checksumSize - 8
    }

    if (payloadLength <= 0) return [type: frameType, dps: [:]]

    // Tenta identificar se o payload é plaintext JSON (começa com '{')
    // ou se está cifrado (AES-128-ECB)
    String plainText = ""
    if (raw.size() > sofIndex + payloadStart &&
        raw[sofIndex + payloadStart] == (byte)'{') {
        // Plaintext: converte diretamente para String
        plainText = new String(raw, sofIndex + payloadStart, payloadLength, "UTF-8")
        logDebug "Payload plaintext: ${plainText}"
    } else {
        // Cifrado: decifra com a chave correta
        // v3.1 usa Base64 antes do AES; v3.3 e v3.4 usam AES direto
        plainText = decryptPayload(raw, useKey, sofIndex + payloadStart, payloadLength, ver == "31")
        logDebug "Payload decifrado: ${plainText}"
    }

    // Extrai o mapa de DPS do JSON decifrado (se presente)
    Map status = [:]
    if (plainText?.contains("dps")) {
        try {
            Map parsed = new JsonSlurper().parseText(plainText.substring(plainText.indexOf('{')))
            // v3.4 STATUS_RESP encapsula os DPS dentro de "data": { "dps": {...} }
            status = (ver == "34" && frameType == FT_STATUS_RESP && parsed.containsKey("data"))
                     ? parsed["data"]
                     : parsed
        } catch (e) {
            logDebug "Erro ao parsear JSON: ${e.message}"
        }
    }

    return [type: frameType, raw: plainText, dps: status?.dps ?: [:], full: status]
}

/**
 * Dispatcher de frames: roteia cada frame decodificado para o handler correto.
 *
 *   FT_KEY_RESP     → step 2/3 da negociação de chave v3.4. Após completar,
 *                     emite presence "present" e chama sendAll() para enviar
 *                     o comando que estava pendente desde o início da sessão.
 *   FT_HEARTBEAT    → ack de keepalive; renova o próximo heartbeat em 18s.
 *   FT_STATUS_RESP  → resposta a um set(). Contém DPS atualizados.
 *   FT_DP_QUERY*    → resposta ao refresh(). Contém todos os DPS do device.
 */
private void handleFrame(Map frame) {
    if (!frame) return
    Integer frameType = frame.type as Integer

    switch (frameType) {

        case FT_KEY_RESP:
            // ── v3.4: Completa a negociação de chave de sessão (step 2 → step 3) ──
            // O device enviou seu nonce cifrado com a localKey.
            // Respondemos com HMAC-SHA256 do nonce do device (step 3).
            // A sessionKey final é calculada como: AES(XOR(localNonce, remoteNonce), localKey)
            logDebug "KEY_RESP recebido — completando negociação de chave de sessão"
            unschedule("get_session_timeout")
            String plainText = frame.raw ?: ""
            if (plainText.size() >= 16) {
                def (byte[] finalMsg, byte[] remoteNonce) = decodeIncomingKeyResponse(plainText)
                state.session_step = "step3"
                socketWrite(finalMsg)                          // envia KEY_FINAL
                state.sessionKey   = calculateSessionKey(remoteNonce)
                state.session_step = "final"
                state.HaveSession  = true
                sendEvent(name: "presence", value: "present")
                logDebug "Chave de sessão negociada com sucesso — enviando comando pendente"
                runInMillis(100, "sendAll")   // pequena espera antes de enviar
                // Após sessão pronta: usa heartbeat (v3.4) ou ciclo de reconexão forçada
                if (settings?.heartbeatEnable) {
                    runIn(20, "heartbeat")
                } else {
                    runIn(30, "socketStatus", [data: "disconnect: ciclo de reconexão forçado"])
                }
            }
            break

        case FT_HEARTBEAT:
            // ── Ack do keepalive recebido → reagenda próximo heartbeat ──
            logDebug "HEARTBEAT ack recebido"
            unschedule("socketStatus")   // cancela o timer de reconexão forçada
            if (settings?.heartbeatEnable) runIn(18, "heartbeat")
            break

        case FT_STATUS_RESP:
        case FT_DP_QUERY:
        case FT_DP_QUERY_NEW:
            // ── Dados de telemetria recebidos → processa DPS ──
            fPendingCommand = ""   // comando foi respondido, limpa pendente
            Map dps = frame.dps ?: [:]
            if (dps) {
                logDebug "DPS recebidos: ${dps}"
                processDps(dps)
            }
            break

        default:
            logDebug "Tipo de frame não tratado: ${frameType}"
    }
}

/* ══════════════════════════════════════════════════════════════════════════════
 *  PROCESSAMENTO DE DPS → ATRIBUTOS HUBITAT
 *
 *  processDps() → itera sobre cada par chave/valor do mapa DPS recebido,
 *                 converte para a unidade do atributo Hubitat correspondente
 *                 e emite o evento via emitIfChanged().
 *
 *  Conversões de escala aplicadas:
 *    DPS_DEPTH (scale=2):          raw (ex: 125) → metros (1,25) → cm (125)
 *    DPS_INSTALL_HEIGHT (scale=3): raw (ex: 3000) → metros (3,000)
 *    DPS_DEPTH_MAX (scale=3):      raw (ex: 2900) → metros (2,900)
 *
 *  Ao final, recalcula volume se profundidade ou altura mudaram.
 * ══════════════════════════════════════════════════════════════════════════════ */
private void processDps(Map dps) {
    boolean touchedLevel  = false   // sinaliza se profundidade/nível mudou
    boolean touchedHeight = false   // sinaliza se altura de referência mudou

    dps.each { String k, v ->
        switch (k) {

            case DPS_LEVEL:
                // Nível em % — inteiro direto, sem conversão de escala
                Integer pct = safeIntClamped(v, 0, 100, "level")
                if (emitIfChanged("level", pct, "%")) logDesc "Nível: ${pct}%"
                touchedLevel = true
                break

            case DPS_DEPTH:
                // Device envia metros × 100 (scale=2) como inteiro → convertemos para cm
                // Ex: valor 125 = 1,25 m = 125 cm
                BigDecimal depthM = safeBigDecimal(v)
                if (depthM != null) {
                    Integer cm = (depthM * 100G).setScale(0, BigDecimal.ROUND_HALF_UP).intValue()
                    if (emitIfChanged("liquidDepthCm", cm, "cm")) logDesc "Profundidade: ${cm} cm"
                    touchedLevel = true
                }
                break

            case DPS_STATE:
                // Estado de alarme: "normal", "lower_alarm" ou "upper_alarm"
                String st = v?.toString() ?: "unknown"
                if (emitIfChanged("liquidState", st, null)) logDesc "Estado: ${st}"
                break

            case DPS_UPPER_LIMIT:
                // Limite superior de alarme em % — inteiro direto
                if (emitIfChanged("upperLimit", safeIntClamped(v, 0, 100, "upperLimit"), "%"))
                    logDesc "Limite superior: ${v}%"
                break

            case DPS_LOWER_LIMIT:
                // Limite inferior de alarme em % — inteiro direto
                if (emitIfChanged("lowerLimit", safeIntClamped(v, 0, 100, "lowerLimit"), "%"))
                    logDesc "Limite inferior: ${v}%"
                break

            case DPS_INSTALL_HEIGHT:
                // Device envia metros × 1000 (scale=3) → convertemos para metros
                // Ex: raw 3000 = 3,000 m
                BigDecimal m = safeBigDecimal(v)
                if (m != null) {
                    BigDecimal meters = (m / 1000G).setScale(3, BigDecimal.ROUND_HALF_UP)
                    if (emitIfChanged("installationHeight", meters, "m"))
                        logDesc "Altura de instalação: ${meters} m"
                    touchedHeight = true
                }
                break

            case DPS_DEPTH_MAX:
                // Device envia metros × 1000 (scale=3) → convertemos para metros
                BigDecimal m = safeBigDecimal(v)
                if (m != null) {
                    BigDecimal meters = (m / 1000G).setScale(3, BigDecimal.ROUND_HALF_UP)
                    if (emitIfChanged("liquidDepthMax", meters, "m"))
                        logDesc "Profundidade máxima: ${meters} m"
                    touchedHeight = true
                }
                break

            default:
                // DPS não mapeado — registra em debug para facilitar identificação de novos DPS
                logDebug "DPS não mapeado: chave=${k} valor=${v}"
        }
    }

    // Recalcula volume se algum valor relevante mudou
    if (enableVolumeCalc && (touchedLevel || touchedHeight)) {
        recalcVolume()
    } else {
        updateTemplate()
    }
}

/* ══════════════════════════════════════════════════════════════════════════════
 *  CÁLCULO DE VOLUME
 *
 *  recalcVolume()   → calcula volume atual e capacidade total do tanque.
 *                     Não recebe parâmetros: lê tudo dos atributos e preferências.
 *
 *  Altura utilizável (usableHeightM) é determinada em ordem de prioridade:
 *    1. Preferência "tankUsableHeightM" (override manual)
 *    2. Atributo "liquidDepthMax" (DPS 106 — configurado no device)
 *    3. Atributo "installationHeight" (DPS 104 — altura física do sensor)
 *
 *  computeAreaM2()  → calcula a área da base do tanque em m²:
 *    Retangular:  área = comprimento × largura  (cm convertidos para m)
 *    Cilíndrico:  área = π × (diâmetro/2)²
 *
 *  updateTemplate() → monta a string compacta "tankTemplate" para uso em tiles
 *                     de dashboard. Formato: "75% • 150 cm • 750 L / 1.000 L • 75,0% • normal"
 * ══════════════════════════════════════════════════════════════════════════════ */
void recalcVolume() {
    if (!enableVolumeCalc) { updateTemplate(); return }

    Integer depthCm = currentInteger("liquidDepthCm")
    if (depthCm == null) { updateTemplate(); return }
    BigDecimal depthM = (depthCm as BigDecimal) / 100G

    // Determina altura útil: override manual > liquidDepthMax > installationHeight
    BigDecimal usableHeightM = (settings?.tankUsableHeightM != null)
        ? safeBigDecimal(settings.tankUsableHeightM) : null
    if (usableHeightM == null) {
        BigDecimal mx = currentDecimal("liquidDepthMax")
        BigDecimal ih = currentDecimal("installationHeight")
        usableHeightM = (mx != null) ? mx : ih
    }

    BigDecimal areaM2 = computeAreaM2()
    // Se não temos área ou altura, não é possível calcular — atualiza só o template
    if (areaM2 == null || areaM2 <= 0G)             { updateTemplate(); return }
    if (usableHeightM == null || usableHeightM <= 0G) { updateTemplate(); return }

    // Limita a profundidade medida ao intervalo físico válido [0..usableHeightM]
    BigDecimal d    = depthM.max(0G).min(usableHeightM)
    // Capacidade total = área × altura total × 1000 (m³ → L)
    BigDecimal capL = areaM2 * usableHeightM * 1000G
    // Volume atual    = área × profundidade atual × 1000 (m³ → L)
    BigDecimal volL = areaM2 * d * 1000G
    // Percentual de ocupação com 1 casa decimal
    BigDecimal pct  = (capL > 0G) ? (volL / capL * 100G) : 0G
    pct = pct.setScale(1, BigDecimal.ROUND_HALF_UP)

    // Aplica arredondamento configurável ao volume
    Integer step      = safeIntClamped(settings?.roundVolumeLiters, 1, 100000, "roundVolumeLiters")
    BigDecimal volRnd = roundToStep(volL, step)

    emitIfChanged("tankCapacityLiters",      capL.setScale(0, BigDecimal.ROUND_HALF_UP), null)
    emitIfChanged("volumeLiters",            volRnd.setScale(0, BigDecimal.ROUND_HALF_UP), "L")
    emitIfChanged("volumePercentOfCapacity", pct, "%")

    logDebug "recalcVolume: prof=${depthM}m altUtil=${usableHeightM}m área=${areaM2}m² " +
             "cap=${capL}L vol=${volL}L arred=${volRnd}L pct=${pct}%"
    updateTemplate()
}

/**
 * Calcula a área da base do tanque em m² de acordo com o formato configurado.
 * Retorna null se as dimensões não estiverem configuradas corretamente.
 */
private BigDecimal computeAreaM2() {
    String shape = settings?.tankShape ?: "rectangular"
    switch (shape) {
        case "rectangular":
            // Área = comprimento × largura (dimensões em cm, convertidas para m)
            BigDecimal Lm = cmToM(settings?.tankLengthCm)
            BigDecimal Wm = cmToM(settings?.tankWidthCm)
            if (Lm == null || Wm == null) return null
            return (Lm * Wm).setScale(6, BigDecimal.ROUND_HALF_UP)
        case "cylVertical":
            // Área = π × r² (diâmetro em cm, convertido para m, dividido por 2)
            BigDecimal Dm = cmToM(settings?.tankDiameterCm)
            if (Dm == null) return null
            BigDecimal r  = Dm / 2G
            return ((Math.PI as BigDecimal) * r * r).setScale(6, BigDecimal.ROUND_HALF_UP)
        default:
            log.warn "[${device.displayName}] Formato de tanque desconhecido: '${shape}'"
            return null
    }
}

/**
 * Atualiza o atributo "tankTemplate" com uma string resumida de todos os dados.
 * Formato exibido: "75% • 150 cm • 750 L / 1.000 L • 75,0% • normal"
 * Valores ausentes são exibidos como "--".
 */
private void updateTemplate() {
    Integer    lvl    = currentInteger("level")
    Integer    depth  = currentInteger("liquidDepthCm")
    String     st     = (device.currentValue("liquidState") ?: "").toString()
    BigDecimal vol    = safeBigDecimal(device.currentValue("volumeLiters"))
    BigDecimal cap    = safeBigDecimal(device.currentValue("tankCapacityLiters"))
    BigDecimal pctCap = safeBigDecimal(device.currentValue("volumePercentOfCapacity"))

    String t = "${lvl    != null ? lvl    + '%' : '--%'} • "  +
               "${depth  != null ? depth  + ' cm' : '-- cm'} • " +
               "${vol    != null ? formatInt(vol)  + ' L' : '-- L'} / " +
               "${cap    != null ? formatInt(cap)  + ' L' : '-- L'} • " +
               "${pctCap != null ? pctCap.setScale(1, BigDecimal.ROUND_HALF_UP) + '%' : '--%'} • " +
               "${st ?: 'unknown'}"

    emitIfChanged("tankTemplate", t, null)
}

/* ══════════════════════════════════════════════════════════════════════════════
 *  PROTOCOLO TUYA — CONSTRUÇÃO DE PAYLOAD
 *
 *  buildPayload()   → ponto de entrada; seleciona o builder correto pela versão.
 *                     Aceita parâmetros opcionais para override (usado nos self-tests).
 *                     Monta o corpo JSON dependendo do tipo de comando:
 *                       "status" → só cabeçalhos gwId/devId/uid/t
 *                       "set"    → adiciona "dps" (v3.1/3.3) ou "data.dps" (v3.4)
 *                       "hb"     → corpo vazio "{}"
 *
 *  buildPayload31() → v3.1:
 *                     SET: cifra JSON com AES/Base64, calcula MD5 auth,
 *                          monta "3.1" + 16 chars MD5 + payload Base64
 *                     STATUS: plaintext, wrapped em frame CRC32
 *
 *  buildPayload33() → v3.3:
 *                     SET e STATUS: prefixo "3.3" + 12 bytes zero + payload AES-ECB
 *                     Frame com CRC32
 *
 *  buildPayload34() → v3.4:
 *                     Todos os comandos: payload AES-ECB sem prefixo de versão
 *                     Frame com HMAC-SHA256 (32 bytes) em vez de CRC32
 *
 *  wrapFrame()      → monta o frame completo v3.1/3.3:
 *                     [PREFIX][reservado][seq][tipo][len][payload][CRC32][SUFFIX]
 *
 *  wrapFrameV34()   → monta o frame completo v3.4:
 *                     [PREFIX][reservado][seq][tipo][len][payload][HMAC-SHA256][SUFFIX]
 * ══════════════════════════════════════════════════════════════════════════════ */

/**
 * Ponto de entrada para construção de payload.
 * Parâmetros com valores default permitem override para testes.
 */
private byte[] buildPayload(String command, Map data = null,
                             String timestamp = null,
                             byte[] key       = null,
                             String devid     = null,
                             String ver       = null,
                             Short  seq       = null) {
    // Resolve defaults: usa sessionKey se disponível (v3.4), senão localKey
    key   = key   ?: ((state.sessionKey != null) ? (state.sessionKey as byte[]) : getRealLocalKey())
    devid = devid ?: (settings?.deviceId ?: "")
    ver   = ver   ?: (settings?.tuyaProtVersion ?: "33")
    seq   = seq   ?: (getNextSeq() as Short)

    // Monta o corpo JSON base com identificadores do device
    Map body = [gwId: devid, devId: devid, uid: devid,
                t: timestamp ?: nowEpochSec()]

    if (command == "status") {
        // STATUS: apenas os identificadores são suficientes para disparar a resposta
    } else if (command == "hb") {
        body = [:]  // heartbeat: corpo vazio
    } else if (data) {
        // SET: v3.4 encapsula DPS em "data", v3.1/3.3 coloca direto em "dps"
        if (ver == "34") {
            body["data"] = ["dps": data]
        } else {
            body["dps"] = data
        }
    }

    String jsonStr = (command == "hb") ? "{}" : cleanJson(body)
    logDebug "buildPayload cmd=${command} ver=${ver} json=${jsonStr}"

    switch (ver) {
        case "31": return buildPayload31(command, jsonStr, key, devid, seq)
        case "33": return buildPayload33(command, jsonStr, key, seq)
        case "34": return buildPayload34(command, jsonStr, key, seq)
        default:   return buildPayload33(command, jsonStr, key, seq)
    }
}

/**
 * Constrói payload para protocolo v3.1.
 * SET: cifra JSON com AES/ECB → Base64, calcula MD5 de autenticação,
 *      monta payload como: "3.1" + 16 chars do MD5 (posições 8..23) + ciphertext Base64
 * STATUS: JSON em plaintext direto
 */
private byte[] buildPayload31(String command, String jsonStr, byte[] key,
                               String devid, Short seq) {
    Integer msgType = (command == "status") ? FT_DP_QUERY : FT_CONTROL
    ByteArrayOutputStream payload = new ByteArrayOutputStream()

    if (command != "status") {
        // Cifra o JSON e calcula assinatura MD5
        String encrypted = encryptToBase64(jsonStr.bytes, key)
        String preMd5    = "data=${encrypted}||lpv=3.1||" + new String(key, "UTF-8")
        String md5part   = md5hex(preMd5)[8..23]  // 16 chars do meio do MD5
        payload.write("3.1${md5part}${encrypted}".bytes)
    } else {
        payload.write(jsonStr.bytes)  // status em plaintext no v3.1
    }

    return wrapFrame(payload.toByteArray(), msgType, seq, false)
}

/**
 * Constrói payload para protocolo v3.3.
 * Prefixo "3.3" + 12 bytes nulos + payload AES-ECB (ou plaintext para status).
 * Frame com checksum CRC32.
 */
private byte[] buildPayload33(String command, String jsonStr, byte[] key, Short seq) {
    Integer msgType = (command == "status") ? FT_DP_QUERY : FT_CONTROL
    ByteArrayOutputStream buf = new ByteArrayOutputStream()
    buf.write("3.3".bytes)          // prefixo de versão (3 bytes)
    buf.write(new byte[12])         // 12 bytes de padding zero

    if (command == "status") {
        buf.write(jsonStr.bytes)    // status: JSON em plaintext após o prefixo
    } else {
        buf.write(encryptBytes(jsonStr.bytes, key))  // set: payload cifrado
    }

    return wrapFrame(buf.toByteArray(), msgType, seq, false)
}

/**
 * Constrói payload para protocolo v3.4.
 * Todo payload é cifrado com AES-ECB, sem prefixo de versão.
 * Frame usa HMAC-SHA256 em vez de CRC32.
 * Tipos de mensagem diferentes conforme o comando.
 */
private byte[] buildPayload34(String command, String jsonStr, byte[] key, Short seq) {
    Integer msgType
    byte[]  encrypted

    switch (command) {
        case "status":
            msgType   = FT_DP_QUERY_NEW                   // consulta de DPS no v3.4
            encrypted = encryptBytes(jsonStr.bytes, key)
            break
        case "hb":
            msgType   = FT_HEARTBEAT
            encrypted = encryptBytes("{}".bytes, key)     // corpo vazio cifrado
            break
        default:
            msgType   = FT_CONTROL_NEW                    // set de DPS no v3.4
            encrypted = encryptBytes(jsonStr.bytes, key)
    }

    return wrapFrameV34(encrypted, msgType, seq, key)
}

/**
 * Encapsula payload em frame Tuya v3.1/v3.3 com CRC32.
 *
 * Estrutura gerada:
 *   [PREFIX 4B][reservado 4B][seq 4B][msgType 4B][length 4B][payload][CRC32 4B][SUFFIX 4B]
 *
 * "length" = comprimento do payload + 4B CRC + 4B sufixo = payload.length + 8
 */
private byte[] wrapFrame(byte[] payload, Integer msgType, Short seq, boolean useCrc32 = false) {
    ByteArrayOutputStream frame = new ByteArrayOutputStream()
    frame.write(HexUtils.hexStringToByteArray(TUYA_PREFIX))
    frame.write(intToBytes4(0))                       // campo reservado
    frame.write(intToBytes4(seq as Integer))          // número de sequência
    frame.write(intToBytes4(msgType))                 // tipo da mensagem
    frame.write(intToBytes4(payload.length + 8))      // tamanho = payload + CRC(4) + suffix(4)
    frame.write(payload)
    // CRC32 calculado sobre tudo até aqui (incluindo cabeçalho)
    frame.write(crc32bytes(frame.toByteArray()))
    frame.write(HexUtils.hexStringToByteArray(TUYA_SUFFIX))
    return frame.toByteArray()
}

/**
 * Encapsula payload em frame Tuya v3.4 com HMAC-SHA256.
 *
 * Estrutura gerada:
 *   [PREFIX 4B][reservado 4B][seq 4B][msgType 4B][length 4B][payload][HMAC-SHA256 32B][SUFFIX 4B]
 *
 * "length" = comprimento do payload + 32B HMAC + 4B sufixo = payload.length + 36
 * O HMAC é calculado sobre toda a mensagem exceto o próprio HMAC e o sufixo.
 */
private byte[] wrapFrameV34(byte[] payload, Integer msgType, Short seq, byte[] key) {
    ByteArrayOutputStream frame = new ByteArrayOutputStream()
    frame.write(HexUtils.hexStringToByteArray(TUYA_PREFIX))
    frame.write(intToBytes4(0))
    frame.write(intToBytes4(seq as Integer))
    frame.write(intToBytes4(msgType))
    frame.write(intToBytes4(payload.length + 36))     // tamanho = payload + HMAC(32) + suffix(4)
    frame.write(payload)
    // HMAC-SHA256 sobre cabeçalho + payload (sem o HMAC e o sufixo)
    frame.write(hmacSha256(frame.toByteArray(), key))
    frame.write(HexUtils.hexStringToByteArray(TUYA_SUFFIX))
    return frame.toByteArray()
}

/* ══════════════════════════════════════════════════════════════════════════════
 *  PROTOCOLO TUYA v3.4 — NEGOCIAÇÃO DE CHAVE DE SESSÃO
 *
 *  O protocolo v3.4 usa uma chave de sessão efêmera derivada a cada conexão,
 *  ao invés de usar a localKey diretamente para cifrar os dados.
 *  O processo de 3 passos é:
 *
 *  STEP 1 — Hub → Device (KEY_START):
 *    Gera um nonce local aleatório de 16 chars (localNonce).
 *    Cifra o nonce com a localKey (AES-ECB).
 *    Encapsula em frame KEY_START com HMAC da localKey.
 *
 *  STEP 2 — Device → Hub (KEY_RESP):
 *    Device responde com seu próprio nonce (remoteNonce) cifrado com a localKey.
 *    Hub decifra, extrai o remoteNonce.
 *    Hub calcula HMAC-SHA256(localKey, remoteNonce) e envia no KEY_FINAL.
 *
 *  STEP 3 — Hub → Device (KEY_FINAL):
 *    Hub envia o HMAC do remoteNonce como confirmação.
 *    Após este passo, ambos calculam a sessionKey:
 *      sessionKey = AES(XOR(localNonce, remoteNonce), localKey)[0..15]
 *    Todos os frames subsequentes usam a sessionKey para cifrar/decifrar.
 * ══════════════════════════════════════════════════════════════════════════════ */

/**
 * Step 1: Gera e envia o KEY_START com o nonce local cifrado.
 * Salva o localNonce em state para uso posterior no cálculo da sessionKey.
 */
private byte[] buildKeyStartMessage() {
    String nonce = generateLocalNonce()
    state.localNonce = nonce   // salvo para calcular sessionKey no step 3
    byte[] encNonce = encryptBytes(nonce.bytes, getRealLocalKey())
    return wrapFrameV34(encNonce, FT_KEY_START, 1 as Short, getRealLocalKey())
}

/**
 * Step 2: Processa o KEY_RESP do device.
 * Extrai o remoteNonce (primeiros 16 chars do plaintext decifrado).
 * Calcula HMAC-SHA256(localKey, remoteNonce) e monta o KEY_FINAL.
 * Retorna [mensagem KEY_FINAL a enviar, remoteNonce para calcular sessionKey].
 */
private List decodeIncomingKeyResponse(String plainText) {
    byte[] remoteNonce = plainText[0..15].bytes  // primeiros 16 chars = nonce do device
    Mac mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(getRealLocalKey(), "HmacSHA256"))
    mac.update(remoteNonce)
    byte[] digest   = mac.doFinal()
    byte[] finalMsg = wrapFrameV34(digest, FT_KEY_FINAL, getNextSeq() as Short, getRealLocalKey())
    return [finalMsg, remoteNonce]
}

/**
 * Step 3: Calcula a chave de sessão final.
 * sessionKey = AES-ECB-encrypt(XOR(localNonce, remoteNonce), localKey)[bytes 0..15]
 * A XOR combina os dois nonces; o resultado é cifrado com a localKey;
 * os primeiros 16 bytes do ciphertext formam a sessionKey de 128 bits.
 */
private byte[] calculateSessionKey(byte[] remoteNonce) {
    byte[] localNonce = (state.localNonce ?: generateLocalNonce()).bytes
    // XOR byte a byte dos dois nonces de 16 bytes
    byte[] xored = new byte[16]
    (0..15).each { i -> xored[i] = (localNonce[i] ^ remoteNonce[i]) as byte }
    // Cifra o resultado com a localKey e usa os primeiros 16 bytes
    String encHex = encryptToHex(xored, getRealLocalKey())
    return HexUtils.hexStringToByteArray(encHex[0..31])  // [0..31] = primeiros 16 bytes em hex
}

/**
 * Gera um nonce local aleatório de 16 caracteres ASCII imprimíveis.
 * Usando letras minúsculas e dígitos para compatibilidade máxima.
 */
private String generateLocalNonce() {
    String chars = "abcdefghijklmnopqrstuvwxyz0123456789"
    Random rng   = new Random()
    return (1..16).collect { chars[rng.nextInt(chars.size())] }.join()
}

/* ══════════════════════════════════════════════════════════════════════════════
 *  CRIPTOGRAFIA
 *
 *  getRealLocalKey()   → decodifica a localKey das preferências (UTF-8).
 *                        Substitui &lt; por < (escape HTML do Hubitat).
 *
 *  encryptBytes()      → AES-128-ECB com PKCS5Padding, retorna byte[].
 *  encryptToHex()      → idem, retorna hex string (para cálculo de sessionKey).
 *  encryptToBase64()   → idem, retorna Base64 string (usada no protocolo v3.1).
 *
 *  decryptPayload()    → decifra um trecho de raw[] com AES-128-ECB.
 *                        isBase64=true para v3.1 (decodifica Base64 antes de decifrar).
 *
 *  hmacSha256()        → HMAC-SHA256 para assinatura de frames v3.4.
 *  md5hex()            → MD5 para autenticação de SET no protocolo v3.1.
 *  crc32bytes()        → CRC32 big-endian para checksum de frames v3.1/v3.3.
 * ══════════════════════════════════════════════════════════════════════════════ */

/** Obtém a Local Key das preferências como byte[]. Lida com escape HTML do Hubitat. */
private byte[] getRealLocalKey() {
    return (settings?.localKey ?: "").replaceAll('&lt;', '<').getBytes("UTF-8")
}

/** AES-128-ECB encrypt com PKCS5Padding. Retorna byte[] cifrado. */
private byte[] encryptBytes(byte[] data, byte[] key) {
    Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"))
    return cipher.doFinal(data)
}

/** AES-128-ECB encrypt. Retorna string hexadecimal (usado no cálculo da sessionKey). */
private String encryptToHex(byte[] data, byte[] key) {
    return HexUtils.byteArrayToHexString(encryptBytes(data, key))
}

/** AES-128-ECB encrypt. Retorna string Base64 (usado no protocolo v3.1). */
private String encryptToBase64(byte[] data, byte[] key) {
    return encryptBytes(data, key).encodeBase64().toString()
}

/**
 * AES-128-ECB decrypt de um trecho de raw[].
 * isBase64=true → decodifica Base64 antes de decifrar (protocolo v3.1).
 * Retorna string vazia em caso de erro (log debug).
 */
private String decryptPayload(byte[] raw, byte[] key, Integer start, Integer length, boolean isBase64 = false) {
    ByteArrayOutputStream buf = new ByteArrayOutputStream()
    (0..<length).each { buf.write(raw[start + it]) }
    byte[] payload = buf.toByteArray()
    try {
        byte[] decoded = isBase64 ? payload.decodeBase64() : payload
        Cipher cipher  = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"))
        return new String(cipher.doFinal(decoded), "UTF-8")
    } catch (e) {
        logDebug "decryptPayload error: ${e.message}"
        return ""
    }
}

/** HMAC-SHA256 para assinatura de frames v3.4. Retorna digest de 32 bytes. */
private byte[] hmacSha256(byte[] data, byte[] key) {
    Mac mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}

/** MD5 hex string do texto fornecido. Usado na autenticação de SET no protocolo v3.1. */
private String md5hex(String text) {
    MessageDigest md = MessageDigest.getInstance("MD5")
    return HexUtils.byteArrayToHexString(md.digest(text.bytes)).toLowerCase()
}

/** CRC32 em big-endian como byte[4]. Checksum de frames v3.1 e v3.3. */
private byte[] crc32bytes(byte[] data) {
  //  java.util.zip.CRC32 crc = new java.util.zip.CRC32()
    crc.update(data)
    long v = crc.getValue()
    return [(byte)((v >> 24) & 0xFF), (byte)((v >> 16) & 0xFF),
            (byte)((v >> 8)  & 0xFF), (byte)(v         & 0xFF)] as byte[]
}

/* ══════════════════════════════════════════════════════════════════════════════
 *  HELPERS GERAIS
 * ══════════════════════════════════════════════════════════════════════════════ */

/**
 * Retorna o próximo número de sequência (0..65534, com wrap).
 * Cada frame Tuya enviado recebe um número único para rastreamento.
 */
private Short getNextSeq() {
    if (state.Msgseq == null) state.Msgseq = 0
    state.Msgseq = (state.Msgseq + 1) % 65535
    return state.Msgseq as Short
}

/** Retorna o epoch Unix atual em segundos como String (campo "t" dos frames Tuya). */
private String nowEpochSec() {
    return (now() / 1000L).toLong().toString()
}

/** Converte Integer para big-endian byte[4]. Usado na montagem de cabeçalhos de frame. */
private static byte[] intToBytes4(Integer v) {
    return [(byte)((v >> 24) & 0xFF), (byte)((v >> 16) & 0xFF),
            (byte)((v >> 8)  & 0xFF), (byte)(v         & 0xFF)] as byte[]
}

/**
 * Serializa um Map para JSON compacto no formato esperado pelo protocolo Tuya.
 * JsonBuilder do Groovy adiciona escapes extras que o device rejeita;
 * este método remove os escapes e aspas externas adicionadas pelo JsonOutput.
 */
private static String cleanJson(Map m) {
    String j = new JsonBuilder(m).toString()
    j = JsonOutput.toJson(j).replaceAll("\\\\", "")
    j = j.replaceFirst('"', '')
    return j[0..-2]
}

/* ── Emissão de eventos ──────────────────────────────────────────────────────── */
/**
 * Emite sendEvent apenas se o valor mudou OU se o último envio foi há mais
 * de minSec segundos (mecanismo anti-spam com staleness guard).
 * Mantém última emissão em state["ev_<name>_val"] e state["ev_<name>_ts"].
 * Retorna true se o evento foi emitido.
 */
private boolean emitIfChanged(String name, Object value, String unit, Integer minSec = 30) {
    def  lastVal = state["ev_${name}_val"]
    Long lastTs  = (state["ev_${name}_ts"] ?: 0L) as Long
    boolean changed = (lastVal?.toString() != value?.toString())
    boolean stale   = (now() - lastTs) > (minSec * 1000L)
    if (changed || stale) {
        Map evt = [name: name, value: value]
        if (unit) evt.unit = unit
        sendEvent(evt)
        state["ev_${name}_val"] = value?.toString()
        state["ev_${name}_ts"]  = now()
        return true
    }
    return false
}

/* ── Helpers numéricos ───────────────────────────────────────────────────────── */

/**
 * Converte o valor para Integer clampado no intervalo [minV..maxV].
 * Retorna minV se o parse falhar.
 */
private static Integer safeIntClamped(def n, Integer minV, Integer maxV, String ctx = "") {
    Integer v = minV
    try {
        v = (n instanceof Number) ? ((Number)n).intValue() : n.toString().toInteger()
    } catch (ignored) { return minV }
    if (v < minV) v = minV
    else if (v > maxV) v = maxV
    return v
}

/** Converte para BigDecimal de forma segura; retorna null se inválido. */
private static BigDecimal safeBigDecimal(def v) {
    try { return (v instanceof Number) ? (v as BigDecimal) : new BigDecimal(v.toString()) }
    catch (ignored) { return null }
}

/** Converte cm para metros; retorna null se o valor for inválido ou zero. */
private static BigDecimal cmToM(def cm) {
    BigDecimal v = safeBigDecimal(cm)
    return (v != null && v > 0G) ? v / 100G : null
}

/**
 * Converte metros para o inteiro raw exigido pelo protocolo Tuya.
 * raw = metros × 10^scale, clampado em [minRaw..maxRaw].
 * Ex: setInstallationHeight(1.5) com scale=3 → raw=1500, clampado em [100..3000]
 */
private static Integer metersToScaledInt(def meters, int scale, int minRaw, int maxRaw) {
    BigDecimal m   = safeBigDecimal(meters) ?: 0G
    Integer raw    = (m * (10 ** scale as BigDecimal)).setScale(0, BigDecimal.ROUND_HALF_UP).intValue()
    return raw.clamp(minRaw, maxRaw)
}

/** Lê atributo do device e retorna como Integer; null se ausente ou inválido. */
private Integer currentInteger(String attr) {
    def v = device.currentValue(attr)
    if (v == null) return null
    try { return (v instanceof Number) ? ((Number)v).intValue() : v.toString().toInteger() }
    catch (ignored) { return null }
}

/** Lê atributo do device e retorna como BigDecimal; null se ausente ou inválido. */
private BigDecimal currentDecimal(String attr) {
    def v = device.currentValue(attr)
    if (v == null) return null
    try { return (v instanceof Number) ? (v as BigDecimal) : new BigDecimal(v.toString()) }
    catch (ignored) { return null }
}

/** Arredonda value para o múltiplo mais próximo de step. Sem arredondamento se step ≤ 1. */
private static BigDecimal roundToStep(BigDecimal value, int step) {
    if (step <= 1) return value
    BigDecimal s = step as BigDecimal
    return ((value / s).setScale(0, BigDecimal.ROUND_HALF_UP)) * s
}

/** Formata BigDecimal como inteiro com separador de milhares (ex.: 1.234). */
private static String formatInt(BigDecimal n) {
    try { return String.format("%,d", n.setScale(0, BigDecimal.ROUND_HALF_UP).intValue()) }
    catch (ignored) { return n.toString() }
}

/* ── Logging ─────────────────────────────────────────────────────────────────── */
// Todos os métodos de log incluem o displayName do device para facilitar debug
// em ambientes com múltiplos devices do mesmo tipo.
private void logDebug(String msg) { if (settings?.logEnable) log.debug "[${device.displayName}] ${msg}" }
private void logInfo(String msg)  { log.info  "[${device.displayName}] ${msg}" }
private void logDesc(String msg)  { if (settings?.txtEnable) log.info "[${device.displayName}] ${msg}" }