package com.vooft.moneytransfer.processors

import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.FluxSink
import reactor.core.publisher.Mono
import com.vooft.moneytransfer.bus.*
import com.vooft.moneytransfer.eventstore.AccountAlreadyExistsException
import com.vooft.moneytransfer.eventstore.AccountEventStore
import com.vooft.moneytransfer.eventstore.LockedAccount
import com.vooft.moneytransfer.eventstore.VersionConflictError

/**
 * Pessimistic locking-based processing
 * Optimistic locking would be more performant, though will require more work
 */
class AccountCommandProcessor(commandFlux: Flux<Command>, eventSink: FluxSink<Event>, private val accountEventStore: AccountEventStore) {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        commandFlux

                // process commands with configurable level of concurrency
                .flatMap({ cmd ->
                    val mono = when (cmd) {
                        is CreateAccountCommand -> process(cmd)
                        is AccountCommand -> process(cmd)
                        else -> Mono.empty()
                    }

                    // at this point mono contains either an exception or an uncommitted event
                    mono
                            .onErrorResume {
                                val msg = it.message ?: "Exception during account command processing"
                                Mono.just(CommandFailedEvent(msg, cmd, it))
                            }
                }, 10)

                .doOnError {
                    // stream will fail, no commands could be processed afterwards
                    // this is a fatal error, should never happen under normal circumstances
                    log.error("Error on processing account commands", it)
                }

                // send downstream generated events
                .subscribe {
                    eventSink.next(it)
                }
    }

    private fun process(cmd: CreateAccountCommand) : Mono<Event> {
        return accountEventStore.createAccount(cmd.accountId, cmd.balance, cmd.immutable)
                .flatMap<Event> {
                    Mono.just(CommandSuccessedEvent(cmd))
                }
                .onErrorResume(AccountAlreadyExistsException::class.java) {
                    Mono.just(CommandFailedEvent(it.message!!, cmd))
                }
    }

    private fun process(cmd: AccountCommand) : Mono<Event> {
        // try to lock account, will return a AccountNotFoundException if account not found
        return accountEventStore.lockAccount(cmd.accountId)

                // check & try to commit generated event
                .flatMap { lockedAccount ->
                    val eventMono = when (cmd) {
                        is DebitAccountCommand -> validateCommand(lockedAccount, cmd)
                        is CreditAccountCommand -> validateCommand(lockedAccount, cmd)
                        else -> Mono.empty()
                    }

                    eventMono.flatMap {
                        if (it is AccountEvent) {
                            // there is an account event to commit
                            lockedAccount.commitEvent(it)
                                    // if all went well, it will be just an empty mono
                                    // we can safely cast
                                    .cast(Event::class.java)
                                    .switchIfEmpty(Mono.defer {
                                        Mono.just(CommandSuccessedEvent(cmd))
                                    })
                        } else {
                            // something happened, most likely the command didn't pass validation
                            lockedAccount.cancel()
                                    // if all went well, it will be just an empty mono
                                    // we can safely cast
                                    .cast(Event::class.java)
                                    .defaultIfEmpty(it)
                        }
                    }.onErrorResume(VersionConflictError::class.java) {
                        // in current pessimistic-locking model this will never happen
                        // just pass downstream
                        return@onErrorResume Mono.error(it)
                    }
                }
    }

    private fun validateCommand(lockedAccount: LockedAccount, cmd: DebitAccountCommand) : Mono<Event> {
        // TODO: add duplicate detection
        if (lockedAccount.balance < cmd.amount) {
            return Mono.just(CommandFailedEvent("Insufficient funds", cmd))
        }

        return Mono.just(AccountDebitedEvent(cmd.transactionId, lockedAccount.id, cmd.amount))
    }

    private fun validateCommand(lockedAccount: LockedAccount, cmd: CreditAccountCommand) : Mono<Event> {
        // TODO: add duplicate detection
        return Mono.just(AccountCreditedEvent(cmd.transactionId, lockedAccount.id, cmd.amount))
    }
}
