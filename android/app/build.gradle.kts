import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ca.dynamicsolutions.sonarbridge"
    compileSdk = 36
    // build-tools stays 35.0.0: the pinned arm64 aapt2 static build (see
    // docker/Dockerfile, arm64 only) only goes up to 35.0.2, and the rest of
    // build-tools is Java inside AGP.
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "ca.dynamicsolutions.sonarbridge"
        minSdk = 29
        // Keep dependency-owned controls within the same language coverage as the app.
        resourceConfigurations += listOf("en", "en-rCA", "es", "fr", "fr-rCA", "de", "b+zh+Hans", "pt", "ja")
        targetSdk = 36
        // CI (release.yml) injects the tag version; local builds stay 0.1-dev
        versionCode = System.getenv("ANDROID_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("ANDROID_VERSION_NAME") ?: "0.1-dev"
    }

    // Two distribution channels from one codebase:
    //  - github: development and the final legacy migration APK
    //  - play:   public APK on both channels, no self-update, softer battery-opt
    flavorDimensions += "dist"
    productFlavors {
        create("github") {
            dimension = "dist"
            buildConfigField("boolean", "IS_PLAY", "false")
        }
        create("play") {
            dimension = "dist"
            buildConfigField("boolean", "IS_PLAY", "true")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // Debug pinned so containerized builds don't regenerate a fresh keystore
    // each run (breaks `adb install -r`); release key comes from env/CI
    // secrets (android/keystore/release.keystore locally, gitignored).
    signingConfigs {
        getByName("debug") {
            storeFile = file("../keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val ks = System.getenv("ANDROID_KEYSTORE_FILE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (System.getenv("ANDROID_KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Android resource/lifecycle checks span three framework sandboxes.
        unitTests.all { it.maxHeapSize = "1g" }
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.18")
}

// ---------------------------------------------------------------- third-party notices
// Third-party notices (HYG-3, D-085), generated at build time for every variant. The module
// POMs of the variant's runtime classpath (plus any parent POM a module inherits its licence
// from) are resolved through Gradle, and a plain-text file is written into the variant's
// generated assets as `third_party_notices.txt`, shown from About > Third-party notices. A
// module whose POM chain names no licence fails the build. Hand-written because no maintained
// notices plugin supports the held AGP 8.5.2 (AGENTS.md); move to one at the AGP upgrade.

/** Writes the notices file from resolved POMs; holds no Project reference, so it is configuration-cache safe. */
abstract class ThirdPartyNoticesTask : DefaultTask() {
    /** group:artifact:version of every module on the variant's runtime classpath. */
    @get:Input
    abstract val modules: ListProperty<String>

    /** Module POMs and the parent POMs they inherit from. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val poms: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun write() {
        // Index every POM by its own coordinates (groupId and version may be inherited from the parent).
        val byCoordinates = poms.files.associateBy { pom -> Pom.read(pom).coordinates }

        val entries = modules.get().sorted().map { coordinates ->
            val pom = byCoordinates[coordinates]?.let(Pom::read)
                ?: throw GradleException("Third-party notices: no POM resolved for $coordinates")
            val licenses = pom.licensesFollowingParents(byCoordinates)
            if (licenses.isEmpty()) throw GradleException("Third-party notices: $coordinates states no licence")
            buildString {
                appendLine(pom.name ?: coordinates)
                appendLine(coordinates)
                pom.url?.let { appendLine(it) }
                licenses.forEach { appendLine("License: $it") }
            }
        }

        val out = outputDir.get().asFile
        out.mkdirs()
        out.resolve("third_party_notices.txt").writeText(
            "SonarBridge includes the following third-party software.\n\n" + entries.joinToString("\n"),
        )
    }

    /** The few POM fields the notices need. */
    private class Pom(
        val coordinates: String,
        val parent: String?,
        val name: String?,
        val url: String?,
        val licenses: List<String>,
    ) {
        /** This POM's licences, or the nearest parent's when it states none. */
        fun licensesFollowingParents(all: Map<String, java.io.File>): List<String> {
            var current: Pom? = this
            while (current != null) {
                if (current.licenses.isNotEmpty()) return current.licenses
                current = current.parent?.let { all[it] }?.let(::read)
            }
            return emptyList()
        }

        companion object {
            fun read(file: java.io.File): Pom {
                val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
                fun child(parent: Element, tag: String): Element? = (0 until parent.childNodes.length).map { parent.childNodes.item(it) }
                    .firstOrNull { it is Element && it.tagName == tag } as Element?
                fun text(parent: Element?, tag: String): String? = parent?.let { child(it, tag) }?.textContent?.trim()?.ifEmpty { null }

                val parentElement = child(root, "parent")
                val parent = parentElement?.let { "${text(it, "groupId")}:${text(it, "artifactId")}:${text(it, "version")}" }
                val group = text(root, "groupId") ?: text(parentElement, "groupId")
                val version = text(root, "version") ?: text(parentElement, "version")
                val licenses = child(root, "licenses")?.let { list ->
                    (0 until list.childNodes.length).map { list.childNodes.item(it) }
                        .filterIsInstance<Element>()
                        .mapNotNull { license ->
                            val name = text(license, "name")
                            val url = text(license, "url")
                            listOfNotNull(name, url?.let { "($it)" }).joinToString(" ").ifEmpty { null }
                        }
                } ?: emptyList()
                return Pom("$group:${text(root, "artifactId")}:$version", parent, text(root, "name"), text(root, "url"), licenses)
            }
        }
    }
}

androidComponents.onVariants { variant ->
    val capitalized = variant.name.replaceFirstChar { it.uppercase() }
    val runtime = configurations.named("${variant.name}RuntimeClasspath")

    /** group:artifact:version of every external module the variant ships. */
    val moduleIds = runtime.map { configuration ->
        configuration.incoming.resolutionResult.allComponents
            .mapNotNull { it.id as? ModuleComponentIdentifier }
            .map { "${it.group}:${it.module}:${it.version}" }
            .distinct()
    }

    // Each module's own POM, resolved without transitive dependencies.
    val modulePoms = configurations.create("${variant.name}NoticePoms") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
        withDependencies { moduleIds.get().forEach { add(project.dependencies.create("$it@pom")) } }
    }

    // Parent POMs, for modules that inherit their licence (for example Guava's listenablefuture).
    val parentPoms = configurations.create("${variant.name}NoticeParentPoms") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
        withDependencies {
            modulePoms.files.mapNotNull { pom ->
                Regex("""<parent>\s*<groupId>([^<]+)</groupId>\s*<artifactId>([^<]+)</artifactId>\s*<version>([^<]+)</version>""")
                    .find(pom.readText())?.destructured?.let { (g, a, v) -> "$g:$a:$v" }
            }.distinct().forEach { add(project.dependencies.create("$it@pom")) }
        }
    }

    val task = tasks.register<ThirdPartyNoticesTask>("generate${capitalized}ThirdPartyNotices") {
        modules.set(moduleIds)
        poms.from(modulePoms, parentPoms)
    }
    variant.sources.assets?.addGeneratedSourceDirectory(task, ThirdPartyNoticesTask::outputDir)
}
