package com.pocketscope.alpaca.model

/**
 * ASCOM standard error codes for Alpaca protocol responses.
 * See: https://ascom-standards.org/Help/Developer/html/T_ASCOM_ErrorCodes.htm
 */
object AlpacaErrors {
    const val SUCCESS = 0
    const val NOT_IMPLEMENTED = 0x400       // 1024
    const val INVALID_VALUE = 0x401         // 1025
    const val VALUE_NOT_SET = 0x402         // 1026
    const val NOT_CONNECTED = 0x407         // 1031
    const val PARKED = 0x408                // 1032
    const val SLAVED = 0x409                // 1033
    const val INVALID_OPERATION = 0x40B     // 1035
    const val ACTION_NOT_IMPLEMENTED = 0x40C // 1036
    const val DRIVER_EXCEPTION = 0x500      // 1280
}
