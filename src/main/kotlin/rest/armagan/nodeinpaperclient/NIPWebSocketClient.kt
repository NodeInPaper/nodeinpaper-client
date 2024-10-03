package rest.armagan.nodeinpaperclient

import org.bukkit.event.EventPriority
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI

class NIPWebSocketClient(private val nip: NodeInPaperClient, serverUri: URI) : WebSocketClient(serverUri) {

    private var isDisconnectedManually = false
    private var isReconnecting = false

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
                e.printStackTrace();
            }
        }
    }

    private fun wsEventMessage(msg: WSEventMessage) {
        // nip.logger.info("Received event from NodeInPaper server: ${msg.event}, data: ${nip.gson.toJson(msg.data)}")
        when (msg.event) {
            "SingularExecute" -> {
                val req = nip.gson.fromJson(nip.gson.toJson(msg.data), SingularExecuteRequest::class.java);
                val runnable = Runnable {
                    try {
                        val base = when (req.base.type) {
                            "Plugin" -> nip
                            "Reference" -> {
                                val ref = nip.refs[req.base.id as String];
                                if (ref != null) {
                                    ref.accessedAt = System.currentTimeMillis();
                                    ref.value
                                } else {
                                    null
                                }
                            }
                            "Class" -> {
                                nip.loadClass(req.base.name as String);
                            }
                            "ClassFromPath" -> {
                                nip.loadClassFromJar(req.base.file as String, req.base.name as String);
                            }
                            else -> null;
                        }

                        // nip.logger.info("Processing actions: ${req.path.joinToString { it.key }}")
                        val response = nip.processActions(base, req.path);
                        // nip.logger.info("Response: $response, base: $base, path: ${req.path.joinToString { it.key }}")
                        if (msg.responseId != null) {
                            if (response != null) {
                                if (response is List<*>) {
                                    val resultList: MutableList<Any?> = mutableListOf();

                                    for (resItem in response) {
                                        if (req.response.isNotEmpty()) {
                                            val responseList: MutableList<ResponseMapItem> = mutableListOf();
                                            for (reqResItem in req.response) {
                                                val itemResponse = nip.processActions(resItem, reqResItem.path);
                                                if (itemResponse != null) {
                                                    responseList.add(ResponseMapItem(reqResItem.key, itemResponse));
                                                }
                                            }
                                            resultList.add(responseList);
                                        } else {
                                            if (nip.isObject(resItem)) {
                                                if (!req.noRef) {
                                                    val id = resItem.hashCode().toString();
                                                    nip.refs[id] = ClientReferenceItem(id, resItem, System.currentTimeMillis());
                                                    resultList.add(ClientReferenceResponse(id));
                                                } else {
                                                    resultList.add(null)    ;
                                                }
                                            } else {
                                                resultList.add(resItem);
                                            }
                                        }
                                    }

                                    sendResponse(msg.responseId, WSMessageResponse(true, ListResponse(resultList)));
                                } else {
                                    if (req.response.isNotEmpty()) {
                                        val responseList: MutableList<ResponseMapItem> = mutableListOf();

                                        for (item in req.response) {
                                            val itemResponse = nip.processActions(response, item.path);
                                            if (itemResponse != null) {
                                                responseList.add(ResponseMapItem(item.key, itemResponse));
                                            }
                                        }

                                        sendResponse(msg.responseId, WSMessageResponse(true, responseList));
                                    } else {
                                        if (nip.isObject(response)) {
                                            if (!req.noRef) {
                                                val id = response.hashCode().toString();
                                                nip.refs[id] = ClientReferenceItem(id, response, System.currentTimeMillis());
                                                sendResponse(msg.responseId, WSMessageResponse(true, ClientReferenceResponse(id)));
                                            } else {
                                                sendResponse(msg.responseId, WSMessageResponse(true, null));
                                            }
                                        } else {
                                            sendResponse(msg.responseId, WSMessageResponse(true, response));
                                        }
                                    }
                                }
                            } else {
                                sendResponse(msg.responseId, WSMessageResponse(true, null));
                            }
                        }
                    } catch (e: Exception) {
                        nip.logger.warning("An error occurred while processing actions from NodeInPaper server.")
                        e.printStackTrace();
                        if (msg.responseId !== null) {
                            sendResponse(msg.responseId, WSMessageResponse(false, e.message ?: "An error occurred while processing actions."))
                        }
                    }
                }

                if (req.sync) {
                    nip.server.scheduler.runTask(nip, runnable);
                } else {
                    nip.server.scheduler.runTaskAsynchronously(nip, runnable);
                }
            }
            "KeepAliveReference" -> {
                val ref = nip.refs[msg.data as String];
                if (msg.responseId != null) {
                    if (ref != null) {
                        ref.accessedAt = System.currentTimeMillis();
                        sendResponse(msg.responseId, WSMessageResponse(ok = true, data = true));
                    } else {
                        sendResponse(msg.responseId, WSMessageResponse(false, "Reference not found."));
                    }
                }
            }
            "RemoveReference" -> {
                nip.refs.remove(msg.data as String);
                if (msg.responseId != null) {
                    sendResponse(msg.responseId, WSMessageResponse(ok = true, data = true));
                }
            }
            "RegisterCommand" -> {
                val req = nip.gson.fromJson(nip.gson.toJson(msg.data), RegisterCommandRequest::class.java);
                nip.registerCommand(req.namespace, req.name, req.aliases, req.description, req.usage);
                if (msg.responseId != null) {
                    sendResponse(msg.responseId, WSMessageResponse(ok = true, data = true));
                }
            }
            "RegisterEvent" -> {
                val req = nip.gson.fromJson(nip.gson.toJson(msg.data), RegisterEventRequest::class.java);
                nip.registerEvent(req.name, EventPriority.valueOf(req.priority.uppercase()), req.cancelConditions);
                if (msg.responseId != null) {
                    sendResponse(msg.responseId, WSMessageResponse(ok = true, data = true));
                }
            }
            else -> {
                nip.logger.warning("Unknown event type received from NodeInPaper server: ${msg.event}")
            }
        }
    }

    fun sendEvent(event: String, data: Any, responseId: String? = null) {
        try {
            send(nip.gson.toJson(WSEventMessage(event, data, responseId)))
        } catch (e: Exception) {
            nip.logger.warning("An error occurred while sending an event to NodeInPaper server: $event, $data")
            e.printStackTrace();
        }
    }

    fun sendResponse(responseId: String, data: Any) {
        sendEvent("Response", data, responseId)
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        nip.unregisterAllCommands();
        nip.unregisterAllEvents();
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
        if (isReconnecting) return;
        isReconnecting = true;
        nip.server.scheduler.runTaskLaterAsynchronously(
            nip,
            Runnable {
                try {
                    isReconnecting = false;
                    // nip.logger.info("Attempting to reconnect to NodeInPaper server.")
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