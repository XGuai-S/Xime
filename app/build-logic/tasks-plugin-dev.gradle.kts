// 配置期捕获工作目录：doLast 内不能再引用 rootDir 等脚本属性（会捕获脚本实例，
// 导致 Gradle 配置缓存无法序列化任务动作）。File 本身可被序列化。
val projectRootDir = rootDir

// 执行命令的辅助逻辑必须是 doLast 内的本地函数：脚本顶层函数会随调用捕获
// 脚本实例，同样破坏配置缓存序列化。
tasks.register("clearPlugins", DefaultTask::class) {
    group = "plugin-dev"
    description = "Clear all plugin data from device (requires connected device with adb)"

    doLast {
        fun runCmd(command: String): String {
            return try {
                val process = ProcessBuilder(command.split(" "))
                    .directory(projectRootDir)
                    .redirectErrorStream(true)
                    .start()
                process.inputStream.bufferedReader().readText()
            } catch (e: Exception) {
                ""
            }
        }

        val packageName = "com.kingzcheung.xime"
        val pluginsDir = "/data/data/$packageName/files/plugins"

        println("=== Clearing Xime plugin data ===")

        val devicesCheck = runCmd("adb devices")
        if (!devicesCheck.contains("device")) {
            println("ERROR: No connected device detected")
        } else {
            println("Clearing plugin directory...")
            runCmd("adb shell rm -rf $pluginsDir")

            println("Clearing plugin config...")
            runCmd("adb shell rm -rf /data/data/$packageName/shared_prefs/plugin_*.xml")
            runCmd("adb shell rm -rf /data/data/$packageName/shared_prefs/plugins.xml")

            println("=== Done ===")
            println("Please restart Xime app to reload plugins")
        }
    }
}

tasks.register("uninstallApp", DefaultTask::class) {
    group = "plugin-dev"
    description = "Completely uninstall Xime app (clear all data)"

    doLast {
        fun runCmd(command: String): String {
            return try {
                val process = ProcessBuilder(command.split(" "))
                    .directory(projectRootDir)
                    .redirectErrorStream(true)
                    .start()
                process.inputStream.bufferedReader().readText()
            } catch (e: Exception) {
                ""
            }
        }

        val packageName = "com.kingzcheung.xime"

        println("=== Completely uninstalling Xime app ===")

        val devicesCheck = runCmd("adb devices")
        if (!devicesCheck.contains("device")) {
            println("ERROR: No connected device detected")
        } else {
            println("Uninstalling $packageName...")
            val result = runCmd("adb uninstall $packageName")
            println(result)

            println("=== Done ===")
            println("All app data cleared. Reinstall to start fresh.")
        }
    }
}
