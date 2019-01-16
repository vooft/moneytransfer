package com.vooft.moneytransfer.processors

import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import com.vooft.moneytransfer.bus.*
import com.vooft.moneytransfer.eventstore.MissingTransferException
import com.vooft.moneytransfer.eventstore.TransferEventStore
import com.vooft.moneytransfer.eventstore.TransferPromotion
import com.vooft.moneytransfer.model.TransferState

class TransferCommandProcessor(private val eventBus: EventBus, private val eventStore: TransferEventStore) {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        eventBus.commandFlux

                // process commands with configurable level of concurrency
                .flatMap<Command>({ cmd ->
                    val mono = when (cmd) {
                        is TransferCommand -> process(cmd)
                        else -> Mono.empty()
                    }

                    mono

                            // try to promote and send command downstream (if available)
                            .flatMap { nextPromotion ->
                                eventStore.promote(nextPromotion.transferCommand.id, nextPromotion.state, nextPromotion.target)
                                        .flatMap {
                                            Mono.justOrEmpty(nextPromotion.cmd)
                                        }
                            }

                            // something happened, log it an continue
                            .onErrorResume {
                                log.error("Transfer is missing, inconsistent state", it)
                                Mono.empty()
                            }
                }, 10)

                .doOnError {
                    // stream will fail, no commands could be processed afterwards
                    // this is a fatal error, should never happen under normal circumstances
                    log.error("Error on processing account commands", it)
                }

                .subscribe {
                    eventBus.commandSink.next(it)
                }

        eventBus.eventFlux

                // listen to debited and credited events in order to change transfer's state
                .flatMap({ event ->
                    val mono = when (event) {
                        is AccountDebitedEvent -> process(event)
                        is AccountCreditedEvent -> process(event)
                        is CommandFailedEvent -> process(event)
                        else -> Mono.empty()
                    }

                    mono.onErrorResume(MissingTransferException::class.java) {
                        log.error("Transfer is missing, inconsistent state", it)
                        Mono.empty()
                    }
                }, 10)

                .flatMap<Command>({ promotion ->
                    // try to promote
                    val promotionMono = eventStore.promote(promotion.transferCommand.id, promotion.state, promotion.target)

                    // send command downstream if available and transfer was promoted
                    if (promotion.cmd != null) {
                        promotionMono.map { promotion.cmd }
                    } else {
                        promotionMono.flatMap { Mono.empty<Command>() }
                    }
                }, 10)

                .doOnError {
                    // stream will fail, no commands could be processed afterwards
                    // this is a fatal error, should never happen under normal circumstances
                    log.error("Error on processing account commands", it)
                }

                .subscribe {
                    eventBus.commandSink.next(it)
                }
    }

    private fun process(sourceCommand: TransferCommand) : Mono<NextPromotion> {
        return eventStore.create(sourceCommand)

                // we will not receive anything if transfer was already created
                .flatMap { promotion ->
                    nextCommand(promotion)
                }
    }

    private fun process(event: AccountCreditedEvent) : Mono<NextPromotion> {
        // here could be 2 cases: we are crediting during normal workflow, or during compensation

        // this promotion will fail if it is a remediation process
        return eventStore.promote(event.transactionId, TransferState.CREDITING, TransferState.CREDITED)

                // first case
                .doOnNext { promotion ->
                    eventBus.eventSink.next(CommandSuccessedEvent(promotion.cmd))
                }

                // second case
                .switchIfEmpty(Mono.defer {
                    eventStore.promote(event.transactionId, TransferState.DEBIT_COMPENSATING, TransferState.FAILED)
                })

                .flatMap { promotion ->
                    nextCommand(promotion)
                }
    }

    private fun process(event: AccountDebitedEvent) : Mono<NextPromotion> {
        return eventStore.promote(event.transactionId, TransferState.DEBITING, TransferState.DEBITED)
                .flatMap { promotion ->
                    nextCommand(promotion)
                }
    }

    private fun process(event: CommandFailedEvent) : Mono<NextPromotion> {
        return when (event.source) {
            // TODO: add retry logic for failed debit
            is DebitAccountCommand -> {
                eventStore.promote(event.source.transactionId, TransferState.DEBITING, TransferState.FAILED)
                        .flatMap { promotion ->
                            eventBus.eventSink.next(CommandFailedEvent(event.msg, promotion.cmd))
                            nextCommand(promotion)
                        }
            }
            // TODO: handle case when "from" account was debited and then compensating crediting failed
            is CreditAccountCommand -> {
                eventStore.promote(event.source.transactionId, TransferState.CREDITING, TransferState.CREDIT_FAILED)
                        .flatMap { promotion ->
                            eventBus.eventSink.next(CommandFailedEvent(event.msg, promotion.cmd))
                            nextCommand(promotion)
                        }
            }
            else -> Mono.empty()
        }
    }

    private fun nextCommand(promotion: TransferPromotion) : Mono<NextPromotion> {
        val cmd = promotion.cmd
        val state = promotion.state

        val nextPromotion = when (state) {
            TransferState.PENDING -> NextPromotion(cmd, state, TransferState.DEBITING, DebitAccountCommand(cmd.id, cmd.from, cmd.amount))
            TransferState.DEBITING -> null
            TransferState.DEBITED -> NextPromotion(cmd, state, TransferState.CREDITING, CreditAccountCommand(cmd.id, cmd.to, cmd.amount))
            TransferState.CREDITING -> null
            TransferState.CREDITED -> NextPromotion(cmd, state, TransferState.COMPLETED, null)
            TransferState.CREDIT_FAILED -> NextPromotion(cmd, state, TransferState.DEBIT_COMPENSATING, CreditAccountCommand(cmd.id, cmd.from, cmd.amount))
            TransferState.DEBIT_COMPENSATING -> NextPromotion(cmd, state, TransferState.FAILED, null)
            TransferState.FAILED -> null
            TransferState.COMPLETED -> null
        }

        return Mono.justOrEmpty(nextPromotion)
    }

    private data class NextPromotion(val transferCommand: TransferCommand,
                                     val state: TransferState,
                                     val target: TransferState,
                                     val cmd: Command?)
}
