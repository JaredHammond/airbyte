/*
 * Copyright (c) 2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.source.e2e_test

import com.fasterxml.jackson.core.util.MinimalPrettyPrinter
import io.airbyte.protocol.models.v0.AirbyteMessage
import java.io.File
import java.io.OutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.CompletableFuture.runAsync
import java.util.concurrent.Executors

private const val N_THREADS = 8

class JavaImprovedSocketWriter {
    private val executor = Executors.newFixedThreadPool(N_THREADS)
    private val writer = DummyMessageIterator.SMILE_MAPPER.writerFor(AirbyteMessage::class.java).with(MinimalPrettyPrinter(System.lineSeparator()))

    companion object {
        const val RECORD =
            """{"type":"RECORD","record":{"stream":"stream1","data":{"field1":"valuevaluevaluevaluevalue1","field3":"valuevaluevaluevaluevalue1","field2":"valuevaluevaluevaluevalue1","field5":"valuevaluevaluevaluevalue1","field4":"valuevaluevaluevaluevalue1"},"emitted_at":1742801071589}}"""

        val array: ByteArray = (RECORD + "\n").toByteArray(Charsets.UTF_8)
    }

    fun startJavaUnixSocketWriter() {
        println("SOURCE SERIALISED , Number of threads/sockets $N_THREADS")
        (0 until N_THREADS)
            .map { socketId ->
                runAsync({
                    start("sock$socketId")
                }, executor)
            }.forEach { it.join() }
    }

    private fun start(sock: String) {
        val socketFile = File("/var/run/sockets/source.$sock")
        if (socketFile.exists()) {
            socketFile.delete()
        }
        val address = UnixDomainSocketAddress.of(socketFile.toPath())

        // Open a server channel for Unix domain sockets
        val serverSocketChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        serverSocketChannel.bind(address)
        println("Source $sock : Server socket bound at ${socketFile.absolutePath}")

        // Accept a client connection (blocking call)
        val socketChannel: SocketChannel = serverSocketChannel.accept()
        socketChannel.use { socket ->
            println("Source $sock : Client connected $sock")

            val bufferedOutputStream = Channels.newOutputStream(socket).buffered()
            bufferedOutputStream.use { outputStream ->
                writeSerialised(outputStream)
//                writeProtobuf(outputStream)
                writeSmileSerialised(outputStream)
            }

            println("Source $sock : Finished writing to socket $sock")
        }
    }

    private fun writeSerialised(outputStream: OutputStream) {
        var records: Long = 0
        println("Writing JSON...")
        DummyIterator().use { dummyIterator ->
            DummyIterator.OBJECT_MAPPER
                .writerFor(AirbyteMessage::class.java)
                .with(MinimalPrettyPrinter(System.lineSeparator()))
                .writeValues(outputStream)
                .use { seq ->
                    dummyIterator.forEachRemaining { message ->
                        seq.write(message)
                        records++
                        if (records == 100_000L) {
                            outputStream.flush()
                            records = 0
                        }
                    }
                }
        }
    }

    private fun writeSmileSerialised(outputStream: OutputStream) {
        var records: Long = 0
        println("Writing SMILE Dummy .....")
        DummyMessageIterator().use { dummyIterator ->
            writer.writeValues(outputStream)
                .use { seq ->
                    dummyIterator.forEachRemaining { message ->
                        seq.write(message)
                        records++
                        if (records == 100_000L) {
                            outputStream.flush()
                            records = 0
                        }
                    }
                }
        }
    }

    private fun writeProtobuf(outputStream: OutputStream) {
        println("Writing protobuf with 8 cpu...")
        var records: Long = 0
        DummyProtobufIterator().use { dummyIterator ->
            dummyIterator.forEachRemaining { message ->
                message.writeDelimitedTo(outputStream)
                records++
                if (records == 100_000L) {
                    outputStream.flush()
                    records = 0
                }
            }
        }
    }

    private fun writeFromOneThread(outputStream: OutputStream) {
        var records: Long = 0
        println("Writing static string...")
        DummyIterator().use {
            it.forEachRemaining {
                outputStream.write(array)
                records++
                if (records == 100_000L) {
                    outputStream.flush()
                    records = 0
                }
            }
        }
    }
}
