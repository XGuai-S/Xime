# SPDX-FileCopyrightText: 2015 - 2024 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later

# 应用 Lua 5.4 Android 兼容性补丁（修复 32 位设备上 fseeko/ftello 不可用的问题）
# 对 liolib.c 中 l_fseek 配置块做条件增强，使 32 位 Android < API 24 能编译
# 使用 CMake 原生方式修补，无需依赖 git/patch
string(ASCII 10 LUA_NL)
set(LUA_LIOLIB_SRC "${CMAKE_SOURCE_DIR}/librime-lua-deps/lua5.4/liolib.c")
if(EXISTS "${LUA_LIOLIB_SRC}")
  file(READ "${LUA_LIOLIB_SRC}" LUA_LIOLIB_CONTENT)
  # 检查补丁是否已应用
  string(FIND "${LUA_LIOLIB_CONTENT}" "ANDROID" LUA_ALREADY_PATCHED)
  if(LUA_ALREADY_PATCHED EQUAL -1)
    string(FIND "${LUA_LIOLIB_CONTENT}" "#if !defined(l_fseek)" LUA_ANCHOR_POS)
    if(LUA_ANCHOR_POS GREATER -1)
      string(SUBSTRING "${LUA_LIOLIB_CONTENT}" ${LUA_ANCHOR_POS} -1 LUA_SUB_CONTENT)
      string(FIND "${LUA_SUB_CONTENT}" "#if defined(LUA_USE_POSIX)" LUA_REL_POS)
      if(LUA_REL_POS GREATER -1)
        math(EXPR LUA_TARGET_POS "${LUA_ANCHOR_POS} + ${LUA_REL_POS}")
        string(SUBSTRING "${LUA_LIOLIB_CONTENT}" ${LUA_TARGET_POS} -1 LUA_TARGET_CONTENT)
        string(FIND "${LUA_TARGET_CONTENT}" "${LUA_NL}" LUA_REL_NL_POS)
        if(LUA_REL_NL_POS GREATER -1)
          math(EXPR LUA_NL_POS "${LUA_TARGET_POS} + ${LUA_REL_NL_POS}")
          string(SUBSTRING "${LUA_LIOLIB_CONTENT}" 0 ${LUA_TARGET_POS} LUA_HEAD)
          string(SUBSTRING "${LUA_LIOLIB_CONTENT}" ${LUA_NL_POS} -1 LUA_TAIL)
          math(EXPR LUA_SUFFIX_START "${LUA_TARGET_POS} + 26")
          math(EXPR LUA_SUFFIX_LEN "${LUA_NL_POS} - ${LUA_SUFFIX_START}")
          string(SUBSTRING "${LUA_LIOLIB_CONTENT}" ${LUA_SUFFIX_START} ${LUA_SUFFIX_LEN} LUA_SUFFIX)
          set(LUA_PATCHED_LINE
            "#if defined(LUA_USE_POSIX) && \\${LUA_NL}   (!defined(ANDROID) || (defined(__LP64__) || ANDROID_PLATFORM >= 24))${LUA_SUFFIX}")
          set(LUA_LIOLIB_CONTENT "${LUA_HEAD}${LUA_PATCHED_LINE}${LUA_TAIL}")
          file(WRITE "${LUA_LIOLIB_SRC}" "${LUA_LIOLIB_CONTENT}")
        endif()
      endif()
    endif()
  endif()
endif()

# 已集成的插件（拷贝式同步；librime-t9 单独处理，见下）
set(RIME_PLUGINS librime-octagram librime-predict)

# 将插件复制到 plugins/ 目录。
# 顶层插件目录（librime-octagram 等）是唯一权威源码，这里在每次 configure 时
# 都全量同步，确保插件编译副本与顶层一致（file(COPY) 保留源文件时间戳，
# 内容未变的文件不会触发重编译）。
foreach(plugin ${RIME_PLUGINS})
  file(COPY "${CMAKE_SOURCE_DIR}/${plugin}/"
       DESTINATION "${CMAKE_SOURCE_DIR}/librime/plugins/${plugin}")
endforeach()

