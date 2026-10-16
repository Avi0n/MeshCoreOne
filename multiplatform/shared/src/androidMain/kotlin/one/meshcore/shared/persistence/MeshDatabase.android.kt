package one.meshcore.shared.persistence

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Opens the mesh database on Android, backed by the bundled SQLite engine so the Android and desktop
 * builds run the same SQLite version. [name] is the database file name within the app's database dir.
 */
fun meshDatabase(context: Context, queryDispatcher: CoroutineDispatcher, name: String = "mesh.db"): MeshDatabase =
    Room.databaseBuilder<MeshDatabase>(context, context.getDatabasePath(name).absolutePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(queryDispatcher)
        .productionSchemaPolicy()
        .build()
