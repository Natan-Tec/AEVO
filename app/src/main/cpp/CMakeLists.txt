cmake_minimum_required(VERSION 3.22.1)
project(aevo)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# O build "debug" do Gradle compila C++ sem otimização, o que deixa o áudio engasgando em
# aparelhos fracos. O motor de áudio sempre é compilado otimizado.
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O2")
set(CMAKE_CXX_FLAGS_DEBUG "-O2 -g")

find_package(oboe REQUIRED CONFIG)

add_library(aevo SHARED aevo_engine.cpp)

find_library(log-lib log)
target_link_libraries(aevo oboe::oboe ${log-lib})
