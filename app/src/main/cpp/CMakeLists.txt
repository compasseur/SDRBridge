cmake_minimum_required(VERSION 3.22.1)

project("rtlsdrbridge" C)

set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG")

find_library(LOG_LIB log)

# Android 15+ devices can use 16 KB memory pages; Play requires native libs to be
# aligned for them when targeting SDK 35+.
set(PAGE_SIZE_FLAGS "-Wl,-z,max-page-size=16384")

# libusb 1.0.20, patched by Signalware Ltd (rtl_tcp_andro) to open a device from an
# Android UsbDeviceConnection file descriptor (libusb_open2 / libusb_get_device2).
file(GLOB_RECURSE LIBUSB_SOURCES "libusb/libusb/*.c")
add_library(usb STATIC ${LIBUSB_SOURCES})
target_include_directories(usb
        PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/libusb/android"
        PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/libusb/libusb")
target_compile_options(usb PRIVATE -w)
target_link_libraries(usb ${LOG_LIB})

# librtlsdr from the RTL-SDR Blog fork (R820T/R820T2/R860/R828D + Blog V4 support),
# with the Android rtlsdr_open2(fd, path) entry point added.
add_library(rtlsdr STATIC
        librtlsdr/src/librtlsdr.c
        librtlsdr/src/tuner_e4k.c
        librtlsdr/src/tuner_fc0012.c
        librtlsdr/src/tuner_fc0013.c
        librtlsdr/src/tuner_fc2580.c
        librtlsdr/src/tuner_r82xx.c)
target_include_directories(rtlsdr PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/librtlsdr/include")
target_compile_definitions(rtlsdr PRIVATE rtlsdr_STATIC)
target_compile_options(rtlsdr PRIVATE -w -include "${CMAKE_CURRENT_SOURCE_DIR}/android_stderr.h")
target_link_libraries(rtlsdr usb ${LOG_LIB})

# JNI bridge used by rfsource/RtlSdr.kt
add_library(rtlsdrbridge SHARED rtlsdr_jni.c)
target_compile_definitions(rtlsdrbridge PRIVATE rtlsdr_STATIC)
target_compile_options(rtlsdrbridge PRIVATE -Wall)
target_link_libraries(rtlsdrbridge rtlsdr ${LOG_LIB})
target_link_options(rtlsdrbridge PRIVATE ${PAGE_SIZE_FLAGS})
