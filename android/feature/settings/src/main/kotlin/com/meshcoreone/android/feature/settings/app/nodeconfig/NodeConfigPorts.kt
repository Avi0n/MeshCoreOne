// PortedFrom: MC1/Views/Settings/NodeConfig/NodeConfigImportViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Settings/NodeConfig/NodeConfigExportViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.settings.app.nodeconfig

import com.meshcoreone.android.core.model.ConfigSections
import com.meshcoreone.android.core.model.MeshCoreNodeConfig

/** A captured connection, never a lookup that can redirect an operation to another radio. */
interface NodeConfigSession {
    val deviceName: String
    fun requireCurrent()
    suspend fun export(sections: ConfigSections): MeshCoreNodeConfig
    suspend fun current(): MeshCoreNodeConfig
    suspend fun preview(config: MeshCoreNodeConfig, sections: ConfigSections): Boolean
    suspend fun apply(config: MeshCoreNodeConfig, sections: ConfigSections, progress: (NodeConfigProgress) -> Unit)
    suspend fun refresh()
}

data class NodeConfigProgress(val current: Int, val total: Int, val description: String)

interface NodeConfigCodec {
    fun decode(bytes: ByteArray): MeshCoreNodeConfig
    fun encode(config: MeshCoreNodeConfig): ByteArray
}

interface NodeConfigDocuments {
    /** Null means the user dismissed the picker. */
    suspend fun pick(): ByteArray?
    /** True only after the selected document was written and closed. */
    suspend fun save(name: String, bytes: ByteArray): Boolean
}

interface NodeConfigMessages {
    fun error(failure: Throwable): String
    fun cancelled(partial: Boolean): String
    fun failed(failure: Throwable, partial: Boolean): String
}
