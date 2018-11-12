package Sok.Internal

external fun require(module : String) : dynamic
val net = require("net")

external class console{
    companion object {
        fun log(value : dynamic)
    }
}