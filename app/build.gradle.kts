import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val ndkVersionPinned = "28.2.13676358"
val cmakeVersionPinned = "3.31.6"
val engineAbis = listOf("arm64-v8a")
val prebuiltRoot = rootProject.file("native/prebuilt")
val ffmpegLibs = listOf("libavcodec.so", "libavformat.so", "libavfilter.so", "libavutil.so", "libswscale.so", "libswresample.so")

// Release signing comes from keystore.properties (never committed). Without it
// the release APK is produced unsigned and verify_apk.sh reports that.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.kuyamcliff.compressor"
    compileSdk = 36
    ndkVersion = ndkVersionPinned

    defaultConfig {
        applicationId = "com.kuyamcliff.compressor"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += engineAbis }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }
        buildConfigField("String", "FFMPEG_VERSION", "\"9.0.2\"")
        buildConfigField("boolean", "BENCHMARK", "false")
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            externalNativeBuild { cmake { arguments += "-DVC_VERBOSE_LOGS=ON" } }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        create("benchmark") {
            initWith(getByName("release"))
            applicationIdSuffix = ".benchmark"
            versionNameSuffix = "-benchmark"
            matchingFallbacks += "release"
            // Benchmarks need an installable build; reuse the debug key when no release key exists.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            buildConfigField("boolean", "BENCHMARK", "true")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../native/engine/CMakeLists.txt")
            version = cmakeVersionPinned
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(layout.buildDirectory.dir("generated/ffmpegJniLibs"))
        }
    }

    packaging {
        jniLibs {
            // Store .so uncompressed and page-aligned so they load in place.
            useLegacyPackaging = false
        }
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*", "/META-INF/NOTICE*")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = false
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// ---------------------------------------------------------------------------
// Native dependencies: FFmpeg is built from source by native/third_party/build_deps.sh
// (see BUILD.md / FFMPEG.md). These tasks verify the prebuilt output exists and
// stage the shared libraries for packaging.
val verifyFfmpegPrebuilt by tasks.registering {
    group = "native"
    description = "Checks that the FFmpeg prebuilt libraries exist for every packaged ABI."
    doLast {
        engineAbis.forEach { abi ->
            ffmpegLibs.forEach { lib ->
                val f = File(prebuiltRoot, "android-$abi/lib/$lib")
                if (!f.exists()) {
                    throw GradleException(
                        "Missing ${f.path}. Run: ./gradlew :app:buildNativeDeps (or native/third_party/build_deps.sh android-$abi)"
                    )
                }
            }
        }
    }
}

val buildNativeDeps by tasks.registering(Exec::class) {
    group = "native"
    description = "Builds FFmpeg and its codec libraries from source for all packaged ABIs (slow)."
    workingDir = rootProject.projectDir
    commandLine("bash", "-c", engineAbis.joinToString(" && ") { "native/third_party/build_deps.sh android-$it" })
}

val stageFfmpegLibs by tasks.registering(Copy::class) {
    group = "native"
    dependsOn(verifyFfmpegPrebuilt)
    engineAbis.forEach { abi ->
        from(File(prebuiltRoot, "android-$abi/lib")) {
            include(ffmpegLibs)
            into(abi)
        }
    }
    into(layout.buildDirectory.dir("generated/ffmpegJniLibs"))
}

tasks.named("preBuild") { dependsOn(stageFfmpegLibs) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.documentfile)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}
