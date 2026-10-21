cmake_minimum_required(VERSION 3.22.1)
project(aevo)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(oboe REQUIRED CONFIG)

add_library(aevo SHARED aevo_engine.cpp)

find_library(log-lib log)
target_link_libraries(aevo oboe::oboe ${log-lib})
