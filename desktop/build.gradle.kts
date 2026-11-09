import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.security.MessageDigest

plugins {
 id("org.jetbrains.kotlin.jvm")
 id("org.jetbrains.kotlin.plugin.compose")
 id("org.jetbrains.compose") version "1.10.3"
}
// Nozzle It All for Desktop: the original Nozzle interface (Compose Desktop). It depends on the shared printer API, the
// PAXX LAN adapter and the project format. It must never depend on :stock-u1-adapter or any vendor-cloud library; the
// Stock U1 helper is found and launched at run time only when the user enables it. verifyPaxxBaseline enforces that on
// the resolved runtime classpath.
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
 implementation(project(":environment-core"))
 implementation("org.xerial:sqlite-jdbc:3.49.1.0")
 implementation("com.github.hypfvieh:dbus-java-core:5.1.1")
 runtimeOnly("com.github.hypfvieh:dbus-java-transport-native-unixsocket:5.1.1")
 implementation(project(":printer-api"))
 // Printer adapters are discovered at run time (ServiceLoader); Desktop's own code never names a vendor. Which adapter
 // modules ship is a build choice: -PnozzleAdapters=paxx,octoprint,prusa,bambu,elegoo (PAXX U1, the flagship, is the default
 // minimum). Building with fewer proves that removing one vendor never breaks another.
 providers.gradleProperty("nozzleAdapters").orElse("paxx,octoprint,prusa,bambu,elegoo").get().split(",").map { it.trim() }.filter { it.isNotEmpty() }
  .forEach { runtimeOnly(project(":adapter-$it")) }
 testImplementation(project(":adapter-paxx"))
 implementation(project(":project-format"))
 // Pure JVM rules shared with Android (flushing volumes).
 implementation(project(":domain"))
 implementation(compose.desktop.currentOs)
 implementation(compose.foundation)
 implementation(compose.ui)
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
 implementation("org.json:json:20240303")
 testImplementation("junit:junit:4.13.2")
 testImplementation(compose.desktop.uiTestJUnit4)
}

// Candidate builds use an exact source archive on GTHost rather than a mutable Git checkout. Accept the immutable
// revision recorded by that transfer while retaining Git discovery for ordinary developer checkouts.
val sourceRevision = providers.gradleProperty("nozzleSourceRevision").orElse(
 providers.exec {
  commandLine("git", "-C", rootProject.projectDir.absolutePath, "rev-parse", "HEAD")
 }.standardOutput.asText.map { it.trim() }
).map { revision ->
 require(revision.matches(Regex("[0-9a-f]{40}"))) {
  "nozzleSourceRevision must be the full lowercase 40-character source commit."
 }
 revision
}

// One source for printer profiles: the flattened OrcaSlicer profiles Android already bundles (scripts/flatten_orca_profile.py).
tasks.named<ProcessResources>("processResources") {
 // Package acceptance can identify the actual source revision inside the installed jar.
 inputs.property("sourceRevision", sourceRevision)
 doLast {
  destinationDir.resolve("nozzle-source-revision.txt").writeText(sourceRevision.get() + "\n")
 }
 from(rootProject.file("app/src/main/assets/slicer_profiles")) { into("profiles"); exclude("PROVENANCE.md") }
 // Profiles the desktop engine (Snapmaker Orca base) can't slice yet; the catalog leaves them out.
 from(rootProject.file("engine/profiles/unsupported-profiles.json")) { into("profiles") }
 // The packaged runtime checks the bundled native engine against the same reviewed repository pin.
 from(rootProject.file("engine/fork/ENGINE_PIN.json")) { into("engine") }
 // Snapmaker Orca's filament colour library (Full Spectrum's recommended palette), from the pinned commit.
 from(rootProject.file("engine/profiles/resources/filaments_colours.json")) { into("fullspectrum") }
 // Printers' whole profile families from their own slicers: nozzle sizes, process presets, filaments
 // (scripts/bundle_printer_library.py).
 from(rootProject.file("engine/profiles/library")) { into("library") }
 // Every engine setting (exported from the engine itself) and Nozzle's own grouping of them.
 from(rootProject.file("schemas/slicing")) { into("settings"); include("settings-schema.json", "settings-groups.json") }
}

val nozzleVersion = providers.gradleProperty("nozzleDesktopVersion").orElse("0.1.0")

