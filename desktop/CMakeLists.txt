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
  src/plugins/PluginHost.cpp
  src/tsf/XimeTextService.cpp
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
endif()
target_link_libraries(xime-tsf PRIVATE
  ole32 uuid user32 gdi32 comctl32 shell32 shlwapi ws2_32 xime-quickjs)
if(MSVC)
  # msctf 是 MSVC/Windows SDK 的导入库（TSF 接口 IID 与 msctf.dll）；
  # MinGW 无同名导入库，其 TSF IID 由 libuuid.a 提供，故仅 MSVC 追加。
  target_link_libraries(xime-tsf PRIVATE msctf)
endif()

# 方案与词库放到 DLL 同级 data 目录（ActivateEx 从此处加载）。
add_custom_command(TARGET xime-tsf POST_BUILD
  COMMAND ${CMAKE_COMMAND} -E copy_directory
    "${CMAKE_CURRENT_SOURCE_DIR}/../app/src/main/assets/rime"
    "$<TARGET_FILE_DIR:xime-tsf>/data"
  COMMENT "复制 librime 方案与词库到 data/"
)

# 注册工具。
add_executable(xime-register tools/Register.cpp)
target_compile_definitions(xime-register PRIVATE UNICODE _UNICODE)
target_link_libraries(xime-register PRIVATE ole32 shlwapi)
add_dependencies(xime-register xime-tsf)

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
  target_link_libraries(xime-aihttp-test PRIVATE ws2_32 Threads::Threads)

  # STT multipart 上传测试（构造 + 回环真实发送 + 解析 text）。
  add_executable(xime-stt-test tests/AiSttTest.cpp
    src/ai/AiHttp.cpp src/ai/AiProtocol.cpp src/ai/Json.cpp)
  target_include_directories(xime-stt-test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")
  target_link_libraries(xime-stt-test PRIVATE ws2_32 Threads::Threads)

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
endif()
