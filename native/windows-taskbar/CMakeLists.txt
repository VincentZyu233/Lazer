cmake_minimum_required(VERSION 3.21)
project(lazer_taskbar_bridge LANGUAGES CXX)

add_library(lazer-taskbar-bridge SHARED taskbar_bridge.cpp)
target_compile_features(lazer-taskbar-bridge PRIVATE cxx_std_20)
target_compile_definitions(lazer-taskbar-bridge PRIVATE UNICODE _UNICODE WIN32_LEAN_AND_MEAN NOMINMAX)
target_link_libraries(lazer-taskbar-bridge PRIVATE comctl32 ole32 shell32 user32)
set_target_properties(lazer-taskbar-bridge PROPERTIES
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/Release"
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}/Release"
)

# The DLL is shipped inside jpackage resources, so do not depend on a redistributable being
# installed on the target machine.
if(MSVC)
    set_property(TARGET lazer-taskbar-bridge PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()
