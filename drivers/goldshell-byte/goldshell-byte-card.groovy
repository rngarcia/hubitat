/**
 *  Goldshell BYTE Miner Card — Hubitat Driver
 *  Versão: 1.1.1 — Build: 2026-10-21
 *  Autor: Ricardo Nogueira Garcia
 *  Repositório: https://github.com/rngarcia/hubitat
 *  Gostou? Buy me a beer: https://www.paypal.com/donate/?business=rngarcia%40gmail.com&currency_code=USD
 *
 *  Changelog:
 *  v1.1.1 (2026-10-21) - RNG
 *    - Publicado em rngarcia/hubitat: cabeçalho padronizado, namespace "rng" e importUrl
 *  (histórico anterior no bloco de comentário abaixo)
 */
/**
 *  Goldshell BYTE Miner Card - Child Driver
 *
 *  Author: Ricardo Nogueira Garcia
 *  Namespace: rng
 *
 *  Version: 1.1.0
 *  Date: 2026-06-18
 *
 *  Changelog:
 *    1.1.0 (2026-06-18) - Ricardo Nogueira Garcia
 *          Compatível com parent v3.5.0 — sem alterações funcionais.
 *          sendChildEventIfChanged() no parent elimina eventos redundantes
 *          neste child sem necessidade de modificação aqui.
 *    1.0.0 (2026-01-26) - Ricardo Nogueira Garcia
 *          Versão inicial
 */

metadata {
    definition(name: "Goldshell BYTE Miner Card", namespace: "rng", author: "Ricardo Nogueira Garcia",
        importUrl: "https://raw.githubusercontent.com/rngarcia/hubitat/main/drivers/goldshell-byte/goldshell-byte-card.groovy"
    ) {
        capability "Refresh"
        capability "Sensor"

        command "setPowerPlan", [[name:"level", type:"NUMBER", description:"Power plan level (ex: 0,2)"]]
        command "rebootMiner"

        attribute "cardId", "number"
        attribute "algo", "string"
        attribute "valid", "number"
        attribute "hashrate", "number"
        attribute "avHashrate", "number"
        attribute "accepted", "number"
        attribute "rejected", "number"
        attribute "hwErrors", "number"
        attribute "hwErrRatio", "number"
        attribute "fanRpm", "number"
        attribute "tempA", "number"
        attribute "tempB", "number"
        attribute "powerW", "number"
        attribute "powerPlan", "number"
        attribute "minerStatus", "number"
        attribute "nonce", "number"
        attribute "lastError", "string"
        attribute "online", "string"
    }
}

def refresh() {
    parent?.componentRefresh(this)
}

def setPowerPlan(Number level) {
    parent?.componentSetPowerPlan(this, level)
}

def rebootMiner() {
    parent?.componentReboot(this)
}
