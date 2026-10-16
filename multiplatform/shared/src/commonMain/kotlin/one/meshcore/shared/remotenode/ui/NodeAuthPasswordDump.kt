package one.meshcore.shared.remotenode.ui

/** iOS `NodeAuthenticationSheet` persist-fail dump after unchecking Remember Password. */
const val FAILED_DELETE_SAVED_PASSWORD = "Failed to delete saved password"

fun failedToDeleteSavedPasswordLine(detail: String): String = "$FAILED_DELETE_SAVED_PASSWORD: $detail"
