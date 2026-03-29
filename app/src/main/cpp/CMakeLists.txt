cmake_minimum_required(VERSION 3.22.1)

project("whisperbridge")

add_library(
    whisperbridge
    SHARED
    whisperbridge.cpp
)

find_library(log-lib log)

target_link_libraries(
    whisperbridge
    ${log-lib}
)
