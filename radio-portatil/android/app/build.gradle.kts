plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pt.radioportatil"
    compileSdk = 35

    defaultConfig {
        applicationId = "pt.radioportatil"
        minSdk = 26
        targetSdk = 35
        // cada compilação no GitHub tem um número maior, para atualizar por cima
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0.$versionCode"
    }

    signingConfigs {
        // Chave de assinatura própria da app (não é segredo: serve só para que
        // cada nova versão do APK possa ser instalada por cima da anterior).
        create("radio") {
            storeFile = file("radio-portatil.keystore")
            storePassword = "radioportatil"
            keyAlias = "radio"
            keyPassword = "radioportatil"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("radio")
        }
        debug {
            signingConfig = signingConfigs.getByName("radio")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = false
        textReport = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.google.zxing:core:3.5.3")
}
