package com.vooft.moneytransfer.bus

import reactor.core.publisher.Flux
import reactor.core.publisher.FluxSink

interface EventBus {
    val commandSink: FluxSink<Command>
    val commandFlux: Flux<Command>

    val eventSink: FluxSink<Event>
    val eventFlux: Flux<Event>
}
