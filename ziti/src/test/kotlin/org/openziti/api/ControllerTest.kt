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

package org.openziti.api

import kotlinx.coroutines.runBlocking
import org.hamcrest.CoreMatchers
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThat
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.openziti.identity.IdentityConfig
import org.openziti.identity.KeyStoreIdentity
import org.openziti.identity.findIdentityAlias
import org.openziti.identity.keystoreFromConfig
import java.net.URL
import java.security.KeyStore

/**
 *
 */
internal class ControllerTest {

    internal lateinit var cfg: IdentityConfig
    internal lateinit var ks: KeyStore
    internal lateinit var identity: KeyStoreIdentity
    lateinit var ctrl: Controller
    @Before
    fun setup() {
        val path = System.getProperty("test.identity")
        Assume.assumeNotNull(path)

        cfg = IdentityConfig.load(path)
        ks = keystoreFromConfig(cfg)

        identity = KeyStoreIdentity(ks, findIdentityAlias(ks))

        ctrl = Controller(URL(identity.controller()), identity.sslContext(), identity.trustManager())
    }

    @Test
    fun testVersion() {

        val v = runBlocking {
            ctrl.version()
        }

        assertNotNull(v)
        assertThat(v!!.runtimeVersion, CoreMatchers.startsWith("go1."))
    }

    @Test
    fun testLogin() {
        val s = runBlocking {
            ctrl.login()
        }
        assertNotNull(s)
        assertNotNull(s.token)
    }

    @Test
    fun testGetSession() {
        runBlocking {
            val s = ctrl.login()
            val services = ctrl.getServices()
            Assume.assumeTrue(!services.isEmpty())

            println(services[0])
            val session = ctrl.createNetSession(services[0], SessionType.Dial)

            println(session)
        }
    }
}
