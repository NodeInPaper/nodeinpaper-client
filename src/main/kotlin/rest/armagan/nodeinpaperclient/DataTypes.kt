package rest.armagan.nodeinpaperclient

data class ActionArg(val __type__: String, val value: Any? = null, val id: String? = null)
data class Action(val key: String, val type: String, val args: List<ActionArg>)

data class WSEventMessage(val event: String, val data: Any?, val responseId: String? = null)

data class RequestResponseMapItem(val key: String, val path: List<Action>)
data class ResponseMapItem(val key: String, val value: Any)

data class WSMessageResponse(val ok: Boolean, val data: Any?)

data class SingularExecuteBase(
    val type: String,
    val name: String? = null,
    val id: String? = null,
    val file: String? = null,
)
data class SingularExecuteRequest(
    val path: List<Action>,
    val sync: Boolean,
    val response: List<RequestResponseMapItem>,
    val base: SingularExecuteBase,
    val noRef: Boolean = false
)

data class ClientReferenceResponse(val id: String, val __type__: String = "Reference")
data class ClientReferenceItem(val id: String, val value: Any? = null, var accessedAt: Long)

data class ListResponse(val list: List<Any?>, val __type__: String = "List")

data class ExecuteCommandResponse(
    val name: String,
    val namespace: String,
    val label: String,
    val args: List<String>,
    val sender: ClientReferenceResponse
)

data class ExecuteEventResponse(
    val name: String,
    val event: ClientReferenceResponse
)

data class RegisterCommandRequest(
    val name: String,
    val namespace: String,
    val aliases: List<String>,
    val description: String,
    val usage: String
)

data class RegisterEventRequest(
    val name: String,
    val priority: String,
    val cancelConditions: CompiledConditionGroup,
)

data class CompiledConditionValue(
    val type: String,
    val value: String? = null,
    val base: String? = null,
    val path: List<Action>? = null
)

data class CompiledCondition(
    val a: CompiledConditionValue,
    val op: String,
    val b: CompiledConditionValue
)

data class CompiledConditionGroup(
    val and: List<CompiledCondition>? = null,
    val or: List<CompiledCondition>? = null,
)