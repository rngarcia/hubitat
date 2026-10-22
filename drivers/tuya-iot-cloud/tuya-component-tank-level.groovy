/**
 *  Tuya Component Tank Level Monitor (Wi-Fi) — Hubitat Driver
 *  Versão: 1.4.1 — Build: 2026-10-21
 *  Autor: Ricardo Nogueira Garcia
 *  Repositório: https://github.com/rngarcia/hubitat
 *  Gostou? Buy me a beer: https://www.paypal.com/donate/?business=rngarcia%40gmail.com&currency_code=USD
 *
 *  Changelog:
 *  v1.4.1 (2026-10-21) - RNG
 *    - Publicado em rngarcia/hubitat: cabeçalho padronizado, namespace "rng" e importUrl
 *    - Nome do driver sem o sufixo "- Claude"
 *  (histórico anterior no bloco de comentário abaixo)
 */
/**
 *  Tuya Component - Tank Level Monitor (Wi-Fi) - Child Driver (Hubitat)
 *
 *  Parent: Tuya IoT Platform (Cloud) - Parent Driver (Hubitat)
 *
 *  Tuya Cloud codes used:
 *    - liquid_level_percent (0..100) -> level
 *    - liquid_depth (unit m, scale 2) -> internal only (state.lastLiquidDepthM)
 *          Confirmado empiricamente: representa a ALTURA DA COLUNA DE ÁGUA
 *          (medida do fundo do tanque pra cima), não a distância sensor->água.
 *          Não é exposto como atributo público (ver changelog 1.4.0) -- usado
 *          apenas internamente para o comando de calibração e para o cálculo
 *          de liquidDepthMaxCalc.
 *    - liquid_state enum -> liquidState
 *    - max_set / mini_set -> upperLimit / lowerLimit
 *    - installation_height (unit m, scale 3) -> installationHeight (m)
 *          Distância do sensor até o FUNDO do tanque. Mesmo campo ajustável
 *          no app Tuya original.
 *    - liquid_depth_max (unit m, scale 3) -> liquidDepthMax (m)
 *          Distância do sensor até o NÍVEL MÁXIMO de água (tanque cheio).
 *          Mesmo campo ajustável no app Tuya original. Calibrar incorretamente
 *          faz o próprio sensor reportar "level" sempre em 100% (ou outro
 *          valor incorreto e fixo).
 *
 *  Atributo calculado (não escrito no device, só para conferência):
 *    - liquidDepthMaxCalc (m) = installationHeight - liquidDepth(atual)
 *          É o valor que liquidDepthMax DEVERIA ter se o tanque estivesse
 *          cheio agora. Compare com liquidDepthMax para aferir a calibração;
 *          quando a caixa estiver de fato cheia, os dois devem coincidir.
 *    - maxWaterHeightM (m) = installationHeight - liquidDepthMax
 *          Altura útil de água (do fundo até o nível máximo configurado).
 *          Puramente informativo, para conferência da calibração.
 *
 *  Adds:
 *    - Configurable polling interval (preference) with automatic scheduling
 *    - Um único comando de calibração fina: ajusta liquidDepthMax a partir do
 *      nível atual, para usar quando a caixa estiver fisicamente cheia.
 *
 *  Version: 1.4.0 (2026-07-04)
 *
 *  Changelog:
 *    1.4.0 (2026-07-04) - Ricardo Nogueira Garcia + Claude
 *          Revisão geral de limpeza e organização:
 *          - Comandos reduzidos aos parâmetros que também existem no app Tuya
 *            (setInstallationHeight, setLiquidDepthMax) + setUpperLimit/
 *            setLowerLimit (confirmados enviando via sendTuya/parent
 *            componentSendTuyaCommand) + Refresh/Configure.
 *          - Removidos os comandos de calibração calibSetUpperFromCurrentLevel,
 *            calibSetLowerFromCurrentLevel, calibSetDepthMaxFromInstallationHeight,
 *            calibSetInstallationHeightFromDepthMax (não usados no fluxo atual).
 *          - Mantido apenas 1 comando de calibração fina: calibSetDepthMaxFromCurrentLevel,
 *            para usar quando a caixa estiver cheia. CORRIGIDA a fórmula: antes
 *            fazia liquidDepthMax = liquidDepth(atual) diretamente (errado, pois
 *            confundia "altura da coluna de água" com "distância sensor->água").
 *            Agora calcula corretamente liquidDepthMax = installationHeight -
 *            liquidDepth(atual), batendo com o que o app Tuya espera.
 *          - Atributo liquidDepthCm removido da lista pública (não aparece mais
 *            em Current States); o valor bruto de profundidade agora é mantido
 *            apenas internamente (state.lastLiquidDepthM), usado pela calibração
 *            e pelo novo atributo calculado liquidDepthMaxCalc.
 *          - Novo atributo liquidDepthMaxCalc (m): calculado em tempo real como
 *            installationHeight - liquidDepth(atual) -- serve para conferir, a
 *            qualquer momento, se a calibração de liquidDepthMax está correta
 *            (compare os dois quando a caixa estiver cheia).
 *          - Novo atributo maxWaterHeightM (m): installationHeight -
 *            liquidDepthMax, puramente informativo.
 *          - Estado final em Current States: level, liquidState, upperLimit,
 *            lowerLimit, installationHeight, liquidDepthMax, liquidDepthMaxCalc,
 *            maxWaterHeightM. Nada mais.
 *          - Todos os valores usados nos cálculos (installationHeight,
 *            liquidDepthMax, liquidDepth) são capturados diretamente dos
 *            eventos recebidos em parse(), guardados em state, e NUNCA relidos
 *            via device.currentValue() logo após um sendEvent() no mesmo ciclo
 *            -- elimina a classe de bug de "valor desatualizado" já vista antes
 *            (ver changelog 1.3.0).
 *          - Em modo debug (logEnable=true), cada evento bruto recebido da Tuya
 *            é logado individualmente (nome/valor/unidade), incluindo liquidDepth,
 *            mesmo não sendo mais exposto como atributo público.
 *    1.3.0 (2026-07-04) - Ricardo Nogueira Garcia + Claude
 *          Removido todo o bloco de cálculo de volume (volumeLiters,
 *          tankCapacityLiters, volumePercentOfCapacity, tankTemplate, e as
 *          preferences de forma/dimensões do tanque) -- o problema de "level
 *          sempre 100%" era de calibração do sensor, não do driver.
 *    1.2.0 (2026-05-21) - Added configurable polling interval; improved lifecycle (schedule/unschedule);
 *                         logsOff made private to hide from commands list; configure() now also
 *                         re-registers polling; safeInt() warns on out-of-range clamping;
 *                         minor code hygiene improvements.
 *    1.1.2 (2026-01-19) - Initial public release.
 */

