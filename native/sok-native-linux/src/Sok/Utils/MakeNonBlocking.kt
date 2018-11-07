package Sok.Utils

import platform.posix.*

/**
 * Make a linux file descriptor non-blocking (mandatory for poll calls)
 *
 * @param fd file descriptor of the socket
 * @return did the opertion succeed
 */
fun makeNonBlocking(fd : Int) : Boolean{
    return fcntl(fd, F_SETFL, fcntl(fd, F_GETFL, 0).or(O_NONBLOCK)) != -1
}