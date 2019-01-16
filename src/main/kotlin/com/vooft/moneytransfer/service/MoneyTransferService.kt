package com.vooft.moneytransfer.service

import reactor.core.publisher.Mono

/**
 * Main facade for the system
 * Non-state-related validation should happen here
 * All internal services assume that data make sense
 */
interface MoneyTransferService {
    fun createAccount(accountId: String, balance: Long, immutable: Boolean): Mono<Response>
    fun transfer(from: String, to: String, amount: Long): Mono<Response>

    fun balances(): Mono<Response>
    fun transfers(): Mono<Response>
}

data class Response(val body: String, val status: Int)