import groovy.transform.Field

@Field static final String DRIVER_VERSION = "1.4.1"
@Field static final String DRIVER_DATE    = "2026-07-04"

metadata {
    definition(
        name: "Tuya Component Tank Level Monitor (Wi-Fi)",
        namespace: "rng",
        author: "Ricardo Nogueira Garcia",
        importUrl: "https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/tuya-iot-cloud/tuya-component-tank-level.groovy"
    ) {
        capability "Refresh"
        capability "Sensor"
        capability "Configuration"

        // Core
        attribute "level", "number"                   // % (bruto, do Tuya Cloud)
        attribute "liquidState", "string"             // normal/lower_alarm/upper_alarm

        // Device config mirrored (from cloud / writable via commands) -- mesmos
        // parâmetros ajustáveis no app Tuya original.
        attribute "upperLimit", "number"              // %
        attribute "lowerLimit", "number"              // %
        attribute "installationHeight", "number"      // m -- sensor -> fundo do tanque
        attribute "liquidDepthMax", "number"          // m -- sensor -> nível máximo (config)

        // Calculados, apenas para conferência/aferição (não escritos no device)
        attribute "liquidDepthMaxCalc", "number"      // m -- installationHeight - liquidDepth(atual)
        attribute "maxWaterHeightM", "number"         // m -- installationHeight - liquidDepthMax

        // Commands that write Tuya cloud codes
        command "setUpperLimit", [[name:"Percent", type:"NUMBER", description:"0..100"]]
        command "setLowerLimit", [[name:"Percent", type:"NUMBER", description:"0..100"]]
        command "setInstallationHeight", [[name:"Meters", type:"NUMBER", description:"Ex.: 3.000"]]
        command "setLiquidDepthMax", [[name:"Meters", type:"NUMBER", description:"Ex.: 2.900"]]

        // Calibração fina: usar quando a caixa estiver fisicamente CHEIA.
        command "calibSetDepthMaxFromCurrentLevel"
    }

    preferences {
        input name: "txtEnable", type: "bool",
              title: "Enable descriptionText logging", defaultValue: true
        input name: "logEnable", type: "bool",
              title: "Enable debug logging", defaultValue: true

        // ── Polling ───────────────────────────────────────────────────────────
        input name: "pollingEnable", type: "bool",
              title: "Enable automatic polling", defaultValue: false
        input name: "pollingInterval", type: "enum",
              title: "Polling interval",
              options: [
                  "30"  : "30 seconds",
                  "60"  : "1 minute",
                  "120" : "2 minutes",
                  "300" : "5 minutes",
                  "600" : "10 minutes",
                  "900" : "15 minutes",
                  "1800": "30 minutes",
                  "3600": "1 hour"
              ],
              defaultValue: "300", required: true
    }
}

