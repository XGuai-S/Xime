# 在 Windows 上从 app/src/main/jni 的捆绑源码构建 librime + T9 定制插件。
# 与 Android CMake 同源，但去掉 GCC/Clang 专有参数，改用 MSVC 可用配置。

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_TEST OFF CACHE BOOL "" FORCE)
set(BUILD_STATIC ON CACHE BOOL "" FORCE)
set(BUILD_DATA OFF CACHE BOOL "" FORCE)
set(BUILD_SAMPLE OFF CACHE BOOL "" FORCE)
set(BUILD_MERGED_PLUGINS ON CACHE BOOL "" FORCE)
set(WITH_GFLAGS OFF CACHE BOOL "" FORCE)
set(WITH_GTEST OFF CACHE BOOL "" FORCE)
set(WITH_UNWIND "none" CACHE STRING "" FORCE)
set(ENABLE_LOGGING ON CACHE BOOL "" FORCE)

# ── Boost 头文件（捆绑源码，无需下载）────────────────────────────────────────
include_directories(
  "${XIME_JNI_DIR}/boost/boost"
  "${XIME_JNI_DIR}/boost/libs/algorithm/include"
  "${XIME_JNI_DIR}/boost/libs/crc/include"
  "${XIME_JNI_DIR}/boost/libs/dll/include"
  "${XIME_JNI_DIR}/boost/libs/interprocess/include"
  "${XIME_JNI_DIR}/boost/libs/range/include"
  "${XIME_JNI_DIR}/boost/libs/regex/include"
  "${XIME_JNI_DIR}/boost/libs/scope_exit/include"
  "${XIME_JNI_DIR}/boost/libs/signals2/include"
  "${XIME_JNI_DIR}/boost/libs/utility/include"
  "${XIME_JNI_DIR}/boost/libs/uuid/include"
  "${XIME_JNI_DIR}/boost/libs/assert/include"
  "${XIME_JNI_DIR}/boost/libs/config/include"
  "${XIME_JNI_DIR}/boost/libs/core/include"
  "${XIME_JNI_DIR}/boost/libs/throw_exception/include"
  "${XIME_JNI_DIR}/boost/libs/static_assert/include"
  "${XIME_JNI_DIR}/boost/libs/mpl/include"
  "${XIME_JNI_DIR}/boost/libs/preprocessor/include"
  "${XIME_JNI_DIR}/boost/libs/vmd/include"
  "${XIME_JNI_DIR}/boost/libs/type_traits/include"
  "${XIME_JNI_DIR}/boost/libs/smart_ptr/include"
  "${XIME_JNI_DIR}/boost/libs/move/include"
  "${XIME_JNI_DIR}/boost/libs/container/include"
  "${XIME_JNI_DIR}/boost/libs/container_hash/include"
  "${XIME_JNI_DIR}/boost/libs/describe/include"
  "${XIME_JNI_DIR}/boost/libs/intrusive/include"
  "${XIME_JNI_DIR}/boost/libs/detail/include"
  "${XIME_JNI_DIR}/boost/libs/integer/include"
  "${XIME_JNI_DIR}/boost/libs/predef/include"
  "${XIME_JNI_DIR}/boost/libs/io/include"
  "${XIME_JNI_DIR}/boost/libs/lexical_cast/include"
  "${XIME_JNI_DIR}/boost/libs/numeric/conversion/include"
  "${XIME_JNI_DIR}/boost/libs/spirit/include"
  "${XIME_JNI_DIR}/boost/libs/serialization/include"
  "${XIME_JNI_DIR}/boost/libs/foreach/include"
  "${XIME_JNI_DIR}/boost/libs/iterator/include"
  "${XIME_JNI_DIR}/boost/libs/function/include"
  "${XIME_JNI_DIR}/boost/libs/bind/include"
  "${XIME_JNI_DIR}/boost/libs/tuple/include"
  "${XIME_JNI_DIR}/boost/libs/optional/include"
  "${XIME_JNI_DIR}/boost/libs/any/include"
  "${XIME_JNI_DIR}/boost/libs/array/include"
  "${XIME_JNI_DIR}/boost/libs/mp11/include"
  "${XIME_JNI_DIR}/boost/libs/unordered/include"
  "${XIME_JNI_DIR}/boost/libs/multi_index/include"
  "${XIME_JNI_DIR}/boost/libs/variant/include"
  "${XIME_JNI_DIR}/boost/libs/concept_check/include"
)

