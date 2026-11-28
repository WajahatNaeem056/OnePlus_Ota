package com.wajahatnaeem.oneplusota

import android.content.Context
import com.wajahatnaeem.oneplusota.data.OplusClient
import com.wajahatnaeem.oneplusota.data.OplusDirectSource
import com.wajahatnaeem.oneplusota.data.OxygenUpdaterSource
import com.wajahatnaeem.oneplusota.data.SourceChain
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object ServiceLocator {
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS) // a stalled download throws -> auto retry
            .retryOnConnectionFailure(true)
            .build()
    }

    val oplusClient by lazy { OplusClient(http) }

    val linkResolver by lazy { com.wajahatnaeem.oneplusota.data.LinkResolver(http) }

    val catalog: com.wajahatnaeem.oneplusota.data.DeviceCatalog by lazy {
        com.wajahatnaeem.oneplusota.data.DeviceCatalog(http)
    }

    private val oplusDirect by lazy { OplusDirectSource(oplusClient) }

    /** The device's own region gateway + the backup source together, newest verified package wins. No other-region fallback. */
    val chain: SourceChain by lazy {
        SourceChain(
            listOf(
                listOf(oplusDirect, OxygenUpdaterSource(http)),
            ),
            linkResolver,
            early = { dev -> oplusDirect.fetchEarly(dev) },
        )
    }
}
