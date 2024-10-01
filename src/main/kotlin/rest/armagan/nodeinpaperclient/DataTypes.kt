package rest.armagan.nodeinpaperclient

data class Action(val key: String, val type: String, val args: List<Any>)

data class WSEventMessage(val event: String, val data: Any, val responseId: String? = null)

data class RequestResponseMapItem(val key: String, val path: List<Action>)
data class ResponseMapItem(val key: String, val value: Any)

data class SingularExecuteResponse(val ok: Boolean, val data: Any?)
data class SingularExecuteRequest(val path: List<Action>, val sync: Boolean, val response: List<RequestResponseMapItem>)