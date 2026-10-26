plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.geanalves.tvhome"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.geanalves.tvhome"
        minSdk = 23
        targetSdk = 34
        // Cada compilação no GitHub ganha um número maior, para a TV aceitar a atualização.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    signingConfigs {
        // Chave fixa para que as atualizações instalem por cima da versão anterior.
        create("fixa") {
            storeFile = file("inicio-tv.jks")
            storePassword = "iniciotv"
            keyAlias = "inicio"
            keyPassword = "iniciotv"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixa")
        }
        debug {
            signingConfig = signingConfigs.getByName("fixa")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
