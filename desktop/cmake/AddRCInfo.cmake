# 桌面版为 xime-tsf.dll 提供了自己的产物；无需 librime 的 rime.rc 版本资源。
# librime/src/CMakeLists.txt 在 WIN32 下 include(${CMAKE_SOURCE_DIR}/cmake/AddRCInfo.cmake)，
# 而本项目 CMAKE_SOURCE_DIR 指向 desktop/，故在此提供空实现，避免依赖 librime/cmake 下的原文件。
# （原文件会为 rime 生成 rime.rc 资源并追加到 rime_core_module_src；这里有意省略。）
