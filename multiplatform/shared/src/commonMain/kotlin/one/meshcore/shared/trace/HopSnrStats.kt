package one.meshcore.shared.trace

/** Per-hop SNR statistics across a batch of trace runs: mean, minimum, and maximum received SNR in dB. */
data class HopSnrStats(val avg: Double, val min: Double, val max: Double)
