// core/net/src/main/java/co/kys/classboard/net/MqttTransport.kt
package co.kys.classboard.net

import android.content.Context
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.*

class MqttTransport(
    context: Context,
    private val brokerUrl: String, // "ssl://<broker-host>:8883" or "tcp://...:1883"
    private val clientId: String,
    private val roomId: String,
    private val userId: String,
    private val onOpen: () -> Unit,
    private val onMessage: (ByteArray) -> Unit,
    private val onClosed: () -> Unit
) {
    private val topicEvents = "classboard/$roomId/events"
    private val client = MqttAndroidClient(context, brokerUrl, clientId)

    fun connect(username: String? = null, password: String? = null) {
        val opts = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = true
            connectionTimeout = 10
            keepAliveInterval = 20
            if (username != null) this.userName = username
            if (password != null) this.password = password.toCharArray()
        }
        client.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                client.subscribe(topicEvents, /*QoS*/0, null, null)
                onOpen()
            }
            override fun messageArrived(topic: String?, msg: MqttMessage?) {
                msg?.payload?.let(onMessage)
            }
            override fun connectionLost(cause: Throwable?) { onClosed() }
            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })
        client.connect(opts, null, object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: IMqttToken?) { /* handled in connectComplete */ }
            override fun onFailure(asyncActionToken: IMqttToken?, e: Throwable?) { onClosed() }
        })
    }

    fun send(bytes: ByteArray) {
        val msg = MqttMessage(bytes).apply { qos = 0; isRetained = false }
        client.publish(topicEvents, msg)
    }

    fun close() {
        runCatching { client.unregisterResources() }
        runCatching { client.close() }
        runCatching { client.disconnect() }
    }
}
