package com.vooft.moneytransfer.bus

import com.vooft.moneytransfer.model.TransferState
import java.util.concurrent.atomic.AtomicLong

val counter = AtomicLong(0)

/**
 * Events -- state-changing messages
 */
sealed class Event {
    // this could be either a global counter, or it could be just sharded
    val id = counter.incrementAndGet()
}

sealed class AccountEvent : Event() {
    abstract val amount: Long
    abstract val accountId: String
}

sealed class CommandStatusEvent : Event() {
    abstract val source: Command
}

data class CommandSuccessedEvent(override val source: Command) : CommandStatusEvent()

data class CommandFailedEvent(val msg: String, override val source: Command, val exception: Throwable?) : CommandStatusEvent() {
    constructor(msg: String, source: Command) : this(msg, source, null)
}

data class TransferCreatedEvent(val source: TransferCommand) : Event()
data class TransferChangeStateEvent(val transactionId: Long, val state: TransferState) : Event()

data class AccountCreatedEvent(override val accountId: String, override val amount: Long) : AccountEvent()
data class AccountDebitedEvent(val transactionId: Long, override val accountId: String, override val amount: Long) : AccountEvent()
data class AccountCreditedEvent(val transactionId: Long, override val accountId: String, override val amount: Long) : AccountEvent()

/**
 * Commands -- action performing messages
 */
sealed class Command {
    val id = counter.incrementAndGet()
}

data class TransferCommand(val from: String, val to: String, val amount: Long) : Command()

sealed class AccountCommand : Command() {
    abstract val accountId: String
}

data class CreateAccountCommand(override val accountId: String, val balance: Long, val immutable: Boolean) : AccountCommand()
data class DebitAccountCommand(val transactionId: Long, override val accountId: String, val amount: Long) : AccountCommand()
data class CreditAccountCommand(val transactionId: Long, override val accountId: String, val amount: Long) : AccountCommand()
