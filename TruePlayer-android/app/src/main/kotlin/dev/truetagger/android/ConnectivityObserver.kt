package dev.truetagger.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Acompanha se o aparelho tem internet. [reconnects] sobe cada vez que a conexão volta depois
 * de ter caído; use-o como chave para tentar de novo o que falhou sem rede (foto de artista etc.).
 */
object ConnectivityObserver {
    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val _reconnects = MutableStateFlow(0)
    val reconnects: StateFlow<Int> = _reconnects.asStateFlow()

    private var started = false

    @Synchronized
    fun start(context: Context) {
        if (started) return
        val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        started = true
        _online.value = manager.activeNetwork
            ?.let(manager::getNetworkCapabilities)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        runCatching {
            manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    setOnline(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                }

                override fun onLost(network: Network) = setOnline(false)
            })
        }
    }

    private fun setOnline(value: Boolean) {
        if (value && !_online.value) _reconnects.value = _reconnects.value + 1
        _online.value = value
    }
}
