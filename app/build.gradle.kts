import java.io.File

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.rainy.screenshot"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rainy.screenshot"
        minSdk = 31
        targetSdk = 36
        versionCode = 7
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        val keystoreFile = rootProject.file("release.jks")
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = keystoreFile

                storePassword = System.getenv("KEYSTORE_PASSWORD")
                    ?.takeIf { it.isNotBlank() }
                    ?: throw GradleException("KEYSTORE_PASSWORD env var not set or empty — cannot sign release")
                keyAlias = System.getenv("KEYSTORE_ALIAS")
                    ?.takeIf { it.isNotBlank() }
                    ?: throw GradleException("KEYSTORE_ALIAS env var not set or empty — cannot sign release")
                keyPassword = System.getenv("KEY_PASSWORD")
                    ?.takeIf { it.isNotBlank() }
                    ?: throw GradleException("KEY_PASSWORD env var not set or empty — cannot sign release")
            }
        } else if (System.getenv("CI") != null) {

            create("release") {
                val debugKeystore = file("${System.getProperty("user.home")}/.android/debug.keystore")
                storeFile = debugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }

    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false

            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

if (System.getProperty("os.arch") == "aarch64") {
    tasks.register("guardReleaseResources") {
        dependsOn("optimizeReleaseResources")
        doLast {
            val linkedAp = layout.buildDirectory
                .file("intermediates/linked_resources_binary_format/release/processReleaseResources/linked-resources-binary-format-release.ap_")
                .get().asFile
            val optimizedDir = layout.buildDirectory
                .dir("intermediates/optimized_processed_res/release/optimizeReleaseResources")
                .get().asFile
            val optimizedAp = File(optimizedDir, "resources-release-optimize.ap_")

            if (optimizedAp.exists() && optimizedAp.length() > 0) {
                val zipList = providers.exec {
                    commandLine("unzip", "-l", optimizedAp.absolutePath)
                    isIgnoreExitValue = true
                }.standardOutput.asText.get()

                val hasManifest = zipList.contains("AndroidManifest.xml")
                val hasArsc = zipList.contains("resources.arsc")
                val hasRes = zipList.contains("res/")

                if (hasManifest && hasArsc && hasRes) {
                    logger.lifecycle("GuardReleaseResources: optimized .ap_ OK (${optimizedAp.length()} bytes)")
                    return@doLast
                }
                logger.warn("GuardReleaseResources: optimized .ap_ incomplete (manifest=$hasManifest arsc=$hasArsc res=$hasRes)")
            } else {
                logger.warn("GuardReleaseResources: optimized .ap_ missing or empty")
            }

            if (!linkedAp.exists() || linkedAp.length() == 0L) {
                throw GradleException("GuardReleaseResources: linked .ap_ also missing or empty — cannot recover")
            }

            optimizedDir.mkdirs()
            linkedAp.copyTo(optimizedAp, overwrite = true)

            val verifyList = providers.exec {
                commandLine("unzip", "-l", optimizedAp.absolutePath)
                isIgnoreExitValue = true
            }.standardOutput.asText.get()

            val vManifest = verifyList.contains("AndroidManifest.xml")
            val vArsc = verifyList.contains("resources.arsc")
            val vRes = verifyList.contains("res/")

            if (!vManifest || !vArsc || !vRes) {
                throw GradleException("GuardReleaseResources: fallback copy verification failed (manifest=$vManifest arsc=$vArsc res=$vRes)")
            }

            logger.lifecycle("GuardReleaseResources: fallback — copied linked .ap_ → optimized .ap_ (${optimizedAp.length()} bytes, path shortening skipped)")
        }
    }

    project.tasks.matching { it.name == "packageRelease" }.configureEach {
        dependsOn("guardReleaseResources")
    }
}

if (System.getProperty("os.arch") == "aarch64") {
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "com.android.tools.build" && requested.name == "aapt2") {
                useTarget("com.android.tools.build:aapt2:${'$'}{requested.version}:linux-aarch64")
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
}
