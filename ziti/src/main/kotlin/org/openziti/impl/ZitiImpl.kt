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

import org.openziti.*
import org.openziti.identity.Enroller
import org.openziti.identity.KeyStoreIdentity
import org.openziti.identity.findIdentityAlias
import org.openziti.identity.loadKeystore
import org.openziti.net.dns.ZitiDNSManager
import org.openziti.net.internal.Sockets
import org.openziti.util.Logged
import org.openziti.util.Version
import org.openziti.util.ZitiLog
import java.io.File
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.URI
import java.security.KeyStore

internal object ZitiImpl : Logged by ZitiLog() {

    internal val onAndroid: Boolean by lazy {
        try {
            Class.forName("android.util.Log")
            true
        } catch (cnf: ClassNotFoundException) {
            false
        }
    }

    init {
        i("ZitiSDK version ${Version.version} @${Version.revision}(${Version.branch})")
    }

    internal fun loadContext(ks: KeyStore, alias: String?): ZitiContextImpl {
        val idName = alias ?: findIdentityAlias(ks)
        val id = KeyStoreIdentity(ks, idName)
        return ZitiContextImpl(id, true).also {
            contexts.add(it)
        }
    }

    internal fun loadContext(idFile: File, pwd: CharArray, alias: String?): ZitiContextImpl {
        val ks = loadKeystore(idFile, pwd)
        return loadContext(ks, alias)
    }

    internal val contexts = mutableListOf<ZitiContextImpl>()
    fun init(file: File, pwd: CharArray, seamless: Boolean): Unit {
        if (seamless) {
            initInternalNetworking()
        }

        val ctx = loadContext(file, pwd, null)
        ctx.checkServicesLoaded()
    }

    fun removeContext(ctx: ZitiContext) {
        contexts.remove(ctx)
        if(ctx is ZitiContextImpl) {
            ctx.destroy()
        }
    }

    fun init(ks: KeyStore, seamless: Boolean): List<ZitiContext> {
        if (seamless) {
            initInternalNetworking()
        }

        for (a in ks.aliases()) {
            if (isZitiIdentity(ks, a)) {
                loadContext(ks, a)
            }
        }

        return contexts
    }

    private fun isZitiIdentity(ks: KeyStore, alias: String): Boolean {
        if (!ks.isKeyEntry(alias))
            return false

        try {
            return URI.create(alias).scheme == "ziti"
        }
        catch (ex: IllegalArgumentException) {
            return false
        }
    }

    private fun initInternalNetworking() {
        Sockets.init()
    }

    fun enroll(ks: KeyStore, jwt: ByteArray, name: String): ZitiContext {
        val enroller = Enroller.fromJWT(String(jwt))
        val alias = enroller.enroll(null, ks, name)

        return loadContext(ks, alias)
    }

    fun connect(addr: SocketAddress): ZitiConnection {
        when (addr) {
            is InetSocketAddress -> {
                val serviceId = ZitiDNSManager.getServiceIdByAddr(addr)
                // if this address was assigned by DNS mananger
                if (serviceId != null) {
                    for (c in contexts) {
                        try {
                            return c.dialById(serviceId)
                        } catch (ex: Exception) {
                            d { "service @[$addr] not available for ${c.name()}" }
                        }
                    }
                } else {
                    for (c in contexts) {
                        try {
                            return c.dial(addr.hostName, addr.port)
                        } catch (ex: Exception) {
                            d { "service @[$addr] not available for ${c.name()}" }
                        }
                    }
                }

                e { "service @[$addr] not available in any contexts" }
                throw ZitiException(Errors.ServiceNotAvailable)
            }
            is ZitiAddress.Service -> {
                for (c in contexts) {
                    try {
                        return c.dial(addr.name)
                    } catch (ex: Exception) {
                        i { "service @[$addr] not available for ${c.name()}" }
                    }
                }

                e { "service @[$addr] not available in any contexts" }
                throw ZitiException(Errors.ServiceNotAvailable)
            }

            else -> error("unsupported address type")
        }
    }
}