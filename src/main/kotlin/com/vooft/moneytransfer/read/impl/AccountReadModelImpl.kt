package com.vooft.moneytransfer.read.impl

import reactor.core.publisher.Flux
import com.vooft.moneytransfer.bus.*
import com.vooft.moneytransfer.model.Account
import com.vooft.moneytransfer.read.AccountReadModel
import java.util.concurrent.ConcurrentHashMap

/**
 * Version of Account with mutable balance
 */
private data class AccountImpl(override val id: String, var currentBalance: Long) : Account {
    override val balance: Long
        get() = currentBalance
}

class AccountReadModelImpl(eventFlux: Flux<Event>) : AccountReadModel {
    private val accounts = ConcurrentHashMap<String, AccountImpl>()

    init {
        eventFlux
                .filter {
                    it is AccountEvent
                }
                .cast(AccountEvent::class.java)
                .subscribe {
                    process(it)
                }
    }

    private fun process(event: AccountEvent) {
        if (event is AccountCreatedEvent) {
            accounts[event.accountId] = AccountImpl(event.accountId, event.amount)
            return
        }

        val account = accounts[event.accountId]!!
        synchronized(account) {
            when (event) {
                is AccountDebitedEvent -> account.currentBalance -= event.amount
                is AccountCreditedEvent -> account.currentBalance += event.amount
                else -> {}
            }
        }
    }

    override fun allAccounts(): List<Account> {
        return accounts.values.asSequence()
                .sortedBy { it.id }
                .toList()
    }
}
