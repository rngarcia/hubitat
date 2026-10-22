/**
 *  Tuya Zigbee Water Level Sensor — Hubitat Driver
 *  Versão: 2.12.1 — Build: 2026-10-21
 *  Autor: Ricardo Nogueira Garcia
 *  Repositório: https://github.com/rngarcia/hubitat
 *  Gostou? Buy me a beer: https://www.paypal.com/donate/?business=rngarcia%40gmail.com&currency_code=USD
 *
 *  Changelog:
 *  v2.12.1 (2026-10-21) - RNG
 *    - Publicado em rngarcia/hubitat: cabeçalho padronizado, namespace "rng" e importUrl
 *    - Nome do driver sem o sufixo "- Claude"
 *  (histórico anterior no bloco de comentário abaixo)
 */
/**
 *  Tuya Zigbee Water Tank Level Sensor (Ultrasonic)
 *
 *  Author: Ricardo Nogueira Garcia + ChatGPT
 *  Namespace: rng
 *  Date: 2026-07-04
 *  Version: 2.12.0
 *
 *  DPs (EF00) usados:
 *    - DP  1  liquidState           (enum)   ro   0=normal,1=low,2=high
 *    - DP  2  liquidDepth           (decimal)ro   scale=100 unit=m
 *          Altura da coluna de água (confirmado via dados reais na v2.12.0 --
 *          ver nota abaixo), não "distância sensor->água" como documentado
 *          pelo driver de referência do kkossev.
 *    - DP  7  upperLimit            (number) rw   % high threshold
 *          Relativo a installationHeight (aciona liquidState alto/baixo).
 *    - DP  8  lowerLimit            (number) rw   % low threshold
 *          Idem acima.
 *    - DP 19  installationHeight    (number) rw   mm  (sensor -> fundo do tanque)
 *    - DP 21  liquidDepthMax        (number) rw   mm  (usado só como referência
 *          para derivar a altura útil máxima; ver fórmula abaixo)
 *    - DP 22  liquidLevelPercent    (number) ro   %
 *
 *  CONFIRMAÇÃO DE FÓRMULA (v2.12.0): testada contra um snapshot de dados reais
 *  (installationHeightMm=820, liquidDepthMaxMm=200, depthCm=18 -> DP22 bruto=29%;
 *  depthCm=17 -> DP22=27-28%). Nem a fórmula original ((installationHeight-depth)/
 *  (installationHeight-max), confirmada na v2.6.0 via fingerprint _TZE200_lvkk0hdg)
 *  nem a hipótese depth/max batiam com o DP22 observado. A fórmula que bate:
 *
 *      maxWaterHeightMm = installationHeightMm - liquidDepthMaxMm
 *      pct              = liquidDepthMm / maxWaterHeightMm * 100
 *
 *  Ou seja: liquidDepth representa a altura da coluna de água diretamente
 *  (não distância sensor->água), mas liquidDepthMax segue combinado com
 *  installationHeight para derivar a altura útil máxima -- uma combinação
 *  híbrida das duas hipóteses testadas nas versões anteriores (v2.5.0/v2.6.0).
 *
 *  Atributos calculados (não escritos no device, só para conferência):
 *    - liquidLevelPercentCalc (%, inteiro) -- percentual calculado localmente,
 *          fonte redundante enquanto se observa se o liquidLevelPercent (DP22)
 *          bruto do device responde de forma confiável.
 *    - liquidDepthMaxCalcMm (mm) -- installationHeightMm - liquidDepth(atual).
 *          O que liquidDepthMaxMm deveria ser SE a caixa estivesse cheia agora
 *          (consistente com a fórmula de calibração). Compare com
 *          liquidDepthMaxMm para aferir a calibração.
 *    - maxWaterHeightCm (cm) -- altura útil de água, informativo.
 *
 *  Adds:
 *    - Configurable polling interval (preference) with automatic scheduling
 *    - Um único comando de calibração fina: calibSetDepthMaxFromCurrentLevel,
 *      para usar quando a caixa estiver fisicamente cheia.
 *
 *  Changelog:
 *    1.0.7 (2026-01-19) - Versão inicial com suporte completo aos DPs mapeados.
 *    2.0.0 (2026-06-18) - Rewrite com protocolo TCP/AES local.
 *    2.1.0 (2026-06-18) - Debounce por threshold no liquidDepth (depthThresholdCm)
 *          para reduzir Hub Load com sensores ultrassônicos ruidosos.
 *    2.2.0 (2026-07-04) - Adiciona liquidLevelPercentCalc (percentual calculado
 *          localmente), já que o DP22 bruto ficava travado.
 *    2.3.0 (2026-07-04) - Adiciona cálculo de volume, em paridade com o driver Wi-Fi.
 *    2.3.1 (2026-07-04) - Corrige condição de corrida: recalcLevelPercentCalc()/
 *          recalcVolume() passam a usar state em vez de device.currentValue().
 *    2.3.2 (2026-07-04) - Só recalcula quando a leitura bruta muda de fato,
 *          evitando recomputo/log a cada ~1s com valor repetido.
 *    2.4.0 (2026-07-04) - Limpeza geral: removido bloco de volume e liquidDepth
 *          público; comandos reduzidos aos parâmetros do sensor + calibração
 *          única (calibSetDepthMaxFromCurrentLevel); corrigida mesma condição
 *          de corrida em installationHeight/liquidDepthMax.
 *    2.5.0 (2026-07-04) - [REVERTIDA na 2.6.0] Tentativa de correção de fórmula
 *          baseada em documentação de outro fingerprint (_TZE284_kyyu8rbj);
 *          não se aplicava a este hardware.
 *    2.6.0 (2026-07-04) - Reverte a fórmula para a original (installationHeight
 *          - liquidDepth), confirmada correta via fingerprint real do device
 *          e comparação com o driver de referência do kkossev. Restaura
 *          maxWaterHeightCm/liquidDepthMaxCalcMm; liquidDepth continua público.
 *    2.7.0 (2026-07-04) - parse() loga o frame Zigbee bruto completo em modo
 *          debug; liquidLevelPercentCalc arredondado para inteiro; nome do
 *          driver passa a "Tuya Zigbee Water Level Sensor - Claude" para
 *          diferenciar do driver de referência do kkossev.
 *    2.8.0 (2026-07-05) - Corrige recalcLevelPercentCalc() abortando
 *          silenciosamente quando installationHeightMm/liquidDepthMaxMm
 *          somem de state (observado após reload). Novo helper stateOrAttr()
 *          cai para device.currentValue() quando o state está vazio.
 *    2.9.0 (2026-07-05) - Corrige fórmula de liquidDepthMaxCalcMm: era
 *          installationHeightMm-depthMm (equivalente a maxWaterHeightMm,
 *          nunca comparável com liquidDepthMaxMm); agora é a leitura atual
 *          em mm, igual à fórmula já usada na calibração.
 *    2.10.0 (2026-07-06) - Novo comando queryRawDPs(): solicita todos os DPs
 *          e loga (log.info, sempre visível) o valor puro em decimal de cada
 *          DP recebido nos 10s seguintes, sem nenhuma conversão de unidade --
 *          para inspecionar exatamente o que o device retorna.
 *    2.11.0 (2026-07-06) - queryRawDPs() agora também acumula os DPs alvo
 *          (1,2,7,8,19,21,22) e loga um snapshot consolidado em uma única
 *          linha assim que todos chegarem (ou em até 11s com o que tiver
 *          sido recebido), para comparar todos os valores do mesmo instante.
 *    2.12.0 (2026-07-06) - CORRIGE a fórmula de recalcLevelPercentCalc() com
 *          base em snapshot de dados reais (queryRawDPs): pct = liquidDepthMm
 *          / (installationHeightMm - liquidDepthMaxMm) * 100 -- nem a fórmula
 *          da v2.6.0 nem a hipótese depth/max batiam com o DP22 observado.
 *          calibSetDepthMaxFromCurrentLevel() ajustada correspondentemente
 *          (liquidDepthMax = installationHeight - depth atual, quando cheio).
 */

