plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "space.linuxct.nothing.icongen"
    compileSdk = 36

    defaultConfig {
        applicationId = "space.linuxct.nothing.icongen"
        minSdk = 22
        targetSdk = 36

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // VersionCode: set by CI via CI_VERSION_CODE env (monotonically increasing)
        val envVersionCode = System.getenv("CI_VERSION_CODE")?.toIntOrNull()
        versionCode = envVersionCode ?: 1
        // VersionName: set by CI via CI_VERSION_NAME env (e.g. "1.0.0.5")
        versionName = System.getenv("CI_VERSION_NAME") ?: "1.0.0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf("META-INF/versions/9/OSGI-INF/**", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        }
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += setOf("**/libaapt2.so", "**/libaapt.so")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    implementation(files("libs/apksig-8.11.0.jar"))
    implementation(files("libs/bcpkix-jdk18on-1.80.2.jar"))
    implementation(files("libs/bcprov-jdk18on-1.80.2.jar"))
    implementation(files("libs/bcutil-jdk18on-1.80.2.jar"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    }
    implementation("org.conscrypt:conscrypt-android:2.8.0")
}

// Robolectric supplies desktop Conscrypt. Android's JNI provider cannot run on the host JVM.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    exclude(group = "org.conscrypt", module = "conscrypt-android")
}

private fun runCommand(vararg args: String) {
    val proc = ProcessBuilder(*args)
        .redirectInput(ProcessBuilder.Redirect.PIPE)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .start()
    proc.waitFor()
    if (proc.exitValue() != 0) {
        val stderr = proc.errorStream.bufferedReader().readText().trim()
        throw GradleException("Command failed: ${args.joinToString(" ")}\n$stderr")
    }
}

/**
 * Pulls icon pack resources ZIP from device, compiles APK using host SDK aapt2,
 * signs with debug keystore, outputs to build/outputs/apk-debug/icon-pack.apk.
 */
tasks.register("buildIconPackApk") {
    description = "Pull icon pack resources from device, compile APK using host SDK aapt2, and sign with debug keystore"
    group = "icon-pack"

    doLast {
        val home = System.getProperty("user.home")
        val sdkDir = "$home/Android/Sdk"
        val aapt2 = "$sdkDir/build-tools/36.0.0/aapt2"
        val apksigner = "$sdkDir/build-tools/36.0.0/apksigner"
        val androidJar = "$sdkDir/platforms/android-36/android.jar"

        val srcDir = file("build/iconpack_resources")
        val compileDir = file("build/iconpack_compiled")
        val outDir = file("build/outputs/apk-debug")
        srcDir.mkdirs()
        compileDir.mkdirs()
        outDir.mkdirs()

        // 1. Pull ZIP from device (parent of app's external files dir)
        println("Pulling iconpack_resources.zip from device...")
        runCommand("adb", "pull", "/sdcard/Android/data/space.linuxct.nothing.icongen/files/iconpack_resources.zip", srcDir.absolutePath)

        val zipFile = file("$srcDir/iconpack_resources.zip")
        if (!zipFile.exists()) {
            throw GradleException("iconpack_resources.zip not found on device. Run generate first from the app.")
        }

        // 2. Unzip to source dir (unzips AndroidManifest.xml + res/ into srcDir)
        println("Unzipping resources...")
        runCommand("unzip", "-o", zipFile.absolutePath, "-d", srcDir.absolutePath)

        // 3. Compile resources with aapt2: --dir produces a .zip (not .flat files)
        println("Compiling resources with aapt2...")
        val resZipCompiled = file("$compileDir/resources.zip")
        runCommand(aapt2, "compile", "--dir", "$srcDir/res", "-o", resZipCompiled.absolutePath)

        // 4. Link APK: use compiled resources.zip with -I flag
        val apkFile = file("$outDir/icon-pack.apk")
        println("Linking APK...")
        val linkArgs = mutableListOf<String>().apply {
            addAll(listOf(aapt2, "link", "-o", apkFile.absolutePath))
            add("--manifest")
            add("$srcDir/AndroidManifest.xml")
            add("--min-sdk-version")
            add("33")
            add("--target-sdk-version")
            add("35")
            add("-I")
            add(androidJar)
            add("-I")
            add(resZipCompiled.absolutePath)
            add("-A")
            add("$srcDir")
        }
        runCommand(*linkArgs.toTypedArray())

        // 6. Sign with debug keystore
        val keyStore = file("$home/.android/debug.keystore")
        if (keyStore.exists()) {
            println("Signing APK with debug keystore...")
            runCommand(
                apksigner, "sign",
                "--ks", keyStore.absolutePath,
                "--ks-pass", "pass:android",
                "--ks-key-alias", "androiddebugkey",
                "--key-pass", "pass:android",
                apkFile.absolutePath
            )
        } else {
            println("Warning: No debug keystore found at $keyStore, APK may be unsigned")
        }

        println("APK built successfully at: ${apkFile.absolutePath} (${apkFile.length()} bytes)")
    }
}

/**
 * Installs the icon pack APK generated by buildIconPackApk.
 */
tasks.register("installIconPackApk") {
    description = "Install the icon pack APK from build/outputs/apk-debug/icon-pack.apk via adb"
    group = "icon-pack"
    dependsOn("buildIconPackApk")

    doLast {
        val apkPath = file("build/outputs/apk-debug/icon-pack.apk")
        if (!apkPath.exists()) {
            throw GradleException("APK not found at ${apkPath.absolutePath}. Run buildIconPackApk first.")
        }

        println("Installing APK from: ${apkPath.absolutePath}")
        runCommand("adb", "install", "-r", apkPath.absolutePath)
        println("APK installed successfully!")
    }
}