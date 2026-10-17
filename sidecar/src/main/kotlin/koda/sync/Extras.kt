package koda.sync

/**
 * Custom tooling model served by `gradle/koda.init.gradle`.
 *
 * The init script's builder answers `canBuild("koda.sync.KodaExtras")`, so this interface's
 * fully qualified name is part of the contract. The model is a single JSON string so that nothing but a `String`
 * has to cross from the init script's classloader into the build action.
 */
interface KodaExtras {
  val json: String
}

/** Parameter for [KodaExtras]: the variant whose classpath and Kotlin options should be reported. */
interface KodaExtrasParameter {
  var variant: String?
}