import groovy.transform.Field
import hubitat.device.HubMultiAction
import hubitat.device.Protocol

metadata {
    definition(
        name: "Tuya Zigbee Water Level Sensor",
        namespace: "rng",
        author: "Ricardo Nogueira Garcia",
        importUrl: "https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/tuya-zigbee-water-level/tuya-zigbee-water-level.groovy"
    ) {
        capability "Sensor"
        capability "Refresh"

        // Primary readings
        attribute "liquidState", "string"            // normal / low / high
        attribute "liquidDepth", "number"            // cm -- distância sensor->água (leitura bruta)
        attribute "liquidLevelPercent", "number"     // % (DP22, bruto)
        attribute "liquidLevelPercentCalc", "number" // % (calculado localmente -- fonte confiável enquanto DP22 é validado)

        // Config DPs (read/write) -- mesmos parâmetros configuráveis do sensor
        attribute "upperLimit", "number"             // % (DP7, relativo a installationHeight)
        attribute "lowerLimit", "number"             // % (DP8, relativo a installationHeight)
        attribute "installationHeightMm", "number"   // mm (DP19, sensor -> fundo do tanque)
        attribute "liquidDepthMaxMm", "number"       // mm (DP21, sensor -> nível máximo de água)

        // Calculados, apenas para conferência/aferição (não escritos no device)
        attribute "liquidDepthMaxCalcMm", "number"   // mm -- installationHeightMm - liquidDepth(atual)
        attribute "maxWaterHeightCm", "number"       // cm -- (installationHeightMm - liquidDepthMaxMm) / 10

        // Commands (RW DPs) -- mesmos parâmetros configuráveis do sensor
        command "setUpperLimit", [[name:"Alto (%)", type:"NUMBER"]]
        command "setLowerLimit", [[name:"Baixo (%)", type:"NUMBER"]]
        command "setInstallationHeightCm", [[name:"Altura instalação (cm)", type:"NUMBER"]]
        command "setLiquidDepthMaxMm", [[name:"Distância sensor->nível máximo (mm)", type:"NUMBER"]]

        // Calibração fina: usar quando a caixa estiver fisicamente CHEIA.
        command "calibSetDepthMaxFromCurrentLevel"

        // Polling manual/automático
        command "pollDevice"

        // Diagnóstico: pede um refresh e loga (via log.info, sempre visível
        // independente do modo debug) o valor puro em decimal de cada DP
        // recebido nos próximos 10s, sem nenhuma conversão de unidade.
        command "queryRawDPs"
    }

    preferences {
        input name: "logDebug", type: "bool",
              title: "Enable debug logging (auto off em 30 min)", defaultValue: false

        input name: "logDesc", type: "bool",
              title: "Enable descriptionText logging", defaultValue: true

        input name: "depthThresholdCm", type: "number",
              title: "<b>Threshold de mudança de nível (cm)</b> — ignora variações menores que este valor " +
                     "antes de recalcular o percentual. Evita oscilação de sensores ultrassônicos " +
                     "(ex: 55/56 cm em loop). Padrão: 2 cm.",
              defaultValue: 2

        // Polling / Refresh (EF00 query)
        input name: "pollEnable", type: "bool",
              title: "Ativar polling (refresh periódico)", defaultValue: false

        input name: "pollIntervalMin", type: "number",
              title: "Intervalo do polling (minutos)", defaultValue: 10

        input name: "pollOnlyWhenNoRecentReportsMin", type: "number",
              title: "Só fazer poll se não houver report recente (min) [0 = sempre]", defaultValue: 0
    }
}

