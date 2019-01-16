package com.vooft.moneytransfer.read

import com.vooft.moneytransfer.model.Account

/**
 * Simple read model that just returns current state for all accounts
 */
interface AccountReadModel {
    fun allAccounts(): List<Account>
}
