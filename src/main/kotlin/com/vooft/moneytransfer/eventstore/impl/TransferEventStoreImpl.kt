package com.vooft.moneytransfer.eventstore.impl

import reactor.core.publisher.FluxSink
import reactor.core.publisher.Mono
import com.vooft.moneytransfer.bus.Event
import com.vooft.moneytransfer.bus.TransferChangeStateEvent
import com.vooft.moneytransfer.bus.TransferCommand
import com.vooft.moneytransfer.bus.TransferCreatedEvent
import com.vooft.moneytransfer.eventstore.MissingTransferException
import com.vooft.moneytransfer.eventstore.TransferEventStore
import com.vooft.moneytransfer.eventstore.TransferPromotion
import com.vooft.moneytransfer.model.TransferState
import java.util.*
import java.util.concurrent.ConcurrentHashMap

private val reversedStates = TransferState.values().reversed()

class TransferEventStoreImpl(private val eventSink: FluxSink<Event>) : TransferEventStore {
    private val currentlyProcessing = ConcurrentHashMap<Long, TransferCommandHolder>()

    override fun create(cmd: TransferCommand): Mono<TransferPromotion> {
        return Mono.defer {
            // this call has to be atomic
            val holder = currentlyProcessing.compute(cmd.id) { _, existingTransfer ->
                if (existingTransfer != null) {
                    throw TransferAlreadyStartedException()
                }

                eventSink.next(TransferCreatedEvent(cmd))
                TransferCommandHolder(cmd)
            }!!

            return@defer Mono.just<TransferPromotion>(TransferPromotionImpl(holder.cmd, TransferState.PENDING))
        }.onErrorResume(TransferAlreadyStartedException::class.java) {
            // if we tried to create a transfer, but it already exists,
            // this means that someone else is taking care of it, just stop
            Mono.empty()
        }
    }

    override fun promote(id: Long, expected: TransferState, target: TransferState) : Mono<TransferPromotion> {
        return Mono.defer {
            val transfer = currentlyProcessing[id] ?: return@defer Mono.error<TransferPromotion>(
                    MissingTransferException(id))

            // this call has to be atomic
            if (transfer.promote(expected, target)) {
                eventSink.next(TransferChangeStateEvent(transfer.cmd.id, target))
                return@defer Mono.just(TransferPromotionImpl(transfer.cmd, target))
            }

            // someone else is already promoting this transfer
            return@defer Mono.empty<TransferPromotion>()
        }
    }
}

// since the whole lifecycle of this object is controlled inside the system
// there is no need to implement pessimistic/optimistic lockings, similar to ones used in accounts
// users can only initiate transfers, they can't change their internal state
private class TransferCommandHolder(val cmd: TransferCommand) {
    private val states = EnumSet.of(TransferState.PENDING)

    // this doesn't _have_ to be atomic, it is synchronized to avoid issues with concurrent access to the set
    val currentState: TransferState
        @Synchronized
        get() = findCurrentState()

    private fun findCurrentState() : TransferState {
        for (state in reversedStates) {
            if (states.contains(state)) {
                return state
            }
        }

        throw IllegalStateException("Transfer isn't at any state")
    }

    // this method has to be atomic
    @Synchronized
    fun promote(expected: TransferState, target: TransferState) : Boolean {
        if (states.contains(target)) {
            return false
        }

        val state = findCurrentState()
        // TODO: validate target state

        if (state != expected) {
            return false
        }

        states.add(target)
        return true
    }
}

data class TransferPromotionImpl(override val cmd: TransferCommand, override val state: TransferState) : TransferPromotion

private class TransferAlreadyStartedException : RuntimeException()
