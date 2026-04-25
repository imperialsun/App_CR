package com.demeter.speech.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

interface NetworkAvailability {
    suspend fun awaitAvailable(timeoutMs: Long): Boolean
}

class NetworkMonitor(context: Context) : NetworkAvailability {
    private val connectivityManager = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override suspend fun awaitAvailable(timeoutMs: Long): Boolean {
        if (hasValidatedNetwork()) return true
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                        if (networkCapabilities.hasUsableInternet()) {
                            runCatching { connectivityManager.unregisterNetworkCallback(this) }
                            if (continuation.isActive) continuation.resume(true)
                        }
                    }

                    override fun onAvailable(network: Network) {
                        if (hasValidatedNetwork()) {
                            runCatching { connectivityManager.unregisterNetworkCallback(this) }
                            if (continuation.isActive) continuation.resume(true)
                        }
                    }
                }
                continuation.invokeOnCancellation {
                    runCatching { connectivityManager.unregisterNetworkCallback(callback) }
                }
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                runCatching { connectivityManager.registerNetworkCallback(request, callback) }
                    .onFailure {
                        if (continuation.isActive) continuation.resume(false)
                    }
            }
        } == true
    }

    private fun hasValidatedNetwork(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasUsableInternet()
    }

    private fun NetworkCapabilities.hasUsableInternet(): Boolean {
        return hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

object AlwaysAvailableNetwork : NetworkAvailability {
    override suspend fun awaitAvailable(timeoutMs: Long): Boolean = true
}
