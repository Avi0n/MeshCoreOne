package one.meshcore.shared.remotenode.ui

internal data class NeighborChartTarget(val name: String, val prefix: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NeighborChartTarget) return false
        return name == other.name && prefix.contentEquals(other.prefix)
    }

    override fun hashCode(): Int = 31 * name.hashCode() + prefix.contentHashCode()
}
