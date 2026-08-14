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

    buildTypes {
        debug {
            // 원본 apk가 debuggable=true 였음. 매니페스트 하드코딩 대신 여기서 관리.
            isDebuggable = true
        }
        release {
            isDebuggable = false
            isMinifyEnabled = false
        }
    }

    packaging {
        jniLibs {
            // 원본 매니페스트의 extractNativeLibs="true"에 해당.
            // GuestRunActivity가 nativeLibraryDir의 libqemu-system-aarch64.so /
            // libpodroid-launcher.so를 ProcessBuilder로 exec 하므로, 실제 파일로
            // 풀려 있어야 한다. false(AGP 기본값)로 두면 QEMU 기동이 깨진다.
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // decompile된 소스 기준으로는 androidx/외부 의존성 없음 (순수 android.* + org.json)
}
