package rest.armagan.nodeinpaperclient

import com.google.gson.Gson
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandMap
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.net.URI
import java.net.URL
import java.net.URLClassLoader
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty0
import kotlin.reflect.KProperty1
import kotlin.reflect.full.*

class NodeInPaperClient : JavaPlugin() {

    private lateinit var refClearerTask: BukkitTask;
    private lateinit var ws: NIPWebSocketClient
    lateinit var gson: Gson;
    lateinit var refs: MutableMap<String, ClientReferenceItem>;
    lateinit var registeredCommands: MutableMap<String, Command>;

    override fun onEnable() {
        saveDefaultConfig();

        this.registeredCommands = mutableMapOf();
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

        logger.info("NodeInPaperClient enabled.")
    }

    override fun onDisable() {
        this.ws.disconnect();
        this.refs.clear();
        this.unregisterAllCommands();

        refClearerTask.cancel();
    }

    private fun findFunction(obj: Any, action: Action): KFunction<*>? {
        val memberFunction = obj::class.memberFunctions.find {
            it.name == action.key
                    && it.parameters.size == action.args.size.plus(1)
                    && it.parameters.drop(1).withIndex().all { (index, param) ->
                val paramType = param.type.classifier as? KClass<*>
                val arg = action.args.getOrNull(index)
                if (arg != null && paramType != null) {
                    paramType.isInstance(arg)
                } else {
                    false
                }
            }
        }
        if (memberFunction != null) return memberFunction

        val staticFunction = obj::class.staticFunctions.find {
            it.name == action.key
                    && it.parameters.size == action.args.size.plus(1)
                    && it.parameters.drop(1).withIndex().all { (index, param) ->
                val paramType = param.type.classifier as? KClass<*>
                val arg = action.args.getOrNull(index)
                if (arg != null && paramType != null) {
                    paramType.isInstance(arg)
                } else {
                    false
                }
            }
        }
        if (staticFunction != null) return staticFunction

        val extensionFunction = obj::class.memberExtensionFunctions.find {
            it.name == action.key
                    && it.parameters.size == action.args.size.plus(1)
                    && it.parameters.drop(1).withIndex().all { (index, param) ->
                val paramType = param.type.classifier as? KClass<*>
                val arg = action.args.getOrNull(index)
                if (arg != null && paramType != null) {
                    paramType.isInstance(arg)
                } else {
                    false
                }
            }
        }
        if (extensionFunction != null) return extensionFunction

        return null
    }


    fun processActions(obj: Any?, actions: List<Action>): Any? {
        var currentObj: Any? = obj

        for (action in actions) {
            if (currentObj == null) return null

            when (action.type) {
                "Get" -> {
                    val clazz = if (currentObj is Class<*>) currentObj.kotlin else currentObj::class

                    // Önce member properties, sonra extension, ardından statik özelliklere bakıyoruz.
                    val property = clazz.memberProperties.firstOrNull { it.name == action.key }
                        ?: clazz.memberExtensionProperties.firstOrNull { it.name == action.key }
                        ?: clazz.staticProperties.firstOrNull { it.name == action.key }

                    if (property != null) {
                        currentObj = when (property) {
                            is KProperty1<*, *> -> {
                                // KProperty1'in çalışabilmesi için currentObj'nin type'ı property'nin receiver'ı olmalı
                                (property as KProperty1<Any?, *>).get(currentObj)
                            }
                            is KProperty0<*> -> property.get()
                            else -> throw IllegalArgumentException("Unsupported property type: ${property::class}")
                        }
                    } else {
                        // Eğer property bulunamazsa, bu sefer class'ın statik alanlarına bakıyoruz.
                        try {
                            val field = clazz.java.getField(action.key) // Statik field'ı kontrol et
                            currentObj = field.get(null) // Statik olduğu için null context kullan
                        } catch (e: NoSuchFieldException) {
                            throw IllegalArgumentException("No property or static field found with name: ${action.key}")
                        }
                    }
                }
                "Apply" -> {
                    val function = findFunction(currentObj, action)
                    if (function != null) {
                        try {
                            val args = action.args.mapIndexed { index, arg ->
                                try {
                                    if (arg is Map<*, *> && arg["__type__"] == "Reference") {
                                        val id = arg["id"] as? String
                                        if (id != null && this.refs.containsKey(id)) {
                                            this.refs[id]?.let {
                                                it.accessedAt = System.currentTimeMillis()
                                                return@mapIndexed it.value ?: arg
                                            }
                                        } else {
                                            return@mapIndexed arg
                                        }
                                    } else {
                                        val paramType = function.parameters.getOrNull(index + 1)?.type?.classifier as? KClass<*>
                                        if (paramType != null) {
                                            return@mapIndexed paramType.safeCast(arg) ?: arg
                                        } else {
                                            return@mapIndexed arg
                                        }
                                    }

                                } catch (e: Exception) {
                                    logger.warning("An error occurred while casting argument: $arg to type: ${function.parameters.getOrNull(index + 1)?.type?.classifier}")
                                    e.printStackTrace()
                                }
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

        return currentObj
    }



    fun isObject(value: Any?): Boolean {
        if (value == null) return false;
        return when (value) {
            is String, is Int, is Float, is Double, is Boolean, is Char, is Long, is Short, is Byte -> false
            else -> true
        }
    }

    fun loadClassFromJar(jarFilePath: String, className: String): Class<*>? {
        try {
            val jarUrl = URL("jar:file:$jarFilePath!/")
            val classLoader = URLClassLoader(arrayOf(jarUrl), this::class.java.classLoader)
            return classLoader.loadClass(className)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun loadClass(className: String): Class<*>? {
        try {
            return Class.forName(className)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun getCommandMap(): CommandMap {
        Bukkit.getPluginManager().javaClass.getDeclaredField("commandMap").let {
            it.isAccessible = true;
            return it.get(Bukkit.getPluginManager()) as CommandMap
        }
    }

    fun registerCommand(namespace: String, commandName: String, aliases: List<String>, description: String, usage: String) {
        val commandMap = getCommandMap()
        val self = this;
        val key = "$namespace:$commandName";

        if (registeredCommands.containsKey(key)) {
            registeredCommands[key]!!.unregister(commandMap);
            registeredCommands.remove(key);
            return;
        }

        val commandObj = object : Command(commandName, description, usage, aliases) {
            override fun execute(sender: org.bukkit.command.CommandSender, label: String, args: Array<String>): Boolean {

                val senderId = sender.hashCode().toString();
                self.refs[senderId] = ClientReferenceItem(senderId, sender, System.currentTimeMillis());

                self.ws.sendEvent(
                    "ExecuteCommand",
                    ExecuteCommandResponse(
                        commandName,
                        namespace,
                        label,
                        args.toList(),
                        ClientReferenceResponse(senderId)
                    )
                );

                return true;
            }
        }

        commandMap.register(namespace, commandObj);
        registeredCommands[key] = commandObj;
    }

    fun unregisterAllCommands() {
        val commandMap = getCommandMap();
        registeredCommands.forEach { (_, command) ->
            command.unregister(commandMap);
        }
        registeredCommands.clear();
    }
}
