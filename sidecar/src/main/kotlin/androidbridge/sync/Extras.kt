package androidbridge.sync

/**
 * Custom tooling model served by `gradle/android-bridge.init.gradle`.
 *
 * The init script's builder answers `canBuild("androidbridge.sync.AndroidBridgeExtras")`, so this interface's
 * fully qualified name is part of the contract. The model is a single JSON string so that nothing but a `String`
 * has to cross from the init script's classloader into the build action.
 */
interface AndroidBridgeExtras {
  val json: String
}

/** Parameter for [AndroidBridgeExtras]: the variant whose classpath and Kotlin options should be reported. */
interface AndroidBridgeExtrasParameter {
  var variant: String?
}
