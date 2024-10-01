package rest.armagan.nodeinpaperclient

import org.bukkit.plugin.java.JavaPlugin
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

class NodeInPaperClient : JavaPlugin() {

    override fun onEnable() {
        // Plugin startup logic


    }

    override fun onDisable() {
        // Plugin shutdown logic
    }

    fun processActions(obj: Any, actions: List<Action>): Any? {
        var currentObj: Any? = obj

        for (action in actions) {
            if (currentObj == null) {
                throw IllegalArgumentException("Invalid object state during processing: ${action.key}")
            }

            when (action.type) {
                "Get" -> {
                    val property = currentObj::class.memberProperties.firstOrNull { it.name == action.key }
                    if (property != null) {
                        currentObj = (property as KProperty1<Any, *>).get(currentObj)
                    } else {
                        throw IllegalArgumentException("No property found with name: ${action.key}")
                    }
                }
                "Apply" -> {
                    val function = currentObj::class.members.find { it.name == action.key && it.parameters.size == (action.args?.size?.plus(1) ?: 1) }
                    if (function != null) {
                        currentObj = function.call(currentObj, *(action.args?.toTypedArray() ?: arrayOf()))
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
