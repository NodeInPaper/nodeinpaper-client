package rest.armagan.nodeinpaperclient

import com.google.gson.Gson
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandMap
import org.bukkit.event.*
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
import kotlin.reflect.jvm.isAccessible

class NodeInPaperClient : JavaPlugin(), Listener {

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
        this.unregisterAllEvents();
        this.unregisterAllCommands();

        refClearerTask.cancel();
    }

    private fun findFunction(obj: Any, action: Action): Any? {
        // Kotlin üye fonksiyonlarını bulma
        val memberFunction = obj::class.memberFunctions.find {
            it.name == action.key
                    && it.parameters.size == action.args.size.plus(1) // İlk parametre receiver olduğu için 1 ekliyoruz
                    && it.parameters.drop(1).withIndex().all { (index, param) ->
                val paramType = param.type.classifier as? KClass<*>
                val arg = action.args.getOrNull(index)
                arg != null && paramType != null && paramType.isInstance(arg.value)
            }
        }
        if (memberFunction != null) return memberFunction

        // Kotlin statik fonksiyonlarını bulma
        val staticFunction = obj::class.staticFunctions.find {
            it.name == action.key
                    && it.parameters.size == action.args.size.plus(1)
                    && it.parameters.drop(1).withIndex().all { (index, param) ->
                val paramType = param.type.classifier as? KClass<*>
                val arg = action.args.getOrNull(index)
                arg != null && paramType != null && paramType.isInstance(arg.value)
            }
        }
        if (staticFunction != null) return staticFunction

        // Kotlin üye genişletme fonksiyonlarını bulma
        val extensionFunction = obj::class.memberExtensionFunctions.find {
            it.name == action.key
                    && it.parameters.size == action.args.size.plus(1)
                    && it.parameters.drop(1).withIndex().all { (index, param) ->
                val paramType = param.type.classifier as? KClass<*>
                val arg = action.args.getOrNull(index)
                arg != null && paramType != null && paramType.isInstance(arg.value)
            }
        }
        if (extensionFunction != null) return extensionFunction

        // Java metotlarını bulma
        val javaClass = if (obj is Class<*>) obj else obj::class.java
        val method = javaClass.methods.find { method ->
            method.name == action.key
                    && method.parameterCount == action.args.size
                    && method.parameterTypes.withIndex().all { (index, paramType) ->
                val arg = action.args.getOrNull(index)
                arg != null && isStrictMatch(paramType, arg.value)
            }
        }
        if (method != null) return method

        return null
    }

    private fun isStrictMatch(paramType: Class<*>, argValue: Any?): Boolean {
        // Eğer argüman null ise sadece referans tipi kabul edilir, ilkel tipler kabul edilmez.
        if (argValue == null) {
            return !paramType.isPrimitive
        }

        // Java'nın ilkel tipleri ve bunların Wrapper sınıfları için strict eşleşme kontrolü
        return when (paramType) {
            java.lang.Integer.TYPE -> argValue is Int
            java.lang.Long.TYPE -> argValue is Long
            java.lang.Double.TYPE -> argValue is Double
            java.lang.Float.TYPE -> argValue is Float
            java.lang.Boolean.TYPE -> argValue is Boolean
            java.lang.Character.TYPE -> argValue is Char
            java.lang.Byte.TYPE -> argValue is Byte
            java.lang.Short.TYPE -> argValue is Short
            else -> paramType.isInstance(argValue) // Diğer referans tipleri için normal instance kontrolü
        }
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
                        property.isAccessible = true  // Erişimi açıyoruz

                        currentObj = when (property) {
                            is KProperty1<*, *> -> {
                                (property as KProperty1<Any?, *>).get(currentObj)
                            }
                            is KProperty0<*> -> property.get()
                            else -> throw IllegalArgumentException("Unsupported property type: ${property::class}")
                        }
                    } else {
                        // Eğer property bulunamazsa, bu sefer class'ın statik alanlarına bakıyoruz.
                        try {
                            val field = clazz.java.getField(action.key) // Statik field'ı kontrol et
                            field.isAccessible = true  // Statik alanın erişimini açıyoruz
                            currentObj = field.get(null) // Statik olduğu için null context kullan
                        } catch (e: NoSuchFieldException) {
                            throw IllegalArgumentException("No property or static field found with name: ${action.key}")
                        }
                    }
                }
                "Apply" -> {
                    val clazz = if (currentObj is Class<*>) currentObj.kotlin else currentObj::class
                    val function = findFunction(currentObj, action)
                        ?: clazz.java.methods.firstOrNull { it.name == action.key } // Statik metotları da kontrol et

                    if (function != null) {
                        try {
                            if (function is KFunction<*>) {
                                function.isAccessible = true  // Fonksiyonun erişimini açıyoruz
                            }

                            // Argümanları işleme, hem Kotlin KFunction hem de Java Method destekleniyor
                            logger.info("Args: ${action.args}")
                            val args = action.args.mapIndexed { index, arg ->
                                try {
                                    if (arg.__type__ == "Reference") {
                                        val id = arg.id;
                                        if (id != null && this.refs.containsKey(id)) {
                                            this.refs[id]?.let {
                                                it.accessedAt = System.currentTimeMillis()
                                                return@mapIndexed it.value ?: arg
                                            }
                                        } else {
                                            return@mapIndexed arg
                                        }
                                    } else {
                                        // Eğer KFunction ise parametre türlerini kullanarak cast yap
                                        val value = arg.value;
                                        if (function is KFunction<*>) {
                                            val paramType = function.parameters.getOrNull(index + 1)?.type?.classifier as? KClass<*>
                                            if (paramType != null) {
                                                return@mapIndexed paramType.safeCast(value) ?: value
                                            } else {
                                                return@mapIndexed value
                                            }
                                        }
                                        // Eğer Java Method ise parametre türlerini kullanarak cast yap
                                        else if (function is java.lang.reflect.Method) {
                                            val paramType = function.parameterTypes.getOrNull(index)
                                            if (paramType != null) {
                                                // Argümanı parametre tipine cast etme
                                                when (paramType) {
                                                    java.lang.Integer.TYPE -> return@mapIndexed (value as? Number)?.toInt() ?: value
                                                    java.lang.Long.TYPE -> return@mapIndexed (value as? Number)?.toLong() ?: value
                                                    java.lang.Double.TYPE -> return@mapIndexed (value as? Number)?.toDouble() ?: value
                                                    java.lang.Float.TYPE -> return@mapIndexed (value as? Number)?.toFloat() ?: value
                                                    java.lang.Boolean.TYPE -> return@mapIndexed (value as? Boolean) ?: value
                                                    java.lang.String::class.java -> return@mapIndexed (value as? String) ?: value
                                                    else -> return@mapIndexed value
                                                }
                                            } else {
                                                return@mapIndexed value
                                            }
                                        } else {
                                            return@mapIndexed value
                                        }
                                    }
                                } catch (e: Exception) {
                                    logger.warning("An error occurred while casting argument: $arg to type: ${function::class}")
                                    e.printStackTrace()
                                    arg.value;
                                }
                            }

                            logger.info("Args after processing: $args, function: $function")

                            if (function is KFunction<*>) {
                                currentObj = function.call(currentObj, *args.toTypedArray())
                            } else {
                                // Java metotları için
                                currentObj = (function as java.lang.reflect.Method).invoke(null, *args.toTypedArray())
                            }

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

                if (!registeredCommands.containsKey(key)) return false;

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

    fun registerEvent(className: String, priority: EventPriority, cancelConditions: CompiledConditionGroup) {
        val clazz = loadClass(className) ?: return

        val self = this

        val eventClass = clazz as Class<out Event>

        server.pluginManager.registerEvent(
            eventClass,
            this,
            priority,
            { listener: Listener, event: Event ->
                 // logger.info("$cancelConditions, $event")
                if (checkConditionGroup(cancelConditions, event)) {
                    // logger.info("Event cancelled: $event");
                    if (event is Cancellable) event.isCancelled = true;
                }

                val eventId = event.hashCode().toString()
                self.refs[eventId] = ClientReferenceItem(eventId, event, System.currentTimeMillis())
                // logger.info("Event received: $eventId, $event")

                ws.sendEvent("ExecuteEvent", ExecuteEventResponse(className, ClientReferenceResponse(eventId)))
            },
            this
        )
    }

    fun unregisterAllEvents() {
        HandlerList.unregisterAll(this as Listener);
    }

    private fun checkCondition(a: String, op: String, b: String): Boolean {
        // logger.info("Checking condition: $a, $op, $b")
        return try {
            when (op) {
                "==" -> a == b;
                "!=" -> a != b;
                ">" -> a.toDouble() > b.toDouble();
                ">=" -> a.toDouble() >= b.toDouble();
                "<" -> a.toDouble() < b.toDouble();
                "<=" -> a.toDouble() <= b.toDouble();
                "Contains" -> a.contains(b);
                "NotContains" -> !a.contains(b);
                "MatchesRegex" -> a.matches(b.toRegex());
                "NotMatchesRegex" -> !a.matches(b.toRegex());
                else -> false;
            }
        } catch (e: Exception) {
            logger.warning("Error while comparing values ($a, $op, $b): ${e.message}");
            false;
        }
    }

    private fun processConditionValue(value: CompiledConditionValue, context: Any?): String {
        return when (value.type) {
            "Value" -> value.value as String;
            "Path" -> {
                val base = when (value.base) {
                    "Context" -> context
                    "Plugin" -> this
                    else -> null;
                }
                processActions(base, value.path as List<Action>).toString();
            }
            else -> "";
        }
    }

    private fun checkConditionGroup(group: CompiledConditionGroup, context: Any?): Boolean {
        if (group.and !== null) {
            group.and.forEach {
                if (!checkCondition(
                        processConditionValue(it.a, context),
                        it.op,
                        processConditionValue(it.b, context)
                    )) return false;
            }
            return true;
        }
        if (group.or !== null) {
            group.or.forEach {
                if (checkCondition(
                        processConditionValue(it.a, context),
                        it.op,
                        processConditionValue(it.b, context)
                    )) return true;
            }
            return false;
        }
        return false;
    }
}