/* -------------------------
 *  Tuya DP Map (EF00)
 * ------------------------- */
@Field static final Map TUYA_DP = [
    liquidState        : 0x01, // enum (0=normal,1=low,2=high)
    liquidDepth        : 0x02, // decimal (scale=100, unit=m)
    upperLimit         : 0x07, // % (rw)
    lowerLimit         : 0x08, // % (rw)
    installationHeight : 0x13, // 19 decimal, mm (rw)
    liquidDepthMax     : 0x15, // 21 decimal, mm (rw)
    liquidLevelPercent : 0x16  // 22 decimal, % (ro)
]

@Field static final Map TUYA_DP_NAMES = [
    (0x01): "liquidState",
    (0x02): "liquidDepth",
    (0x07): "upperLimit",
    (0x08): "lowerLimit",
    (0x13): "installationHeight",
    (0x15): "liquidDepthMax",
    (0x16): "liquidLevelPercent"
]

// DPs alvo do queryRawDPs(), na ordem em que devem aparecer no snapshot
// consolidado (dp1, dp2, dp7, dp8, dp19, dp21, dp22).
@Field static final List<Integer> RAW_DUMP_TARGET_DPS = [1, 2, 7, 8, 19, 21, 22]

/* -------------------------
 *  Lifecycle
 * ------------------------- */
def installed() {
    updated()
}

def updated() {
    if (logDebug) {
        log.debug "updated(): pollEnable=${settings?.pollEnable}, pollIntervalMin=${settings?.pollIntervalMin}, " +
                  "pollOnlyWhenNoRecentReportsMin=${settings?.pollOnlyWhenNoRecentReportsMin}, " +
                  "depthThresholdCm=${settings?.depthThresholdCm}"
        unschedule("logsOff")
        runIn(30 * 60, "logsOff")
    }

    unschedule("pollDevice")
    unschedule("pollReschedule")

    if (settings?.pollEnable) {
        pollReschedule()
    }

    // Garante que o percentual calculado reflita a calibração atual assim que
    // as preferences forem salvas.
    runIn(1, "recalcLevelPercentCalc")
}

def logsOff() {
    if (logDebug) log.warn "Debug logging desligado automaticamente (30 min)."
    try {
        device.updateSetting("logDebug", [value: "false", type: "bool"])
    } catch (ignored) {}
}

/* -------------------------
 *  Parse
 * ------------------------- */
