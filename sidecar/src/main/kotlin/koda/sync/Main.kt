package koda.sync

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.model.build.BuildEnvironment
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * koda-sync: exports an Android Gradle project's IDE model for Koda Studio.
 *
 * Writes JSON event lines to stdout (`{"event": ...}`); Gradle's own output goes to stderr.
 */
private class Options(
  val projectDir: File,
  val initScript: File,
  val out: File,
  val variant: String?,
  val javaHome: File?,
  val generate: String,
  val gradleArgs: List<String>,
  /** Stop the Gradle daemon this sync started once it's done: the Kotlin server indexes next and needs the memory. */
  val stopDaemon: Boolean,
)

private val events: PrintStream = PrintStream(System.out, true, Charsets.UTF_8)

fun main(args: Array<String>) {
  val options = try {
    parseOptions(args)
  } catch (e: IllegalArgumentException) {
    emit("error", "stage" to "arguments", "message" to (e.message ?: "bad arguments"))
    exitProcess(2)
  }
  // Keep stdout for events only; anything else printing to System.out would corrupt the stream.
  System.setOut(PrintStream(System.err, true, Charsets.UTF_8))

  val cancellation = GradleConnector.newCancellationTokenSource()
  Runtime.getRuntime().addShutdownHook(Thread { cancellation.cancel() })

  val connector = GradleConnector.newConnector().forProjectDirectory(options.projectDir)
  val exitCode = try {
    val connection = connector.connect()
    try {
      sync(connection, options, cancellation)
    } finally {
      if (options.stopDaemon) {
        emit("phase", "name" to "stop-daemon")
        // Stops the daemon of this sync's connection (only open connections are), then closes it.
        runCatching { connector.disconnect() }.onFailure { emit("warning", "message" to "Could not stop the Gradle daemon: ${it.describe()}") }
      }
      connection.close()
    }
  } catch (e: Throwable) {
    emit("error", "stage" to "sync", "message" to e.describe())
    1
  }
  exitProcess(exitCode)
}

private fun sync(
  connection: ProjectConnection,
  options: Options,
  cancellation: org.gradle.tooling.CancellationTokenSource,
): Int {
  emit("phase", "name" to "environment")
  val environment = connection.model(BuildEnvironment::class.java).also { builder ->
    options.javaHome?.let(builder::setJavaHome)
    builder.withCancellationToken(cancellation.token())
  }.get()

  emit("phase", "name" to "model")
  val modelArguments = buildList {
    add("--init-script"); add(options.initScript.absolutePath)
    add("--no-configuration-cache")
    add("-Dorg.gradle.configureondemand=false")
    // The properties Android Studio sends during sync: models only, lenient dependency resolution.
    add("-Pandroid.injected.build.model.only=true")
    add("-Pandroid.injected.build.model.only.advanced=true")
    add("-Pandroid.injected.build.model.only.versioned=3")
    add("-Pandroid.injected.build.model.v2=true")
    add("-Pandroid.injected.invoked.from.ide=true")
    add("-Pandroid.injected.build.model.disable.src.download=true")
    addAll(options.gradleArgs)
  }
  val modelJson = connection.action(SyncAction(options.variant)).also { action ->
    action.withArguments(modelArguments)
    options.javaHome?.let(action::setJavaHome)
    action.setStandardOutput(System.err)
    action.setStandardError(System.err)
    action.addProgressListener(org.gradle.tooling.ProgressListener { event -> progress(event.description) })
    action.withCancellationToken(cancellation.token())
  }.run()

  val model = JsonParser.parseString(modelJson).asJsonObject
  model.addProperty("gradleVersion", environment.gradle.gradleVersion)
  model.addProperty("javaHome", environment.java.javaHome.absolutePath)
  options.out.parentFile?.mkdirs()
  options.out.writeText(model.toString())
  emit("model", "path" to options.out.absolutePath)

  if (options.generate != "none") {
    val tasks = generationTasks(model, full = options.generate == "full")
    emit("phase", "name" to "generate", "tasks" to tasks)
    if (tasks.isNotEmpty()) {
      try {
        connection.newBuild().also { build ->
          build.forTasks(*tasks.toTypedArray())
          build.withArguments(buildList { add("--continue"); addAll(options.gradleArgs) })
          options.javaHome?.let(build::setJavaHome)
          build.setStandardOutput(System.err)
          build.setStandardError(System.err)
          build.addProgressListener(org.gradle.tooling.ProgressListener { event -> progress(event.description) })
          build.withCancellationToken(cancellation.token())
        }.run()
      } catch (e: Exception) {
        // The model is still usable; missing generated files only leave some symbols unresolved.
        emit("warning", "stage" to "generate", "message" to e.describe())
      }
    }
  }
  emit("done", "path" to options.out.absolutePath)
  return 0
}

