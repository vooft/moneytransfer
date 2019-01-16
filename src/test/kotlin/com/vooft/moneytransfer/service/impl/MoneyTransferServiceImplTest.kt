package com.vooft.moneytransfer.service.impl

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import com.vooft.moneytransfer.bus.AccountCreatedEvent
import com.vooft.moneytransfer.bus.AccountCreditedEvent
import com.vooft.moneytransfer.bus.AccountDebitedEvent
import com.vooft.moneytransfer.bus.AccountEvent
import com.vooft.moneytransfer.model.Transfer
import com.vooft.moneytransfer.model.TransferState
import com.vooft.moneytransfer.service.Response
import java.util.*

private const val TEST_ACCOUNT_NAME = "test"
private const val TEST_ACCOUNT_BALANCE = 100L

internal class MoneyTransferServiceImplTest {
    private lateinit var service: MoneyTransferServiceImpl

    @BeforeEach
    fun setUp() {
        service = MoneyTransferServiceImpl()
    }

    @Test
    fun testCreateAccount() {
        assertResponseCode(service.createAccount(TEST_ACCOUNT_NAME, TEST_ACCOUNT_BALANCE, false), 200)
        assertSingleTestAccount()

        // test duplicate
        assertResponseCode(service.createAccount(TEST_ACCOUNT_NAME, 9999, false), 412)
        assertSingleTestAccount()
    }

    @Test
    fun testInvalidAccountCreation() {
        assertResponseCode(service.createAccount(TEST_ACCOUNT_NAME, TEST_ACCOUNT_BALANCE, false), 200)
        assertSingleTestAccount()

        assertResponseCode(service.createAccount("abcd", -100, false), 400)
        assertSingleTestAccount()

        assertResponseCode(service.createAccount("", 100, false), 400)
        assertSingleTestAccount()
    }

    @Test
    fun testSuccessfulTransfer() {
        assertResponseCode(service.createAccount("bob", 100, false), 200)
        assertResponseCode(service.createAccount("alice", 100, false), 200)

        // try first transfer bob -> alice
        assertResponseCode(service.transfer("bob", "alice", 10), 200)
        assertAccounts("bob" to 90, "alice" to 110)

        // try second transfer alice -> bob
        assertResponseCode(service.transfer("alice", "bob", 50), 200)
        assertAccounts("bob" to 140, "alice" to 60)

        Thread.sleep(100) // need to wait for read model to update

        assertTransfers(TransferMock("bob", "alice", 10, TransferState.COMPLETED),
                TransferMock("alice", "bob", 50, TransferState.COMPLETED))
    }

    @Test
    fun testInsufficientFunds() {
        assertResponseCode(service.createAccount("bob", 10, false), 200)
        assertResponseCode(service.createAccount("alice", 100, false), 200)

        assertResponseCode(service.transfer("bob", "alice", 100), 412)

        assertAccounts("bob" to 10, "alice" to 100)
        assertTransfers(TransferMock("bob", "alice", 100, TransferState.FAILED))
    }

    @Test
    fun testInvalidUsername() {
        assertResponseCode(service.createAccount("bob", 100, false), 200)
        assertResponseCode(service.createAccount("alice", 100, false), 200)

        assertAccounts("bob" to 100, "alice" to 100)

        assertResponseCode(service.transfer("bob123", "alice", 100), 412)
        assertResponseCode(service.transfer("bob", "alice123", 100), 412)

        Thread.sleep(100)

        assertTransfers(TransferMock("bob123", "alice", 100, TransferState.FAILED),
                TransferMock("bob", "alice123", 100, TransferState.FAILED))
    }

    @Test
    fun testDebitRollback() {
        assertResponseCode(service.createAccount("bob", 100, false), 200)
        assertResponseCode(service.createAccount("alice", 1, false), 200)
        assertResponseCode(service.createAccount("imma", 100, true), 200)

        assertResponseCode(service.transfer("bob", "alice", 1), 200)
        assertAccounts("bob" to 99, "alice" to 2, "imma" to 100)

        assertResponseCode(service.transfer("alice", "bob", 1), 200)
        assertAccounts("bob" to 100, "alice" to 1, "imma" to 100)

        assertResponseCode(service.transfer("imma", "bob", 1), 412)
        assertAccounts("bob" to 100, "alice" to 1, "imma" to 100)

        assertResponseCode(service.transfer("bob", "imma", 1), 412)
        Thread.sleep(100) // need to sleep, because controll will be returned before the rollback occur
        assertAccounts("bob" to 100, "alice" to 1, "imma" to 100)

        assertTransfers(TransferMock("bob", "alice", 1, TransferState.COMPLETED),
                TransferMock("alice", "bob", 1, TransferState.COMPLETED),
                TransferMock("imma", "bob", 1, TransferState.FAILED),
                TransferMock("bob", "imma", 1, TransferState.FAILED))
    }

    @Test
    fun testDebitRollbackEvent() {
        val events = Collections.synchronizedList(LinkedList<AccountEvent>())
        service.eventBus.eventFlux.filter {
            it is AccountEvent && it.accountId == "bob"
        }
                .doOnNext { events.add(it as AccountEvent) }
                .subscribe()

        assertResponseCode(service.createAccount("bob", 100, false), 200)
        assertResponseCode(service.createAccount("alice", 1, false), 200)
        assertResponseCode(service.createAccount("imma", 100, true), 200)

        assertResponseCode(service.transfer("bob", "imma", 1), 412)

        Thread.sleep(100) // ensure all events were processed

        assertThat(events, hasSize(3))
        assertThat(events[0], instanceOf(AccountCreatedEvent::class.java))

        val debited = events[1] as AccountDebitedEvent
        val credited = events[2] as AccountCreditedEvent

        assertEquals(debited.transactionId, credited.transactionId)
        assertEquals(debited.amount, credited.amount)
    }

    private fun assertAccounts(vararg pairs: Pair<String, Long>) {
        val accounts = service.accountReadModel.allAccounts()
        assertThat(accounts, hasSize(pairs.size))

        for (pair in pairs) {
            val account = accounts.find { it.id == pair.first }
            assertNotNull(account, "not found account ${pair.first}")

            assertThat(account!!.balance, `is`(pair.second))
        }
    }

    private fun assertTransfers(vararg mocks: TransferMock) {
        val transfers = service.transferReadModel.allTransfers()
        assertThat(transfers, hasSize(mocks.size))

        for ((index, t) in mocks.withIndex()) {
            val transfer = transfers[index]
            assertThat(transfer.from, `is`(t.from))
            assertThat(transfer.to, `is`(t.to))
            assertThat(transfer.amount, `is`(t.amount))
            assertThat(transfer.state, `is`(t.state))
        }
    }

    private fun assertSingleTestAccount() {
        assertAccounts(TEST_ACCOUNT_NAME to TEST_ACCOUNT_BALANCE)
    }

    private fun assertResponseCode(mono: Mono<Response>, status: Int) {
        val response = mono.block()!!
        assertEquals(response.status, status)
    }

    private data class TransferMock(override val from: String,
                                    override val to: String,
                                    override val amount: Long,
                                    override val state: TransferState) : Transfer {
        override val id: Long = -1 // not important here
    }
}
