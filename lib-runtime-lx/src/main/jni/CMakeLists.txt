cmake_minimum_required(VERSION 3.22.1)
project(lx-runtime LANGUAGES CXX)

add_library(lx-runtime SHARED lx_interrupt.cpp)
target_compile_features(lx-runtime PRIVATE cxx_std_17)
target_link_libraries(lx-runtime PRIVATE ${CMAKE_DL_LIBS})
