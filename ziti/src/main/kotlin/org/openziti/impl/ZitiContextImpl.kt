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

package org.openziti.impl

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.consumeAsFlow
import org.openziti.*
import org.openziti.api.*
import org.openziti.identity.Identity
import org.openziti.net.Channel
import org.openziti.net.ZitiServerSocketChannel
import org.openziti.net.ZitiSocketChannel
import org.openziti.net.dns.ZitiDNSManager
import org.openziti.net.internal.ZitiSocket
import org.openziti.util.Logged
import org.openziti.util.ZitiLog
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.AsynchronousSocketChannel
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext
import kotlin.properties.Delegates
import kotlinx.coroutines.channels.Channel as Chan
import org.openziti.api.Identity as ApiIdentity

/**
 * Object maintaining current Ziti session.
 *
 */
@ExperimentalCoroutinesApi
internal class ZitiContextImpl(internal val id: Identity, enabled: Boolean) : ZitiContext, Identity by id,
    CoroutineScope, Logged by ZitiLog() {

    private var _enabled: Boolean by Delegates.observable(false) { _, _, isEnabled ->
        if (isEnabled) {
            statusCh.offer(ZitiContext.Status.Loading)
            val session = login()
            runServiceUpdates(session)
        } else {
            statusCh.offer(ZitiContext.Status.Disabled)
            stop()
        }
    }

    private val supervisor = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + supervisor

    private var apiId: ApiIdentity? = null
    private val controller: Controller
    private val statusCh: ConflatedBroadcastChannel<ZitiContext.Status>
    private val serviceCh: BroadcastChannel<ZitiContext.ServiceEvent>

    private val servicesLoaded = CompletableDeferred<Unit>()
    private val servicesByName = mutableMapOf<String, Service>()
    private val servicesById = mutableMapOf<String, Service>()
    private val servicesByAddr = mutableMapOf<InetSocketAddress, Service>()
    private val addrById = mutableMapOf<String, InetSocketAddress>()

    data class SessionKey (val serviceId: String, val type: SessionType)
    private val networkSessions = ConcurrentHashMap<SessionKey, NetworkSession>()

    init {
        controller = Controller(URI.create(id.controller()).toURL(), sslContext(), trustManager(), sessionToken)
        statusCh = ConflatedBroadcastChannel(ZitiContext.Status.Disabled)
        serviceCh = BroadcastChannel(kotlinx.coroutines.channels.Channel.BUFFERED)

        launch {
            val sub = statusCh.openSubscription()
            for (s in sub)  {
                d { "${this@ZitiContextImpl} transitioned to $s" }
            }
        }
        this._enabled = enabled
    }

    override fun getId(): ApiIdentity? = apiId

    override fun getStatus() = statusCh.value
    override fun statusUpdates() = statusCh.openSubscription()

    override fun serviceUpdates(): ReceiveChannel<ZitiContext.ServiceEvent> {

        val ch = Chan<ZitiContext.ServiceEvent>(Chan.BUFFERED)
        val sub = serviceCh.openSubscription()
        launch {
            servicesByName.values.asFlow().collect {
                ch.send(ZitiContext.ServiceEvent(it, ZitiContext.ServiceUpdate.Available))
            }
            sub.consumeAsFlow().collect {
                ch.send(it)
            }
        }

        ch.invokeOnClose {
            sub.cancel(null)
        }
        return ch
    }

    private fun updateStatus(s: ZitiContext.Status) {
        if (statusCh.value != ZitiContext.Status.Disabled) {
            statusCh.offer(s)
        }
    }

    override fun setEnabled(v: Boolean) {
        _enabled = v
    }

    override fun dial(serviceName: String): ZitiConnection {
        val conn = open() as ZitiSocketChannel
        conn.connect(ZitiAddress.Service(serviceName)).get()
        return conn
    }

    internal fun dialById(serviceId: String): ZitiConnection =
        servicesById[serviceId]?.let {
            dial(it.name)
        } ?: throw ZitiException(Errors.ServiceNotAvailable)


    internal fun dial(host: String, port: Int): ZitiConnection {
        val service = getService(host, port)
        return dial(service.name)
    }

    override fun connect(host: String, port: Int): Socket = ZitiSocket(dial(host, port))

    override fun stop() {
        val copy = channels.values

        copy.forEach { ch ->
            ch.close()
        }
    }

    fun destroy() {
        try { controller.shutdown() } catch(_: Exception) {}
        runBlocking { supervisor.cancelAndJoin() }
    }

    internal fun login() = async {
        try {
            controller.login().also {
                d("${name()} logged in successfully s[${it.token}] at ${controller()} t[${Thread.currentThread().name}]")
                sessionToken = it.token
                apiId = it.identity
                updateStatus(ZitiContext.Status.Active)
            }
        } catch (ex: Exception) {
            e(ex) { "[${name()}] failed to login" }
            if (ex is ZitiException && ex.code == Errors.NotAuthorized) {
                sessionToken = null
                updateStatus(ZitiContext.Status.NotAuthorized(ex))
            }
            else {
                updateStatus(ZitiContext.Status.Unavailable(ex))
            }
            throw ex
        }
    }

    private fun runServiceUpdates(session: Deferred<Session>) = launch {
        try {
            session.await()
            while (true) {
                d("[${id.name()}] slept and restarting on t[${Thread.currentThread().name}]")
                val services = controller.getServices()
                processServiceUpdates(services)
                d("[${id.name()}] got ${services.size} services on t[${Thread.currentThread().name}]")
                delay(5 * 60000)
            }
        } catch (ze: ZitiException) {
            w("[${name()}] failed ${ze.localizedMessage}")
            if (ze.code == Errors.NotAuthorized) {
                sessionToken = null
                updateStatus(ZitiContext.Status.NotAuthorized(ze))
            }
        } catch (ce: CancellationException) {
            d("[${name()}] refresh stopped")
        } catch (ex: Exception) {
            e("[${name()}] failed to load: ${ex.localizedMessage}", ex)
            updateStatus(ZitiContext.Status.Impaired(ex))
        }
    }

    fun logout() = runBlocking { controller.logout() }

    fun checkServicesLoaded() = runBlocking {
        try {
            withTimeout(30_000) {
                servicesLoaded.await()
            }
        } catch (tex: TimeoutCancellationException) {
            e("failed to load services: $tex")
        }
    }

    internal fun getNetworkSession(service: Service, st: SessionType): NetworkSession = runBlocking {
        d("getNetworkSession(${service.name})")

        _enabled || throw ZitiException(Errors.ServiceNotAvailable)

        checkServicesLoaded()

        networkSessions.getOrPut(SessionKey(service.id, st)) {
            val netSession = controller.createNetSession(service, st)
            d("received net session[${netSession.id}] for service[${service.name}]")
            netSession
        }
    }

    internal fun getNetworkSessionByID(servId: String, st: SessionType): NetworkSession {
        checkServicesLoaded()

        servicesById.get(servId)?.let {
            if (it.permissions.contains(st))
                return getNetworkSession(it, st)
        }

        throw ZitiException(Errors.ServiceNotAvailable)
    }

    internal fun getNetworkSession(name: String, st: SessionType): NetworkSession {
        checkServicesLoaded()

        val service = servicesByName.get(name) ?: throw ZitiException(Errors.ServiceNotAvailable)
        return getNetworkSession(service, st)

    }

    internal fun getService(host: String, port: Int): Service {
        checkServicesLoaded()

        return servicesByName.values.find {
            it.dns?.hostname == host && it.dns?.port == port
        } ?: throw ZitiException(Errors.ServiceNotAvailable)
    }

    internal val channels: MutableMap<String, Channel> = mutableMapOf()

    internal fun getChannel(ns: NetworkSession): Channel {
        val addrList = ns.edgeRouters.map { it.urls["tls"] }.filterNotNull()
        for (addr in addrList) {
            channels[addr]?.let { return it }
        }

        for (addr in addrList) {
            try {
                val ch = Channel.Dial(addr, this)
                channels[addr] = ch
                ch.onClose {
                    channels.remove(addr)
                }
                return ch

            } catch (ex: Exception) {
                w("failed to dial channel ${ex.localizedMessage}")
            }
        }

        throw ZitiException(Errors.EdgeRouterUnavailable)
    }

    internal fun processServiceUpdates(services: Array<Service>) {
        val currentIds = services.map { it.id }.toCollection(mutableSetOf())
        // check removed access

        val removeIds = servicesById.keys.filter { !currentIds.contains(it) }
        for (rid in removeIds) {
            servicesById.remove(rid)?.let {
                servicesByName.remove(it.name)
                addrById.remove(it.id)?.let { addr ->
                    servicesByAddr.remove(addr)
                }
                ZitiDNSManager.unregisterService(it)
                launch {
                    serviceCh.send(ZitiContext.ServiceEvent(it, ZitiContext.ServiceUpdate.Unavailable))
                }
            }

            networkSessions.remove(SessionKey(rid, SessionType.Dial))
            networkSessions.remove(SessionKey(rid, SessionType.Bind))
        }

        // update
        for (s in services) {
            val oldV = servicesByName.put(s.name, s)
            if (oldV == null) {
                launch {
                    serviceCh.send(ZitiContext.ServiceEvent(s, ZitiContext.ServiceUpdate.Available))
                }
            }
            servicesById.put(s.id, s)

            ZitiDNSManager.registerService(s)?.let { addr ->
                addrById.put(s.id, addr)
                servicesByAddr.put(addr, s)

                d("[${name()}] service[${s.name}] => $addr")
            }
        }

        if (!servicesLoaded.isCompleted)
            servicesLoaded.complete(Unit)
    }

    override fun open(): AsynchronousSocketChannel {
        checkActive()
        return ZitiSocketChannel(this)
    }

    override fun openServer(): AsynchronousServerSocketChannel {
        checkActive()
        return ZitiServerSocketChannel(this)
    }

    override fun toString(): String {
        val id = getId()
        return "${id?.name ?: name()}[${id?.id}]@${controller()}"
    }

    private fun checkActive() {
        if (getStatus() == ZitiContext.Status.Active)
            return

        // wait for active
        runBlocking {
            val sub = statusCh.openSubscription()
            try {
                loop@
                for (s in sub) {
                    when (s) {
                        ZitiContext.Status.Active -> break@loop
                        ZitiContext.Status.Loading -> {
                        }
                        else -> throw IllegalStateException("invalid state")
                    }
                }
            } finally {
                sub.cancel()
            }
        }
    }
}