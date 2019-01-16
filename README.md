## Overview
This is a simple money transfer RESTful service designed using Event Sourcing, CQRS and a bit of Saga patterns.

## Tech stack

* Base library is [Project Reactor](http://projectreactor.io)
* Http implementation is from [Reactor Netty](https://projectreactor.io/docs/netty/release/reference/index.html)
* Logging is slf4j with log4j2 backend
* JUnit5/hamcrest for unit testing
* Kotlin-dsl Gradle as a build system (>=Gradle-4.10, tested on 5.0)

## How to use

Agreements:

* Server runs on port 8080
* All requests return some free-form text description
* All successful responses will have status 200, all failed ones will have something else

```
# Start the server
$ ./gradlew run

# There are 4 preconfigured accounts:
# alice - 100
# bob - 200
# carol - 0
# imma - 200 -- this is immutable account

# these numbers could be checked using following command
$ curl 'localhost:8080/balances'

# no transfers happened at this point, this request will return an empty response
$ curl 'localhost:8080/transfers'

# try to create a new account
$ curl -i 'localhost:8080/createAccount?accountId=test&balance=100'

# any invalid input (negative balance, missing/invalid values) will result in response 400
$ curl -i 'localhost:8080/createAccount?balance=-100&accountId=abcd'
$ curl -i 'localhost:8080/createAccount?balance=100&accountId'
$ curl -i 'localhost:8080/createAccount'

# there is one optional parameter that can create an immutable account
$ curl -i 'localhost:8080/createAccount?balance=100&accountId=abcde&immutable=true'

# transfer money between accounts
$ curl -i 'localhost:8080/transfer?from=bob&to=alice&amount=1'

# system will try to fail-fast in case of any issue
# insufficient funds
$ curl -i 'localhost:8080/transfer?from=bob&to=alice&amount=99999'

# invalid account name
$ curl -i 'localhost:8080/transfer?from=bob123&to=alice&amount=1'
$ curl -i 'localhost:8080/transfer?from=bob&to=alice123&amount=1'

# immutable account
$ curl -i 'localhost:8080/transfer?from=imma&to=alice&amount=1'
$ curl -i 'localhost:8080/transfer?from=bob&to=imma&amount=1'

# transfer history will show all transfers and their current state
$ curl -i 'localhost:8080/transfers'
```

### Misc

There is an integration test that covers most of the use cases.
Thread.sleep are inserted there because validation happens against read models with eventual consistency.

## Architecture overview

Overall I tried to achieve full non-blocking architecture with a lightweight message exchange between different actors.

### Domain model
Underlying implementation may vary depending on use case, persistence level stores every object as a sequence of events,
specific read-only models may just store current state

Account has 2 fields: account id and a current balance. Account command doesn't have any state, it is either committed or not.
For demonstration purposes was added an "immutable" type of account, that can have only start balance and all further events are rejected.

Transfer has 4 fields: from account, to account, amount and current state. Current state is dynamic and calculated based
on state change events. Transfer command itself generates at least one Debit command and one Credit command, if Credit command fails,
there is one more compensation Credit for the "from" account generated

### Messages
2 base event entities: Command and Event.
Command could be rejected and are directly or indirectly responding on a user's request
Event changes the system state and is an outcome of an execution of a command.

### Event Bus
EventBus class has one flux (=stream) and one flux sink (=publisher) for consuming and producing messages correspondingly.


### Handling commands

#### Account
AccountCommandProcessor accepts 3 types of commands: AccountCreateCommand, AccountDebitCommand and AccountCreditCommand.
I made an assumption that a not in-memory event queue could be partitioned by event type + accountId, so the same command
will not be received by 2 independent processors. Deduplication could be implemented on event storage level by checking source command.

To commit an event, store tries to acquire a lock and just repeats this attempt until succeeded. It is not a fair locking,
but should be good enough for the test case. Solution would be using a queue of lock requests, or an external locking system.

If something happened during the commit, the whole command is considered failed

#### Transfer
TransferCommandProcessor only accepts TransferCommand, but during different stages of the transfer, it generates 0 or 1
account commands. The atomicity is only required for promoting the command to the next stage and submitting a new account command.

Currently there is still a point of failure when a transfer was promoted, but something happened before new command is published.

### Read models
There are two read models implemented, one for accounts, one for transfers. They just store current state of an entity.

