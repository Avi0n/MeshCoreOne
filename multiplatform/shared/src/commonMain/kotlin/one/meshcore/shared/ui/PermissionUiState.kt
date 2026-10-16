package one.meshcore.shared.ui

/** Grant state per [OnboardingPermission]; `determined` distinguishes "not asked" from "asked and denied". */
data class PermissionUiState(
    val granted: Set<OnboardingPermission> = emptySet(),
    val determined: Set<OnboardingPermission> = emptySet(),
) {
    fun isGranted(permission: OnboardingPermission): Boolean = permission in granted
}