# ── 顶层插件源码接入 librime 插件构建 ──
# 权威源码在 jni/<name>，这里接入到 librime/plugins/<name> 供 librime 编译。
#
# 首选符号链接（物理单份，IDE 不会改错副本），但**不能只信 CREATE_LINK 的返回值**：
# Windows 未开启开发者模式时 file(CREATE_LINK ... SYMBOLIC) 会返回 0（成功）却
# 只产出 0 字节文件或空目录。而 librime/plugins/CMakeLists.txt 用
# file(GLOB "*") + IS_DIRECTORY 发现插件，这种“成功”的坏链接会让插件被
# **静默跳过**——configure 与编译都过，直到链接 rime_jni 时才报
# undefined symbol: rime::T9ProcessorRequire() / correction_set_log_file，
# 报错点在 JNI 侧，很难反查到这里。故链接后必须实测能否读到插件的
# CMakeLists.txt，读不到就整体回退为拷贝。
function(rime_link_plugin name)
  set(_link "${CMAKE_SOURCE_DIR}/librime/plugins/${name}")

  # 清掉上一次残留（链接 / 空目录 / 拷贝副本）。
  # 注意：目录联接（junction）不能用 REMOVE_RECURSE——会穿透链接删空权威源码，
  # 故 junction 走 IS_SYMLINK 分支用 file(REMOVE) 只删链接本身。
  if(IS_SYMLINK "${_link}")
    file(REMOVE "${_link}")
  elseif(EXISTS "${_link}")
    file(REMOVE_RECURSE "${_link}")
  endif()

  file(CREATE_LINK "../../${name}" "${_link}" SYMBOLIC RESULT _link_result)

  # 关键校验：链接是否真的可达（而非只看 _link_result）
  if(NOT EXISTS "${_link}/CMakeLists.txt")
    message(STATUS "plugins/${name}: symlink unusable (result=${_link_result}), falling back to copy")
    if(EXISTS "${_link}")
      file(REMOVE "${_link}")
    endif()
    if(IS_DIRECTORY "${_link}")
      file(REMOVE_RECURSE "${_link}")
    endif()
    file(COPY "${CMAKE_SOURCE_DIR}/${name}/" DESTINATION "${_link}")
  endif()

  # 兜底断言：拷贝也失败时必须显式报错，不能让插件继续被静默跳过
  if(NOT EXISTS "${_link}/CMakeLists.txt")
    message(FATAL_ERROR
      "plugins/${name}: 无法接入插件源码（符号链接与拷贝均失败），"
      "请检查 jni/${name} 是否存在且可读")
  endif()
endfunction()

# librime-t9：权威源码 jni/librime-t9（Gradle 侧 syncT9Plugin 也会幂等建链接）
rime_link_plugin(librime-t9)
# librime-correction：邻键误触纠错插件，权威源码 jni/librime-correction
rime_link_plugin(librime-correction)

# 注：上游 v3.2.2 在此处另加了一段 librime-correction 的裸 CREATE_LINK。
# 它会在上面 rime_link_plugin 之后再次删除并重建链接（不带可达性校验），
# 从而抵消本文件的修复（Windows 无开发者模式时链接不可用 → 插件被静默跳过 →
# 链接期 undefined symbol: correction_set_log_file）。rime_link_plugin 已完整
# 覆盖该插件的接入，故不合并上游这段。

# librime-lua 需要特殊命名 lua
file(COPY "${CMAKE_SOURCE_DIR}/librime-lua/"
     DESTINATION "${CMAKE_SOURCE_DIR}/librime/plugins/lua")

# librime-lua thirdparty 依赖（Lua 5.4 源码）
if(NOT EXISTS "${CMAKE_SOURCE_DIR}/librime/plugins/lua/thirdparty")
  file(COPY "${CMAKE_SOURCE_DIR}/librime-lua-deps/"
       DESTINATION "${CMAKE_SOURCE_DIR}/librime/plugins/lua/thirdparty")
endif()

option(BUILD_TEST "" OFF)
option(BUILD_STATIC "" ON)
add_subdirectory(librime)
target_compile_options(
  rime-static PRIVATE "-ffile-prefix-map=${CMAKE_SOURCE_DIR}=." "-Wno-error=deprecated-declarations")

target_compile_options(
  rime-lua-objs PRIVATE "-ffile-prefix-map=${CMAKE_SOURCE_DIR}=.")
