package one.meshcore.shared.tools

fun ToolsCliSession.startCountdown(seconds: Int) {
    remainingSeconds = seconds
}

fun ToolsCliSession.tickCountdown() {
    remainingSeconds = remainingSeconds?.let { left -> if (left <= 1) null else left - 1 }
}

fun ToolsCliSession.stopCountdown() {
    remainingSeconds = null
}
