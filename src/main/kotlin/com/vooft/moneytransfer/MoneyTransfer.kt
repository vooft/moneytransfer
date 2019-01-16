package com.vooft.moneytransfer

import io.netty.handler.codec.http.QueryStringDecoder
import org.reactivestreams.Publisher
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import reactor.netty.http.server.HttpServerResponse
import com.vooft.moneytransfer.service.MoneyTransferService
import com.vooft.moneytransfer.service.Response
import com.vooft.moneytransfer.service.impl.MoneyTransferServiceImpl

private val service: MoneyTransferService = MoneyTransferServiceImpl()

fun main(args: Array<String>) {
    startServer()

    service.createAccount("alice", 100, false).block()
    service.createAccount("bob", 200, false).block()
    service.createAccount("carol", 0, false).block()
    service.createAccount("imma", 200, true).block()

    LoggerFactory.getLogger("main").info("started")

    Thread.sleep(Long.MAX_VALUE)
}

fun startServer(): DisposableServer {
    return HttpServer.create()
            .port(8080)
            .route { routes ->
                routes.get("/createAccount") { request, response ->
                    val mono = Mono.defer {
                        val query = QueryStringDecoder(request.uri())

                        val balance = query.longParam("balance")
                        val accountId = query.param("accountId")
                        val immutable = query.booleanParam("immutable", "false")
                        service.createAccount(accountId, balance, immutable)
                    }

                    sendResponse(response, mono)
                }.get("/balances") { _, response ->
                    val mono = service.balances()
                    sendResponse(response, mono)
                }.get("/transfers") { _, response ->
                    val mono = service.transfers()
                    sendResponse(response, mono)
                }.get("/transfer") { request, response ->
                    val mono = Mono.defer {
                        val query = QueryStringDecoder(request.uri())

                        val from = query.param("from")
                        val to = query.param("to")
                        val amount = query.longParam("amount")

                        service.transfer(from, to, amount)
                    }

                    sendResponse(response, mono)
                }
            }
            .bindNow()
}


fun sendResponse(response: HttpServerResponse, mono: Mono<Response>) : Publisher<Void> {
    return mono.onErrorResume {
        if (it is InvalidParameterException) {
            Mono.just(Response("Invalid parameter ${it.param}", 400))
        } else {
            Mono.just(Response(it.message ?: it.javaClass.canonicalName, 500))
        }
    }.flatMapMany {
        val body = if (!it.body.endsWith("\n")) {
            it.body + "\n"
        } else {
            it.body
        }

        response.status(it.status)
                .sendString(Mono.just(body))
    }
}

fun QueryStringDecoder.param(key: String) : String {
    try {
        val params = this.parameters()
        val values = params[key]!!
        return values[0]!!
    } catch (e: Exception) {
        throw InvalidParameterException(key, e)
    }
}

fun QueryStringDecoder.longParam(key: String) : Long {
    val string = param(key)
    try {
        return string.toLong()
    } catch (e: Exception) {
        throw InvalidParameterException(key, e)
    }
}

fun QueryStringDecoder.booleanParam(key: String, defaultValue: String) : Boolean {
    try {
        return param(key, defaultValue).toBoolean()
    } catch (e: Exception) {
        throw InvalidParameterException(key, e)
    }
}

fun QueryStringDecoder.param(key: String, defaultValue: String) : String {
    val params = this.parameters()
    val values = params[key] ?: listOf(defaultValue)
    return values[0]!!
}

class InvalidParameterException(val param: String, cause: Exception) : RuntimeException(cause)
