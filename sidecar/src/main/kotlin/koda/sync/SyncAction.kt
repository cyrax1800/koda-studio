package koda.sync

import com.android.builder.model.v2.ide.AbstractArtifact
import com.android.builder.model.v2.ide.BasicArtifact
import com.android.builder.model.v2.ide.BasicVariant
import com.android.builder.model.v2.ide.ProjectType
import com.android.builder.model.v2.ide.SourceProvider
import com.android.builder.model.v2.ide.SourceSetContainer
import com.android.builder.model.v2.ide.Variant
import com.android.builder.model.v2.models.AndroidProject
import com.android.builder.model.v2.models.BasicAndroidProject
import com.android.builder.model.v2.models.ModelBuilderParameter
import com.android.builder.model.v2.models.ProjectGraph
import com.android.builder.model.v2.models.Versions
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.gradle.tooling.BuildAction
import org.gradle.tooling.BuildController
import org.gradle.tooling.model.gradle.BasicGradleProject
import java.io.File

/**
 * Runs inside the Gradle daemon. Reads Android Studio's v2 models plus the init script's extras for every project
 * and returns the whole project model as one JSON string (see `src/gradle/modelTypes.ts`).
 */
class SyncAction(private val requestedVariant: String?) : BuildAction<String> {

  private class AndroidModels(
    val project: BasicGradleProject,
    val versions: Versions,
    val basic: BasicAndroidProject,
    val android: AndroidProject,
  )

  /** Raw `ProjectGraph.resolvedVariants` keys, reported for diagnosing the key format across AGP versions. */
  private val projectGraphKeys = mutableListOf<String>()

  override fun execute(controller: BuildController): String {
    val build = controller.buildModel
    val projects = build.projects.toList()
    val problems = JsonArray()

    val androidModels: Map<String, AndroidModels> = runAll(controller, projects) { c, project ->
      fetchAndroidModels(c, project, problems)
    }.filterNotNull().associateBy { it.project.path }

    val selected = selectVariants(controller, androidModels, problems)

    val extras: List<String?> = runAll(controller, projects) { c, project ->
      fetchExtras(c, project, selected[project.path], problems)
    }

    val result = JsonObject()
    result.addProperty("schemaVersion", 1)
    result.addProperty("rootDir", build.rootProject.projectDirectory.absolutePath)
    result.addProperty("rootProjectName", build.rootProject.name)
    requestedVariant?.let { result.addProperty("requestedVariant", it) }
    val projectsJson = JsonArray()
    projects.forEachIndexed { index, project ->
      val json = JsonObject()
      json.addProperty("path", project.path)
      json.addProperty("name", project.name)
      json.addProperty("dir", project.projectDirectory.absolutePath)
      androidModels[project.path]?.let { models ->
        val variant = selected[project.path]
        if (variant != null) json.add("android", androidJson(models, variant))
      }
      extras[index]?.let { text ->
        runCatching { JsonParser.parseString(text) }
          .onSuccess { json.add("extras", it) }
          .onFailure { problems.add(problem(project.path, "extras", "unparsable extras: ${it.message}")) }
      }
      projectsJson.add(json)
    }
    result.add("projects", projectsJson)
    result.add("problems", problems)
    result.add("selectedVariants", JsonObject().apply { selected.forEach { (k, v) -> addProperty(k, v) } })
    result.add("projectGraphKeys", strings(projectGraphKeys))
    return result.toString()
  }

  /** Runs one action per project, in parallel when the build allows it (`org.gradle.tooling.parallel`). */
  private fun <T> runAll(
    controller: BuildController,
    projects: List<BasicGradleProject>,
    body: (BuildController, BasicGradleProject) -> T,
  ): List<T> {
    val actions = projects.map { project -> BuildAction { c -> body(c, project) } }
    return try {
      controller.run(actions)
    } catch (e: UnsupportedOperationException) {
      projects.map { body(controller, it) }
    }
  }

  private fun fetchAndroidModels(c: BuildController, project: BasicGradleProject, problems: JsonArray): AndroidModels? {
    val versions = try {
      c.findModel(project, Versions::class.java)
    } catch (e: Exception) {
      null
    } ?: return null
    return try {
      AndroidModels(
        project,
        versions,
        c.getModel(project, BasicAndroidProject::class.java),
        c.getModel(project, AndroidProject::class.java),
      )
    } catch (e: Exception) {
      synchronized(problems) { problems.add(problem(project.path, "android-models", e.describe())) }
      null
    }
  }

  private fun fetchExtras(c: BuildController, project: BasicGradleProject, variant: String?, problems: JsonArray): String? =
    try {
      c.findModel(project, KodaExtras::class.java, KodaExtrasParameter::class.java) {
        it.variant = variant
      }?.json
    } catch (e: Exception) {
      synchronized(problems) { problems.add(problem(project.path, "extras", e.describe())) }
      null
    }

