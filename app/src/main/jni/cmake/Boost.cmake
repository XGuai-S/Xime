# SPDX-FileCopyrightText: 2015 - 2024 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later

set(BOOST_VERSION 1.89.0)

if(NOT EXISTS "boost-${BOOST_VERSION}.tar.xz" AND NOT EXISTS "${CMAKE_SOURCE_DIR}/boost")
  set(BOOST_SHA256 "67acec02d0d118b5de9eb441f5fb707b3a1cdd884be00ca24b9a73c995511f74")
  set(BOOST_URL "https://github.com/boostorg/boost/releases/download/boost-${BOOST_VERSION}/boost-${BOOST_VERSION}-cmake.tar.xz")

  message(STATUS "Downloading Boost ${BOOST_VERSION} from GitHub ......")
  file(DOWNLOAD "${BOOST_URL}" boost-${BOOST_VERSION}.tar.xz
       EXPECTED_HASH SHA256=${BOOST_SHA256} SHOW_PROGRESS STATUS _dl_status)
  list(GET _dl_status 0 _dl_code)
  list(GET _dl_status 1 _dl_msg)

  if(NOT _dl_code EQUAL 0)
    message(STATUS "GitHub download failed (${_dl_msg}), trying ghproxy mirror ...")
    file(DOWNLOAD "https://ghproxy.net/${BOOST_URL}" boost-${BOOST_VERSION}.tar.xz
         EXPECTED_HASH SHA256=${BOOST_SHA256} SHOW_PROGRESS STATUS _dl_status2)
    list(GET _dl_status2 0 _dl_code2)

    if(NOT _dl_code2 EQUAL 0)
      message(STATUS "ghproxy also failed, trying local Boost source ...")
      file(REMOVE_RECURSE "${CMAKE_SOURCE_DIR}/boost")
      if(EXISTS "/tmp/boost_${BOOST_VERSION}")
        file(COPY "/tmp/boost_${BOOST_VERSION}/" DESTINATION "${CMAKE_SOURCE_DIR}/boost")
      elseif(EXISTS "/tmp/boost_1_89_0")
        file(COPY "/tmp/boost_1_89_0/" DESTINATION "${CMAKE_SOURCE_DIR}/boost")
      else()
        message(FATAL_ERROR "Cannot download Boost ${BOOST_VERSION}. "
          "Please download boost_${BOOST_VERSION}.tar.bz2 from "
          "https://archives.boost.io/release/${BOOST_VERSION}/source/ "
          "and extract to /tmp/boost_${BOOST_VERSION}/")
      endif()
    endif()
  endif()

  if(_dl_code EQUAL 0 OR _dl_code2 EQUAL 0)
    message(STATUS "Remove older version Boost")
    file(REMOVE_RECURSE "${CMAKE_SOURCE_DIR}/boost")
  endif()
endif()

if(NOT EXISTS "${CMAKE_SOURCE_DIR}/boost")
  if(EXISTS "boost-${BOOST_VERSION}.tar.xz")
    message(STATUS "Extracting Boost ${BOOST_VERSION} from cached archive ......")
    file(ARCHIVE_EXTRACT INPUT boost-${BOOST_VERSION}.tar.xz DESTINATION
         ${CMAKE_SOURCE_DIR})
    file(RENAME "boost-${BOOST_VERSION}" boost)
  endif()
endif()

# The CMake adaptor may not include header-only Boost libraries.
# Ensure all header-only targets exist to satisfy transitive dependencies.
set(BOOST_INCLUDE_LIBRARIES
    algorithm
    crc
    dll
    interprocess
    range
    regex
    scope_exit
    signals2
    utility
    uuid)

add_subdirectory(boost EXCLUDE_FROM_ALL)

# Create INTERFACE IMPORTED targets for any Boost::* library that is
# referenced as a dependency but not provided by the CMake adaptor.
set(__boost_header_only_libs
    algorithm any array assert bind concept_check config container_hash
    conversion core crc detail foreach function integer io iterator
    lexical_cast move mp11 mpl multi_index numeric_conversion optional
    predef preprocessor range rational scope_exit signals2 smart_ptr
    static_assert system throw_exception tti tuple type_index type_traits
    typeof utility variant variant2)
foreach(__boost_lib ${__boost_header_only_libs})
  if(NOT TARGET "Boost::${__boost_lib}")
    add_library("Boost::${__boost_lib}" INTERFACE IMPORTED GLOBAL)
  endif()
endforeach()