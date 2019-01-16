package com.vooft.moneytransfer.read

import com.vooft.moneytransfer.model.Transfer

/**
 * Simple read model that just returns current state for all transfers
 */
interface TransferReadModel {
    fun allTransfers(): List<Transfer>
}
