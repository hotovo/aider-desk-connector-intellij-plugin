package com.hotovo.plugins.aiderdesk

enum class ConnectionStatus(val displayName: String) {
    DISCONNECTED("Disconnected"),
    CONNECTING("Connecting..."),
    CONNECTED("Connected"),
    ERROR("Error")
}