def parse(String description) {
    // Log do frame Zigbee bruto completo (não só o data[] já recortado) --
    // inspirado no driver de referência do kkossev, útil para depurar sem
    // depender exclusivamente do que o parser já extraiu.
    if (logDebug) log.debug "${device.displayName} parse: raw=${description}"

    Map descMap = zigbee.parseDescriptionAsMap(description)
    if (descMap?.clusterInt == 0xEF00) {
        processTuyaCluster(descMap)
    } else {
        if (logDebug) log.debug "parse(): Ignorando cluster ${descMap?.clusterInt} description=${description}"
    }
}

/* -------------------------
 *  Tuya EF00 handling
 * ------------------------- */
def processTuyaCluster(Map descMap) {
    state.lastReportTs = now()

    // Ignore Tuya EF00 ACK frames (cmd 0x0B)
    if (descMap?.command == "0B") {
        if (logDebug) log.debug "Tuya EF00 ACK (cmd 0x0B): data=${descMap?.data}"
        return
    }

    Integer dp  = safeHexToInt(descMap?.data?.getAt(2))
    Integer len = safeHexToInt(descMap?.data?.getAt(5))
    Integer value = (dp != null && len != null) ? getTuyaValue(descMap.data, len) : null

    if (dp == null || len == null || value == null) {
        if (logDebug) log.debug "processTuyaCluster(): pacote EF00 inesperado: ${descMap}"
        return
    }

    if (logDebug) {
        String dpName = TUYA_DP_NAMES[dp] ?: "?"
        log.debug "Tuya DP ${dp} (${dpName}) → ${value} (len=${len})"
    }

    // Diagnóstico via queryRawDPs(): loga o valor puro em decimal (sem
    // conversão de unidade) de cada DP recebido, independente de logDebug,
    // enquanto a janela de dump estiver ativa.
    Long rawDumpUntil = state.rawDumpUntil as Long
    if (rawDumpUntil != null && now() <= rawDumpUntil) {
        String dpName = TUYA_DP_NAMES[dp] ?: "?"
        log.info "${device.displayName} RAW DP dump: dp=${dp} (${dpName}) value=${value}"

        // Acumula os DPs alvo (1,2,7,8,19,21,22) para o snapshot consolidado
        // em uma única linha. Assim que todos chegarem, loga o resumo na hora
        // (sem esperar o timeout de 11s do runIn).
        if (dp in RAW_DUMP_TARGET_DPS) {
            Map vals = (state.rawDumpValues ?: [:]) as Map
            vals[dp.toString()] = value
            state.rawDumpValues = vals
            if (vals.size() >= RAW_DUMP_TARGET_DPS.size()) {
                logRawDumpSummary()
            }
        }
    }

    switch (dp) {
        case TUYA_DP.liquidState:
            String st = (value == 0) ? "normal" : (value == 1) ? "low" : "high"
            if (sendEventIfChanged("liquidState", st, null)) {
                if (logDesc) log.info "${device.displayName} liquid state: ${st}"
            }
            break

        case TUYA_DP.liquidDepth:
            handleLiquidDepth(value)
            break

        case TUYA_DP.liquidLevelPercent:
            // Mantido apenas para referência -- ver nota no topo sobre não-confiabilidade.
            if (sendEventIfChanged("liquidLevelPercent", value, "%")) {
                if (logDesc) log.info "${device.displayName} Tank level (raw): ${value}%"
            }
            break

        case TUYA_DP.upperLimit:
            if (sendEventIfChanged("upperLimit", value, "%")) {
                if (logDesc) log.info "${device.displayName} Upper limit: ${value}%"
            }
            break

        case TUYA_DP.lowerLimit:
            if (sendEventIfChanged("lowerLimit", value, "%")) {
                if (logDesc) log.info "${device.displayName} Lower limit: ${value}%"
            }
            break

        case TUYA_DP.installationHeight:
            applyInstallationHeightMm(value)
            break

        case TUYA_DP.liquidDepthMax:
            applyLiquidDepthMaxMm(value)
            break

        default:
            if (logDebug) log.debug "${device.displayName} DP não tratado: ${dp} (value=${value})"
    }
}

/**
 * Processa uma leitura bruta de liquidDepth (DP2). Exposto como atributo
 * público (ver changelog 2.5.0 -- central para verificar calibração no
 * modelo corrigido). Só emite/recalcula quando a leitura muda pelo menos
 * depthThresholdCm em relação à última leitura processada, para reduzir Hub
 * Load com sensores ultrassônicos ruidosos.
 */
