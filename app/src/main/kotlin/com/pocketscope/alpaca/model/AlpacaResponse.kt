package com.pocketscope.alpaca.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StringResponse(
    @SerialName("Value") val value: String,
    @SerialName("ClientTransactionID") val clientTransactionID: Int = 0,
    @SerialName("ServerTransactionID") val serverTransactionID: Int = 0,
    @SerialName("ErrorNumber") val errorNumber: Int = 0,
    @SerialName("ErrorMessage") val errorMessage: String = ""
)

@Serializable
data class BoolResponse(
    @SerialName("Value") val value: Boolean,
    @SerialName("ClientTransactionID") val clientTransactionID: Int = 0,
    @SerialName("ServerTransactionID") val serverTransactionID: Int = 0,
    @SerialName("ErrorNumber") val errorNumber: Int = 0,
    @SerialName("ErrorMessage") val errorMessage: String = ""
)

@Serializable
data class DoubleResponse(
    @SerialName("Value") val value: Double,
    @SerialName("ClientTransactionID") val clientTransactionID: Int = 0,
    @SerialName("ServerTransactionID") val serverTransactionID: Int = 0,
    @SerialName("ErrorNumber") val errorNumber: Int = 0,
    @SerialName("ErrorMessage") val errorMessage: String = ""
)

@Serializable
data class IntResponse(
    @SerialName("Value") val value: Int,
    @SerialName("ClientTransactionID") val clientTransactionID: Int = 0,
    @SerialName("ServerTransactionID") val serverTransactionID: Int = 0,
    @SerialName("ErrorNumber") val errorNumber: Int = 0,
    @SerialName("ErrorMessage") val errorMessage: String = ""
)

@Serializable
data class IntArrayResponse(
    @SerialName("Value") val value: List<Int>,
    @SerialName("ClientTransactionID") val clientTransactionID: Int = 0,
    @SerialName("ServerTransactionID") val serverTransactionID: Int = 0,
    @SerialName("ErrorNumber") val errorNumber: Int = 0,
    @SerialName("ErrorMessage") val errorMessage: String = ""
)

@Serializable
data class StringArrayResponse(
    @SerialName("Value") val value: List<String>,
    @SerialName("ClientTransactionID") val clientTransactionID: Int = 0,
    @SerialName("ServerTransactionID") val serverTransactionID: Int = 0,
    @SerialName("ErrorNumber") val errorNumber: Int = 0,
    @SerialName("ErrorMessage") val errorMessage: String = ""
)

@Serializable
data class ImageArrayResponse(
    @SerialName("Type") val type: Int = 2,       // 2 = Int32
    @SerialName("Rank") val rank: Int = 2,        // 2 = 2D
    @SerialName("Value") val value: List<List<Int>>,
    @SerialName("ClientTransactionID") val clientTransactionID: Int = 0,
    @SerialName("ServerTransactionID") val serverTransactionID: Int = 0,
    @SerialName("ErrorNumber") val errorNumber: Int = 0,
    @SerialName("ErrorMessage") val errorMessage: String = ""
)

@Serializable
data class MethodResponse(
    @SerialName("ClientTransactionID") val clientTransactionID: Int = 0,
    @SerialName("ServerTransactionID") val serverTransactionID: Int = 0,
    @SerialName("ErrorNumber") val errorNumber: Int = 0,
    @SerialName("ErrorMessage") val errorMessage: String = ""
)

@Serializable
data class ServerDescriptionResponse(
    @SerialName("Value") val value: ServerDescription,
    @SerialName("ClientTransactionID") val clientTransactionID: Int = 0,
    @SerialName("ServerTransactionID") val serverTransactionID: Int = 0,
    @SerialName("ErrorNumber") val errorNumber: Int = 0,
    @SerialName("ErrorMessage") val errorMessage: String = ""
)

@Serializable
data class ConfiguredDevicesResponse(
    @SerialName("Value") val value: List<ConfiguredDevice>,
    @SerialName("ClientTransactionID") val clientTransactionID: Int = 0,
    @SerialName("ServerTransactionID") val serverTransactionID: Int = 0,
    @SerialName("ErrorNumber") val errorNumber: Int = 0,
    @SerialName("ErrorMessage") val errorMessage: String = ""
)

@Serializable
data class ServerDescription(
    @SerialName("ServerName") val serverName: String,
    @SerialName("Manufacturer") val manufacturer: String,
    @SerialName("ManufacturerVersion") val manufacturerVersion: String,
    @SerialName("Location") val location: String
)

@Serializable
data class ConfiguredDevice(
    @SerialName("DeviceName") val deviceName: String,
    @SerialName("DeviceType") val deviceType: String,
    @SerialName("DeviceNumber") val deviceNumber: Int,
    @SerialName("UniqueID") val uniqueID: String
)
