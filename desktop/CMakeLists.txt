cmake_minimum_required(VERSION 3.20)
project(xime-desktop VERSION 3.4.70 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 本机源码树：app/src/main/jni（含 librime、T9 插件与全部捆绑依赖）。
get_filename_component(XIME_JNI_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/jni" ABSOLUTE)
if(NOT EXISTS "${XIME_JNI_DIR}/librime/CMakeLists.txt")
  message(FATAL_ERROR "找不到 librime 源码：${XIME_JNI_DIR}/librime")
endif()

option(XIME_BUILD_RIME "从捆绑源码构建 librime（含 T9 定制）" ON)

if(XIME_BUILD_RIME)
  include("${CMAKE_CURRENT_SOURCE_DIR}/cmake/RimeWindows.cmake")
endif()

# 插件宿主：内置 QuickJS（vendored 源码）。C 源，-O0 保证本机编译速度。
add_library(xime-quickjs STATIC
  third_party/quickjs/quickjs.c
  third_party/quickjs/libregexp.c
  third_party/quickjs/libunicode.c
  third_party/quickjs/libbf.c
  third_party/quickjs/cutils.c
)
target_include_directories(xime-quickjs PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/third_party/quickjs")
target_compile_definitions(xime-quickjs PRIVATE CONFIG_VERSION="2024-01-13" CONFIG_BIGNUM)
if(NOT MSVC)
  target_compile_definitions(xime-quickjs PRIVATE _GNU_SOURCE __STDC_CONSTANT_MACROS)
  target_compile_options(xime-quickjs PRIVATE -O0 -w)
  target_link_libraries(xime-quickjs PUBLIC m)
else()
  target_compile_options(xime-quickjs PRIVATE /O1)
endif()

# TSF 前端 DLL。
add_library(xime-tsf SHARED
  src/core/RimeEngine.cpp
  src/core/Log.cpp
  src/core/CandidatePicker.cpp
  src/core/KeyEatPolicy.cpp
  src/core/WordFreqStore.cpp
  src/core/AutoWordStore.cpp
  src/core/SchemeCodeIndex.cpp
  src/core/SchemaListConfig.cpp
  src/backup/BackupArchive.cpp
  src/backup/BackupService.cpp
  src/core/KeyMap.cpp
  src/ai/Json.cpp
  src/ai/AiProtocol.cpp
  src/ai/AiHttp.cpp
  src/ai/AiProviderConfig.cpp
  src/ai/AiService.cpp
  src/clipboard/ClipboardStore.cpp
  src/clipboard/WinClipboard.cpp
  src/clipboard/ClipboardWatcher.cpp
  src/settings/SettingsStore.cpp
  src/settings/SettingsApplier.cpp
  src/plugins/PluginHost.cpp
  src/plugins/PluginManager.cpp
  src/plugins/PluginHost.cpp
  src/voice/Wav.cpp
  src/voice/VoiceService.cpp
  src/voice/AudioRecorder.cpp
  src/tsf/XimeTextService.cpp
  src/tsf/LangBarButton.cpp
  src/tsf/Dll.cpp
  src/ui/CandidateWindow.cpp
)
target_include_directories(xime-tsf PRIVATE
  "${CMAKE_CURRENT_SOURCE_DIR}/src"
  "${XIME_JNI_DIR}/librime/include"
  "${XIME_JNI_DIR}/librime/src"
  "${XIME_JNI_DIR}/librime-t9/src"
  "${CMAKE_BINARY_DIR}/librime/src"
)
# 语言栏图标资源（中/英状态图标 + 版本信息）。
# 注意：不能把 .rc 直接加入 target —— xime-tsf 挂了上百个 boost include 路径，
# windres 预处理时命令行会超长（"command line is too long"）。
# 故先用 windres 单独编译成 .o（只传必要参数），再作为目标文件链接进去。
if(MSVC)
  target_sources(xime-tsf PRIVATE res/xime.rc)
else()
  find_program(XIME_WINDRES windres HINTS "${CMAKE_CXX_COMPILER}/../")
  if(XIME_WINDRES)
    set(XIME_RC_OBJ "${CMAKE_CURRENT_BINARY_DIR}/xime_res.o")
    add_custom_command(
      OUTPUT ${XIME_RC_OBJ}
      COMMAND ${XIME_WINDRES} -I "${CMAKE_CURRENT_SOURCE_DIR}/res"
              -i "${CMAKE_CURRENT_SOURCE_DIR}/res/xime.rc" -o ${XIME_RC_OBJ}
              --input-format=rc --output-format=coff
      DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/res/xime.rc"
              "${CMAKE_CURRENT_SOURCE_DIR}/res/xime.ico"
              "${CMAKE_CURRENT_SOURCE_DIR}/res/xime_zh.ico"
              "${CMAKE_CURRENT_SOURCE_DIR}/res/xime_en.ico"
      COMMENT "编译语言栏图标资源")
    target_sources(xime-tsf PRIVATE ${XIME_RC_OBJ})
  endif()
endif()

target_compile_definitions(xime-tsf PRIVATE UNICODE _UNICODE)
if(MSVC)
  target_compile_options(xime-tsf PRIVATE /utf-8 /W3 /EHsc)
else()
  target_compile_options(xime-tsf PRIVATE -Wall -Wextra)
endif()

# 插件注册符号只存在于 rime-static 的静态对象中，必须整包链接。
if(MSVC)
  target_link_options(xime-tsf PRIVATE "/WHOLEARCHIVE:rime-static.lib")
  target_link_libraries(xime-tsf PRIVATE rime-static)
else()
  target_link_libraries(xime-tsf PRIVATE
    -Wl,--whole-archive rime-static -Wl,--no-whole-archive)
  # 静态链接 MinGW 运行时（libgcc/libstdc++/winpthread），使 DLL 自包含：
  # TSF 被加载进 explorer 等任意进程，不能依赖外部 MinGW 运行时 DLL。
  target_link_options(xime-tsf PRIVATE -static -static-libgcc -static-libstdc++)
endif()
target_link_libraries(xime-tsf PRIVATE
  ole32 uuid user32 gdi32 comctl32 shell32 shlwapi ws2_32 winhttp winmm xime-quickjs)
if(MSVC)
  # msctf 是 MSVC/Windows SDK 的导入库（TSF 接口 IID 与 msctf.dll）；
  # MinGW 无同名导入库，其 TSF IID 由 libuuid.a 提供，故仅 MSVC 追加。
  target_link_libraries(xime-tsf PRIVATE msctf)
endif()

# 方案与词库放到 DLL 同级 data 目录（ActivateEx 从此处加载）。
# MinGW 默认产物带 lib 前缀（libxime-tsf.dll），TSF 注册按 xime-tsf.dll 加载，
# 故去掉前缀，使产物名在 MSVC/MinGW 下统一为 xime-tsf.dll。
set_target_properties(xime-tsf PROPERTIES PREFIX "")
add_custom_command(TARGET xime-tsf POST_BUILD
  COMMAND ${CMAKE_COMMAND} -E copy_directory
    "${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/assets/rime"
    "$<TARGET_FILE_DIR:xime-tsf>/data"
  COMMENT "复制 librime 方案与词库到 data/"
)

# 注册工具。
add_executable(xime-register tools/Register.cpp)
target_compile_definitions(xime-register PRIVATE UNICODE _UNICODE)
target_link_libraries(xime-register PRIVATE ole32 uuid shlwapi)
if(NOT MSVC)
  target_link_options(xime-register PRIVATE -static -static-libgcc -static-libstdc++)
endif()
add_dependencies(xime-register xime-tsf)

# 部署预热工具：安装后离线完成 librime 部署，避免首次打字等待。
add_executable(xime-deploy tools/Deploy.cpp
  src/core/RimeEngine.cpp src/core/WordFreqStore.cpp src/core/SchemeCodeIndex.cpp)
target_include_directories(xime-deploy PRIVATE
  "${CMAKE_CURRENT_SOURCE_DIR}/src"
  "${XIME_JNI_DIR}/librime/include"
  "${XIME_JNI_DIR}/librime/src"
  "${XIME_JNI_DIR}/librime-t9/src"
  "${CMAKE_BINARY_DIR}/librime/src"
)
if(MSVC)
  target_link_options(xime-deploy PRIVATE "/WHOLEARCHIVE:rime-static.lib")
  target_link_libraries(xime-deploy PRIVATE rime-static)
else()
  target_link_libraries(xime-deploy PRIVATE
    -Wl,--whole-archive rime-static -Wl,--no-whole-archive)
  target_link_options(xime-deploy PRIVATE -static -static-libgcc -static-libstdc++)
endif()
target_link_libraries(xime-deploy PRIVATE ole32 uuid)
add_dependencies(xime-deploy xime-tsf)

# 引擎冒烟测试（仅在本机构建时启用；CI 亦可验证核心链路）。
option(XIME_BUILD_SMOKE "构建引擎冒烟测试" ON)
if(XIME_BUILD_SMOKE)
  add_executable(xime-smoke tests/EngineSmoke.cpp src/core/RimeEngine.cpp src/core/WordFreqStore.cpp src/core/SchemeCodeIndex.cpp)
  target_include_directories(xime-smoke PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src"
    "${XIME_JNI_DIR}/librime/include"
    "${XIME_JNI_DIR}/librime/src"
    "${XIME_JNI_DIR}/librime-t9/src"
    "${CMAKE_BINARY_DIR}/librime/src"
  )
  if(MSVC)
    target_link_options(xime-smoke PRIVATE "/WHOLEARCHIVE:rime-static.lib")
    target_link_libraries(xime-smoke PRIVATE rime-static)
  else()
    target_link_libraries(xime-smoke PRIVATE
      -Wl,--whole-archive rime-static -Wl,--no-whole-archive)
  endif()
  target_link_libraries(xime-smoke PRIVATE ole32 uuid)
  add_dependencies(xime-smoke xime-tsf)

  add_executable(xime-t9-smoke tests/T9Smoke.cpp src/core/RimeEngine.cpp src/core/WordFreqStore.cpp src/core/SchemeCodeIndex.cpp)
  target_include_directories(xime-t9-smoke PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src"
    "${XIME_JNI_DIR}/librime/include"
    "${XIME_JNI_DIR}/librime/src"
    "${XIME_JNI_DIR}/librime-t9/src"
    "${CMAKE_BINARY_DIR}/librime/src"
  )
  if(MSVC)
    target_link_options(xime-t9-smoke PRIVATE "/WHOLEARCHIVE:rime-static.lib")
    target_link_libraries(xime-t9-smoke PRIVATE rime-static)
  else()
    target_link_libraries(xime-t9-smoke PRIVATE
      -Wl,--whole-archive rime-static -Wl,--no-whole-archive)
  endif()
  target_link_libraries(xime-t9-smoke PRIVATE ole32 uuid)
  add_dependencies(xime-t9-smoke xime-tsf)

  add_executable(xime-page-smoke tests/PageSizeSmoke.cpp src/core/RimeEngine.cpp src/core/WordFreqStore.cpp src/core/SchemeCodeIndex.cpp)
  target_include_directories(xime-page-smoke PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src"
    "${XIME_JNI_DIR}/librime/include"
    "${XIME_JNI_DIR}/librime/src"
    "${XIME_JNI_DIR}/librime-t9/src"
    "${CMAKE_BINARY_DIR}/librime/src"
  )
  if(MSVC)
    target_link_options(xime-page-smoke PRIVATE "/WHOLEARCHIVE:rime-static.lib")
    target_link_libraries(xime-page-smoke PRIVATE rime-static)
  else()
    target_link_libraries(xime-page-smoke PRIVATE
      -Wl,--whole-archive rime-static -Wl,--no-whole-archive)
  endif()
  target_link_libraries(xime-page-smoke PRIVATE ole32 uuid)
  add_dependencies(xime-page-smoke xime-tsf)

  add_executable(xime-word-smoke tests/WordFreqSmoke.cpp src/core/RimeEngine.cpp src/core/WordFreqStore.cpp src/core/SchemeCodeIndex.cpp)
  target_include_directories(xime-word-smoke PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src"
    "${XIME_JNI_DIR}/librime/include"
    "${XIME_JNI_DIR}/librime/src"
    "${XIME_JNI_DIR}/librime-t9/src"
    "${CMAKE_BINARY_DIR}/librime/src"
  )
  if(MSVC)
    target_link_options(xime-word-smoke PRIVATE "/WHOLEARCHIVE:rime-static.lib")
    target_link_libraries(xime-word-smoke PRIVATE rime-static)
  else()
    target_link_libraries(xime-word-smoke PRIVATE
      -Wl,--whole-archive rime-static -Wl,--no-whole-archive)
  endif()
  target_link_libraries(xime-word-smoke PRIVATE ole32 uuid)
  add_dependencies(xime-word-smoke xime-tsf)

  # 自动造词纯逻辑单测（不依赖 librime，秒级运行）。
  add_executable(xime-autoword-test tests/AutoWordTest.cpp src/core/AutoWordStore.cpp)
  target_include_directories(xime-autoword-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")

  # 组合生词编码生成纯逻辑单测。
  add_executable(xime-scheme-test tests/SchemeCodeIndexTest.cpp src/core/SchemeCodeIndex.cpp)
  target_include_directories(xime-scheme-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")

  # AI 配置策略纯逻辑单测（STT 复用密钥 + 润色开关）。
  add_executable(xime-ai-test tests/AiProviderTest.cpp src/ai/AiProviderConfig.cpp)
  target_include_directories(xime-ai-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")

  # 自动造词候选接入冒烟（真实 librime + wubi86 单字码）。
  add_executable(xime-autocand-smoke tests/AutoCandSmoke.cpp
    src/core/RimeEngine.cpp src/core/WordFreqStore.cpp src/core/SchemeCodeIndex.cpp)
  target_include_directories(xime-autocand-smoke PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src"
    "${XIME_JNI_DIR}/librime/include"
    "${XIME_JNI_DIR}/librime/src"
    "${XIME_JNI_DIR}/librime-t9/src"
    "${CMAKE_BINARY_DIR}/librime/src"
  )
  if(MSVC)
    target_link_options(xime-autocand-smoke PRIVATE "/WHOLEARCHIVE:rime-static.lib")
    target_link_libraries(xime-autocand-smoke PRIVATE rime-static)
  else()
    target_link_libraries(xime-autocand-smoke PRIVATE
      -Wl,--whole-archive rime-static -Wl,--no-whole-archive)
  endif()
  target_link_libraries(xime-autocand-smoke PRIVATE ole32 uuid)
  add_dependencies(xime-autocand-smoke xime-tsf)

  # 备份/恢复往返测试（纯文件 IO，不依赖 librime）。
  add_executable(xime-backup-test tests/BackupTest.cpp
    src/backup/BackupArchive.cpp src/backup/BackupService.cpp)
  target_include_directories(xime-backup-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")

  # 方案列表持久化单测。
  add_executable(xime-schemalist-test tests/SchemaListTest.cpp src/core/SchemaListConfig.cpp)
  target_include_directories(xime-schemalist-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")

  # AI 协议层单测（JSON + OpenAI 兼容请求/响应，纯逻辑）。
  add_executable(xime-aiprotocol-test tests/AiProtocolTest.cpp
    src/ai/Json.cpp src/ai/AiProtocol.cpp)
  target_include_directories(xime-aiprotocol-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")

  # AI 服务门面测试（润色开关 + LLM 请求/回退，http 桩）。
  add_executable(xime-aiservice-test tests/AiServiceTest.cpp
    src/ai/AiService.cpp src/ai/AiProviderConfig.cpp
    src/ai/AiProtocol.cpp src/ai/Json.cpp)
  target_include_directories(xime-aiservice-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")

  # AI 传输层回环测试（Winsock 本机收发）。
  find_package(Threads REQUIRED)
  add_executable(xime-aihttp-test tests/AiHttpLoopbackTest.cpp
    src/ai/AiHttp.cpp src/ai/AiProtocol.cpp src/ai/Json.cpp)
  target_include_directories(xime-aihttp-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")
  target_link_libraries(xime-aihttp-test PRIVATE ws2_32 winhttp Threads::Threads)

  # STT multipart 上传测试（构造 + 回环真实发送 + 解析 text）。
  add_executable(xime-stt-test tests/AiSttTest.cpp
    src/ai/AiHttp.cpp src/ai/AiProtocol.cpp src/ai/Json.cpp)
  target_include_directories(xime-stt-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")
  target_link_libraries(xime-stt-test PRIVATE ws2_32 winhttp Threads::Threads)

  # 方案管理端到端：写 schema_list → 部署 → 选中。
  add_executable(xime-schemadeploy-smoke tests/SchemaDeploySmoke.cpp
    src/core/RimeEngine.cpp src/core/WordFreqStore.cpp src/core/SchemeCodeIndex.cpp src/core/SchemaListConfig.cpp)
  target_include_directories(xime-schemadeploy-smoke PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src"
    "${XIME_JNI_DIR}/librime/include"
    "${XIME_JNI_DIR}/librime/src"
    "${XIME_JNI_DIR}/librime-t9/src"
    "${CMAKE_BINARY_DIR}/librime/src")
  if(MSVC)
    target_link_options(xime-schemadeploy-smoke PRIVATE "/WHOLEARCHIVE:rime-static.lib")
    target_link_libraries(xime-schemadeploy-smoke PRIVATE rime-static)
  else()
    target_link_libraries(xime-schemadeploy-smoke PRIVATE
      -Wl,--whole-archive rime-static -Wl,--no-whole-archive)
  endif()
  target_link_libraries(xime-schemadeploy-smoke PRIVATE ole32 uuid)
  add_dependencies(xime-schemadeploy-smoke xime-tsf)

  # 剪贴板存储纯逻辑测试。
  add_executable(xime-clipboard-test tests/ClipboardStoreTest.cpp
    src/clipboard/ClipboardStore.cpp src/ai/Json.cpp)
  target_include_directories(xime-clipboard-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")

  # Win32 剪贴板读写往返测试（真实 OS 剪贴板）。
  add_executable(xime-winclip-test tests/WinClipboardTest.cpp
    src/clipboard/WinClipboard.cpp)
  target_include_directories(xime-winclip-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")
  target_link_libraries(xime-winclip-test PRIVATE user32)

  # 剪贴板自动捕获（轮询系统剪贴板入历史）。
  add_executable(xime-clipwatch-test tests/ClipboardWatcherTest.cpp
    src/clipboard/ClipboardWatcher.cpp src/clipboard/ClipboardStore.cpp
    src/clipboard/WinClipboard.cpp src/ai/Json.cpp)
  target_include_directories(xime-clipwatch-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")
  target_link_libraries(xime-clipwatch-test PRIVATE user32)

  # 插件宿主测试（QuickJS 加载 main.js 并调用 transform.candidates）。
  add_executable(xime-plugin-test tests/PluginHostTest.cpp src/plugins/PluginHost.cpp)
  target_include_directories(xime-plugin-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")
  target_link_libraries(xime-plugin-test PRIVATE xime-quickjs)

  # 插件候选变换端到端：插件改候选 + 选词仍正确。
  add_executable(xime-plugincand-smoke tests/PluginCandSmoke.cpp
    src/core/RimeEngine.cpp src/core/WordFreqStore.cpp src/core/SchemeCodeIndex.cpp
    src/plugins/PluginHost.cpp)
  target_include_directories(xime-plugincand-smoke PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src"
    "${XIME_JNI_DIR}/librime/include"
    "${XIME_JNI_DIR}/librime/src"
    "${XIME_JNI_DIR}/librime-t9/src"
    "${CMAKE_BINARY_DIR}/librime/src")
  if(MSVC)
    target_link_options(xime-plugincand-smoke PRIVATE "/WHOLEARCHIVE:rime-static.lib")
    target_link_libraries(xime-plugincand-smoke PRIVATE rime-static)
  else()
    target_link_libraries(xime-plugincand-smoke PRIVATE
      -Wl,--whole-archive rime-static -Wl,--no-whole-archive)
  endif()
  target_link_libraries(xime-plugincand-smoke PRIVATE ole32 uuid xime-quickjs)
  add_dependencies(xime-plugincand-smoke xime-tsf)

  # 插件完整契约（engineIndex + 新增候选）端到端。
  add_executable(xime-pluginfull-smoke tests/PluginFullCandSmoke.cpp
    src/core/RimeEngine.cpp src/core/WordFreqStore.cpp src/core/SchemeCodeIndex.cpp
    src/plugins/PluginHost.cpp)
  target_include_directories(xime-pluginfull-smoke PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src"
    "${XIME_JNI_DIR}/librime/include" "${XIME_JNI_DIR}/librime/src"
    "${XIME_JNI_DIR}/librime-t9/src" "${CMAKE_BINARY_DIR}/librime/src")
  if(MSVC)
    target_link_options(xime-pluginfull-smoke PRIVATE "/WHOLEARCHIVE:rime-static.lib")
    target_link_libraries(xime-pluginfull-smoke PRIVATE rime-static)
  else()
    target_link_libraries(xime-pluginfull-smoke PRIVATE
      -Wl,--whole-archive rime-static -Wl,--no-whole-archive)
  endif()
  target_link_libraries(xime-pluginfull-smoke PRIVATE ole32 uuid xime-quickjs)
  add_dependencies(xime-pluginfull-smoke xime-tsf)

  # 语音链路：WAV 编码 + AI STT 回环上传（合成音频）。
  add_executable(xime-voice-test tests/VoiceTest.cpp
    src/voice/Wav.cpp src/voice/VoiceService.cpp
    src/ai/AiProtocol.cpp src/ai/Json.cpp src/ai/AiHttp.cpp)
  target_include_directories(xime-voice-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")
  target_link_libraries(xime-voice-test PRIVATE ws2_32 winhttp)

  # 麦克风采集器 headless 验证（无设备优雅失败；真机录音另验）。
  add_executable(xime-recorder-test tests/AudioRecorderTest.cpp
    src/voice/AudioRecorder.cpp src/voice/Wav.cpp)
  target_include_directories(xime-recorder-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")
  target_link_libraries(xime-recorder-test PRIVATE winmm)

  # 语音全链路：真实录音 → WAV → STT → 润色/直出（回环，不连外网）。
  add_executable(xime-voicechain-smoke tests/VoiceChainSmoke.cpp
    src/voice/AudioRecorder.cpp src/voice/Wav.cpp src/voice/VoiceService.cpp
    src/ai/AiHttp.cpp src/ai/AiProtocol.cpp src/ai/Json.cpp
    src/ai/AiService.cpp src/ai/AiProviderConfig.cpp)
  target_include_directories(xime-voicechain-smoke PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")
  target_link_libraries(xime-voicechain-smoke PRIVATE winmm ws2_32 winhttp)

  # WinHTTP 传输回环测试（https 走此路径；本机用 http 回环验证链路）。
  add_executable(xime-aihttp-winhttp-test tests/AiHttpWinhttpTest.cpp
    src/ai/AiHttp.cpp src/ai/AiProtocol.cpp src/ai/Json.cpp)
  target_include_directories(xime-aihttp-winhttp-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")
  target_link_libraries(xime-aihttp-winhttp-test PRIVATE ws2_32 winhttp)

  # 设置存储往返测试。
  add_executable(xime-settings-test tests/SettingsStoreTest.cpp
    src/settings/SettingsStore.cpp src/ai/Json.cpp)
  target_include_directories(xime-settings-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")

  # 设置应用：schemaList 落 default.custom.yaml（幂等/空列表不写）。
  add_executable(xime-settingsapplier-test tests/SettingsApplierTest.cpp
    src/settings/SettingsApplier.cpp src/core/SchemaListConfig.cpp)
  target_include_directories(xime-settingsapplier-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")

  # 插件生命周期 + host.quickSend 服务测试。
  add_executable(xime-pluglifecycle-test tests/PluginLifecycleTest.cpp
    src/plugins/PluginHost.cpp)
  target_include_directories(xime-pluglifecycle-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")
  target_link_libraries(xime-pluglifecycle-test PRIVATE xime-quickjs)

  # 插件管理器测试（目录扫描 + 链式变换）。
  add_executable(xime-pluginmgr-test tests/PluginManagerTest.cpp
    src/plugins/PluginManager.cpp src/plugins/PluginHost.cpp)
  target_include_directories(xime-pluginmgr-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")
  target_link_libraries(xime-pluginmgr-test PRIVATE xime-quickjs)

  # 插件 transform 硬超时测试（死循环插件不卡死输入热路径）。
  add_executable(xime-plugintimeout-test tests/PluginTimeoutTest.cpp
    src/plugins/PluginHost.cpp)
  target_include_directories(xime-plugintimeout-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")
  target_link_libraries(xime-plugintimeout-test PRIVATE xime-quickjs)

  # TSF 启动接线模拟：plugins/ 目录加载 → 引擎注册 → 输入时插件改候选。
  add_executable(xime-tsfplugin-smoke tests/TsfPluginSmoke.cpp
    src/core/RimeEngine.cpp src/core/WordFreqStore.cpp src/core/SchemeCodeIndex.cpp
    src/plugins/PluginManager.cpp src/plugins/PluginHost.cpp)
  target_include_directories(xime-tsfplugin-smoke PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src"
    "${XIME_JNI_DIR}/librime/include"
    "${XIME_JNI_DIR}/librime/src"
    "${XIME_JNI_DIR}/librime-t9/src"
    "${CMAKE_BINARY_DIR}/librime/src"
  )
  if(MSVC)
    target_link_options(xime-tsfplugin-smoke PRIVATE "/WHOLEARCHIVE:rime-static.lib")
    target_link_libraries(xime-tsfplugin-smoke PRIVATE rime-static)
  else()
    target_link_libraries(xime-tsfplugin-smoke PRIVATE
      -Wl,--whole-archive rime-static -Wl,--no-whole-archive)
  endif()
  target_link_libraries(xime-tsfplugin-smoke PRIVATE ole32 uuid xime-quickjs)
  add_dependencies(xime-tsfplugin-smoke xime-tsf)

  # 候选翻页端到端：真实 librime 多页 → ChangePage 前后翻页。
  add_executable(xime-pageflip-smoke tests/PageFlipSmoke.cpp
    src/core/RimeEngine.cpp src/core/WordFreqStore.cpp src/core/SchemeCodeIndex.cpp src/core/CandidatePicker.cpp)
  target_include_directories(xime-pageflip-smoke PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src"
    "${XIME_JNI_DIR}/librime/include"
    "${XIME_JNI_DIR}/librime/src"
    "${XIME_JNI_DIR}/librime-t9/src"
    "${CMAKE_BINARY_DIR}/librime/src"
  )
  if(MSVC)
    target_link_options(xime-pageflip-smoke PRIVATE "/WHOLEARCHIVE:rime-static.lib")
    target_link_libraries(xime-pageflip-smoke PRIVATE rime-static)
  else()
    target_link_libraries(xime-pageflip-smoke PRIVATE
      -Wl,--whole-archive rime-static -Wl,--no-whole-archive)
  endif()
  target_link_libraries(xime-pageflip-smoke PRIVATE ole32 uuid)
  add_dependencies(xime-pageflip-smoke xime-tsf)

  # 候选选取解析单测（纯逻辑，数字键与鼠标点击共用）。
  add_executable(xime-picker-test tests/CandidatePickerTest.cpp
    src/core/CandidatePicker.cpp)
  target_include_directories(xime-picker-test PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src"
    "${XIME_JNI_DIR}/librime/include"
    "${XIME_JNI_DIR}/librime/src"
    "${CMAKE_BINARY_DIR}/librime/src"
  )
  target_link_libraries(xime-picker-test PRIVATE ole32 uuid)

  # 按键吞掉策略单测（纯逻辑）。
  add_executable(xime-keyeat-test tests/KeyEatPolicyTest.cpp
    src/core/KeyEatPolicy.cpp)
  target_include_directories(xime-keyeat-test PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src"
    "${XIME_JNI_DIR}/librime/include"
    "${XIME_JNI_DIR}/librime/src"
    "${CMAKE_BINARY_DIR}/librime/src"
  )

  # 中/英文切换端到端：真实 librime ascii_mode toggle。
  add_executable(xime-ascii-smoke tests/AsciiToggleSmoke.cpp
    src/core/RimeEngine.cpp src/core/WordFreqStore.cpp src/core/SchemeCodeIndex.cpp src/core/CandidatePicker.cpp)
  target_include_directories(xime-ascii-smoke PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src"
    "${XIME_JNI_DIR}/librime/include"
    "${XIME_JNI_DIR}/librime/src"
    "${XIME_JNI_DIR}/librime-t9/src"
    "${CMAKE_BINARY_DIR}/librime/src"
  )
  if(MSVC)
    target_link_options(xime-ascii-smoke PRIVATE "/WHOLEARCHIVE:rime-static.lib")
    target_link_libraries(xime-ascii-smoke PRIVATE rime-static)
  else()
    target_link_libraries(xime-ascii-smoke PRIVATE
      -Wl,--whole-archive rime-static -Wl,--no-whole-archive)
  endif()
  target_link_libraries(xime-ascii-smoke PRIVATE ole32 uuid)
  add_dependencies(xime-ascii-smoke xime-tsf)

  # 方案循环切换端到端：枚举已部署方案 → CycleSchema 依次切换回首个。
  add_executable(xime-schemacycle-smoke tests/SchemaCycleSmoke.cpp
    src/core/RimeEngine.cpp src/core/WordFreqStore.cpp src/core/SchemeCodeIndex.cpp src/core/CandidatePicker.cpp)
  target_include_directories(xime-schemacycle-smoke PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src"
    "${XIME_JNI_DIR}/librime/include"
    "${XIME_JNI_DIR}/librime/src"
    "${XIME_JNI_DIR}/librime-t9/src"
    "${CMAKE_BINARY_DIR}/librime/src"
  )
  if(MSVC)
    target_link_options(xime-schemacycle-smoke PRIVATE "/WHOLEARCHIVE:rime-static.lib")
    target_link_libraries(xime-schemacycle-smoke PRIVATE rime-static)
  else()
    target_link_libraries(xime-schemacycle-smoke PRIVATE
      -Wl,--whole-archive rime-static -Wl,--no-whole-archive)
  endif()
  target_link_libraries(xime-schemacycle-smoke PRIVATE ole32 uuid)
  add_dependencies(xime-schemacycle-smoke xime-tsf)

  # 设置生效端到端：写 settings.json → 加载应用 → 引擎 pageSize 生效。
  add_executable(xime-settingapply-smoke tests/SettingsApplySmoke.cpp
    src/core/RimeEngine.cpp src/core/WordFreqStore.cpp src/core/SchemeCodeIndex.cpp
    src/core/CandidatePicker.cpp src/settings/SettingsStore.cpp src/ai/Json.cpp)
  target_include_directories(xime-settingapply-smoke PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src"
    "${XIME_JNI_DIR}/librime/include"
    "${XIME_JNI_DIR}/librime/src"
    "${XIME_JNI_DIR}/librime-t9/src"
    "${CMAKE_BINARY_DIR}/librime/src"
  )
  if(MSVC)
    target_link_options(xime-settingapply-smoke PRIVATE "/WHOLEARCHIVE:rime-static.lib")
    target_link_libraries(xime-settingapply-smoke PRIVATE rime-static)
  else()
    target_link_libraries(xime-settingapply-smoke PRIVATE
      -Wl,--whole-archive rime-static -Wl,--no-whole-archive)
  endif()
  target_link_libraries(xime-settingapply-smoke PRIVATE ole32 uuid)
  add_dependencies(xime-settingapply-smoke xime-tsf)

  # 首启部署端到端：全新用户目录 → 部署 → 已部署 → 有候选。
  add_executable(xime-firstrun-smoke tests/FirstRunSmoke.cpp
    src/core/RimeEngine.cpp src/core/WordFreqStore.cpp src/core/SchemeCodeIndex.cpp
    src/core/CandidatePicker.cpp)
  target_include_directories(xime-firstrun-smoke PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src"
    "${XIME_JNI_DIR}/librime/include"
    "${XIME_JNI_DIR}/librime/src"
    "${XIME_JNI_DIR}/librime-t9/src"
    "${CMAKE_BINARY_DIR}/librime/src"
  )
  if(MSVC)
    target_link_options(xime-firstrun-smoke PRIVATE "/WHOLEARCHIVE:rime-static.lib")
    target_link_libraries(xime-firstrun-smoke PRIVATE rime-static)
  else()
    target_link_libraries(xime-firstrun-smoke PRIVATE
      -Wl,--whole-archive rime-static -Wl,--no-whole-archive)
  endif()
  target_link_libraries(xime-firstrun-smoke PRIVATE ole32 uuid)
  add_dependencies(xime-firstrun-smoke xime-tsf)

  # 异常安全验收：TSF 回调里的异常不得逃逸到宿主进程（曾致宿主 0xC000041D 崩溃）。
  add_executable(xime-exsafe-test tests/ExceptionSafetyTest.cpp
    src/tsf/XimeTextService.cpp src/tsf/LangBarButton.cpp src/core/Log.cpp
    src/core/RimeEngine.cpp src/core/WordFreqStore.cpp src/core/SchemeCodeIndex.cpp
    src/core/CandidatePicker.cpp src/core/KeyMap.cpp src/core/KeyEatPolicy.cpp
    src/core/AutoWordStore.cpp src/core/SchemaListConfig.cpp
    src/settings/SettingsStore.cpp src/settings/SettingsApplier.cpp
    src/plugins/PluginHost.cpp src/plugins/PluginManager.cpp
    src/clipboard/ClipboardStore.cpp src/clipboard/WinClipboard.cpp
    src/clipboard/ClipboardWatcher.cpp
    src/ai/Json.cpp src/ai/AiProtocol.cpp src/ai/AiHttp.cpp
    src/ai/AiProviderConfig.cpp src/ai/AiService.cpp
    src/voice/Wav.cpp src/voice/VoiceService.cpp src/voice/AudioRecorder.cpp
    src/ui/CandidateWindow.cpp)
  target_include_directories(xime-exsafe-test PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src"
    "${XIME_JNI_DIR}/librime/include"
    "${XIME_JNI_DIR}/librime/src"
    "${XIME_JNI_DIR}/librime-t9/src"
    "${CMAKE_BINARY_DIR}/librime/src")
  if(MSVC)
    target_link_options(xime-exsafe-test PRIVATE "/WHOLEARCHIVE:rime-static.lib")
    target_link_libraries(xime-exsafe-test PRIVATE rime-static)
  else()
    target_link_libraries(xime-exsafe-test PRIVATE
      -Wl,--whole-archive rime-static -Wl,--no-whole-archive)
  endif()
  target_link_libraries(xime-exsafe-test PRIVATE
    ole32 uuid user32 gdi32 comctl32 shell32 shlwapi ws2_32 winhttp winmm xime-quickjs)
  add_dependencies(xime-exsafe-test xime-tsf)
  # 共享数据目录解析：必须用本 DLL 的 HMODULE 取路径，而非宿主 EXE（否则
  # 引擎找不到 data/ 方案，表现为"只能打英文"）。
  add_executable(xime-pathresolve-test tests/PathResolveTest.cpp)
  # 宿主模拟：把安装好的 DLL 载入另一个进程，验证 DLL 能定位自己的 data/。
  add_executable(xime-hostsim-test tests/HostSimTest.cpp)
  # 候选窗定位：跟随光标 + 边缘翻转，不越界。
  add_executable(xime-placement-test tests/CandidatePlacementTest.cpp
    src/ui/CandidateWindow.cpp)
  target_include_directories(xime-placement-test PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src"
    "${XIME_JNI_DIR}/librime/include"
    "${XIME_JNI_DIR}/librime/src"
    "${XIME_JNI_DIR}/librime-t9/src"
    "${CMAKE_BINARY_DIR}/librime/src")
  target_link_libraries(xime-placement-test PRIVATE ole32 uuid user32 gdi32)
  # 语言栏项：IID/vtable 正确性 + 语言栏管理器可达性。
  add_executable(xime-langbar-test tests/LangBarTest.cpp)
  target_include_directories(xime-langbar-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")
  target_link_libraries(xime-langbar-test PRIVATE ole32 uuid)
  # 日志功能：文件创建/格式/级别开关/立即落盘。
  add_executable(xime-log-test tests/LogTest.cpp src/core/Log.cpp)
  target_include_directories(xime-log-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")
  # 恶意输入健壮性：深嵌套 JSON 不炸栈、畸形 ZIP 不越界读。
  add_executable(xime-fuzz-test tests/MaliciousInputTest.cpp
    src/ai/Json.cpp src/backup/BackupArchive.cpp)
  target_include_directories(xime-fuzz-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")
  # 插件/剪贴板资源限额：内存上限、加载期超时不挂死、淘汰不崩、置顶保留。
  add_executable(xime-limit-test tests/ResourceLimitTest.cpp
    src/plugins/PluginHost.cpp src/clipboard/ClipboardStore.cpp src/ai/Json.cpp)
  target_include_directories(xime-limit-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")
  target_link_libraries(xime-limit-test PRIVATE xime-quickjs)
  # 空格键选词：VK_SPACE 必须进 IsRimeKey/ToRimeKey，且能提交首候选。
  add_executable(xime-space-smoke tests/SpaceCommitTest.cpp
    src/core/RimeEngine.cpp src/core/WordFreqStore.cpp src/core/SchemeCodeIndex.cpp
    src/core/KeyMap.cpp)
  target_include_directories(xime-space-smoke PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/src"
    "${XIME_JNI_DIR}/librime/include"
    "${XIME_JNI_DIR}/librime/src"
    "${XIME_JNI_DIR}/librime-t9/src"
    "${CMAKE_BINARY_DIR}/librime/src")
  if(MSVC)
    target_link_options(xime-space-smoke PRIVATE "/WHOLEARCHIVE:rime-static.lib")
    target_link_libraries(xime-space-smoke PRIVATE rime-static)
  else()
    target_link_libraries(xime-space-smoke PRIVATE
      -Wl,--whole-archive rime-static -Wl,--no-whole-archive)
  endif()
  target_link_libraries(xime-space-smoke PRIVATE ole32 uuid)
  add_dependencies(xime-space-smoke xime-tsf)
endif()

# ---- 安装规则 ----
# 产物布局（CMAKE_INSTALL_PREFIX 下）：
#   xime-tsf.dll       TSF 输入法前端（自包含，静态链 MinGW 运行时）
#   xime-register.exe  注册/注销工具
#   data/              librime 方案与词库（同 DLL 同级）
# 安装后由 xime-register.exe install 完成系统注册（需管理员）。
# 默认安装到 %LOCALAPPDATA%\Xime\ime（用户目录，无需管理员；注册时才提权）。
# 显式传 -DCMAKE_INSTALL_PREFIX=... 可覆盖（此处仅在未显式传入时设置）。
if(NOT XIME_INSTALL_PREFIX_SET)
  if(DEFINED ENV{LOCALAPPDATA})
    file(TO_CMAKE_PATH "$ENV{LOCALAPPDATA}/Xime/ime" XIME_DEFAULT_PREFIX)
    set(CMAKE_INSTALL_PREFIX "${XIME_DEFAULT_PREFIX}" CACHE PATH "安装目录" FORCE)
  endif()
endif()

install(TARGETS xime-tsf RUNTIME DESTINATION . LIBRARY DESTINATION .)
install(TARGETS xime-register RUNTIME DESTINATION .)
install(DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/assets/rime/"
        DESTINATION data
        PATTERN ".git" EXCLUDE
        PATTERN ".github" EXCLUDE)
install(FILES "${CMAKE_CURRENT_SOURCE_DIR}/INSTALL.md" DESTINATION . OPTIONAL)
