package rest.armagan.nodeinpaperclient

data class Action(val key: String, val type: String, val args: List<Any>)

data class WSEventMessage(val event: String, val data: Any, val responseId: String? = null)

data class RequestResponseMapItem(val key: String, val path: List<Action>)
data class ResponseMapItem(val key: String, val value: Any)

data class WSMessageResponse(val ok: Boolean, val data: Any?)

data class SingularExecuteRequest(
    val path: List<Action>,
    val sync: Boolean,
    val response: List<RequestResponseMapItem>,
    val base: String = "Plugin",
    val noRef: Boolean = false
)
data class GetReferenceRequest(val id: String, val path: List<Action>)


data class ClientReferenceResponse(val id: String, val __type__: String = "Reference")
data class ClientReferenceItem(val id: String, val value: Any? = null, var accessedAt: Long)