private void handleLiquidDepth(Integer rawValue) {
    // Spec: decimal scale=100, unit=m. Example: value=63 -> 0.63 m -> 63 cm.
    BigDecimal meters = (rawValue as BigDecimal) / 100G
    Integer cm = Math.round(meters * 100G) as Integer

    Integer previousCm = stateOrAttr('lastLiquidDepthCm', 'liquidDepth')
    Integer threshold = Math.max(1, (settings?.depthThresholdCm ?: 2) as Integer)
    boolean changedEnough = (previousCm == null) || (Math.abs(cm - previousCm) >= threshold)

    state.lastLiquidDepthCm = cm

    if (!changedEnough) {
        if (logDebug) {
            log.debug "${device.displayName} liquidDepth: ${previousCm} -> ${cm} cm ignorado (delta=${Math.abs(cm - previousCm)} < threshold=${threshold} cm)"
        }
        return
    }

    if (sendEventIfChanged("liquidDepth", cm, "cm")) {
        if (logDesc) log.info "${device.displayName} Liquid depth: ${cm} cm"
    }
    recalcLevelPercentCalc()
}

private void applyInstallationHeightMm(Integer mm) {
    // Captura direto do evento recebido, antes de qualquer releitura via
    // device.currentValue() -- elimina a condição de corrida já vista antes.
    state.lastInstallationHeightMm = mm

    boolean changed = sendEventIfChanged("installationHeightMm", mm, "mm")
    updateDerivedMaxWaterHeight()
    recalcLevelPercentCalc()

    if (changed && logDesc) log.info "${device.displayName} Installation height: ${Math.round(mm/10.0) as Integer} cm (${mm} mm)"
}

private void applyLiquidDepthMaxMm(Integer mm) {
    state.lastLiquidDepthMaxMm = mm

    boolean changed = sendEventIfChanged("liquidDepthMaxMm", mm, "mm")
    updateDerivedMaxWaterHeight()
    recalcLevelPercentCalc()

    if (changed && logDesc) log.info "${device.displayName} Liquid depth max: ${mm} mm"
}

/**
 * Lê um valor Integer de state[stateKey]; se ausente (ex.: state perdido após
 * reload do driver/hub), cai para device.currentValue(attrName) -- os
 * atributos são persistidos de forma independente do mapa state e sobrevivem
 * a esse tipo de perda. Se encontrado via fallback, também repopula o state
 * para as próximas chamadas. Corrige o caso observado em produção onde
 * installationHeightMm/liquidDepthMaxMm sumiram de state após um reload,
 * abortando recalcLevelPercentCalc() silenciosamente até o próximo comando
 * de Set ou report espontâneo desses DPs (que pode demorar horas).
 */
private Integer stateOrAttr(String stateKey, String attrName) {
    Integer v = state[stateKey] as Integer
    if (v != null) return v

    def cur = device.currentValue(attrName)
    if (cur == null) return null

    try {
        Integer parsed = (cur instanceof Number) ? (cur as Number).intValue() : cur.toString().toInteger()
        state[stateKey] = parsed
        if (logDebug) log.debug "${device.displayName} stateOrAttr(): ${stateKey} recuperado de currentValue(${attrName})=${parsed}"
        return parsed
    } catch (ignored) {
        return null
    }
}

private void updateDerivedMaxWaterHeight() {
    Integer instMm = stateOrAttr('lastInstallationHeightMm', 'installationHeightMm')
    Integer maxMm  = stateOrAttr('lastLiquidDepthMaxMm', 'liquidDepthMaxMm')
    if (instMm == null || maxMm == null) return

    Integer maxWaterHeightCm = Math.round((instMm - maxMm) / 10.0) as Integer
    if (maxWaterHeightCm < 0) maxWaterHeightCm = 0

    if (sendEventIfChanged("maxWaterHeightCm", maxWaterHeightCm, "cm")) {
        if (logDesc) log.info "${device.displayName} Max water height: ${maxWaterHeightCm} cm"
    }
}

/* -------------------------
 *  Percentual calculado localmente
 * ------------------------- */
/**
 * Calcula liquidLevelPercentCalc e liquidDepthMaxCalcMm a partir de:
 *   - state.lastLiquidDepthCm        -> leitura bruta atual (cm)
 *   - state.lastInstallationHeightMm -> sensor->fundo do tanque (mm)
 *   - state.lastLiquidDepthMaxMm     -> nível máximo configurado (mm)
 *
 * Fórmula CONFIRMADA na v2.12.0 via dados reais (snapshot com
 * installationHeightMm=820, liquidDepthMaxMm=200, depthCm=18 -> DP22
 * bruto=29%; depthCm=17 -> DP22=27-28%): nem a fórmula original
 * (installationHeight-depth)/(installationHeight-max), nem depth/max
 * batiam com o DP22 observado. A fórmula que bate é:
 *
 *     maxWaterHeightMm = installationHeightMm - liquidDepthMaxMm
 *     pct              = liquidDepthMm / maxWaterHeightMm * 100
 *
 * Ou seja, liquidDepth (bruto) representa a altura da coluna de água
 * diretamente (não distância sensor->água), enquanto liquidDepthMax
 * continua sendo usado apenas para derivar a altura útil máxima junto
 * com installationHeight -- uma combinação híbrida das duas hipóteses
 * testadas anteriormente (v2.5.0 e v2.6.0).
 */