  // ---- variant selection ----

  private fun selectVariants(
    controller: BuildController,
    models: Map<String, AndroidModels>,
    problems: JsonArray,
  ): Map<String, String> {
    val selected = LinkedHashMap<String, String>()
    val roots = models.values.filter {
      it.basic.projectType == ProjectType.APPLICATION || it.basic.projectType == ProjectType.DYNAMIC_FEATURE
    }
    for (root in roots) defaultVariant(root.basic, requestedVariant)?.let { selected[root.project.path] = it }

    // Android Studio's own resolution: which variant of every reachable module the app actually consumes.
    val graphs = runAll(controller, roots.map { it.project }) { c, project ->
      val variant = selected[project.path] ?: return@runAll null
      try {
        c.findModel(project, ProjectGraph::class.java, ModelBuilderParameter::class.java) { p -> p.configure(variant) }
          ?.resolvedVariants
      } catch (e: Exception) {
        synchronized(problems) { problems.add(problem(project.path, "project-graph", e.describe())) }
        null
      }
    }
    for (graph in graphs) {
      graph?.forEach { (key, variant) ->
        projectGraphKeys.add(key)
        val path = models.keys.firstOrNull { key == it } ?: models.keys.firstOrNull { it != ":" && key.endsWith(it) }
        if (path != null) selected.putIfAbsent(path, variant)
      }
    }

    val reference = roots.firstNotNullOfOrNull { root ->
      selected[root.project.path]?.let { name -> root.basic.variants.firstOrNull { it.name == name } }
    }
    for (model in models.values) {
      if (model.project.path in selected) continue
      matchVariant(model.basic, reference)?.let { selected[model.project.path] = it }
    }
    return selected
  }

  private fun ModelBuilderParameter.configure(variant: String) {
    variantName = variant
    dontBuildRuntimeClasspath = true
    dontBuildUnitTestRuntimeClasspath = true
    dontBuildAndroidTestRuntimeClasspath = true
    dontBuildTestFixtureRuntimeClasspath = true
    dontBuildScreenshotTestRuntimeClasspath = true
    // AGP reads `map[suffix] ?: false`, so an empty map would build every host-test runtime classpath.
    dontBuildHostTestRuntimeClasspath = mapOf("UnitTest" to true, "ScreenshotTest" to true)
    additionalArtifactsInModel = false
  }

  private fun defaultVariant(basic: BasicAndroidProject, requested: String?): String? {
    val variants = basic.variants.toList()
    if (requested != null && variants.any { it.name == requested }) return requested
    return (variants.firstOrNull { it.buildType == "debug" } ?: variants.firstOrNull())?.name
  }

  private fun matchVariant(basic: BasicAndroidProject, reference: BasicVariant?): String? {
    val variants = basic.variants.toList()
    if (reference != null) {
      variants.firstOrNull { it.name == reference.name }?.let { return it.name }
      val sameBuildType = variants.filter { it.buildType == reference.buildType }
      sameBuildType.firstOrNull { reference.productFlavors.containsAll(it.productFlavors) }?.let { return it.name }
      sameBuildType.firstOrNull()?.let { return it.name }
    }
    return defaultVariant(basic, null)
  }

  // ---- JSON for one Android project ----

  private fun androidJson(models: AndroidModels, variantName: String): JsonObject {
    val basic = models.basic
    val basicVariant = basic.variants.firstOrNull { it.name == variantName }
    val variant = models.android.variants.firstOrNull { it.name == variantName }
    val json = JsonObject()
    json.addProperty("agpVersion", models.versions.agp)
    json.addProperty("projectType", basic.projectType.name)
    json.addProperty("namespace", safe { models.android.namespace })
    json.addProperty("buildFolder", safe { basic.buildFolder.absolutePath })
    json.addProperty("selectedVariant", variantName)
    json.add("bootClasspath", files(safe { basic.bootClasspath }))
    safe { models.android.javaCompileOptions }?.let { options ->
      json.add("javaCompileOptions", JsonObject().apply {
        addProperty("sourceCompatibility", options.sourceCompatibility)
        addProperty("targetCompatibility", options.targetCompatibility)
      })
    }
    json.add("variants", JsonArray().apply {
      for (v in basic.variants) add(JsonObject().apply {
        addProperty("name", v.name)
        addProperty("buildType", v.buildType)
        add("productFlavors", strings(v.productFlavors))
      })
    })

    val components = JsonObject()
    if (basicVariant != null && variant != null) {
      components.add("main", componentJson(
        providers = mainProviders(basic, basicVariant),
        artifact = variant.mainArtifact,
      ))
      val unitTestArtifact = safe { variant.hostTestArtifacts["UnitTest"] } ?: safe { variant.unitTestArtifact }
      if (unitTestArtifact != null) {
        components.add("unitTest", componentJson(
          providers = testProviders(basic, basicVariant, device = false),
          artifact = unitTestArtifact,
        ))
      }
      val androidTestArtifact = safe { variant.deviceTestArtifacts["AndroidTest"] } ?: safe { variant.androidTestArtifact }
      if (androidTestArtifact != null) {
        components.add("androidTest", componentJson(
          providers = testProviders(basic, basicVariant, device = true),
          artifact = androidTestArtifact,
        ))
      }
    }
    json.add("components", components)
    return json
  }

