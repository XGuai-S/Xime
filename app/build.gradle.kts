import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

apply(from = "build-logic/tasks-native.gradle.kts")
apply(from = "build-logic/tasks-plugin-dev.gradle.kts")

// 获取 Git 提交哈希（短哈希）。
// 为什么不跑 `git rev-parse`：配置期启动外部进程会被 Gradle 配置缓存拒绝；
// providers.exec 又是 Provider，喂不了要求 String 的 buildConfigField。
// 故直接解析 .git（纯文件读取，配置缓存允许且会纳入输入指纹）：
// .git/HEAD → refs/heads/<branch>（松散引用）→ packed-refs（打包引用兜底）。
fun gitShortHash(): String {
    return try {
        val gitDir = File(rootDir, ".git")
        val headText = File(gitDir, "HEAD").readText().trim()
        if (!headText.startsWith("ref: ")) {
            // detached HEAD：内容即完整 SHA
            return headText.take(7)
        }
        val ref = headText.removePrefix("ref: ").trim()
        val refFile = File(gitDir, ref.replace('/', File.separatorChar))
        val full = if (refFile.isFile) {
            refFile.readText().trim()
        } else {
            File(gitDir, "packed-refs").takeIf { it.isFile }
                ?.readLines()
                ?.firstOrNull { line ->
                    !line.startsWith("#") && !line.startsWith("^") && line.endsWith(" $ref")
                }
                ?.substringBefore(' ')
                ?: return "unknown"
        }
        if (full.length >= 7) full.substring(0, 7) else "unknown"
    } catch (e: Exception) {
        "unknown"
    }
}

// 获取构建时间已移除：构建时刻会写入 BuildConfig 进而进入 classes.dex，
// 破坏 F-Droid 可复现构建（不同环境构建时间不同导致产物不一致）。

// 加载签名配置
val keystorePropertiesFile = rootProject.file("app/keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

// 本地构建默认只打一个架构：`local.properties`（不入库）里写 `xime.abi=arm64-v8a` 生效，
// 省掉其余 ABI 的 native 编译与重复打包（release 从约 14 分钟降到数分钟）。
// 该文件在 CI 上不存在 → 四个 ABI + universal 全量，发布产物不变。
val localAbi: String? = Properties().run {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(file.inputStream())
    getProperty("xime.abi")?.trim()?.takeIf { it.isNotEmpty() }
}

android {
    namespace = "com.kingzcheung.xime"
    compileSdk = 37
    // 平台按小版本安装（SDK 里是 platforms;android-37.2），不写 minor 时 AGP 会去找 android-37.0
    compileSdkMinor = 2

    // JVM 单测中未 mock 的 Android 框架方法（如 android.util.Log）返回默认值而非抛异常，
    // 使服务层状态机（如语音收尾流程）可直接实例化测试
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    defaultConfig {
        applicationId = "com.kingzcheung.xime"
        minSdk = 28
        targetSdk = 35
        versionCode = 20261016
        versionName = "3.2.2"


        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // NDK 配置：本地只编译 xime.abi 指定的架构，未指定时全量
        ndk {
            abiFilters += localAbi?.let { listOf(it) }
                ?: listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }

        // 构建信息
        buildConfigField("String", "GIT_HASH", "\"${gitShortHash()}\"")
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                // 优先使用 storeFile（已存在的 keystore 文件）；否则解码 keyBase64。
                // keyBase64 方式用于 CI/F-Droid 从 secrets 注入签名。
                val storeFileProp = keystoreProperties.getProperty("storeFile")?.let { file(it) }
                storeFile = if (storeFileProp != null && storeFileProp.exists()) {
                    storeFileProp
                } else {
                    keystoreProperties.getProperty("keyBase64")?.let { keyBase64 ->
                        val ks = File(layout.buildDirectory.get().asFile, "release-keystore.jks")
                        ks.writeBytes(Base64.getDecoder().decode(keyBase64.replace("\n", "").trim()))
                        ks
                    }
                }
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 有 keystore.properties 时使用 release 密钥（CI 通过 secrets 注入，走这条）；
            // 本地没有该文件时回退到 debug 密钥，保证 assembleRelease 产物可直接安装，
            // 否则 AGP 会产出未签名 APK（apksigner 报 DOES NOT VERIFY），无法安装。
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xunused")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    // NDK 构建配置
    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // 打包配置
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            // 剔除依赖带入、但本应用从不读取的资源（合计约 3.3MB）。
            // 这些都不是代码依赖，删除不会影响功能，只是把没人用的体积还给用户：
            //
            // 1. `tables/Transcoder_*.bin`（约 2.9MB）：字符集转码映射表，随 Sora 编辑器的
            //    language-textmate 传递进来。本应用只用它做 YAML 语法高亮，YAML 本身是
            //    UTF-8，永不触发 Big5/GBK/日文/韩文转码；代码中没有任何 Transcoder 引用。
            // 2. `src/org/eclipse/jdt/**`（约 41KB）：org.eclipse.jdt.annotation 的 .java
            //    源码。注解库只需 class 文件，源码进 APK 属于上游打包疏漏。
            // 3. `org/apache/commons/codec/language/bm/**`（约 234KB）：Apache Commons Codec
            //    的语音编码规则表，仅 DoubleMetaphone/ColognePhonetic 等语言编码器加载。
            //    okhttp 只用 Codec 做 Base64/Hex，不碰语言编码器。
            excludes += setOf(
                "tables/**",
                "src/**",
                "org/apache/commons/codec/language/**",
                // 构建元数据，运行时无用（Kotlin 反射不读它）
                "kotlin-tooling-metadata.json",
                "DebugProbesKt.bin",
                "META-INF/INDEX.LIST",
                // 依赖元数据块，F-Droid 可复现构建不需要（见下方 dependenciesInfo）
                "META-INF/DEPENDENCIES",
            )
        }
    }
    ndkVersion = "29.0.14206865"

    // F-Droid 一致性验证：不在 APK 中写入依赖元数据（Dependency metadata 签名块）。
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // 测试 classpath 包含 main assets，使 T9Decoder() 无参构造可加载 pinyin_lm.bin
    sourceSets {
        getByName("test") {
            resources.srcDirs("src/main/assets")
        }
    }
    lint {
        checkReleaseBuilds = false
        checkGeneratedSources = false
        abortOnError = false
        checkDependencies = true
    }

    // 分架构打包：本地指定 xime.abi 时改由 ndk.abiFilters 收敛（AGP 禁止它与 splits 同时设置），
    // 未指定时才按架构拆分出四个 ABI + universal
    splits {
        abi {
            isEnable = localAbi == null
            reset()
            if (localAbi == null) {
                include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
                isUniversalApk = true
            }
        }
    }
}

