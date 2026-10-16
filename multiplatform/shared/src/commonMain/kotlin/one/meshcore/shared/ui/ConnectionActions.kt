package one.meshcore.shared.ui

/**
 * The connection commands the shell and onboarding invoke. Suspend calls surface failures the caller
 * routes into the alert machine; [changeDevice] reopens the picker.
 */
interface ConnectionActions :
    ConnectionSessionActions,
    ConnectionSavedDeviceActions
