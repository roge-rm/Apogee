cmake_minimum_required(VERSION 3.22)
project(apogee_desktop CXX)

# The sound engine for desktop: the synth and JNI calls from app/src/main/cpp, with miniaudio
# opening the device in place of Oboe.

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()
set(CMAKE_CXX_FLAGS_RELEASE "-O2")
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(CPP "${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp")

# Cross builds pass JNI_INCLUDE_DIRS themselves: the headers are the same everywhere, and FindJNI
# would look for a JVM of the target's.
if(NOT JNI_INCLUDE_DIRS)
    find_package(JNI REQUIRED)
endif()

find_package(Threads REQUIRED)

add_library(apogee_audio SHARED
    engine_desktop.cpp
    ${CPP}/jni.cpp
    ${CPP}/synth/synth.cpp)

target_include_directories(apogee_audio PRIVATE ${CPP} ${CMAKE_CURRENT_SOURCE_DIR}/miniaudio ${JNI_INCLUDE_DIRS})
# miniaudio is C that also compiles as C++. Its warnings aren't ours.
set_source_files_properties(engine_desktop.cpp PROPERTIES COMPILE_OPTIONS "-w")

if(WIN32)
    target_link_libraries(apogee_audio ole32)
    target_link_options(apogee_audio PRIVATE -static -static-libgcc -static-libstdc++)

    # Apogee.exe, which starts the app on the Java runtime next to it (desktop/windows/launcher.c),
    # with the app's icon.
    enable_language(C RC)
    set(APOGEE_VERSION "0.0" CACHE STRING "The version the launcher reports")
    set(LAUNCHER_ICON ${CMAKE_CURRENT_SOURCE_DIR}/../windows/apogee.ico)
    configure_file(../windows/launcher.rc.in ${CMAKE_BINARY_DIR}/launcher.rc @ONLY)
    add_executable(launcher WIN32 ../windows/launcher.c ${CMAKE_BINARY_DIR}/launcher.rc)
    set_target_properties(launcher PROPERTIES OUTPUT_NAME Apogee)
    target_compile_definitions(launcher PRIVATE _WIN32_WINNT=0x0A00 UNICODE _UNICODE)
    target_link_options(launcher PRIVATE -municode -static)
    target_link_libraries(launcher shell32)
else()
    # miniaudio opens PulseAudio or ALSA itself at run time.
    target_link_libraries(apogee_audio Threads::Threads ${CMAKE_DL_LIBS} m)
endif()
# The DLL is apogee_audio.dll, without MinGW's lib prefix, for System.loadLibrary.
set_target_properties(apogee_audio PROPERTIES PREFIX "")
if(NOT WIN32)
    set_target_properties(apogee_audio PROPERTIES PREFIX "lib")
endif()
