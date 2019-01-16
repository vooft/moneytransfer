package com.vooft.moneytransfer.bus.impl

import org.slf4j.LoggerFactory
import reactor.core.publisher.EmitterProcessor
import reactor.core.publisher.Flux
import reactor.core.publisher.FluxSink
import reactor.core.scheduler.Schedulers
import com.vooft.moneytransfer.bus.Command
import com.vooft.moneytransfer.bus.Event
import com.vooft.moneytransfer.bus.EventBus

class EventBusImpl : EventBus {
    private val log = LoggerFactory.getLogger(javaClass)

    private val dispatcherPool = Schedulers.newParallel("EventBusDispatcher", Runtime.getRuntime().availableProcessors())

    private val commandProcessor = EmitterProcessor.create<Command>(Int.MAX_VALUE)
    private val commandFluxShare = commandProcessor.publishOn(dispatcherPool).share()

    private val eventProcessor = EmitterProcessor.create<Event>(Int.MAX_VALUE)
    private val eventFluxShare = eventProcessor.publishOn(dispatcherPool).share()

    override val commandFlux: Flux<Command> get() = Flux.from(commandFluxShare)
    override val commandSink: FluxSink<Command> = commandProcessor.sink()

    override val eventFlux: Flux<Event> get() = Flux.from(eventFluxShare)
    override val eventSink: FluxSink<Event> = eventProcessor.sink()

    init {
        eventFlux.subscribe {
            log.info("event $it")
        }

        commandFlux.subscribe {
            log.info("command $it")
        }
    }
}
