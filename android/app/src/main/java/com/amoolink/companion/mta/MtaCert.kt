package com.amoolink.companion.mta

import android.content.Context
import io.ktor.network.tls.certificates.buildKeyStore
import java.security.KeyStore

/** 自签 TLS 证书(发送方 HTTPS/WSS server 用;证书内容不参与互通校验,接收端全信任)。内存缓存。 */
object MtaCert {
    const val ALIAS = "apartnerAlias"
    const val KEY_PASSWORD = "apartnerKey"
    const val STORE_PASSWORD = "apartnerStore"

    @Volatile
    private var cached: KeyStore? = null

    fun build(context: Context): KeyStore {
        cached?.let { return it }
        val ks = buildKeyStore {
            certificate(ALIAS) {
                password = KEY_PASSWORD
                domains = listOf("127.0.0.1", "0.0.0.0", "localhost")
            }
        }
        cached = ks
        return ks
    }
}
