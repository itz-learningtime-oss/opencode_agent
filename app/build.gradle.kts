plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.opencode.term"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.opencode.term"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // Target device (Samsung Galaxy M02 class) is 32-bit ARM only.
        ndk {
            abiFilters += "armeabi-v7a"
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=none")
                cFlags += listOf("-Wall", "-Wextra", "-fvisibility=hidden")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug") // replaced by real keystore for distribution
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}

// ---------------------------------------------------------------------------
// verifyAbi: fails the build if any unexpected ABI directory ends up in the APK.
// ---------------------------------------------------------------------------
androidComponents {
    onVariants { variant ->
        val variantName = variant.name
        tasks.register("verifyAbi${variantName.replaceFirstChar { it.uppercase() }}") {
            group = "verification"
            description = "Verifies that the packaged APK contains only armeabi-v7a native libraries."
            doLast {
                val apkDir = layout.buildDirectory.dir("outputs/apk/${variant.name}").get().asFile
                val apks = apkDir.listFiles { f -> f.name.endsWith(".apk") } ?: emptyArray()
                require(apks.isNotEmpty()) { "No APK produced in $apkDir" }
                for (apk in apks) {
                    java.util.zip.ZipFile(apk).use { zip ->
                        val abiDirs = zip.entries().asSequence()
                            .filter { !it.isDirectory && it.name.startsWith("lib/") }
                            .map { it.name.removePrefix("lib/").substringBefore('/') }
                            .toSet()
                        check(abiDirs.all { it == "armeabi-v7a" }) {
                            "APK ${apk.name} contains unexpected ABIs: $abiDirs"
                        }
                    }
                }
            }
        }
    }
}