// The native slicing engine (nozzle-engine: the same engine commit and bridge code as Android and the Web App).
// -PnozzleEngine=/path/to/nozzle-engine; defaults to engine/native/scripts/build_engine_fork.sh's output. When the file
// exists it is bundled into the Linux distribution's app resources, where SliceEngine.locateNative() looks first.
// Packaging must fail if it is absent; an engine-free Deb could otherwise appear successful and then silently use a
// developer workstation's fallback binary at runtime.
val nozzleEngine = providers.gradleProperty("nozzleEngine").orElse("/mnt/faststorage/build-work/nozzle-native-fork/dist/nozzle-engine")
val engineResources = layout.buildDirectory.dir("engine-resources")
val verifyNozzleEngine = tasks.register("verifyNozzleEngine") {
 doLast {
  val engineFile = file(nozzleEngine.get())
  require(engineFile.isFile && engineFile.canExecute()) {
   "A runnable nozzle-engine is required for the desktop package: ${engineFile.absolutePath}. " +
    "Build the pinned engine on GTHost and pass -PnozzleEngine=<absolute path>."
  }
  val pinText = rootProject.file("engine/fork/ENGINE_PIN.json").readText()
  val desktopCommit = Regex("\"desktop_commit\"\\s*:\\s*\"([0-9a-f]{40})\"")
   .find(pinText)?.groupValues?.get(1)
  require(desktopCommit != null || "\"desktop_commit\"" !in pinText) {
   "The Desktop engine commit in engine/fork/ENGINE_PIN.json is invalid."
  }
  val pinnedCommit = desktopCommit ?: Regex("\"commit\"\\s*:\\s*\"([0-9a-f]{40})\"")
   .find(pinText)?.groupValues?.get(1)
   ?: error("The pinned engine commit could not be read from engine/fork/ENGINE_PIN.json.")
  val pinnedSha = Regex("\"desktop_binary_sha256\"\\s*:\\s*\"([0-9a-f]{64})\"")
   .find(pinText)?.groupValues?.get(1)
   ?: error("The pinned desktop engine SHA-256 could not be read from engine/fork/ENGINE_PIN.json.")
  val provenance = engineFile.resolveSibling("PROVENANCE.txt")
  require(provenance.isFile) { "The nozzle-engine build provenance is missing: ${provenance.absolutePath}." }
  val record = provenance.readText()
  val recordedCommits = Regex("(?m)^engine source: nozzle-engine ([0-9a-f]{40}) \\(engine/fork/ENGINE_PIN\\.json\\), bridges from the engine \\(nozzle/bridge\\)$")
   .findAll(record).map { it.groupValues[1] }.toList()
  require(recordedCommits.singleOrNull() == pinnedCommit) {
   "The nozzle-engine provenance does not match pinned source $pinnedCommit."
  }
  val recordedSha = Regex("(?m)^([0-9a-f]{64})  nozzle-engine$")
   .findAll(record).map { it.groupValues[1] }.toList().singleOrNull()
   ?: error("The nozzle-engine provenance has no binary SHA-256 record.")
  val digest = MessageDigest.getInstance("SHA-256")
  engineFile.inputStream().use { input ->
   val buffer = ByteArray(1024 * 1024)
   while (true) {
    val read = input.read(buffer)
    if (read < 0) break
    digest.update(buffer, 0, read)
   }
  }
  val actualSha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
  require(actualSha == recordedSha && actualSha == pinnedSha) {
   "The nozzle-engine SHA-256 does not match the repository pin and build provenance " +
    "(actual $actualSha, pinned $pinnedSha, recorded $recordedSha)."
  }
 }
}
val viewportLibrary=layout.buildDirectory.file("native/viewport/libnia_viewport.so")
val buildViewport=tasks.register<Exec>("buildViewportRenderer") {
 onlyIf { System.getProperty("os.name")=="Linux" && System.getProperty("os.arch") in listOf("amd64","x86_64") }
 inputs.file("src/main/cpp/viewport.cpp");inputs.property("sourceRevision",sourceRevision)
 inputs.property("javaHome",System.getProperty("java.home"))
 if(System.getProperty("os.name")=="Linux") {
  inputs.property("compilerVersion",providers.exec { commandLine("g++","--version") }.standardOutput.asText)
  inputs.files(fileTree("/usr/include/EGL"),fileTree("/usr/include/GLES3"),fileTree("/usr/include/KHR"))
 }
 outputs.file(viewportLibrary)
 doFirst {
  viewportLibrary.get().asFile.parentFile.mkdirs()
  val javaHome=System.getProperty("java.home")
  commandLine("g++","-std=c++17","-O3","-fPIC","-shared","-fvisibility=hidden","-static-libstdc++","-static-libgcc",
   "-I$javaHome/include","-I$javaHome/include/linux","-DNIA_SOURCE_REVISION=\"${sourceRevision.get()}\"",
   file("src/main/cpp/viewport.cpp").absolutePath,"-o",viewportLibrary.get().asFile.absolutePath,"-lEGL","-lGLESv2")
 }
}
val prepareEngineResources = tasks.register<Sync>("prepareEngineResources") {
 dependsOn(buildViewport, verifyNozzleEngine)
 from(viewportLibrary) { into("linux-x64") }
 from(nozzleEngine.map { path -> files(path) }) {
  into("linux-x64")
  rename { "nozzle-engine" }
  filePermissions { unix("rwxr-xr-x") }
 }
 into(engineResources)
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(prepareEngineResources) }

