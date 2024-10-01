package rest.armagan.nodeinpaperclient

import com.google.gson.Gson
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.net.URI
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty1
import kotlin.reflect.full.*

class NodeInPaperClient : JavaPlugin() {

    private lateinit var refClearerTask: BukkitTask;
    private lateinit var ws: NIPWebSocketClient
    lateinit var gson: Gson;

    lateinit var refs: MutableMap<String, ClientReferenceItem>;

    override fun onEnable() {
        saveDefaultConfig();

        this.refs = mutableMapOf();
        this.gson = Gson();
        this.ws = NIPWebSocketClient(this, URI(config.getString("server-uri")!!));
        this.ws.connect();

        val refTTL = config.getLong("reference-ttl-seconds", 7200);

        this.refClearerTask = server.scheduler.runTaskTimerAsynchronously(
            this,
            Runnable {
                val currentTime = System.currentTimeMillis();
                val toRemove = this.refs.filter { currentTime - it.value.accessedAt > refTTL * 1000 }
                toRemove.forEach { this.refs.remove(it.key) }
            },
            60 * 20L,
            60 * 20L
        );
    }

    override fun onDisable() {
        this.ws.disconnect();
        this.refs.clear();

        refClearerTask.cancel();
    }

    private fun findFunction(obj: Any, action: Action): KFunction<*>? {
        val memberFunction = obj::class.memberFunctions.find { it.name == action.key && it.parameters.size == action.args.size.plus(1) }
        if (memberFunction != null) return memberFunction

        val staticFunction = obj::class.staticFunctions.find { it.name == action.key && it.parameters.size == action.args.size.plus(1) }
        if (staticFunction != null) return staticFunction

        val extensionFunction = obj::class.memberExtensionFunctions.find { it.name == action.key && it.parameters.size == action.args.size.plus(1) }
        if (extensionFunction != null) return extensionFunction

        return null
    }


    fun processActions(obj: Any?, actions: List<Action>): Any? {
        var currentObj: Any? = obj

        for (action in actions) {
            if (currentObj == null) return null;

            when (action.type) {
                "Get" -> {
                    val property = currentObj::class.memberProperties.firstOrNull { it.name == action.key }
                        ?: currentObj::class.memberExtensionProperties.firstOrNull { it.name == action.key }
                        ?: currentObj::class.staticProperties.firstOrNull { it.name == action.key }

                    if (property != null) {
                        currentObj = (property as KProperty1<Any, *>).get(currentObj)
                    } else {
                        throw IllegalArgumentException("No property found with name: ${action.key}")
                    }
                }
                "Apply" -> {
                    val function =  findFunction(currentObj, action);
                    if (function != null) {
                        try {
                            val args = action.args.mapIndexed { index, arg ->
                                if (isObject(arg)) {
                                    val ref = arg as Map<*, *>
                                    if (ref.containsKey("__type__") && ref["__type__"] == "Reference") {
                                        val id = ref["id"] as String
                                        if (this.refs[id] != null) {
                                            this.refs[id]?.accessedAt = System.currentTimeMillis();
                                            return this.refs[id];
                                        } else return null;
                                    }
                                }
                                val paramType = function.parameters[index + 1].type.classifier as KClass<*>
                                paramType.safeCast(arg) ?: throw IllegalArgumentException("Argument type mismatch for ${action.key}")
                            }
                            currentObj = function.call(currentObj, *args.toTypedArray())
                        } catch (e: Exception) {
                            throw IllegalArgumentException("An error occurred while calling function: ${action.key}", e)
                        }
                    } else {
                        throw IllegalArgumentException("No function found with name: ${action.key}")
                    }
                }
                else -> throw IllegalArgumentException("Unknown action type: ${action.type}")
            }
        }

        return currentObj;
    }

    fun isObject(value: Any?): Boolean {
        if (value == null) return false;
        return when (value) {
            is String, is Int, is Float, is Double, is Boolean, is Char, is Long, is Short, is Byte -> false
            else -> true
        }
    }
}
