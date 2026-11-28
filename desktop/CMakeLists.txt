cmake_minimum_required(VERSION 3.20)
project(xime-desktop VERSION 3.4.70 LANGUAGES CXX)

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

# TSF 前端 DLL。
add_library(xime-tsf SHARED
  src/core/RimeEngine.cpp
  src/core/KeyMap.cpp
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
  ole32 uuid user32 gdi32 comctl32 shell32 shlwapi)
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
  add_executable(xime-smoke tests/EngineSmoke.cpp src/core/RimeEngine.cpp)
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

  add_executable(xime-t9-smoke tests/T9Smoke.cpp src/core/RimeEngine.cpp)
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
endif()