# ── 捆绑依赖 ─────────────────────────────────────────────────────────────────
set(YAML_CPP_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(YAML_CPP_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(YAML_CPP_INSTALL OFF CACHE BOOL "" FORCE)
add_subdirectory("${XIME_JNI_DIR}/librime/deps/yaml-cpp" "${CMAKE_BINARY_DIR}/yaml-cpp")
set(YamlCpp_FOUND TRUE CACHE BOOL "" FORCE)
set(YamlCpp_INCLUDE_PATH "${XIME_JNI_DIR}/librime/deps/yaml-cpp/include" CACHE PATH "" FORCE)
set(YamlCpp_LIBRARY yaml-cpp CACHE STRING "" FORCE)
set(YamlCpp_STATIC TRUE CACHE BOOL "" FORCE)
set(YamlCpp_NEW_API TRUE CACHE BOOL "" FORCE)

set(SNAPPY_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(SNAPPY_BUILD_BENCHMARKS OFF CACHE BOOL "" FORCE)
set(SNAPPY_INSTALL OFF CACHE BOOL "" FORCE)
add_subdirectory("${XIME_JNI_DIR}/snappy" "${CMAKE_BINARY_DIR}/snappy")
set(HAVE_SNAPPY TRUE)

set(LEVELDB_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LEVELDB_BUILD_BENCHMARKS OFF CACHE BOOL "" FORCE)
set(LEVELDB_INSTALL OFF CACHE BOOL "" FORCE)
add_subdirectory("${XIME_JNI_DIR}/librime/deps/leveldb" "${CMAKE_BINARY_DIR}/leveldb")
set(LevelDb_FOUND TRUE CACHE BOOL "" FORCE)
set(LevelDb_INCLUDE_PATH "${XIME_JNI_DIR}/librime/deps/leveldb/include" CACHE PATH "" FORCE)
set(LevelDb_LIBRARY leveldb CACHE STRING "" FORCE)
set(LevelDb_STATIC TRUE CACHE BOOL "" FORCE)

set(ENABLE_TOOLS OFF CACHE BOOL "" FORCE)
add_subdirectory("${XIME_JNI_DIR}/librime/deps/marisa-trie" "${CMAKE_BINARY_DIR}/marisa")
set(Marisa_FOUND TRUE CACHE BOOL "" FORCE)
set(Marisa_INCLUDE_PATH "${XIME_JNI_DIR}/librime/deps/marisa-trie/include" CACHE PATH "" FORCE)
set(Marisa_LIBRARY marisa CACHE STRING "" FORCE)
set(Marisa_STATIC TRUE CACHE BOOL "" FORCE)
set(LIBMARISA marisa)
set(USE_SYSTEM_MARISA ON CACHE BOOL "" FORCE)

add_subdirectory("${XIME_JNI_DIR}/librime/deps/opencc" "${CMAKE_BINARY_DIR}/opencc")
set(Opencc_FOUND TRUE CACHE BOOL "" FORCE)
set(Opencc_INCLUDE_PATH "${XIME_JNI_DIR}/librime/deps/opencc/src" CACHE PATH "" FORCE)
set(Opencc_LIBRARY libopencc CACHE STRING "" FORCE)
set(Opencc_STATIC TRUE CACHE BOOL "" FORCE)

include_directories("${XIME_JNI_DIR}/librime/include")

# ── 插件接入 ─────────────────────────────────────────────────────────────────
# Windows 无开发者模式时符号链接可能返回成功但产出空目录，故一律用拷贝。
function(xime_copy_plugin name source)
  set(_dst "${XIME_JNI_DIR}/librime/plugins/${name}")
  if(EXISTS "${_dst}")
    file(REMOVE_RECURSE "${_dst}")
  endif()
  file(COPY "${source}/" DESTINATION "${_dst}")
  if(NOT EXISTS "${_dst}/CMakeLists.txt")
    message(FATAL_ERROR "插件 ${name} 接入失败：${_dst}")
  endif()
endfunction()

xime_copy_plugin(librime-t9 "${XIME_JNI_DIR}/librime-t9")
xime_copy_plugin(librime-correction "${XIME_JNI_DIR}/librime-correction")
xime_copy_plugin(librime-octagram "${XIME_JNI_DIR}/librime-octagram")
xime_copy_plugin(librime-predict "${XIME_JNI_DIR}/librime-predict")

if(NOT EXISTS "${XIME_JNI_DIR}/librime/plugins/lua")
  file(COPY "${XIME_JNI_DIR}/librime-lua/" DESTINATION "${XIME_JNI_DIR}/librime/plugins/lua")
endif()
if(NOT EXISTS "${XIME_JNI_DIR}/librime/plugins/lua/thirdparty")
  file(COPY "${XIME_JNI_DIR}/librime-lua-deps/" DESTINATION "${XIME_JNI_DIR}/librime/plugins/lua/thirdparty")
endif()

# ── librime 本体 ─────────────────────────────────────────────────────────────
# 模块路径需指向 librime/cmake（AddRCInfo.cmake 等）；BOOST_DLL 定义在
# Boost 头文件齐备时手动补上（find_package(Boost) 在 Windows 非必需，这里跳过）。
set(CMAKE_MODULE_PATH "${XIME_JNI_DIR}/librime/cmake" "${CMAKE_CURRENT_SOURCE_DIR}/cmake")
add_definitions(-DBOOST_DLL_USE_STD_FS)
set(Boost_FOUND FALSE)

add_subdirectory("${XIME_JNI_DIR}/librime" "${CMAKE_BINARY_DIR}/librime")

# 统一导出名：rime-static 由 librime 的 BUILD_STATIC=ON 提供。
if(NOT TARGET rime-static)
  message(FATAL_ERROR "librime 未提供 rime-static 目标")
endif()
