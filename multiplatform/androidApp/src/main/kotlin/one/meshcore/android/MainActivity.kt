package one.meshcore.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import one.meshcore.shared.contact.ui.LocalAvatarImagePicker
import one.meshcore.shared.map.ui.AndroidMapLibreHostRenderer
import one.meshcore.shared.map.ui.LocalMapLibreHostRenderer
import one.meshcore.shared.navigation.SettingsDestination
import one.meshcore.shared.navigation.navigateToSettings
import one.meshcore.shared.ui.AppRoot
import one.meshcore.shared.ui.OnboardingPermission
import one.meshcore.shared.ui.PermissionController
import one.meshcore.shared.ui.ShellDependencies

/**
 * The single Activity hosting the shared Compose root. Beyond rendering it wires: the scene lifecycle
 * (`ON_START`/`ON_STOP` drive activate/foreground/background; `ON_RESUME` re-reads permissions, the iOS
 * `scenePhase`-active re-check); the Android runtime permissions the onboarding cards manage; the
 * onboarding resume pre-seed; and `meshcore://` deep links (cold-launch links staged and drained once
 * launch settles, warm links via `onNewIntent`). `singleTask` in the manifest guarantees `onNewIntent`.
 *
 * The connection graph lives in [MeshCoreApplication] (process-scoped) and the [ConnectionService] keeps
 * it resident while connected, so an Activity recreation re-reads the same live connection rather than
 * rebuilding it. Platform clipboard/share/settings/haptic bridges live in [AndroidSystemBridges].
 */
class MainActivity : ComponentActivity() {
    private val container get() = (application as MeshCoreApplication).container

    // Activity-backed collaborators initialize after super.onCreate; CompanionDeviceManager requires it.
    private lateinit var bridges: AndroidSystemBridges
    private lateinit var companionDevices: AndroidCompanionDeviceRegistry
    private lateinit var permissions: PermissionController

