package Sok.Utils

import platform.posix.*

fun makeNonBlocking(fd : Int) : Boolean{
    return fcntl(fd, F_SETFL, fcntl(fd, F_GETFL, 0).or(O_NONBLOCK)) != -1
}