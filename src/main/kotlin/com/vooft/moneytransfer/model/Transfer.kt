package com.vooft.moneytransfer.model

interface Transfer {
    val id: Long // currently it is just an id of source command
    val from: String
    val to: String
    val amount: Long
    val state: TransferState
}