void recalcLevelPercentCalc() {
    Integer depthCm = stateOrAttr('lastLiquidDepthCm', 'liquidDepth')
    Integer instMm  = stateOrAttr('lastInstallationHeightMm', 'installationHeightMm')
    Integer maxMm   = stateOrAttr('lastLiquidDepthMaxMm', 'liquidDepthMaxMm')

    if (depthCm == null || instMm == null || maxMm == null) {
        if (logDebug) log.debug "recalcLevelPercentCalc(): dados insuficientes (depthCm=${depthCm}, instMm=${instMm}, maxMm=${maxMm}) — abortando"
        return
    }

    BigDecimal maxWaterHeightMm = (instMm - maxMm) as BigDecimal
    if (maxWaterHeightMm <= 0G) {
        log.warn "${device.displayName}: recalcLevelPercentCalc(): maxWaterHeightMm=${maxWaterHeightMm} <= 0 " +
                 "(installationHeightMm=${instMm}, liquidDepthMaxMm=${maxMm}) — verifique a calibração"
        return
    }

    BigDecimal depthMm = (depthCm as BigDecimal) * 10G
    BigDecimal waterHeightMm = depthMm

    if (waterHeightMm < 0G) waterHeightMm = 0G
    if (waterHeightMm > maxWaterHeightMm) waterHeightMm = maxWaterHeightMm

    BigDecimal pctDecimal = (waterHeightMm / maxWaterHeightMm) * 100G
    Integer pct = pctDecimal.setScale(0, BigDecimal.ROUND_HALF_UP).intValue()

    if (sendEventIfChanged("liquidLevelPercentCalc", pct, "%")) {
        if (logDesc) log.info "${device.displayName} Liquid level (calc): ${pct}%"
    }

    // liquidDepthMaxCalcMm: o que liquidDepthMaxMm deveria ser, SE a caixa
    // estivesse cheia agora -- installationHeightMm - depthMm (consistente
    // com a fórmula acima: no ponto de calibração, depthAtFull =
    // installationHeightMm - liquidDepthMaxMm).
    BigDecimal depthMaxCalc = (instMm as BigDecimal) - depthMm
    if (depthMaxCalc < 0G) depthMaxCalc = 0G
    Integer depthMaxCalcMm = depthMaxCalc.setScale(0, BigDecimal.ROUND_HALF_UP).intValue()
    if (sendEventIfChanged("liquidDepthMaxCalcMm", depthMaxCalcMm, "mm")) {
        if (logDesc) log.info "${device.displayName} Liquid depth max (calc): ${depthMaxCalcMm} mm"
    }

    if (logDebug) {
        log.debug "recalcLevelPercentCalc: depthCm=${depthCm} instMm=${instMm} maxMm=${maxMm} " +
                   "maxWaterHeightMm=${maxWaterHeightMm} waterHeightMm=${waterHeightMm} pct=${pct}% " +
                   "liquidDepthMaxCalcMm=${depthMaxCalcMm}"
    }
}

/* -------------------------
 *  Commands (RW)
 * ------------------------- */
def setUpperLimit(Number percent) {
    Integer p = clampInt(percent, 0, 100, "setUpperLimit")
    if (logDebug) log.debug "setUpperLimit(): ${percent} -> ${p}%"
    dispatchTuyaCommand(TUYA_DP.upperLimit, "upperLimit", p)
}

def setLowerLimit(Number percent) {
    Integer p = clampInt(percent, 0, 100, "setLowerLimit")
    if (logDebug) log.debug "setLowerLimit(): ${percent} -> ${p}%"
    dispatchTuyaCommand(TUYA_DP.lowerLimit, "lowerLimit", p)
}

def setInstallationHeightCm(Number cm) {
    Integer mm = Math.round((cm as BigDecimal) * 10G) as Integer
    mm = clampInt(mm, 100, 3000, "setInstallationHeightCm")
    if (logDebug) log.debug "setInstallationHeightCm(): ${cm}cm -> ${mm}mm"
    applyInstallationHeightMm(mm)
    dispatchTuyaCommand(TUYA_DP.installationHeight, "installationHeight", mm)
}

def setLiquidDepthMaxMm(Number mm) {
    Integer v = clampInt(mm, 100, 3000, "setLiquidDepthMaxMm")
    if (logDebug) log.debug "setLiquidDepthMaxMm(): ${mm}mm -> ${v}mm"
    applyLiquidDepthMaxMm(v)
    dispatchTuyaCommand(TUYA_DP.liquidDepthMax, "liquidDepthMax", v)
}

