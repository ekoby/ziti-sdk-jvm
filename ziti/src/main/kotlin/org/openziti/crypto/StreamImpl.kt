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

package org.openziti.crypto

import com.goterl.lazycode.lazysodium.interfaces.SecretStream
import com.goterl.lazycode.lazysodium.utils.Key
import com.goterl.lazycode.lazysodium.utils.SessionPair
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.atomic.AtomicReference

class StreamImpl(pair: SessionPair): Crypto.SecretStream {

    val header = AtomicReference(ByteArray(SecretStream.HEADERBYTES))
    val txState: SecretStream.State
    var rxKey: ByteArray? = pair.rx
    lateinit var rxState: SecretStream.State
    init {
        SecureRandom().nextBytes(header.get())
        txState = Crypto.sodium.cryptoSecretStreamInitPush(header.get(), Key.fromBytes(pair.tx))
    }

    override fun header(): ByteArray {
        header.getAndSet(null)?.let {
            return it
        }
        error("header was already consumed")
    }

    override fun initialized(): Boolean = this::rxState.isInitialized

    override fun init(peerHeader: ByteArray) {
        rxKey?.let {
            val rxs = SecretStream.State()
            Crypto.sodium.cryptoSecretStreamInitPull(rxs, peerHeader, it)
            rxState = rxs
            Arrays.fill(it, 0)
            rxKey = null
            return
        }

        error("rxState was already initialized")
    }

    override fun encrypt(b: ByteArray): ByteArray {
        val cipher = ByteArray(b.size + SecretStream.ABYTES)
        Crypto.sodium.cryptoSecretStreamPush(txState, cipher, b, b.size.toLong(), SecretStream.TAG_MESSAGE)
        return cipher
    }

    override fun decrypt(b: ByteArray): ByteArray {
        val plain = ByteArray(b.size - SecretStream.ABYTES)
        Crypto.sodium.cryptoSecretStreamPull(rxState, plain, null, b, b.size.toLong())
        return plain
    }
}