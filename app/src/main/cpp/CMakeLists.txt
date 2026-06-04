cmake_minimum_required(VERSION 3.22.1)

project("nyandroid-pty")

# Native PTY helper. Bionic provides forkpty()/openpty() via <pty.h>
# on modern Android (API 23+), which is well within our Android 16 target.
add_library(nyandroid-pty SHARED
    pty.c)

find_library(log-lib log)

target_link_libraries(nyandroid-pty
    ${log-lib})
