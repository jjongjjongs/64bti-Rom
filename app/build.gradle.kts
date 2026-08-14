plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.singlevm"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.singlevm"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.2.0" // jadx로 확인된 원본 버전명

        ndk {
            // 원본 apk는 arm64-v8a 네이티브 라이브러리만 포함
            abiFilters += listOf("arm64-v8a")
        }
    }

    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // decompile된 소스 기준으로는 androidx/외부 의존성 없음 (순수 android.* + org.json)
}
