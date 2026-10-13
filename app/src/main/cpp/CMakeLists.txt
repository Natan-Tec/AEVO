cmake_minimum_required(VERSION 3.22.1)
project(aevo)

add_library(aevo SHARED aevo_engine.cpp)

find_library(log-lib log)
target_link_libraries(aevo ${log-lib})
