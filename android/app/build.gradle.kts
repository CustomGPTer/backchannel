import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signingProps = Properties().apply {
    val f = rootProject.file("keys/signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Tor and its bridge helper are shipped as ready-built programs inside jar files.
// They are unpacked into the app's native library folder at build time.
val torLibs: Configuration by configurations.creating { isTransitive = false }

android {
    namespace = "uk.jj.backchannel"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "uk.jj.backchannel"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "0.3"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        create("release") {
            if (signingProps.getProperty("storeFile") != null) {
                storeFile = rootProject.file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingProps.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        // Required by the Signal library.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            // Tor runs as a separate program, so its file must be unpacked on the phone.
            useLegacyPackaging = true
            excludes += "**/libsignal_jni_testing.so"
        }
        resources {
            // Desktop builds of the Signal library that ride along in its jar.
            excludes += setOf(
                "libsignal_jni*.so", "libsignal_jni*.dylib", "signal_jni*.dll",
                "META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                // Data files for parts of the crypto library the app does not use.
                "org/bouncycastle/**", "DebugProbesKt.bin", "kotlin-tooling-metadata.json",
            )
        }
    }
    androidResources {
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~:!libsignal-testing.md"
    }
    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("torLibs"))

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                it.maxHeapSize = "2g"
                // Lets the screen tests save pictures of each screen to app/build/shots.
                it.systemProperty("roborazzi.test.record", "true")
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            }
        }
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

val unpackTorLibs = tasks.register<Sync>("unpackTorLibs") {
    from({ torLibs.map { zipTree(it) } }) {
        include("arm64-v8a/**", "armeabi-v7a/**")
    }
    into(layout.buildDirectory.dir("torLibs"))
}
tasks.named("preBuild") { dependsOn(unpackTorLibs) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-process:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Encryption: the Signal Protocol, from Signal.
    implementation("org.signal:libsignal-android:0.108.1")
    // Encrypted database.
    implementation("net.zetetic:sqlcipher-android:4.20.0")
    implementation("androidx.sqlite:sqlite-ktx:2.5.2")
    implementation("androidx.sqlite:sqlite-framework:2.5.2")
    // Tor, from the Briar project.
    implementation("org.briarproject:onionwrapper-android:0.1.7")
    torLibs("org.briarproject:tor-android:0.4.9.15")
    torLibs("org.briarproject:lyrebird-android:0.6.2")
    // Slow password hashing (Argon2id).
    implementation("org.bouncycastle:bcprov-jdk18on:1.82")
    // QR codes.
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") { isTransitive = false }

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.7.0")
    // Screen tests: draw each screen on the computer and save a picture of it.
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.50.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.50.0")
    // Desktop builds of Tor, so the start-up sequence can be checked off the phone (Linux only).
    testImplementation("org.briarproject:onionwrapper-java:0.1.7")
    testRuntimeOnly("org.briarproject:tor-linux:0.4.9.15")
    testRuntimeOnly("org.briarproject:lyrebird-linux:0.6.2")
}
