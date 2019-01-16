package com.vooft.moneytransfer.read.impl

import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import com.vooft.moneytransfer.bus.Event
import com.vooft.moneytransfer.bus.TransferChangeStateEvent
import com.vooft.moneytransfer.bus.TransferCreatedEvent
import com.vooft.moneytransfer.model.Transfer
import com.vooft.moneytransfer.model.TransferState
import com.vooft.moneytransfer.read.TransferReadModel
import java.util.concurrent.ConcurrentHashMap

/**
 * Version of Transfer with mutable state
 */
private class TransferImpl(override val id: Long,
                           override val from: String,
                           override val to: String,
                           override val amount: Long,
                           var currentState: TransferState) : Transfer {
    override val state: TransferState
        get() = currentState

    override fun toString(): String {
        return "id=$id, from='$from', to='$to', amount=$amount, currentState=$currentState"
    }


}

class TransferReadModelImpl(eventFlux: Flux<Event>) : TransferReadModel {
    private val transfers = ConcurrentHashMap<Long, TransferImpl>()

    init {
        eventFlux
                .flatMap({
                    when (it) {
                        is TransferCreatedEvent -> process(it)
                        is TransferChangeStateEvent -> process(it)
                    }

                    Mono.empty<Void>()
                }, 1)
                .subscribe()
    }

    private fun process(event: TransferCreatedEvent) {
        transfers[event.source.id] = TransferImpl(event.source.id, event.source.from, event.source.to,
                event.source.amount, TransferState.PENDING)
    }

    private fun process(event: TransferChangeStateEvent) {
        // here we have to process all requests sequentially
        // flatMap concurrency level should take care of that
        transfers[event.transactionId]!!.currentState = event.state
    }

    override fun allTransfers(): List<Transfer> {
        return transfers.values.asSequence()
                .sortedBy { it.id }
                .toList()
    }
}