  /** Source providers that contribute to a variant's main sources, most general first. */
  private fun mainProviders(basic: BasicAndroidProject, variant: BasicVariant): List<SourceProvider> = buildList {
    safe { basic.mainSourceSet?.sourceProvider }?.let(::add)
    for (flavor in variant.productFlavors) {
      basic.productFlavorSourceSets.findContainer(flavor)?.let { safe { it.sourceProvider } }?.let(::add)
    }
    variant.buildType?.let { basic.buildTypeSourceSets.findContainer(it) }?.let { safe { it.sourceProvider } }?.let(::add)
    safe { variant.mainArtifact.multiFlavorSourceProvider }?.let(::add)
    safe { variant.mainArtifact.variantSourceProvider }?.let(::add)
  }.distinctBy { it.name }

  private fun testProviders(basic: BasicAndroidProject, variant: BasicVariant, device: Boolean): List<SourceProvider> {
    fun SourceSetContainer.testProvider(): SourceProvider? =
      if (device) safe { deviceTestSourceProviders["AndroidTest"] } ?: safe { androidTestSourceProvider }
      else safe { hostTestSourceProviders["UnitTest"] } ?: safe { unitTestSourceProvider }
    val artifact: BasicArtifact? =
      if (device) safe { variant.deviceTestArtifacts["AndroidTest"] } ?: safe { variant.androidTestArtifact }
      else safe { variant.hostTestArtifacts["UnitTest"] } ?: safe { variant.unitTestArtifact }
    return buildList {
      safe { basic.mainSourceSet }?.testProvider()?.let(::add)
      for (flavor in variant.productFlavors) basic.productFlavorSourceSets.findContainer(flavor)?.testProvider()?.let(::add)
      variant.buildType?.let { basic.buildTypeSourceSets.findContainer(it) }?.testProvider()?.let(::add)
      artifact?.let { a ->
        safe { a.multiFlavorSourceProvider }?.let(::add)
        safe { a.variantSourceProvider }?.let(::add)
      }
    }.distinctBy { it.name }
  }

  private fun Collection<SourceSetContainer>.findContainer(name: String): SourceSetContainer? =
    firstOrNull { safe { it.sourceProvider?.name } == name }

  private fun componentJson(providers: List<SourceProvider>, artifact: AbstractArtifact): JsonObject {
    val json = JsonObject()
    json.add("sourceProviders", JsonArray().apply {
      for (provider in providers) add(JsonObject().apply {
        addProperty("name", provider.name)
        add("java", files(safe { provider.javaDirectories }))
        add("kotlin", files(safe { provider.kotlinDirectories }))
        add("resources", files(safe { provider.resourcesDirectories }))
        add("aidl", files(safe { provider.aidlDirectories }))
      })
    })
    json.add("generatedSourceFolders", files(safe { artifact.generatedSourceFolders }))
    json.add("classesFolders", files(safe { artifact.classesFolders }))
    json.add("generatedClassPaths", JsonObject().apply {
      safe { artifact.generatedClassPaths }?.forEach { (key, file) -> addProperty(key, file.absolutePath) }
    })
    json.addProperty("compileTaskName", safe { artifact.compileTaskName })
    json.add("ideSetupTaskNames", strings(safe { artifact.ideSetupTaskNames }))
    (artifact as? com.android.builder.model.v2.ide.AndroidArtifact)?.let { android ->
      json.addProperty("sourceGenTaskName", safe { android.sourceGenTaskName })
    }
    return json
  }

  private fun files(files: Collection<File>?): JsonArray =
    JsonArray().apply { files?.forEach { add(it.absolutePath) } }

  private fun strings(values: Collection<String>?): JsonArray =
    JsonArray().apply { values?.forEach { add(it) } }

  private fun problem(path: String, stage: String, message: String) = JsonObject().apply {
    addProperty("project", path)
    addProperty("stage", stage)
    addProperty("message", message)
  }

  /** Older AGP versions don't implement every getter; the Tooling API throws for missing ones. */
  private inline fun <T> safe(block: () -> T): T? = try {
    block()
  } catch (e: Exception) {
    null
  }

  private fun Throwable.describe(): String =
    generateSequence(this) { it.cause }.joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" }.take(2000)
}
