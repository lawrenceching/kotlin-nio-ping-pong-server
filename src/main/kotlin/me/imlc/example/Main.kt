package me.imlc.example

import java.io.IOException
import java.lang.RuntimeException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit


class Main {

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {

            val server = NioServer()
            val client = NioClient()

            server.start()
            client.start()
        }
    }
}

private class NioServer {
    fun start() {
        val selector = Selector.open()
        val serverSocketChannel = ServerSocketChannel.open()

        val inetAddress = InetSocketAddress("localhost", 8080)
        serverSocketChannel.bind(inetAddress)

        serverSocketChannel.configureBlocking(false)

        val validOps = serverSocketChannel.validOps()
        serverSocketChannel.register(selector, validOps)

        val thread = Thread {
            while (!Thread.currentThread().isInterrupted) {
                selector.select();
                val iterator = selector.selectedKeys().iterator()
                while (iterator.hasNext()) {
                    val selectedKey = iterator.next()
                    if (!selectedKey.isValid) continue
                    if (selectedKey.isAcceptable) {
                        val socketChannel = serverSocketChannel.accept()
                        if (socketChannel != null) {
                            socketChannel.configureBlocking(false)
                            socketChannel.register(selector, SelectionKey.OP_READ)
                            println("Accepted new connection")
                        } else {
                            error("ERROR: Null socketChannel")
                        }

                    } else if (selectedKey.isReadable) {
                        val channel = selectedKey.channel() as SocketChannel
                        val bf = ByteBuffer.allocate(1024)
                        try {
                            val count = channel.read(bf)
                            val msg = java.lang.String(bf.array()).trim()
                            println("Server: $msg")
                            channel.register(selector, SelectionKey.OP_WRITE)
                        } catch (e: IOException) {
                            selectedKey.cancel()
                            serverSocketChannel.close()
                            error("ERROR: NIO Server IO Exception: ${e.message}")
                        }

                    } else if (selectedKey.isWritable) {
                        val socketChannel = selectedKey.channel() as SocketChannel
                        val byteBuffer = ByteBuffer.wrap("PONG".toByteArray(StandardCharsets.UTF_8))
                        socketChannel.write(byteBuffer)
                        socketChannel.register(selector, SelectionKey.OP_READ)
                    }
                    iterator.remove()
                }
            }
        }

        thread.name = "NIO-Server"
        thread.start()

        while(!serverSocketChannel.isOpen) {

        }

    }
}

private class NioClient {

    fun start() {
        val address = InetSocketAddress("localhost", 8080)
        val socketChannel = SocketChannel.open()
        socketChannel.configureBlocking(false)

        val selector = Selector.open()
        if (socketChannel.connect(address)) {
            socketChannel.register(selector, SelectionKey.OP_READ)
            val byteBuffer = ByteBuffer.wrap("PING".toByteArray(StandardCharsets.UTF_8))
            socketChannel.write(byteBuffer)
        } else {
            socketChannel.register(selector, SelectionKey.OP_CONNECT)
        }

        val connectLatch = CountDownLatch(1)

        val thread = Thread {
            while (!Thread.currentThread().isInterrupted) {
                selector.select()

                val iterator = selector.selectedKeys().iterator()

                while (iterator.hasNext()) {
                    val selectedKey = iterator.next()
                    iterator.remove()

                    if (selectedKey.isConnectable) {
                        if (socketChannel.finishConnect()) {
                            socketChannel.register(selector, SelectionKey.OP_WRITE)
                            println("Connected to $address")
                            connectLatch.countDown()
                        }
                    } else if (selectedKey.isWritable) {
                        val byteBuffer = ByteBuffer.wrap("PING".toByteArray(StandardCharsets.UTF_8))
                        socketChannel.write(byteBuffer)
                        socketChannel.register(selector, SelectionKey.OP_READ)
                    } else if (selectedKey.isReadable) {
                        val byteBuffer = ByteBuffer.allocate(1024)
                        val socketChannel = selectedKey.channel() as SocketChannel
                        val count = socketChannel.read(byteBuffer)
                        println("Client: ${byteBuffer.array().copyOfRange(0, count).toString(StandardCharsets.UTF_8)}")
                        socketChannel.register(selector, SelectionKey.OP_WRITE)
                        Thread.sleep(1000)
                    }
                }
            }
        }

        thread.name = "NIO-Client"

        thread.start()

        if(!connectLatch.await(1, TimeUnit.SECONDS)) {
            socketChannel.close()
            throw RuntimeException("Client did not start")
        }
    }


}