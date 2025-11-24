plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Usar un directorio de build alternativo en Windows para evitar locks en R.jar
// Cambiar temporalmente el directorio para desbloquear builds si hay archivos en uso
layout.buildDirectory.set(file("build_win2"))

android {
    namespace = "cl.shoppersa"
    compileSdk = 36

    defaultConfig {
        applicationId = "cl.shoppersa"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Xano: base para endpoints de usuarios (workspace provisto)
        buildConfigField("String", "XANO_AUTH_BASE",  "\"https://x8ki-letl-twmt.n7.xano.io/api:W89rt-YX\"")
        buildConfigField("String", "XANO_STORE_BASE", "\"https://x8ki-letl-twmt.n7.xano.io/api:QlBTvlvV\"")
        buildConfigField("int", "XANO_TOKEN_TTL_SEC", "86400")
        buildConfigField("String", "XANO_UPLOAD_BASE", "\"https://x8ki-letl-twmt.n7.xano.io/api:rP0BKx0s/\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // Variante alternativa para desarrollo: usa el pipeline de release pero es depurable
        create("dev") {
            initWith(getByName("release"))
            isDebuggable = true
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            // Fallbacks por si alguna dependencia solo publica "debug" o "release"
            matchingFallbacks += listOf("release", "debug")
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
        viewBinding = true
        buildConfig = true
    }
}

// Sin hooks personalizados para clean ni processDebugResources

dependencies {
    // AndroidX + Material
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")



    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Corrutinas
    implementation(libs.coroutines.android)
    implementation("androidx.viewpager2:viewpager2:${libs.versions.viewpager2.get()}")
    implementation(libs.androidx.viewpager2)

    // Imágenes
    implementation(libs.coil)

    // Tests
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
}