android.applicationVariants.all {
    val appName = "Xime"
    outputs.all {
        val abi = filters.find { it.filterType.toString() == "ABI" }?.identifier
            ?: localAbi ?: "universal"
        (this as BaseVariantOutputImpl).outputFileName = "$appName-$versionName-$abi.apk"
    }
}

// Nightly 构建通过 androidComponents API 覆盖 versionCode/versionName
androidComponents {
    onVariants { variant ->
        val vc = project.findProperty("versionCode")?.toString()?.toIntOrNull()
        val vn = project.findProperty("versionName")?.toString()
        if (vc != null && vn != null) {
            variant.outputs.forEach { output ->
                output.versionCode.set(vc)
                output.versionName.set(vn)
            }
        }
    }
}
dependencies {
    implementation(project(":plugin-core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Kotlin stdlib - CRITICAL for plugin compatibility
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
    implementation(libs.kotlinx.coroutines.core)

    // Jetpack Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.activity.compose)

    // Material Icons
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // SavedState
    implementation(libs.androidx.savedstate)

    // Coil (Image Loading)
    implementation(libs.coil)

    // OkHttp for WebSocket and model download
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    // okhttp-sse for SSE stream parsing (plugin streaming API)
    implementation("com.squareup.okhttp3:okhttp-sse:5.4.0")
    // Apache Commons Compress for tar.bz2 extraction
    implementation("org.apache.commons:commons-compress:1.28.0")

    // Kaml for YAML parsing
    implementation(libs.kaml)

    // Autofill inline suggestions (API 30+)
    implementation(libs.androidx.autofill)

    // exp4j for calculator expression evaluation
    implementation(libs.exp4j)

    // ZXing for QR code generation
    implementation("com.google.zxing:core:3.5.4")

    // Ktor embedded server for wireless import
    implementation("io.ktor:ktor-server-core:3.6.0")
    implementation("io.ktor:ktor-server-cio:3.6.0")
    implementation(libs.kotlinx.serialization.json)

    // Room 3.0 (SQLite)
    implementation(libs.androidx.room3.runtime)
    ksp(libs.androidx.room3.compiler)

    // Sora Code Editor for YAML viewing/editing
    implementation(platform("io.github.rosemoe:editor-bom:0.24.6"))
    implementation("io.github.rosemoe:editor")
    implementation("io.github.rosemoe:language-textmate")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.mockito:mockito-core:5.24.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.4.0")
    // JVM 单测使用真实 org.json 实现（android.jar 内为抛异常的 stub）
    testImplementation("org.json:json:20240303")

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.concurrent:concurrent-futures:1.2.0")
}

// Align concurrent-futures version: espresso 3.7.0 requires 1.2.0
dependencies {
    constraints {
        implementation("androidx.concurrent:concurrent-futures:1.2.0") {
            because("test dependencies (espresso 3.7.0) require 1.2.0")
        }
    }
}