    private val openBackupDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val owner = container
        if (uri != null && owner.backupWorkspace.recoverPickedBackup { documents.readBackupFileJson(uri) }) {
            owner.appScope.launch {
                owner.awaitInitialOfflineData()
                owner.coordinator.navigateToSettings(SettingsDestination.BACKUP)
            }
        } else {
            owner.backupPickWaiter.offer(uri)
        }
    }

    private val createBackupDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument(AndroidSystemBridges.BACKUP_MIME_TYPE),
    ) { uri ->
        val owner = container
        if (
            uri != null &&
            owner.backupWorkspace.recoverCreatedBackup {
                owner.awaitInitialOfflineData()
                owner.exportBackupDocument { compressed -> documents.saveBackupDocumentToUri(uri, compressed) }
            }
        ) {
            owner.appScope.launch {
                owner.awaitInitialOfflineData()
                owner.coordinator.navigateToSettings(SettingsDestination.BACKUP)
            }
        } else {
            owner.backupCreateWaiter.offer(uri)
        }
    }

    private val openNodeConfigDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val owner = container
        owner.nodeConfigImportWorkspace.retireIfDestinationChanged()
        val consumed = owner.nodeConfigPickWaiter.offer(uri)
        if (!consumed && uri != null) {
            owner.nodeConfigImportWorkspace.recoverPickedConfig(
                readSelected = { documents.readNodeConfigJsonText(uri) },
                showReview = { owner.coordinator.navigateToSettings(SettingsDestination.CONFIG_IMPORT) },
            )
        }
    }

    private val documents: MainActivityDocuments by lazy {
        MainActivityDocuments(
            container = container,
            contentResolver = contentResolver,
            createBackupDocument = createBackupDocument,
            openBackupDocument = openBackupDocument,
            openNodeConfigDocument = openNodeConfigDocument,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        bridges = AndroidSystemBridges(this, container.ioDispatcher)
        container.billingActivity = this
        container.systemCopyToClipboard = bridges::copyToClipboard
        container.systemPresentSystemTranslation = bridges::presentSystemTranslation

        permissions = AndroidPermissionController(this)
        companionDevices = AndroidCompanionDeviceRegistry(this)
        container.companionDevices = companionDevices
        container.requestLocationPermission = { permissions.request(OnboardingPermission.LOCATION) }

        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        container.lifecycle.onEnterForeground()
                        container.reconcileEntitlements()
                        companionDevices.refreshAssociationSnapshot()
                        lifecycleScope.launch { container.syncCompanionPresence() }
                    }
                    Lifecycle.Event.ON_RESUME -> {
                        permissions.refresh()
                        container.locationService.refreshAuthorization()
                    }
                    Lifecycle.Event.ON_STOP -> container.lifecycle.onEnterBackground()
                    else -> Unit
                }
            },
        )

        ViewModelProvider(this)[MainActivityIntentState::class.java].submitInitialIntent {
            submitNavigationIntent(intent)
        }

        lifecycleScope.launch {
            container.awaitInitialOfflineData()
            val onboardingComplete = container.hasCompletedOnboarding()
            if (!onboardingComplete) seedOnboardingResume()
            val deps = buildShellDependencies(onboardingComplete)

            setContent {
                CompositionLocalProvider(
                    LocalAvatarImagePicker provides rememberAndroidAvatarImagePicker(),
                    LocalMapLibreHostRenderer provides AndroidMapLibreHostRenderer,
                ) {
                    AppRoot(deps)
                }
            }
        }
    }

    private fun buildShellDependencies(onboardingComplete: Boolean): ShellDependencies = AndroidShellHost(
        activity = this,
        container = container,
        permissions = permissions,
        companionDevices = companionDevices,
        bridges = bridges,
        files = AndroidShellFiles(
            saveBackupDocument = documents::saveBackupDocument,
            pickBackupFileJson = documents::pickBackupFileJson,
            pickNodeConfigJsonText = documents::pickNodeConfigJsonText,
        ),
    ).dependencies(onboardingComplete)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        submitNavigationIntent(intent)
    }

    private fun submitNavigationIntent(intent: Intent?) {
        deepLinkUrl(intent)?.let { container.submitDeepLink(it) }
        conversationKey(intent)?.let { container.submitConversationOpen(it, conversationMessageId(intent)) }
        contactDetailId(intent)?.let { container.submitContactOpen(it) }
    }

    override fun onDestroy() {
        if (container.billingActivity === this) {
            container.billingActivity = null
        }
        super.onDestroy()
    }

    // Compute the resume path from the current permission/region state and seed the onboarding stack, so a
    // returning user who quit mid-onboarding lands past the steps they finished (the iOS resume push).
    private suspend fun seedOnboardingResume() {
        val determined = permissions.state.value.determined
        val path = container.suggestedOnboardingPath(
            locationDetermined = OnboardingPermission.LOCATION in determined,
            notificationDetermined = OnboardingPermission.NOTIFICATIONS in determined,
            regionAlreadySet = container.regionAlreadySet(),
        )
        container.onboarding.seedResume(path)
    }

    private fun deepLinkUrl(intent: Intent?): String? {
        val raw = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString
        val extraText = intent?.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent?.getStringExtra("message.text")
            ?: intent?.getStringExtra("text")
        return mergeSendMessageText(raw, extraText)
    }

    // A message/room notification body tap opens the Activity carrying the tapped conversation key (set by
    // AndroidNotificationPoster.tapIntent). The reply/mark-read actions carry the same extra but reach the
    // broadcast receiver, not the Activity.
    private fun conversationKey(intent: Intent?): String? =
        intent?.getStringExtra(NotificationActionReceiver.EXTRA_CONVERSATION_KEY)

    // A reaction notification body tap additionally carries the reacted message id so the conversation
    // scrolls to it, mirroring iOS `onReactionNotificationTapped`; a plain message tap omits it.
    private fun conversationMessageId(intent: Intent?): String? =
        intent?.getStringExtra(NotificationActionReceiver.EXTRA_MESSAGE_ID)

    // A new-contact-discovery notification body tap opens the contact's detail, carrying the contact id
    // (set by AndroidNotificationPoster.contactDetailIntent) rather than a conversation key.
    private fun contactDetailId(intent: Intent?): String? =
        intent?.getStringExtra(NotificationActionReceiver.EXTRA_CONTACT_ID)

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}
