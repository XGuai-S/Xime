plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.kingzcheung.xime.plugin.core"
    compileSdk = 37
    // 平台按小版本安装（SDK 里是 platforms;android-37.2），不写 minor 时 AGP 会去找 android-37.0
    compileSdkMinor = 2

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }
}

dependencies {
    constraints {
        implementation("org.jetbrains:annotations:23.0.0")
    }
    
    api(kotlin("stdlib"))
    api(libs.androidx.core.ktx)
    api(libs.kotlinx.coroutines.core)

    api(platform(libs.androidx.compose.bom))
    api("androidx.compose.runtime:runtime")
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.material3)
    api(libs.androidx.activity.compose)
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.lifecycle.runtime.compose)

    // JS 脚本插件运行时（QuickJS，main.js 沙箱执行）
    api("io.github.dokar3:quickjs-kt-android:1.0.15")
    // manifest.json 解析（kotlinx Json 宽松模式：注释 + 尾逗号；不用 kaml，插件链路与 JS 生态对齐）
    implementation(libs.kotlinx.serialization.json)
    
    testImplementation("junit:junit:4.13.2")
}

// quickjs-kt 不提供纯 JVM 的 Android 替代：本地单元测试用 -jvm artifact（不含 .so）
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.github.dokar3:quickjs-kt-android"))
            .using(module("io.github.dokar3:quickjs-kt-jvm:1.0.15"))
    }
}

// ── JS 插件编译产物（build/plugin-js）──
//
// plugin.core.js.* 的单测直接载入 `xipm build` 产出的 main.js（与发布同源），
// 该产物不入库。CI 在跑 gradle 之前手动执行过这两条命令（见 .github/workflows/{build,test}.yml），
// 但本地直接 `./gradlew :plugin-core:test` 时几乎必然漏掉这一步，表现为
// 89 个用例集体 AssertionError("找不到 build/plugin-js/...") —— 报错点离真实原因很远。
// 这里把它做成显式 Gradle 依赖 + 增量检查，不再依赖开发者记得先跑 xipm build。
val buildPluginJs = tasks.register("buildPluginJs") {
    group = "build"
    description = "编译 JS 插件（TS → build/plugin-js/<name>/main.js），plugin-core 单测依赖该产物"

    val rootDir = rootProject.projectDir
    val pluginsDir = rootDir.resolve("plugins")
    val xipmProject = rootDir.resolve("tools/xime-plugin")
    val fixtureDir = xipmProject.resolve("test-fixture")
    val outDir = rootDir.resolve("build/plugin-js")

    inputs.dir(pluginsDir).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(fixtureDir).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(xipmProject.resolve("src")).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(outDir)

    doLast {
        val isWindows = System.getProperty("os.name").contains("Windows", ignoreCase = true)
        fun exeName(base: String) = if (isWindows) "$base.exe" else base

        fun findOnPath(name: String): File? {
            val separator = if (isWindows) ';' else ':'
            val extensions = if (isWindows) listOf(".exe", ".cmd", ".bat", "") else listOf("")
            for (dir in System.getenv("PATH").orEmpty().split(separator)) {
                if (dir.isBlank()) continue
                for (ext in extensions) {
                    val candidate = File(dir, name + ext)
                    if (candidate.isFile) return candidate
                }
            }
            return null
        }

        fun run(workDir: File, command: List<String>) {
            val process = ProcessBuilder(command)
                .directory(workDir)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            if (code != 0) {
                throw GradleException(
                    "命令执行失败（退出码 $code）：${command.joinToString(" ")}\n工作目录：$workDir\n$output"
                )
            }
        }

        // 1) 定位 xipm：XIPM 环境变量 → PATH → cargo 编译产物
        var xipm: File? = System.getenv("XIPM")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)?.takeIf { it.isFile }
            ?: findOnPath("xipm")
            ?: xipmProject.resolve("target/release/${exeName("xipm")}").takeIf { it.isFile }

        // 2) 都没有就用 cargo 现编（与 CI 的 `cargo run -- build` 等价）
        if (xipm == null) {
            val cargo = findOnPath("cargo")
            if (cargo != null) {
                logger.lifecycle("未找到 xipm，改用 cargo 编译：${xipmProject.path}")
                run(xipmProject, listOf(cargo.absolutePath, "build", "--release"))
                xipm = xipmProject.resolve("target/release/${exeName("xipm")}").takeIf { it.isFile }
            }
        }
        if (xipm == null) {
            throw GradleException(
                """
                无法定位 xipm：plugin-core 的 JS 插件单测需要 build/plugin-js/<name>/main.js。
                请任选其一：
                  1) 安装 Rust 工具链后执行 `cd tools/xime-plugin && cargo build --release`
                  2) 把 xipm 可执行文件放进 PATH
                  3) 设置环境变量 XIPM 指向 xipm 可执行文件
                """.trimIndent()
            )
        }

        // 3) 编译全部插件 + 测试用 fixture（与 CI 的两条命令保持一致）
        run(
            rootDir,
            listOf(xipm.absolutePath, "build", "--all", "--plugins-dir", pluginsDir.absolutePath,
                "--out", outDir.absolutePath)
        )
        run(
            xipmProject,
            listOf(xipm.absolutePath, "build", "test-fixture", "--out", outDir.absolutePath)
        )
    }
}

tasks.withType<Test>().configureEach {
    dependsOn(buildPluginJs)
}
