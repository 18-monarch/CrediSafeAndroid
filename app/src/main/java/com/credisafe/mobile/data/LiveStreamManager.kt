package com.credisafe.mobile.data

import android.content.Context
import android.util.Log
import com.credisafe.mobile.BuildConfig
import com.credisafe.mobile.data.api.LiveTelemetryFrame
import com.credisafe.mobile.domain.StreamStatus
import com.credisafe.mobile.service.TripSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.math.min

class LiveStreamManager(context: Context) {
    private val auth = AuthManager(context.applicationContext)
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var webSocket: WebSocket? = null
    private val json = Json { ignoreUnknownKeys = true }
    private var currentTripId: String? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var stoppedExplicitly = true

    fun start(tripId: String) {
        stoppedExplicitly = false
        currentTripId = tripId
        reconnectAttempt = 0
        connect()
    }

    private fun connect() {
        val tripId = currentTripId ?: return
        val token = auth.getAuthToken()
        if (token.isNullOrBlank()) {
            TripSession.update {
                it.copy(
                    streamStatus = StreamStatus.ERROR,
                    lastError = "Live stream needs an authenticated session.",
                )
            }
            return
        }

        webSocket?.cancel()
        val url = BuildConfig.CREDISAFE_API_BASE_URL
            .replace("https://", "wss://")
            .replace("http://", "ws://") + "live/trips/$tripId"

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()

        TripSession.update {
            it.copy(
                streamStatus = if (reconnectAttempt == 0) StreamStatus.CONNECTING else StreamStatus.RECONNECTING,
            )
        }

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempt = 0
                reconnectJob?.cancel()
                Log.d("LiveStream", "Connected to live stream for $tripId")
                TripSession.update { it.copy(streamStatus = StreamStatus.LIVE) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w("LiveStream", "WebSocket disconnected: ${t.message}")
                if (!stoppedExplicitly) scheduleReconnect()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!stoppedExplicitly) scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (stoppedExplicitly || currentTripId == null || reconnectJob?.isActive == true) return
        reconnectAttempt++
        TripSession.update { it.copy(streamStatus = StreamStatus.RECONNECTING) }

        val delayMs = min(30_000L, 1_000L * (1L shl min(reconnectAttempt - 1, 5)))
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!stoppedExplicitly) connect()
        }
    }

    fun sendFrame(frame: LiveTelemetryFrame) {
        val ws = webSocket ?: return
        if (TripSession.state.value.streamStatus != StreamStatus.LIVE) return
        runCatching {
            ws.send(json.encodeToString(frame))
        }.onFailure {
            Log.w("LiveStream", "Failed to send live frame: ${it.message}")
        }
    }

    fun stop() {
        stoppedExplicitly = true
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(1000, "Trip stopped")
        webSocket = null
        currentTripId = null
        reconnectAttempt = 0
        TripSession.update { it.copy(streamStatus = StreamStatus.DISCONNECTED) }
    }

    fun close() {
        stop()
        scope.cancel()
    }
}