/**
 * Envia o valor ao device via Zigbee e confirma o envio no log (mesmo padrão
 * de confirmação usado no driver Wi-Fi, "Comando enviado ao Tuya Cloud").
 */
private void dispatchTuyaCommand(Integer dp, String dpName, Integer value) {
    sendZigbeeCommands(sendTuyaValueDP(dp, value))
    logInfo "Comando enviado via Zigbee: DP=${dp} (${dpName})=${value}"
    runInMillis(1500, "refresh")
}

/* -------------------------
 *  Calibração fina (usar com a caixa CHEIA)
 * ------------------------- */
/**
 * Calibra liquidDepthMaxMm a partir do estado atual, para usar quando a caixa
 * estiver fisicamente cheia.
 *
 * Fórmula CORRIGIDA na v2.12.0, consistente com a fórmula confirmada de
 * recalcLevelPercentCalc() (pct = depth / (installationHeight - liquidDepthMax)):
 * quando o tanque está cheio, depthAtFull = installationHeight - liquidDepthMax,
 * logo:
 *
 *     liquidDepthMaxMm = installationHeightMm - liquidDepth(atual, mm)
 */
void calibSetDepthMaxFromCurrentLevel() {
    Integer depthCm = stateOrAttr('lastLiquidDepthCm', 'liquidDepth')
    Integer instMm  = stateOrAttr('lastInstallationHeightMm', 'installationHeightMm')

    if (depthCm == null) {
        log.warn "${device.displayName}: calibSetDepthMaxFromCurrentLevel(): liquidDepth atual ainda não recebido; faça um Refresh antes."
        return
    }
    if (instMm == null) {
        log.warn "${device.displayName}: calibSetDepthMaxFromCurrentLevel(): installationHeightMm ainda não recebido; faça um Refresh antes."
        return
    }

    Integer depthMm = depthCm * 10
    Integer newMaxMm = instMm - depthMm

    if (newMaxMm <= 0) {
        log.warn "${device.displayName}: calibSetDepthMaxFromCurrentLevel(): resultado ${newMaxMm}mm <= 0 " +
                 "(installationHeightMm=${instMm}, liquidDepth=${depthMm}mm) — confira se a caixa está realmente cheia e se installationHeightMm está calibrado."
        return
    }

    if (logDebug) log.debug "calibSetDepthMaxFromCurrentLevel(): installationHeightMm=${instMm} - liquidDepth=${depthMm}mm -> liquidDepthMaxMm=${newMaxMm}"
    setLiquidDepthMaxMm(newMaxMm)
}

/* -------------------------
 *  Diagnóstico: dump raw de todos os DPs
 * ------------------------- */
/**
 * Solicita ao device (via o mesmo queryAllTuyaDP() usado no refresh) todos os
 * DPs, e abre uma janela de 10s durante a qual processTuyaCluster() loga
 * (via log.info, sempre visível, independente de logDebug) o valor puro em
 * decimal de cada DP recebido -- sem nenhuma conversão de unidade (cm/mm/%).
 *
 * Além disso, acumula os DPs alvo (1,2,7,8,19,21,22) e loga um snapshot
 * consolidado em uma única linha assim que todos chegarem (ou, no máximo,
 * 11s depois, com o que tiver sido recebido até lá).
 */
void queryRawDPs() {
    state.rawDumpUntil = now() + 10_000L
    state.rawDumpValues = [:]
    unschedule("logRawDumpSummary")
    runIn(11, "logRawDumpSummary")
    logInfo "queryRawDPs(): solicitando DPs 1,2,7,8,19,21,22 — snapshot consolidado em até 11s"
    def cmds = queryAllTuyaDP()
    sendZigbeeCommands(cmds)
}

/**
 * Loga em uma única linha os valores puros (decimal) dos DPs alvo
 * (1,2,7,8,19,21,22) recebidos desde o último queryRawDPs(). DPs que não
 * chegaram a tempo aparecem como "—".
 */
void logRawDumpSummary() {
    unschedule("logRawDumpSummary")
    Map vals = (state.rawDumpValues ?: [:]) as Map

    if (vals.isEmpty()) {
        logInfo "RAW DP snapshot: nenhum dos DPs solicitados (1,2,7,8,19,21,22) foi recebido na janela de 11s"
    } else {
        String line = RAW_DUMP_TARGET_DPS.collect { Integer dpId ->
            String name = TUYA_DP_NAMES[dpId] ?: "?"
            String v = vals.containsKey(dpId.toString()) ? vals[dpId.toString()].toString() : "—"
            "dp${dpId}(${name})=${v}"
        }.join("  ")
        logInfo "RAW DP snapshot: ${line}"
    }

    state.rawDumpUntil = 0L
    state.rawDumpValues = [:]
}

/* -------------------------
 *  Refresh / Poll
 * ------------------------- */
