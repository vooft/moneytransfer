package com.vooft.moneytransfer.eventstore

import reactor.core.publisher.Mono
import com.vooft.moneytransfer.bus.TransferCommand
import com.vooft.moneytransfer.model.TransferState

/**
 * Result of promotion encapsulates the fact of successful promotion and retrieval of the original command
 */
interface TransferPromotion {
    val cmd: TransferCommand
    val state: TransferState
}

// there is no need for set validation, so no need for the transfer representation at all
interface TransferEventStore {
    /**
     * Returns an empty mono if this transfer was already registered
     */
    fun create(cmd: TransferCommand) : Mono<TransferPromotion>

    /**
     * Returns an empty mono if transfer state is not the same as expected (e.g. another service already promoted it)
     */
    fun promote(id: Long, expected: TransferState, target: TransferState) : Mono<TransferPromotion>
}

// should never happen, but don't want to swallow the error either
class MissingTransferException(id: Long): RuntimeException("Attempt to change state of a missing transaction $id", null, true, false)
