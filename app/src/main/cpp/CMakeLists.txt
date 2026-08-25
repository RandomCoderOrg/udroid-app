cmake_minimum_required(VERSION 3.22.1)

project(udroid_ahb_presenter LANGUAGES CXX)

add_library(
    udroid_ahb_presenter
    SHARED
    ahb_surface_presenter.cpp
)

target_compile_options(
    udroid_ahb_presenter
    PRIVATE
    -fvisibility=hidden
    -fvisibility-inlines-hidden
)

target_link_libraries(
    udroid_ahb_presenter
    PRIVATE
    android
    dl
    EGL
    GLESv2
    log
)