/* =========================
 *  Lifecycle
 * ========================= */
void installed() {
    logInfo "Installed v${DRIVER_VERSION} (${DRIVER_DATE})"
    updated()
}

void updated() {
    logInfo "Updated v${DRIVER_VERSION} (${DRIVER_DATE})"
    // Auto-disable debug logging after 30 min
    unschedule("logsOff")
    if (settings?.logEnable) runIn(1800, "logsOff")

    // Re-register polling
    schedulePolling()
}

// Made private so it does NOT appear in the Hubitat commands list
private void logsOff() {
    device.updateSetting("logEnable", [value: "false", type: "bool"])
    logInfo "Debug logging disabled"
}

/* =========================
 *  Polling
 * ========================= */

/**
 * Registra ou cancela o agendamento de polling com base nas preferences atuais.
 * Chamado por updated() e configure().
 */
private void schedulePolling() {
    unschedule("pollDevice")

    if (!settings?.pollingEnable) {
        logInfo "Automatic polling disabled"
        return
    }

    Integer secs = (settings?.pollingInterval ?: "300") as Integer
    if (secs < 30) secs = 30   // floor: never hammer the cloud API

    if (secs < 60) {
        // runEvery helpers only go down to 1 minute; use runIn loop via schedule
        schedule("0/${secs} * * * * ?", "pollDevice")
    } else {
        Integer mins = secs / 60
        switch (mins) {
            case 1:  runEvery1Minute("pollDevice");    break
            case 2:  runEvery5Minutes("pollDevice");   break  // no 2-min helper; use 5
            case 5:  runEvery5Minutes("pollDevice");   break
            case 10: runEvery10Minutes("pollDevice");  break
            case 15: runEvery15Minutes("pollDevice");  break
            case 30: runEvery30Minutes("pollDevice");  break
            default: runEvery1Hour("pollDevice");      break
        }
    }

    logInfo "Polling scheduled every ${secs}s (pollingEnable=${settings.pollingEnable})"
}

/** Called by the Hubitat scheduler — delegates to refresh(). */
void pollDevice() {
    logDebug "pollDevice() -> refresh()"
    refresh()
}

/* =========================
 *  Parent interaction
 * ========================= */
void refresh() {
    logDebug "refresh() -> parent.componentRefresh()"
    parent?.componentRefresh(device)
}

void configure() {
    logDebug "configure()"
    schedulePolling()
}

/**
 * Parent calls child.parse(List<Map> events)
 */
void parse(Object description) {
    if (logDebug) log.debug "[${device.displayName}] parse(): recebido ${description}"
    if (!(description instanceof List)) return

    List<Map> evts = (List<Map>) description
    boolean touchedHeightOrDepth = false

    evts.each { Map e ->
        if (!e?.name) return

        // Log raw de cada evento recebido, independente de virar atributo ou
        // não -- garante visibilidade total dos DPs/eventos da Tuya em modo debug.
        if (logDebug) {
            log.debug "[${device.displayName}] Tuya raw: ${e.name} = ${e.value}${e.unit ? ' ' + e.unit : ''}"
        }

        // liquidDepth: mantido só internamente (state), não exposto como atributo
        // público (ver changelog 1.4.0). Usado pela calibração e por
        // liquidDepthMaxCalc.
        if (e.name == "liquidDepth") {
            BigDecimal m = safeBigDecimal(e.value)
            if (m != null) {
                state.lastLiquidDepthM = m
                touchedHeightOrDepth = true
            }
            return
        }

        // Captura installationHeight/liquidDepthMax diretamente do evento (não
        // via device.currentValue() depois do sendEvent, para evitar reler um
        // valor desatualizado -- ver changelog 1.3.0).
        if (e.name == "installationHeight") {
            BigDecimal m = safeBigDecimal(e.value)
            if (m != null) { state.lastInstallationHeightM = m; touchedHeightOrDepth = true }
        }
        if (e.name == "liquidDepthMax") {
            BigDecimal m = safeBigDecimal(e.value)
            if (m != null) { state.lastLiquidDepthMaxM = m; touchedHeightOrDepth = true }
        }

        // Emite o evento normalmente (level, liquidState, upperLimit, lowerLimit,
        // installationHeight, liquidDepthMax) se mudou ou está stale.
        if (shouldEmit(e.name, e.value)) {
            sendEvent(e)
            if (e?.descriptionText) logDesc(e.descriptionText.toString())
        }
    }

    if (touchedHeightOrDepth) recalcDerivedAttributes()
}