/**
 * Tasks that produce the generated sources and class jars a variant's code compiles against.
 * "light" skips `generate<V>Sources`: with AGP's built-in Kotlin it depends on KSP, which compiles upstream modules.
 */
internal fun generationTasks(model: JsonObject, full: Boolean): List<String> {
  val tasks = mutableListOf<String>()
  for (projectElement in model.getAsJsonArray("projects")) {
    val project = projectElement.asJsonObject
    val android = project.getAsJsonObject("android") ?: continue
    val extras = project.getAsJsonObject("extras") ?: continue
    val existing = extras.getAsJsonArray("tasks")?.map { it.asString }?.toSet() ?: continue
    val variant = android.get("selectedVariant")?.asString ?: continue
    val v = variant.replaceFirstChar { it.uppercaseChar() }
    val wanted = mutableListOf("generate${v}BuildConfig", "compile${v}Aidl", "dataBindingGenBaseClasses$v")
    wanted += rTaskName(android) ?: "generate${v}RFile"
    if (rTaskName(android) == null) wanted += "process${v}Resources"
    if (full) wanted += listOf("generate${v}Sources", "ksp${v}Kotlin", "kapt${v}Kotlin")
    val prefix = project.get("path").asString.let { if (it == ":") "" else it }
    for (name in wanted.distinct()) if (name in existing) tasks += "$prefix:$name"
  }
  return tasks
}

/** AGP stores the R jar at `intermediates/<artifact>/<variant>/<task>/R.jar`, so its parent folder names the task. */
private fun rTaskName(android: JsonObject): String? {
  val folders: JsonArray = android.getAsJsonObject("components")?.getAsJsonObject("main")
    ?.getAsJsonArray("classesFolders") ?: return null
  return folders.map { File(it.asString) }.firstOrNull { it.name == "R.jar" }?.parentFile?.name
}

private fun parseOptions(args: Array<String>): Options {
  var projectDir: File? = null
  var initScript: File? = null
  var out: File? = null
  var variant: String? = null
  var javaHome: File? = null
  var generate = "light"
  var stopDaemon = false
  val gradleArgs = mutableListOf<String>()
  var i = 0
  fun value(): String = args.getOrNull(++i) ?: throw IllegalArgumentException("missing value for ${args[i - 1]}")
  while (i < args.size) {
    when (args[i]) {
      "--project-dir" -> projectDir = File(value())
      "--init-script" -> initScript = File(value())
      "--out" -> out = File(value())
      "--variant" -> variant = value().ifBlank { null }
      "--java-home" -> javaHome = File(value())
      "--generate" -> generate = value().also { require(it in setOf("none", "light", "full")) { "--generate must be none|light|full" } }
      "--gradle-arg" -> gradleArgs += value()
      "--stop-daemon" -> stopDaemon = true
      else -> throw IllegalArgumentException("unknown argument ${args[i]}")
    }
    i++
  }
  return Options(
    projectDir = projectDir ?: throw IllegalArgumentException("--project-dir is required"),
    initScript = initScript ?: throw IllegalArgumentException("--init-script is required"),
    out = out ?: throw IllegalArgumentException("--out is required"),
    variant = variant,
    javaHome = javaHome,
    generate = generate,
    gradleArgs = gradleArgs,
    stopDaemon = stopDaemon,
  )
}

private var lastProgress = 0L

private fun progress(message: String) {
  val now = System.currentTimeMillis()
  if (now - lastProgress < 250 || message.isBlank()) return
  lastProgress = now
  emit("progress", "message" to message)
}

private fun emit(event: String, vararg fields: Pair<String, Any?>) {
  val json = JsonObject()
  json.addProperty("event", event)
  for ((key, value) in fields) {
    when (value) {
      null -> {}
      is List<*> -> json.add(key, JsonArray().apply { value.forEach { add(it.toString()) } })
      else -> json.addProperty(key, value.toString())
    }
  }
  synchronized(events) { events.println(json.toString()) }
}

private fun Throwable.describe(): String =
  generateSequence(this) { it.cause }.joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }.take(4000)

@Suppress("unused")
private object NullOutput : OutputStream() {
  override fun write(b: Int) {}
}
