package one.meshcore.shared.persistence

import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import one.meshcore.shared.backup.ConnectionMethod
import one.meshcore.shared.backup.backupJson
import one.meshcore.shared.model.Uuid

/**
 * Room type converters for the mesh database. The [uuidToString]/[stringToUuid] pair is the single
 * shared UUID converter: every entity id and `radioID` column stores the uppercase canonical form, so
 * persistence and the backup wire agree on one representation. The remaining converters serialize the
 * list-valued columns (which SwiftData stored as transformable arrays) as JSON text, reusing the
 * backup serializers so a column and its DTO can never diverge.
 */
class Converters {
    @TypeConverter
    fun uuidToString(value: Uuid?): String? = value?.value?.uppercase()

    @TypeConverter
    fun stringToUuid(value: String?): Uuid? = value?.let { Uuid.normalized(it) }

    @TypeConverter
    fun connectionMethodsToString(value: List<ConnectionMethod>): String =
        backupJson.encodeToString(ListSerializer(ConnectionMethod.serializer()), value)

    @TypeConverter
    fun stringToConnectionMethods(value: String): List<ConnectionMethod> =
        backupJson.decodeFromString(ListSerializer(ConnectionMethod.serializer()), value)

    @TypeConverter
    fun stringListToString(value: List<String>): String =
        backupJson.encodeToString(ListSerializer(String.serializer()), value)

    @TypeConverter
    fun stringToStringList(value: String): List<String> =
        backupJson.decodeFromString(ListSerializer(String.serializer()), value)

    @TypeConverter
    fun doubleListToString(value: List<Double>): String =
        backupJson.encodeToString(ListSerializer(Double.serializer()), value)

    @TypeConverter
    fun stringToDoubleList(value: String): List<Double> =
        backupJson.decodeFromString(ListSerializer(Double.serializer()), value)
}