/**
 * Recalcula os atributos derivados (liquidDepthMaxCalc, maxWaterHeightM) a
 * partir dos valores guardados em state -- nunca via device.currentValue()
 * logo após um sendEvent() no mesmo ciclo.
 */
private void recalcDerivedAttributes() {
    BigDecimal instM  = state.lastInstallationHeightM as BigDecimal
    BigDecimal depthM = state.lastLiquidDepthM as BigDecimal
    BigDecimal maxM   = state.lastLiquidDepthMaxM as BigDecimal

    // liquidDepthMaxCalc = installationHeight - liquidDepth(atual)
    if (instM != null && depthM != null) {
        BigDecimal calc = (instM - depthM)
        if (calc < 0G) calc = 0G
        calc = calc.setScale(3, BigDecimal.ROUND_HALF_UP)
        if (shouldEmit("liquidDepthMaxCalc", calc)) {
            sendEvent(name: "liquidDepthMaxCalc", value: calc, unit: "m",
                      descriptionText: "liquid depth max (calc) is ${calc} m")
            logDesc "liquid depth max (calc) is ${calc} m"
        }
    }

    // maxWaterHeightM = installationHeight - liquidDepthMax (config)
    if (instM != null && maxM != null) {
        BigDecimal usableH = (instM - maxM)
        if (usableH < 0G) usableH = 0G
        usableH = usableH.setScale(3, BigDecimal.ROUND_HALF_UP)
        if (shouldEmit("maxWaterHeightM", usableH)) {
            sendEvent(name: "maxWaterHeightM", value: usableH, unit: "m",
                      descriptionText: "max water height is ${usableH} m")
            logDesc "max water height is ${usableH} m"
        }
    }
}

/* =========================
 *  Commands (write Tuya cloud)
 * ========================= */
void setUpperLimit(Number pct) {
    Integer v = safeIntClamped(pct, 0, 100, "setUpperLimit")
    logDebug "setUpperLimit(${pct}) -> ${v}"
    sendTuya("max_set", v)
}

void setLowerLimit(Number pct) {
    Integer v = safeIntClamped(pct, 0, 100, "setLowerLimit")
    logDebug "setLowerLimit(${pct}) -> ${v}"
    sendTuya("mini_set", v)
}

/**
 * installation_height: unit m, scale=3 → send raw = meters × 1000
 * Hardware range raw: 100..3000 (0.100..3.000 m)
 */
void setInstallationHeight(Number meters) {
    Integer raw = metersToScaledInt(meters, 3, 100, 3000)
    logDebug "setInstallationHeight(${meters}) -> raw=${raw}"
    sendTuya("installation_height", raw)
}

/**
 * liquid_depth_max: unit m, scale=3 → send raw = meters × 1000
 * Hardware range raw: 100..2900 (0.100..2.900 m)
 */
void setLiquidDepthMax(Number meters) {
    Integer raw = metersToScaledInt(meters, 3, 100, 2900)
    logDebug "setLiquidDepthMax(${meters}) -> raw=${raw}"
    sendTuya("liquid_depth_max", raw)
}

/* =========================
 *  Calibração fina (usar com a caixa CHEIA)
 * ========================= */
/**
 * Calibra liquidDepthMax a partir do estado atual, para usar quando a caixa
 * estiver fisicamente cheia.
 *
 * IMPORTANTE (corrigido na v1.4.0): liquidDepthMax representa a distância do
 * sensor até o nível máximo de água. Como liquidDepth é a altura da coluna de
 * água (não a distância sensor->água), a fórmula correta é:
 *
 *     liquidDepthMax = installationHeight - liquidDepth(atual)
 *
 * A versão anterior deste comando usava liquidDepth(atual) diretamente como
 * novo liquidDepthMax, o que estava errado.
 */
