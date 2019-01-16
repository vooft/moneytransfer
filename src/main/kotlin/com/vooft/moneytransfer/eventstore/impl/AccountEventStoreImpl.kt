package com.vooft.moneytransfer.eventstore.impl

import org.slf4j.LoggerFactory
import reactor.core.publisher.FluxSink
import reactor.core.publisher.Mono
import reactor.retry.Retry
import com.vooft.moneytransfer.bus.AccountCreatedEvent
import com.vooft.moneytransfer.bus.AccountDebitedEvent
import com.vooft.moneytransfer.bus.AccountEvent
import com.vooft.moneytransfer.bus.Event
import com.vooft.moneytransfer.eventstore.*
import com.vooft.moneytransfer.model.Account
import java.time.Duration
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Base in-memory storage for the account
 */
open class AccountImpl(event: AccountCreatedEvent) : Account {
    private val events = LinkedList<AccountEvent>()
    val locked = AtomicBoolean(false)
    override val id = event.accountId

    init {
        events.add(event)
    }

    open fun saveEvent(event: AccountEvent) {
        events.add(event)
    }

    // purely event-based balance
    override val balance: Long
        get() {
            return events.asSequence()
                    .map {
                        when (it) {
                            is AccountDebitedEvent -> -it.amount
                            else -> it.amount
                        }
                    }
                    .sum()
        }

    override fun toString(): String {
        return "AccountImpl(events=$events, locked=$locked, id='$id')"
    }
}

/**
 * Immutable version of the in-memory storage, mostly here to test credit failure
 */
class ImmutableAccountImpl(event: AccountCreatedEvent) : AccountImpl(event) {
    override fun saveEvent(event: AccountEvent) {
        throw RuntimeException("Account is immutable")
    }
}

/**
 * Marker for locked account to make sure no one will try to commit unlocked account
 */
private class LockedAccountImpl(val delegate: Account, val commit: (account: Account, event: AccountEvent?) -> Mono<Void>) : LockedAccount {
    private val committed = AtomicBoolean(false)

    override fun commitEvent(event: AccountEvent): Mono<Void> {
        if (!committed.compareAndSet(false, true)) {
            return Mono.error(CommitException("Already committed", event))
        }

        return commit(delegate, event)
    }

    override fun cancel(): Mono<Void> {
        if (!committed.compareAndSet(false, true)) {
            return Mono.empty()
        }

        return commit(delegate, null)
    }

    override val id: String
        get() = delegate.id
    override val balance: Long
        get() = delegate.balance

}

class AccountEventStoreImpl(private val eventSink : FluxSink<Event>) : AccountEventStore {
    private val log = LoggerFactory.getLogger(javaClass)
    private val accounts = ConcurrentHashMap<String, AccountImpl>()

    override fun lockAccount(account: String): Mono<LockedAccount> {
        return Mono.defer {
            val existingAccount = accounts[account] ?: return@defer Mono.error<LockedAccount>(AccountNotFoundException(account))

            // not a fair locking, this could be solved by using an external locking system or a locks queue
            Mono.justOrEmpty(existingAccount)
                    .flatMap {
                        if (it.locked.compareAndSet(false, true)) {
                            Mono.just(it)
                        } else {
                            Mono.error(UnableToLockException())
                        }
                    }

                    // retry on UnableToLockException after a random backoff for Long.MAX_VALUE times
                    .retryWhen(Retry.anyOf<Account>(UnableToLockException::class.java)
                            .randomBackoff(Duration.ofMillis(1), Duration.ofMillis(100)))
                    .map {
                        LockedAccountImpl(it, this::commitAccount)
                    }
        }
    }

    override fun createAccount(account: String, balance: Long, immutable: Boolean): Mono<Account> {
        return Mono.defer {
            // this call has to be atomic
            val accountImpl = accounts.compute(account) { id, existingAccount ->
                if (existingAccount != null) {
                    throw AccountAlreadyExistsException(id)
                }

                val event = AccountCreatedEvent(id, balance)
                eventSink.next(event)

                if (immutable) {
                    ImmutableAccountImpl(event)
                } else {
                    AccountImpl(event)
                }
            }!!

            return@defer Mono.just<Account>(accountImpl)
        }
    }

    private fun commitAccount(account: Account, event: AccountEvent?): Mono<Void> {
        return Mono.defer {
            cast(account)
        }.flatMap<Void> {
            // no event -- it is a cancellation
            if (event == null) {
                it.locked.set(false)
                return@flatMap Mono.empty()
            }

            // check if we are really locked to avoid unnecessary rollback
            // should not normally happen
            if (!it.locked.get()) {
                return@flatMap Mono.error(CommitException("Account not locked", event))
            }

            try {
                // start of the atomic commit
                it.saveEvent(event)
                eventSink.next(event)
                // end of the atomic commit
            } catch (e: Exception) {
                val commitError = CommitException("Error occurred on commit: ${e.message}", event)
                log.error("Commit error", commitError)

                // unlock and fail
                it.locked.set(false)
                return@flatMap Mono.error(commitError)
            }

            // if we use optimistic locking, this should be ok, just need to rollback
            // with pessimistic locking this should never-ever happen and now we are in undefined state
            if (!it.locked.compareAndSet(true, false)) {
                return@flatMap Mono.error(VersionConflictError(event))
            }

            return@flatMap Mono.empty()
        }
    }

    private fun cast(account: Account) : Mono<AccountImpl> {
        if (account is AccountImpl) {
            return Mono.just(account)
        }

        return Mono.error(IllegalArgumentException("Invalid Account subclass " + account.javaClass.canonicalName))
    }
}

/**
 * Exception indicating that lock was unsuccessful and need to try again
 */
private class UnableToLockException : RuntimeException(null, null, true, false)