compose.desktop {
 application {
  mainClass = "com.nozzleitall.desktop.MainKt"
  jvmArgs += listOf("-Dnozzle.version=${nozzleVersion.get()}", "-Dsun.java2d.uiScale.enabled=true",
   // Lets Main.kt name the window class so Linux docks show the right icon.
   "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED")
  nativeDistributions {
   appResourcesRootDir.set(engineResources)
   targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi)
   packageName = "nozzle-it-all"
   packageVersion = nozzleVersion.get()
   description = "Nozzle It All: printer management, project preparation, slicing and monitoring"
   vendor = "Nozzle It All"
   copyright = "Copyright 2026 Nozzle It All contributors. AGPL-3.0-or-later."
   licenseFile.set(rootProject.file("LICENSE"))
   // Only what the app uses, so the bundled runtime stays small.
   modules("java.desktop", "java.logging", "java.net.http", "java.xml", "jdk.unsupported", "java.naming", "java.sql", "jdk.security.auth", "jdk.accessibility")
   linux {
    packageName = "nozzle-it-all"
    debMaintainer = "support@nozzleitall.com"
    menuGroup = "Graphics;3DGraphics;Engineering"
    appCategory = "graphics"
    iconFile.set(project.file("packaging/icons/nozzle-it-all-512.png"))
   }
   windows {
    upgradeUuid = "7c4f6a1e-5d9b-4c52-9a0e-6b2f0d1c8e41"
    menuGroup = "Nozzle It All"
    perUserInstall = true
    iconFile.set(project.file("packaging/icons/nozzle-it-all.ico"))
   }
  }
 }
}

// PAXX baseline guard: nothing on Desktop's runtime classpath may be the Stock helper or a vendor-cloud/Flutter library.
val verifyPaxxBaseline = tasks.register("verifyPaxxBaseline") {
 val runtime = configurations.named("runtimeClasspath")
 doLast {
  // Vendor clouds, Flutter and the optional Stock U1 helper must never be part of Desktop. (Local protocol libraries
  // such as Bambu's LAN MQTT client may be, inside their own adapter module.)
  val banned = listOf("stock-u1-adapter", "paho", "flutter", "firebase", "snapmaker-cloud")
  val offenders = runtime.get().resolvedConfiguration.resolvedArtifacts.map { it.moduleVersion.id.toString() + " (" + it.file.name + ")" }
   .filter { name -> banned.any { name.contains(it, ignoreCase = true) } }
  if (offenders.isNotEmpty()) throw GradleException("PAXX baseline violated; Desktop's runtime classpath contains: $offenders")
  val sources = fileTree("src/main") { include("**/*.kt") }.files
  val badImports = sources.filter { f -> f.readLines().any { it.startsWith("import com.nozzleitall.stocku1") || it.startsWith("import com.nozzleitall.adapter.") } }
  if (badImports.isNotEmpty()) throw GradleException("Desktop must not import adapter modules (they're discovered at run time): $badImports")
 }
}
tasks.named("check") { dependsOn(verifyPaxxBaseline) }

tasks.withType<Test>().configureEach {
 // Source/resource parity tests intentionally resolve paths from the desktop module.
 // Keep this invariant independent of the directory used to invoke Gradle.
 workingDir(project.projectDir)
 dependsOn(buildViewport)
 systemProperty("nozzle.viewport.library",viewportLibrary.get().asFile.absolutePath)
 // Room for real-world models (a million-triangle painted 3MF needs more than the 512 MB default).
 maxHeapSize = "4g"
 // Tests slice with the native engine when it has been built (SliceEngine.locateNative()).
 systemProperty("nozzle.engine", nozzleEngine.get())
 // LocalConnectorTest sends a forged Host header to prove the DNS-rebinding check.
 systemProperty("sun.net.http.allowRestrictedHeaders", "true")
 systemProperty("nozzle.adapters", providers.gradleProperty("nozzleAdapters").orElse("paxx,octoprint,prusa,bambu,elegoo").get())
}
