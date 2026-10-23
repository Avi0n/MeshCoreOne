// AndroidOnly: WP-504 Actual Application entry ordering and opt-in policy gates; not device violation evidence.
package com.meshcoreone.android.app

import android.app.Application
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.os.StrictMode
import com.meshcoreone.android.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowApplication
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], application = Application::class, shadows = [StartupBoundaryApplicationShadow::class])
class StartupStrictModeTest {
    private lateinit var previousThreadPolicy: StrictMode.ThreadPolicy
    private lateinit var previousVmPolicy: StrictMode.VmPolicy

    @Before fun rememberPolicies() {
        previousThreadPolicy = StrictMode.getThreadPolicy()
        previousVmPolicy = StrictMode.getVmPolicy()
        StartupBoundaryApplicationShadow.calls = 0
    }

    @After fun restorePolicies() {
        StrictMode.setThreadPolicy(previousThreadPolicy)
        StrictMode.setVmPolicy(previousVmPolicy)
    }

    @Test fun optInInstallsBothDetectAllLogDeathPoliciesBeforeApplicationSuperAndStartupWork() {
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX)
        StrictMode.setVmPolicy(StrictMode.VmPolicy.LAX)
        enterApplication(debuggable = true, enabled = true)
        assertEquals(mask(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().penaltyDeath().build()),
            StartupBoundaryApplicationShadow.threadMask)
        assertEquals(mask(StrictMode.VmPolicy.Builder().detectAll().penaltyLog().penaltyDeath().build()),
            StartupBoundaryApplicationShadow.vmMask)
    }

    @Test fun disabledDebugBuildPreservesBothExistingPolicies() {
        assertPoliciesPreserved(debuggable = true, enabled = false)
    }

    @Test fun nonDebuggableTargetCannotOptInEvenWithResourceEnabled() {
        assertPoliciesPreserved(debuggable = false, enabled = true)
    }

    @Test fun nonDebuggableDefaultPreservesBothExistingPolicies() {
        assertPoliciesPreserved(debuggable = false, enabled = false)
    }

    @Test fun ordinaryDebugBuildResourceDefaultsToDisabled() {
        assertFalse(RuntimeEnvironment.getApplication().resources.getBoolean(R.bool.wp504_startup_strict_mode))
    }

    private fun assertPoliciesPreserved(debuggable: Boolean, enabled: Boolean) {
        val thread = StrictMode.ThreadPolicy.Builder().detectNetwork().penaltyLog().build()
        val vm = StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().penaltyLog().build()
        StrictMode.setThreadPolicy(thread)
        StrictMode.setVmPolicy(vm)
        enterApplication(debuggable, enabled)
        assertEquals(mask(thread), StartupBoundaryApplicationShadow.threadMask)
        assertEquals(mask(vm), StartupBoundaryApplicationShadow.vmMask)
    }

    private fun enterApplication(debuggable: Boolean, enabled: Boolean) {
        val base = RuntimeEnvironment.getApplication()
        val values = object : Resources(base.assets, base.resources.displayMetrics, base.resources.configuration) {
            override fun getBoolean(id: Int): Boolean =
                if (id == R.bool.wp504_startup_strict_mode) enabled else base.resources.getBoolean(id)
        }
        val context = object : ContextWrapper(base) {
            override fun getResources(): Resources = values
            override fun getApplicationInfo(): ApplicationInfo = ApplicationInfo(base.applicationInfo).apply {
                flags = if (debuggable) flags or ApplicationInfo.FLAG_DEBUGGABLE
                    else flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
            }
        }
        val application = MeshCoreApplication()
        shadowOf(application).callAttach(context)
        assertThrows(StartupBoundaryReached::class.java) { application.onCreate() }
        assertEquals("Real Application.super.onCreate must be reached exactly once", 1, StartupBoundaryApplicationShadow.calls)
    }
}

private fun mask(policy: Any): Int = ReflectionHelpers.getField(policy, "mask")

class StartupBoundaryReached : RuntimeException()

@Implements(Application::class)
class StartupBoundaryApplicationShadow : ShadowApplication() {
    @RealObject private lateinit var actual: Application

    @Implementation
    protected fun onCreate() {
        if (actual !is MeshCoreApplication) return
        threadMask = mask(StrictMode.getThreadPolicy())
        vmMask = mask(StrictMode.getVmPolicy())
        calls++
        throw StartupBoundaryReached()
    }

    companion object {
        var calls = 0
        var threadMask = 0
        var vmMask = 0
    }
}
