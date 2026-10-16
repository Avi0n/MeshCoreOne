package one.meshcore.shared.persistence

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

/**
 * Opens the mesh database at [path] on the desktop JVM, backed by the bundled SQLite engine so the
 * desktop and Android builds run the same SQLite version. This platform composition boundary owns
 * the database query dispatcher; feature code never selects one.
 */
@Suppress("InjectDispatcher")
fun meshDatabase(path: String): MeshDatabase = Room.databaseBuilder<MeshDatabase>(name = path)
    .setDriver(BundledSQLiteDriver())
    .setQueryCoroutineContext(Dispatchers.IO)
    .productionSchemaPolicy()
    .build()
