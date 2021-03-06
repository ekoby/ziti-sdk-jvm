/*
 * Copyright (c) 2018-2020 NetFoundry, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.openziti.net.nio

import org.hamcrest.CoreMatchers.isA
import org.hamcrest.CoreMatchers.startsWith
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import kotlin.random.Random

class AsyncTLSChannelTest {

    lateinit var ch: AsynchronousSocketChannel

    @Rule
    @JvmField val thrown = ExpectedException.none()

    @After
    fun tearDown() {
        ch.close()
    }

    @Test
    fun connect() {
        ch = AsyncTLSChannel.open()
        ch.connect(InetSocketAddress("httpbin.org", 443)).get(1, TimeUnit.SECONDS)
        verifyConnection(ch)
    }

    @Test
    fun useUnconnected() {
        val transport = AsynchronousSocketChannel.open()
        ch = AsyncTLSChannel(transport, SSLContext.getDefault())

        ch.connect(InetSocketAddress("google.com", 443)).get(1, TimeUnit.SECONDS)
        verifyConnection(ch)
    }

    @Test
    fun useConnected() {
        val transport = AsynchronousSocketChannel.open()
        transport.connect(InetSocketAddress("google.com", 443)).get(1, TimeUnit.SECONDS)
        ch = AsyncTLSChannel(transport, SSLContext.getDefault())

        verifyConnection(ch)
    }

    @Test
    fun sslError() {
        thrown.expect(ExecutionException::class.java)
        thrown.expectCause(isA(SSLException::class.java))
        ch = AsyncTLSChannel.open()
        ch.connect(InetSocketAddress("httpbin.org", 80)).get(1, TimeUnit.SECONDS)
    }

    @Test
    fun testMultiBufferWrite() {
        val payload = ByteBuffer.allocate(1024)
        Random.nextBytes(payload.array())

        val req = """POST /post HTTP/1.1
Accept: */*
Accept-Encoding: gzip, deflate
Connection: keep-alive
Host: httpbin.org
User-Agent: HTTPie/1.0.2
Content-Length: ${payload.remaining()}

"""
        val wb = arrayOf(StandardCharsets.US_ASCII.encode(req), payload)
        val writeTotal = wb.fold(0L){ c, b -> c + b.remaining() }
        ch = AsyncTLSChannel.open()
        ch.connect(InetSocketAddress("httpbin.org", 443)).get(1, TimeUnit.SECONDS)

        val wf = CompletableFuture<Long>()
        ch.write(wb, 0, 2, 1, TimeUnit.SECONDS, wf, FutureHandler())
        val wc = wf.get(2, TimeUnit.SECONDS)
        assertEquals(writeTotal, wc)

        val rb = ByteBuffer.allocate(10 * 1024)
        ch.read(rb).get(2, TimeUnit.SECONDS)
        rb.flip()

        val resp = StandardCharsets.UTF_8.decode(rb).toString().reader().readLines()
        assertThat(resp.first(), startsWith("HTTP/1.1 200 OK"))

    }

    fun verifyConnection(ch: AsynchronousSocketChannel) {
        assertNotNull(ch.localAddress)
        assertNotNull(ch.remoteAddress)
        val addr = ch.remoteAddress as InetSocketAddress
        val req = """GET / HTTP/1.1
Accept: */*
Accept-Encoding: gzip, deflate
Connection: keep-alive
Host: ${addr.hostName}
User-Agent: HTTPie/1.0.2


"""
        val wc = ch.write(StandardCharsets.US_ASCII.encode(req)).get(1, TimeUnit.SECONDS)
        assertEquals(req.length, wc)

        val resp = ByteBuffer.allocate(16 * 1024)
        val rc = ch.read(resp).get(5, TimeUnit.SECONDS)
        assertEquals(rc, resp.position())
        resp.flip()

        val lines = StandardCharsets.UTF_8.decode(resp).toString().reader().readLines()
        assertThat(lines[0], startsWith("HTTP/1.1"))
    }

}