/*
 * Copyright 2026  Kumarapu Hemram
 * Modified 2026 by Koda Studio contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp

import org.eclipse.lsp4j.launch.LSPLauncher
import org.kotlinlsp.lsp.KotlinLanguageServer
import org.kotlinlsp.common.ServerStatus
import org.kotlinlsp.common.getLspVersion
import org.kotlinlsp.common.profileJvmStartup
import org.kotlinlsp.lsp.KotlinLanguageServerNotifier
import java.util.concurrent.Executors
import kotlin.system.exitProcess
import java.net.ServerSocket
import java.net.Socket

fun main(args: Array<String>) {
    profileJvmStartup()
    if ("-v" in args || "--version" in args) {
        println(getLspVersion())
        return
    }

    // Detect TCP mode
    val portFlagIndex = args.indexOf("--port")
    val tcpEnabled = "--tcp" in args || portFlagIndex != -1

    if (tcpEnabled) {
        // Determine port (default 2090 if not specified)
        val port = if (portFlagIndex != -1 && portFlagIndex + 1 < args.size) {
            args[portFlagIndex + 1].toIntOrNull() ?: 2090
        } else 2090

        startTcpServer(port)
    } else {
        startStdioServer()
    }
}

/**
 * VS Code cancels completions it no longer needs, usually after the reply was already sent; LSP4J logs each one as
 * "Unmatched cancel notification". The logger is kept in a field: java.util.logging holds loggers weakly, and a
 * collected logger would lose the filter.
 */
private val rpcLogger = java.util.logging.Logger.getLogger(org.eclipse.lsp4j.jsonrpc.RemoteEndpoint::class.java.name).apply {
    filter = java.util.logging.Filter { record -> record.message?.startsWith("Unmatched cancel notification") != true }
}

private fun startStdioServer() {
    val notifier = object : KotlinLanguageServerNotifier {
        override fun onExit() {
            exitProcess(0)
        }
    }
    val executor = Executors.newSingleThreadExecutor {
        Thread(it, "KotlinLSP-LSP")
    }
    val server = KotlinLanguageServer(notifier)
    val launcher = LSPLauncher.createServerLauncher(server, System.`in`, System.out, executor, ::guardMessages)

    server.connect(launcher.remoteProxy)
    ServerStatus.connect { launcher.remoteEndpoint.notify("koda/status", it) }
    // The listener ends when stdin closes: the client is gone (VS Code quit or crashed). Exit then; otherwise the
    // server outlived its editor, holding memory and its index lock.
    launcher.startListening().get()
    runCatching { server.exit() } // closes the index; its notifier ends the process
    exitProcess(0)
}

private fun startTcpServer(port: Int) {
    println("Kotlin LSP listening on 0.0.0.0:$port (TCP)...")
    val serverSocket = ServerSocket(port)
    while (true) {
        val clientSocket: Socket = serverSocket.accept()
        val notifier = object : KotlinLanguageServerNotifier {
            override fun onExit() {
                // Close the client socket when the server requests exit
                try {
                    clientSocket.close()
                } catch (_: Exception) {}
            }
        }

        val executor = Executors.newSingleThreadExecutor {
            Thread(it, "KotlinLSP-LSP")
        }
        val server = KotlinLanguageServer(notifier)
        val launcher = LSPLauncher.createServerLauncher(
            server,
            clientSocket.getInputStream(),
            clientSocket.getOutputStream(),
            executor
        ) { it }

        server.connect(launcher.remoteProxy)
        ServerStatus.connect { launcher.remoteEndpoint.notify("koda/status", it) }

        // Handle the connection synchronously; once it ends, loop back to accept a new one
        launcher.startListening()
    }
}

/**
 * The JSON-RPC reader runs as one task on the executor, which swallows anything thrown, so a single Error in a
 * handler (e.g. a NoSuchMethodError) silently stopped the server from reading further messages. Log it and go on.
 */
private fun guardMessages(consumer: org.eclipse.lsp4j.jsonrpc.MessageConsumer) = org.eclipse.lsp4j.jsonrpc.MessageConsumer { message ->
    try {
        consumer.consume(message)
    } catch (t: Throwable) {
        System.err.println("Error while handling ${(message as? org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage)?.method ?: message.javaClass.simpleName}:")
        t.printStackTrace()
    }
}
