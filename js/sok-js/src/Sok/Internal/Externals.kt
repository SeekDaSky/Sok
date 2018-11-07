package Sok.Internal

external fun setTimeout(operation : () -> Unit, time : Int)
external fun require(module : String) : dynamic
val net = require("net")

external class console{
    companion object {
        fun log(value : dynamic)
    }
}