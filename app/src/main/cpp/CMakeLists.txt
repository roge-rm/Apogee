cmake_minimum_required(VERSION 3.22)
project(apogee_audio CXX)

# Oboe comes from Maven as a prefab package.
find_package(oboe REQUIRED CONFIG)

add_library(apogee_audio SHARED
        engine.cpp
        synth/synth.cpp)

target_include_directories(apogee_audio PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(apogee_audio oboe::oboe log)
