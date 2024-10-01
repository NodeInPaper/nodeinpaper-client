package rest.armagan.nodeinpaperclient

import com.google.gson.Gson
import org.bukkit.plugin.java.JavaPlugin
import java.net.URI
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
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

    fun findFunction(obj: Any, action: Action): KFunction<*>? {
        // 1. Üye fonksiyonları kontrol et (normal ve üst sınıf fonksiyonları)
        val memberFunction = obj::class.memberFunctions.find { it.name == action.key && it.parameters.size == action.args.size.plus(1) }
        if (memberFunction != null) return memberFunction

        // 2. Static fonksiyonları kontrol et
        val staticFunction = obj::class.staticFunctions.find { it.name == action.key && it.parameters.size == action.args.size.plus(1) }
        if (staticFunction != null) return staticFunction

        // 3. Üye genişletme fonksiyonlarını kontrol et
        val extensionFunction = obj::class.memberExtensionFunctions.find { it.name == action.key && it.parameters.size == action.args.size.plus(1) }
        if (extensionFunction != null) return extensionFunction

        return null
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
                    val function =  findFunction(currentObj, action);
                    if (function != null) {
                        // logger.info("Calling function: ${function.name} args: ${function.parameters.stream().map { it.type }.toList().joinToString(", ")}")
                        try {
                            val args = action.args.mapIndexed { index, arg ->
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

}
