package com.credisafe.mobile.data

import android.content.Context
import android.util.Log
import com.credisafe.mobile.BuildConfig
import com.credisafe.mobile.data.api.LiveTelemetryFrame
import com.credisafe.mobile.domain.StreamStatus
import com.credisafe.mobile.service.TripSession
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import java.util.concurrent.TimeUnit

class LiveStreamManager(context: Context) {
    private val auth = AuthManager(context)
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    
    private var webSocket: WebSocket? = null
    private val json = Json { ignoreUnknownKeys = true }
    private var currentTripId: String? = null

    fun start(tripId: String) {
        if (webSocket != null) stop()
        currentTripId = tripId
        
        val url = BuildConfig.CREDISAFE_API_BASE_URL
            .replace("https://", "wss://")
            .replace("http://", "ws://") + "live/trips/$tripId"
        
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${auth.getAuthToken()}")
            .build()
        
        TripSession.update { it.copy(streamStatus = StreamStatus.CONNECTING) }
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("LiveStream", "Connected to live stream for $tripId")
                TripSession.update { it.copy(streamStatus = StreamStatus.LIVE) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("LiveStream", "Received: $text")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("LiveStream", "WebSocket failure", t)
                TripSession.update { it.copy(streamStatus = StreamStatus.RECONNECTING) }
                // Reconnect logic would go here, but OkHttp doesn't auto-reconnect.
                // We'll rely on the service loop to re-trigger if needed.
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                TripSession.update { it.copy(streamStatus = StreamStatus.DISCONNECTED) }
            }
        })
    }

    fun sendFrame(frame: LiveTelemetryFrame) {
        val ws = webSocket ?: return
        try {
            val payload = json.encodeToString(frame)
            ws.send(payload)
        } catch (e: Exception) {
            Log.e("LiveStream", "Failed to send frame", e)
        }
    }

    fun stop() {
        webSocket?.close(1000, "Trip stopped")
        webSocket = null
        currentTripId = null
        TripSession.update { it.copy(streamStatus = StreamStatus.DISCONNECTED) }
    }
}
