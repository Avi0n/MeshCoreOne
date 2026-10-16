package one.meshcore.shared.prefs

/** Prevents a suspended preference load from replacing state changed after that load began. */
internal class PreferenceLoadGuard<Field> {
    private val generations = mutableMapOf<Field, Long>()

    fun beginLoad(): Snapshot<Field> = Snapshot(generations.toMap())

    fun recordMutation(vararg fields: Field) {
        fields.forEach { field -> generations[field] = generation(field) + 1L }
    }

    fun isCurrent(snapshot: Snapshot<Field>, field: Field): Boolean =
        snapshot.generations[field].orZero() == generation(field)

    fun <Value> resolve(snapshot: Snapshot<Field>, field: Field, loaded: Value, current: Value): Value =
        if (isCurrent(snapshot, field)) loaded else current

    private fun generation(field: Field): Long = generations[field].orZero()

    internal class Snapshot<Field>(internal val generations: Map<Field, Long>)
}

private fun Long?.orZero(): Long = this ?: 0L
