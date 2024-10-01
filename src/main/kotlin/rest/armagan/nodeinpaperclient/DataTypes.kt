package rest.armagan.nodeinpaperclient

data class Action(val key: String, val type: String, val args: List<Any>)

data class WSEventMessage(val event: String, val data: Any, val responseId: String? = null)

data class SingularExecuteResponse(val ok: Boolean, val data: Any)
data class SingularExecuteRequest(val path: List<Action>, val sync: Boolean)