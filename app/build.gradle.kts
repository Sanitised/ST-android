plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val fallbackVersionName = "0.3.1"

android {
    namespace = "io.github.sanitised.st"
    compileSdk = 36

    fun envOrProp(name: String): String? =
        (findProperty(name) as String?)?.takeIf { it.isNotBlank() }
            ?: System.getenv(name)?.takeIf { it.isNotBlank() }

    fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    val releaseStoreFile = envOrProp("RELEASE_STORE_FILE")
    val releaseStorePassword = envOrProp("RELEASE_STORE_PASSWORD")
    val releaseKeyAlias = envOrProp("RELEASE_KEY_ALIAS")
    val releaseKeyPassword = firstNonBlank(envOrProp("RELEASE_KEY_PASSWORD"), releaseStorePassword)
    val releaseSigningAvailable = !releaseStoreFile.isNullOrBlank()
        && !releaseStorePassword.isNullOrBlank()
        && !releaseKeyAlias.isNullOrBlank()

    defaultConfig {
        applicationId = "io.github.sanitised.st"
        minSdk = 26
        targetSdk = 36

        val githubTag = run {
            val refType = System.getenv("GITHUB_REF_TYPE")
            val refName = System.getenv("GITHUB_REF_NAME")
            val ref = System.getenv("GITHUB_REF")
            when {
                refType == "tag" && !refName.isNullOrBlank() -> refName
                ref?.startsWith("refs/tags/") == true -> ref.removePrefix("refs/tags/")
                else -> null
            }
        }

        val versionNameOverride = firstNonBlank(
            envOrProp("VERSION_NAME"),
            githubTag
        )?.removePrefix("v")

        val versionCodeOverride = firstNonBlank(
            envOrProp("VERSION_CODE"),
            System.getenv("GITHUB_RUN_NUMBER")
        )

        versionCode = versionCodeOverride?.toIntOrNull() ?: 2
        versionName = versionNameOverride ?: fallbackVersionName
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            if (releaseSigningAvailable) {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-dev"
            applicationIdSuffix = ".dev"
            resValue("string", "app_name", "ST dev")
        }
        release {
            isMinifyEnabled = false
            if (releaseSigningAvailable) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.yaml:snakeyaml:2.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
