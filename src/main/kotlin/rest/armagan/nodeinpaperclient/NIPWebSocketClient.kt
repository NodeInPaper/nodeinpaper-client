package rest.armagan.nodeinpaperclient

import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI

class NIPWebSocketClient(private val nip: NodeInPaperClient, serverUri: URI) : WebSocketClient(serverUri) {

    private var isDisconnectedManually = false

    override fun onOpen(handshakedata: ServerHandshake?) {
        nip.logger.info("Connected to NodeInPaper server.")
    }

    override fun onMessage(message: String?) {
        message?.let {
            try {
                val jsonData = this.nip.gson.fromJson(it, WSEventMessage::class.java);
                wsEventMessage(jsonData)
            } catch (e: Exception) {
                nip.logger.warning("An error occurred while processing a message from NodeInPaper server.")
                nip.logger.warning(e.message)
            }
        }
    }

    private fun wsEventMessage(msg: WSEventMessage) {
        when (msg.event) {
            "SingularExecute" -> {
                val req = nip.gson.fromJson(nip.gson.toJson(msg.data), SingularExecuteRequest::class.java);

                val runnable = Runnable {
                    try {
                        val response = nip.processActions(nip, req.path);
                        if (msg.responseId != null && response != null) {
                            sendResponse(msg.responseId, SingularExecuteResponse(true, response));
                        }
                    } catch (e: Exception) {
                        nip.logger.warning("An error occurred while processing actions from NodeInPaper server.")
                        nip.logger.warning(e.message)
                        e.printStackTrace();
                        if (msg.responseId !== null) {
                            sendResponse(msg.responseId, SingularExecuteResponse(false, e.message ?: "An error occurred while processing actions."))
                        }
                    }
                }

                if (req.sync) {
                    nip.server.scheduler.runTask(nip, runnable);
                } else {
                    nip.server.scheduler.runTaskAsynchronously(nip, runnable);
                }
            }
            else -> {
                nip.logger.warning("Unknown event type received from NodeInPaper server: ${msg.event}")
            }
        }
    }

    fun sendEvent(event: String, data: Any, responseId: String? = null) {
        send(nip.gson.toJson(WSEventMessage(event, data, responseId)))
    }

    fun sendResponse(responseId: String, data: Any) {
        sendEvent("Response", data, responseId)
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        if (!isDisconnectedManually) {
            attemptReconnect()
        }
    }

    override fun onError(ex: Exception?) {
        if (!isDisconnectedManually) {
            attemptReconnect()
        }
    }

    private fun attemptReconnect() {
        nip.server.scheduler.runTaskLaterAsynchronously(
            nip,
            Runnable {
                try {
                    nip.logger.info("Attempting to reconnect to NodeInPaper server.")
                    this.reconnect()
                } catch (e: Exception) {
                    attemptReconnect()
                }
            },
            5 * 20L
        );
    }

    fun disconnect() {
        nip.logger.info("Disconnecting from NodeInPaper server.")
        isDisconnectedManually = true
        close()
    }
}