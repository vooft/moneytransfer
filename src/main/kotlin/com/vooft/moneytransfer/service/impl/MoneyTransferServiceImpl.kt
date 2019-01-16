package com.vooft.moneytransfer.service.impl

import reactor.core.publisher.Mono
import com.vooft.moneytransfer.bus.*
import com.vooft.moneytransfer.bus.impl.EventBusImpl
import com.vooft.moneytransfer.eventstore.AccountEventStore
import com.vooft.moneytransfer.eventstore.TransferEventStore
import com.vooft.moneytransfer.eventstore.impl.AccountEventStoreImpl
import com.vooft.moneytransfer.eventstore.impl.TransferEventStoreImpl
import com.vooft.moneytransfer.processors.AccountCommandProcessor
import com.vooft.moneytransfer.processors.TransferCommandProcessor
import com.vooft.moneytransfer.read.AccountReadModel
import com.vooft.moneytransfer.read.TransferReadModel
import com.vooft.moneytransfer.read.impl.AccountReadModelImpl
import com.vooft.moneytransfer.read.impl.TransferReadModelImpl
import com.vooft.moneytransfer.service.MoneyTransferService
import com.vooft.moneytransfer.service.Response

// this trick is required to ensure, that sending event will happen _after_ subscription to the queue
// this is required mostly due to implementation details of the reactor framework
fun <T> Mono<T>.doAfterSubscription(consumer: () -> Unit) : Mono<T> {
    return Mono.create<T> { sink ->
        val subscription = this.subscribe({
            sink.success(it)
        }, {
            sink.error(it)
        }, {
            sink.success()
        })

        sink.onCancel {
            subscription.dispose()
        }

        consumer()
    }
}

class MoneyTransferServiceImpl : MoneyTransferService {
    val eventBus: EventBus = EventBusImpl()

    private val accountEventStore: AccountEventStore = AccountEventStoreImpl(eventBus.eventSink)
    private val accountCommandProcessor = AccountCommandProcessor(eventBus.commandFlux, eventBus.eventSink, accountEventStore)

    private val transferEventStore: TransferEventStore = TransferEventStoreImpl(eventBus.eventSink)
    private val transferCommandProcessor = TransferCommandProcessor(eventBus, transferEventStore)

    val accountReadModel: AccountReadModel = AccountReadModelImpl(eventBus.eventFlux)
    val transferReadModel: TransferReadModel = TransferReadModelImpl(eventBus.eventFlux)

    override fun createAccount(accountId: String, balance: Long, immutable: Boolean) : Mono<Response> {
        if (balance < 0) {
            return Mono.just(Response("Start balance can not be negative", 400))
        }

        if (accountId.isBlank()) {
            return Mono.just(Response("Account id can not be empty or blank", 400))
        }

        val cmd = CreateAccountCommand(accountId, balance, immutable)

        return sendCommand(cmd)
                .flatMap { event ->
                    when (event) {
                        is CommandSuccessedEvent -> Mono.just(Response("Account successfully created", 200))
                        is CommandFailedEvent -> Mono.just(Response("Account creation failed: ${event.msg}", 412))
                    }
                }
    }

    override fun balances(): Mono<Response> {
        return Mono.defer {
            val result = accountReadModel.allAccounts().asSequence()
                    .map { "${it.id}: ${it.balance}" }
                    .joinToString("\n")

            Mono.just(Response(result, 200))
        }
    }

    override fun transfers(): Mono<Response> {
        return Mono.defer {
            val result = transferReadModel.allTransfers().asSequence()
                    .map { it.toString() }
                    .joinToString("\n")

            Mono.just(Response(result, 200))
        }
    }

    override fun transfer(from: String, to: String, amount: Long): Mono<Response> {
        if (from.isBlank() || to.isBlank()) {
            return Mono.just(Response("Account id can not be blank", 400))
        }

        if (from == to) {
            return Mono.just(Response("From and to accounts should be different", 400))
        }

        if (amount < 0) {
            return Mono.just(Response("Transfer amount can not be negative", 400))
        }

        val cmd = TransferCommand(from, to, amount)
        return sendCommand(cmd)
                .flatMap { event ->
                    when (event) {
                        is CommandSuccessedEvent -> Mono.just(Response("Money transfer succeeded", 200))
                        is CommandFailedEvent -> Mono.just(Response("Money transfer failed: ${event.msg}", 412))
                    }
                }
    }

    private fun sendCommand(cmd: Command) : Mono<CommandStatusEvent> {
        return eventBus.eventFlux
                .filter {
                    it is CommandStatusEvent && it.source.id == cmd.id
                }
                .cast(CommandStatusEvent::class.java)
                .next()
                .doAfterSubscription {
                    eventBus.commandSink.next(cmd)
                }
    }
}
