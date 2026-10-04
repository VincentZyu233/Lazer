# Windows consumes the project's prebuilt shared-development bundle. Linux and macOS use the
# platform package manager's pkg-config metadata, keeping include, linker and runtime names aligned.
if(WIN32)
    include(FindPackageHandleStandardArgs)

    find_path(LAZER_FFMPEG_INCLUDE_DIR
        NAMES libavformat/avformat.h libavcodec/avcodec.h libavutil/version.h libswresample/swresample.h
        PATHS "${LAZER_FFMPEG_ROOT}/include" ENV FFMPEG_ROOT)

    set(_lazer_ffmpeg_components avformat avcodec avutil swresample)
    set(_lazer_ffmpeg_libraries "")
    set(LAZER_FFMPEG_RUNTIME_FILES "")

    foreach(component IN LISTS _lazer_ffmpeg_components)
        file(GLOB candidates
            "${LAZER_FFMPEG_ROOT}/lib/${component}-[0-9]*.lib"
            "${LAZER_FFMPEG_ROOT}/lib/${component}.lib"
            "$ENV{FFMPEG_ROOT}/lib/${component}-[0-9]*.lib"
            "$ENV{FFMPEG_ROOT}/lib/${component}.lib")
        if(NOT candidates)
            message(FATAL_ERROR "FFmpeg import library '${component}' was not found under ${LAZER_FFMPEG_ROOT}/lib")
        endif()
        list(SORT candidates COMPARE NATURAL ORDER DESCENDING)
        list(GET candidates 0 chosen)
        list(APPEND _lazer_ffmpeg_libraries "${chosen}")
        get_filename_component(chosen_name "${chosen}" NAME_WE)
        file(GLOB chosen_dll
            "${LAZER_FFMPEG_ROOT}/bin/${chosen_name}-*.dll"
            "$ENV{FFMPEG_ROOT}/bin/${chosen_name}-*.dll")
        if(chosen_dll)
            list(APPEND LAZER_FFMPEG_RUNTIME_FILES ${chosen_dll})
        endif()
        message(STATUS "Lazer FFmpeg ${component}: ${chosen}")
    endforeach()

    find_package_handle_standard_args(LazerFFmpeg
        REQUIRED_VARS LAZER_FFMPEG_INCLUDE_DIR _lazer_ffmpeg_libraries)

    if(LazerFFmpeg_FOUND)
        set(LAZER_FFMPEG_LIBRARIES ${_lazer_ffmpeg_libraries})
        set(LAZER_FFMPEG_INCLUDE_DIRS ${LAZER_FFMPEG_INCLUDE_DIR})
    endif()
else()
    find_package(PkgConfig REQUIRED)

    # On Unix hosts, a caller may point at a custom shared FFmpeg development package. Search its
    # pkg-config metadata before the system paths so the headers, linker flags and runtime copy
    # all come from the same package. With no custom root, use the distribution's pkg-config data.
    set(_lazer_ffmpeg_root "${LAZER_FFMPEG_ROOT}")
    if(NOT _lazer_ffmpeg_root AND DEFINED ENV{FFMPEG_ROOT})
        set(_lazer_ffmpeg_root "$ENV{FFMPEG_ROOT}")
    endif()
    if(_lazer_ffmpeg_root)
        file(TO_CMAKE_PATH "${_lazer_ffmpeg_root}" _lazer_ffmpeg_root)
        set(_lazer_ffmpeg_pkgconfig_dirs
            "${_lazer_ffmpeg_root}/lib/pkgconfig"
            "${_lazer_ffmpeg_root}/lib64/pkgconfig"
            "${_lazer_ffmpeg_root}/lib/${CMAKE_LIBRARY_ARCHITECTURE}/pkgconfig")
        set(_lazer_ffmpeg_pkgconfig_dirs_existing "")
        foreach(directory IN LISTS _lazer_ffmpeg_pkgconfig_dirs)
            if(IS_DIRECTORY "${directory}")
                list(APPEND _lazer_ffmpeg_pkgconfig_dirs_existing "${directory}")
            endif()
        endforeach()
        if(NOT _lazer_ffmpeg_pkgconfig_dirs_existing)
            message(FATAL_ERROR
                "FFmpeg root '${_lazer_ffmpeg_root}' has no lib/pkgconfig, lib64/pkgconfig, or architecture-specific pkgconfig directory")
        endif()
        foreach(component avformat avcodec avutil swresample)
            set(_lazer_pc_found FALSE)
            foreach(directory IN LISTS _lazer_ffmpeg_pkgconfig_dirs_existing)
                if(EXISTS "${directory}/lib${component}.pc")
                    set(_lazer_pc_found TRUE)
                    break()
                endif()
            endforeach()
            if(NOT _lazer_pc_found)
                message(FATAL_ERROR
                    "FFmpeg root '${_lazer_ffmpeg_root}' is missing pkg-config module lib${component}.pc")
            endif()
        endforeach()

        set(_lazer_saved_pkg_config_path "$ENV{PKG_CONFIG_PATH}")
        string(JOIN ":" _lazer_ffmpeg_pkgconfig_path ${_lazer_ffmpeg_pkgconfig_dirs_existing})
        if(_lazer_saved_pkg_config_path)
            set(ENV{PKG_CONFIG_PATH} "${_lazer_ffmpeg_pkgconfig_path}:${_lazer_saved_pkg_config_path}")
        else()
            set(ENV{PKG_CONFIG_PATH} "${_lazer_ffmpeg_pkgconfig_path}")
        endif()
    endif()

    pkg_check_modules(LAZER_FFMPEG REQUIRED IMPORTED_TARGET
        libavformat libavcodec libavutil libswresample)
    if(_lazer_ffmpeg_root)
        set(ENV{PKG_CONFIG_PATH} "${_lazer_saved_pkg_config_path}")
    endif()
    set(LAZER_FFMPEG_INCLUDE_DIR ${LAZER_FFMPEG_INCLUDE_DIRS})
    set(LAZER_FFMPEG_LIBRARIES PkgConfig::LAZER_FFMPEG)

    foreach(component avformat avcodec avutil swresample)
        foreach(directory IN LISTS LAZER_FFMPEG_LIBRARY_DIRS)
            file(GLOB runtime_files
                "${directory}/lib${component}.so*"
                "${directory}/lib${component}.dylib*")
            list(APPEND LAZER_FFMPEG_RUNTIME_FILES ${runtime_files})
        endforeach()
    endforeach()
    list(REMOVE_DUPLICATES LAZER_FFMPEG_RUNTIME_FILES)
    set(LazerFFmpeg_FOUND TRUE)
    if(_lazer_ffmpeg_root)
        message(STATUS "Lazer FFmpeg via pkg-config under ${_lazer_ffmpeg_root}: ${LAZER_FFMPEG_VERSION}")
    else()
        message(STATUS "Lazer FFmpeg via system pkg-config: ${LAZER_FFMPEG_VERSION}")
    endif()
endif()
