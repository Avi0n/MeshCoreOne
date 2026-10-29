// PortedFrom: MC1Tests/ViewModels/NodeConfigImportViewModelTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Settings/NodeConfig/NodeConfigImportViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.settings.app.nodeconfig

import android.content.res.Resources
import com.meshcoreone.android.feature.settings.R
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.core.ui.UiErrorMapper

class ResourceNodeConfigMessages(
    private val resources: Resources,
    private val isContactSaveFailure: (Throwable) -> Boolean,
    private val mapper: UiErrorMapper = UiErrorMapper(),
) : NodeConfigMessages {
    override fun error(failure: Throwable): String =
        if (isContactSaveFailure(failure)) resources.getString(R.string.node_config_contact_save_failed)
        else mapper.message(failure).resolve(resources)
    override fun cancelled(partial: Boolean) =
        resources.getString(if (partial) S.configImportCancelledPartial else S.configImportCancelled)
    override fun failed(failure: Throwable, partial: Boolean): String =
        error(failure).let { if (partial) S.configImportFailedPartial(resources, it) else it }
}
