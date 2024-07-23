# Sets the minimum version of CMake required to build your native library.
# This ensures that a certain set of CMake features is available to
# your build.

cmake_minimum_required(VERSION 3.4.1)

# Specifies a library name, specifies whether the library is STATIC or
# SHARED, and provides relative paths to the source code. You can
# define multiple libraries by adding multiple add_library() commands,
# and CMake builds them for you. When you build your app, Gradle
# automatically packages shared libraries with your APK.

add_library( # Specifies the name of the library.
             secrets

             # Sets the library as a shared library.
             SHARED

             # Provides a relative path to your source file(s).
             secrets.cpp )

# On different build machines different NDK paths and different paths
# to the project (and thus to its jni directory) are used. This leads
# to different paths to the source files in debug symbols, causing
# linker to generate different build-id, which is preserved after
# stripping.

target_link_libraries(secrets -Wl,--build-id=none)
