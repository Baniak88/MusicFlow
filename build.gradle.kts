import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val sdkCompile = catalog.findVersion("compileSdk").get().requiredVersion.toInt()
val sdkMin = catalog.findVersion("minSdk").get().requiredVersion.toInt()

subprojects {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-Xannotation-default-target=param-property",
                "-opt-in=kotlin.RequiresOptIn",
            )
        }
    }
    tasks.withType<JavaCompile>().configureEach {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    // Shared configuration for every Android module (application and library).
    val configureAndroid: Project.() -> Unit = {
        extensions.configure<com.android.build.gradle.BaseExtension> {
            compileSdkVersion(sdkCompile)
            defaultConfig {
                minSdk = sdkMin
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
                isCoreLibraryDesugaringEnabled = true
            }
            testOptions {
                unitTests.isIncludeAndroidResources = true
                unitTests.isReturnDefaultValues = true
            }
        }
        dependencies.add("coreLibraryDesugaring", catalog.findLibrary("desugar-jdk-libs").get())
    }
    plugins.withId("com.android.library") { configureAndroid() }
    plugins.withId("com.android.application") { configureAndroid() }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }

    tasks.withType<Test>().configureEach {
        // Modules such as pure UI features may legitimately have no JVM tests yet.
        failOnNoDiscoveredTests.set(false)
        // Live provider tests hit real YouTube Music endpoints. They are opt-in
        // (-PliveTests) so CI is not flaky; see TESTING.md.
        systemProperty("musicflow.liveTests", project.findProperty("liveTests")?.toString() ?: "false")
        maxHeapSize = "2g"
        testLogging { events("failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    }
}