def refresh() {
    if (logDebug) log.debug "refresh(): queryAllTuyaDP()"
    state.lastPollRequestTs = now()
    def cmds = queryAllTuyaDP()
    sendZigbeeCommands(cmds)
    return cmds
}

def pollDevice() {
    if (!(settings?.pollEnable)) {
        if (logDebug) log.debug "pollDevice(): pollEnable=false (executando apenas refresh manual)"
        refresh()
        return
    }

    Integer quietMin = (settings?.pollOnlyWhenNoRecentReportsMin ?: 0) as Integer
    if (quietMin > 0) {
        Long lastRpt = (state?.lastReportTs ?: 0L) as Long
        Long ageMs = now() - lastRpt
        if (lastRpt > 0 && ageMs < (quietMin * 60_000L)) {
            if (logDebug) log.debug "pollDevice(): pulando poll (último report há ${Math.round(ageMs/1000)}s)"
            pollReschedule()
            return
        }
    }

    if (logDebug) log.debug "pollDevice(): enviando EF00 queryAllTuyaDP()"
    state.lastPollRequestTs = now()
    sendZigbeeCommands(queryAllTuyaDP())
    pollReschedule()
}

def pollReschedule() {
    if (!(settings?.pollEnable)) return

    Integer mins = (settings?.pollIntervalMin ?: 10) as Integer
    if (mins < 1) mins = 1
    Integer seconds = mins * 60

    if (logDebug) log.debug "pollReschedule(): próximo poll em ${mins} min"
    runIn(seconds, "pollDevice")
}

private List<String> queryAllTuyaDP() {
    return zigbee.command(0xEF00, 0x03)
}

/* -------------------------
 *  Tuya EF00 command builder
 * ------------------------- */
private List<String> sendTuyaValueDP(Integer dp, Integer value) {
    String payloadHex =
            "00" +
            hex8(dp) +
            "02" +
            "0004" +
            hex32(value)

    if (logDebug) log.debug "sendTuyaValueDP(): dp=${dp} value=${value} payload=${payloadHex}"
    return zigbee.command(0xEF00, 0x00, payloadHex)
}

private void sendZigbeeCommands(List<String> cmds) {
    if (!cmds) return
    if (logDebug) log.debug "sendZigbeeCommands(${cmds})"
    sendHubCommand(new HubMultiAction(cmds, Protocol.ZIGBEE))
}

/* -------------------------
 *  Event helper
 * ------------------------- */
private boolean sendEventIfChanged(String name, Object value, String unit = null) {
    def current = device.currentValue(name)
    boolean changed = (current?.toString() != value?.toString())
    if (!changed) return false

    Map evt = [name: name, value: value]
    if (unit != null) evt.unit = unit
    sendEvent(evt)
    return true
}

/* -------------------------
 *  Helpers
 * ------------------------- */
private Integer safeHexToInt(Object hexStr) {
    if (hexStr == null) return null
    try {
        return zigbee.convertHexToInt(hexStr.toString())
    } catch (e) {
        return null
    }
}

private Integer getTuyaValue(List<String> data, int len) {
    int value = 0
    for (int i = 0; i < len; i++) {
        value = (value << 8) | zigbee.convertHexToInt(data[6 + i])
    }
    return value
}

private static String hex8(Integer v) {
    return String.format("%02X", (v & 0xFF))
}

private static String hex32(Integer v) {
    long lv = v as long
    return String.format("%08X", (lv & 0xFFFFFFFFL))
}

/**
 * Clampa um Number para o range [min..max], logando um log.warn sempre que o
 * valor precisar ser ajustado (ver changelog 2.2.0 -- caso real de erro de
 * unidade cm/mm que ficou mascarado antes desta checagem existir).
 */
@SuppressWarnings('deprecation')
private Integer clampInt(Number n, Integer min, Integer max, String context = "") {
    if (n == null) {
        log.warn "${device.displayName}: ${context ?: 'clampInt'} recebeu valor nulo; usando mínimo ${min}"
        return min
    }
    Integer v = (n as BigDecimal).setScale(0, BigDecimal.ROUND_HALF_UP).intValue()
    if (v < min) {
        log.warn "${device.displayName}: ${context ?: 'valor'} ${v} abaixo do mínimo ${min}; ajustado para ${min}. " +
                 "Confira se a unidade está correta (ex.: mm vs cm)."
        v = min
    } else if (v > max) {
        log.warn "${device.displayName}: ${context ?: 'valor'} ${v} acima do máximo ${max}; ajustado para ${max}. " +
                 "Confira se a unidade está correta (ex.: mm vs cm)."
        v = max
    }
    return v
}

/* -------------------------
 *  Logging helpers
 * ------------------------- */
private void logInfo(String msg)     { log.info  "[${device.displayName}] ${msg}" }