void calibSetDepthMaxFromCurrentLevel() {
    BigDecimal instM  = state.lastInstallationHeightM as BigDecimal
    BigDecimal depthM = state.lastLiquidDepthM as BigDecimal

    if (instM == null) {
        log.warn "${device.displayName}: calibSetDepthMaxFromCurrentLevel(): installationHeight ainda não recebido; faça um Refresh antes."
        return
    }
    if (depthM == null) {
        log.warn "${device.displayName}: calibSetDepthMaxFromCurrentLevel(): liquidDepth atual ainda não recebido; faça um Refresh antes."
        return
    }

    BigDecimal newMaxM = (instM - depthM)
    if (newMaxM <= 0G) {
        log.warn "${device.displayName}: calibSetDepthMaxFromCurrentLevel(): resultado ${newMaxM} <= 0 " +
                 "(installationHeight=${instM}, liquidDepth=${depthM}) — confira se a caixa está realmente cheia e a calibração de installationHeight está correta."
        return
    }
    newMaxM = newMaxM.setScale(3, BigDecimal.ROUND_HALF_UP)

    logDebug "calibSetDepthMaxFromCurrentLevel(): installationHeight=${instM}m - liquidDepth=${depthM}m -> liquidDepthMax=${newMaxM}m"
    setLiquidDepthMax(newMaxM)
}

/* =========================
 *  Low-level helpers
 * ========================= */
private void sendTuya(String code, Object value) {
    logDebug "sendTuya(code=${code}, value=${value}) -> parent.componentSendTuyaCommand()"
    parent?.componentSendTuyaCommand(device, code, value)
    logInfo "Comando enviado ao Tuya Cloud: ${code}=${value}"
}

/**
 * Converts a Number to an Integer clamped to [minV..maxV].
 * Logs a warning if the raw value fell outside range.
 */
private Integer safeIntClamped(def n, Integer minV, Integer maxV, String context = "") {
    Integer v = minV
    try {
        v = (n instanceof Number) ? ((Number) n).intValue() : n.toString().toInteger()
    } catch (ignored) {
        log.warn "${device.displayName}: Could not parse '${n}' as integer${context ? ' for ' + context : ''}; using ${minV}"
        return minV
    }
    if (v < minV) {
        log.warn "${device.displayName}: ${context ?: 'value'} ${v} below minimum ${minV}; clamped"
        v = minV
    } else if (v > maxV) {
        log.warn "${device.displayName}: ${context ?: 'value'} ${v} above maximum ${maxV}; clamped"
        v = maxV
    }
    return v
}

private static BigDecimal safeBigDecimal(def v) {
    try {
        return (v instanceof Number) ? (v as BigDecimal) : new BigDecimal(v.toString())
    } catch (ignored) {
        return null
    }
}

/**
 * metersToScaledInt: scale=3 → meters × 1000; clamps to [minRaw..maxRaw].
 */
private static Integer metersToScaledInt(def meters, int scale, int minRaw, int maxRaw) {
    BigDecimal m = safeBigDecimal(meters) ?: 0G
    BigDecimal factor = (10 ** scale) as BigDecimal
    Integer raw = (m * factor).setScale(0, BigDecimal.ROUND_HALF_UP).intValue()
    if (raw < minRaw) raw = minRaw
    if (raw > maxRaw) raw = maxRaw
    return raw
}

/* =========================
 *  Event spam-reduction helpers
 * ========================= */
/**
 * Returns true if the value has changed OR if the last emit was more than minSec ago (staleness guard).
 * State keys: last_<name>_val and last_<name>_ts.
 */
private boolean shouldEmit(String key, Object newVal, Integer minSec = 30) {
    def  lastVal = state["last_${key}_val"]
    Long lastTs  = (state["last_${key}_ts"] ?: 0L) as Long
    boolean changed = (lastVal?.toString() != (newVal?.toString()))
    boolean stale   = (now() - lastTs) > (minSec * 1000L)
    if (changed || stale) {
        state["last_${key}_val"] = newVal?.toString()
        state["last_${key}_ts"]  = now()
        return true
    }
    return false
}

/* =========================
 *  Logging helpers
 * ========================= */
private void logDebug(String msg) { if (settings?.logEnable) log.debug "[${device.displayName}] ${msg}" }
private void logInfo(String msg)  { log.info  "[${device.displayName}] ${msg}" }
private void logDesc(String msg)  { if (settings?.txtEnable) log.info "[${device.displayName}] ${msg}" }