package com.vooft.moneytransfer.model

enum class TransferState {
    /**
     * initial state
     */
    PENDING,

    /**
     * sent debit command
     */
    DEBITING,
    /**
     * debit command processed
     */
    DEBITED,

    /**
     * sent credit command
     */
    CREDITING,
    /**
     * credit command processed
     */
    CREDITED,

    /**
     * failed to commit credit
     */
    CREDIT_FAILED,

    /**
     * transfer completed successfully
     */
    COMPLETED,

    /**
     * in current model if debit fails, no commit is made, so we need to compensate only for failed credit
     * to avoid "money lost in system"
     * to solve more complex cases we just need more states and events
     */
    DEBIT_COMPENSATING,

    /**
     * transfer failed after compensation
     */
    FAILED
}
