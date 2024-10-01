package rest.armagan.nodeinpaperclient

import com.google.gson.Gson
import org.bukkit.plugin.java.JavaPlugin
import java.net.URI
import kotlin.reflect.KProperty1
import kotlin.reflect.full.*

class NodeInPaperClient : JavaPlugin() {

    private lateinit var ws: NIPWebSocketClient
    lateinit var gson: Gson;

    override fun onEnable() {
        saveDefaultConfig();

        this.gson = Gson();
        this.ws = NIPWebSocketClient(this, URI(config.getString("server-uri")!!));
        this.ws.connect();
    }

    override fun onDisable() {
        this.ws.disconnect();
    }

    fun processActions(obj: Any, actions: List<Action>): Any? {
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
                    val function = currentObj::class.functions.find { it.name == action.key }
                        ?: currentObj::class.memberFunctions.find { it.name == action.key }
                        ?: currentObj::class.memberExtensionFunctions.find { it.name == action.key }
                        ?: currentObj::class.staticFunctions.find { it.name == action.key }

                    if (function != null) {
                        currentObj = function.call(currentObj, *(action.args.toTypedArray()))
                    } else {
                        throw IllegalArgumentException("No function found with name: ${action.key}")
                    }
                }
                else -> throw IllegalArgumentException("Unknown action type: ${action.type}")
            }
        }

        return currentObj;
    }

}
