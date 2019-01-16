package com.vooft.moneytransfer.eventstore

import reactor.core.publisher.Mono
import com.vooft.moneytransfer.bus.AccountEvent
import com.vooft.moneytransfer.model.Account

interface AccountEventStore {
    /**
     * Tries to create an account, returns mono with {@link AccountAlreadyExistsException}
     */
    fun createAccount(account: String, balance: Long, immutable: Boolean) : Mono<Account>

    /**
     * Locks an account and returns an active object with the latest version from the storage
     */
    fun lockAccount(account: String) : Mono<LockedAccount>
}

/**
 * Active object representing a currently locked account
 */
interface LockedAccount : Account {
    /**
     * Performs a commit and returns the committed event in case of success, or a {@link CommitException} in case of an issue
     */
    fun commitEvent(event: AccountEvent) : Mono<Void>

    /**
     * Unlocks account without committing anything
     */
    fun cancel() : Mono<Void>
}

class AccountAlreadyExistsException(account: String) : RuntimeException("Account $account already exists", null, true, false)
class AccountNotFoundException(account: String) : RuntimeException("Account $account not found", null, true, false)

/**
 * Non-fatal error indicating that commit has failed, but system is still in consistent state
 */
class CommitException(message: String, val event: AccountEvent) : RuntimeException(message, null, true, false)

/**
 * Fatal error indicating that system is in inconsistent state and remediation is required
 */
// should be probably converted into Exception in optimistic-locking model
class VersionConflictError(val event: AccountEvent) : Error(null, null, true, false)
