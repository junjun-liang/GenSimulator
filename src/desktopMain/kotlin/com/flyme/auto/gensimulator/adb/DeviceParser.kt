package com.flyme.auto.gensimulator.adb

object DeviceParser {
    fun parse(output: String): List<DeviceInfo> = output.lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("List of devices attached") && !it.startsWith("*") }
        .mapNotNull(::parseLine)
        .toList()

    private fun parseLine(line: String): DeviceInfo? {
        val parts = line.split(Regex("\\s+"))
        if (parts.size < 2) return null

        val serial = parts[0]
        val state = when (parts[1].lowercase()) {
            "device" -> DeviceState.DEVICE
            "offline" -> DeviceState.OFFLINE
            "unauthorized" -> DeviceState.UNAUTHORIZED
            else -> DeviceState.UNKNOWN
        }
        val metadata = parts.drop(2)
            .mapNotNull { token ->
                val index = token.indexOf(':')
                if (index <= 0 || index == token.lastIndex) null
                else token.substring(0, index) to token.substring(index + 1)
            }
            .toMap()

        return DeviceInfo(
            serial = serial,
            state = state,
            model = metadata["model"]?.replace('_', ' '),
            product = metadata["product"],
            device = metadata["device"],
        )
    }
}
