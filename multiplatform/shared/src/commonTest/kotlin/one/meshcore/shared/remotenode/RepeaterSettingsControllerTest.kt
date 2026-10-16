package one.meshcore.shared.remotenode

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Repeater behavior fetch/apply diffing, region operations, and late-response routing. */
class RepeaterSettingsControllerTest {
    private fun controller(sender: ScriptedCliSender): RepeaterSettingsController {
        val helper = NodeSettingsController(sender.send, sender.send)
        return RepeaterSettingsController(helper, sender.send, sender.send)
    }

    private suspend fun loaded(
        sender: ScriptedCliSender,
        firmwareVersion: String? = "v1.15.0",
    ): RepeaterSettingsController {
        val controller = controller(sender)
        firmwareVersion?.let { controller.helper.setNodeInfo(it, null, null) }
        controller.fetchRegions()
        return controller
    }

    @Test
    fun fetchBehaviorStoresValues() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "get repeat" -> "on"
                    "get advert.interval" -> "120"
                    "get flood.advert.interval" -> "24"
                    "get flood.max" -> "8"
                    else -> "OK"
                }
            }
        }
        val controller = controller(sender)
        controller.fetchBehaviorSettings()

        assertEquals(true, controller.repeaterEnabled)
        assertEquals(120, controller.advertIntervalMinutes)
        assertEquals(24, controller.floodAdvertIntervalHours)
        assertEquals(8, controller.floodMaxHops)
        assertTrue(controller.behaviorLoaded)
        assertFalse(controller.behaviorSettingsModified)
    }

    @Test
    fun applyBehaviorSendsOnlyChangedFields() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "get repeat" -> "on"
                    "get advert.interval" -> "120"
                    "get flood.advert.interval" -> "24"
                    "get flood.max" -> "8"
                    else -> "OK"
                }
            }
        }
        val controller = controller(sender)
        controller.fetchBehaviorSettings()
        sender.sent.clear()

        controller.repeaterEnabled = false
        controller.floodMaxHops = 10
        val result = controller.applyBehaviorSettings()

        assertIs<SettingsApplyResult.Applied>(result)
        assertContentEquals(listOf("set repeat off", "set flood.max 10"), sender.setCommands)
    }

    @Test
    fun applyBehaviorValidationFailureSendsNothing() = runTest {
        val sender = ScriptedCliSender()
        val controller = controller(sender)
        controller.advertIntervalMinutes = 30
        val result = controller.applyBehaviorSettings()
        assertIs<SettingsApplyResult.InvalidFields>(result)
        assertTrue(controller.behaviorValidation!!.advertIntervalInvalid)
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun fetchRegionsParsesTree() = runTest {
        val sender = ScriptedCliSender().apply { respond = { "* F\n eu^\n" } }
        val controller = controller(sender)
        controller.fetchRegions()
        assertEquals(listOf("*", "eu"), controller.regions.map { it.name })
        assertEquals("*", controller.regions[1].parentName)
        assertTrue(controller.regionsLoaded)
    }

    @Test
    fun fetchRegionsDoesNotMarkLoadedOnEmptyParse() = runTest {
        val sender = ScriptedCliSender().apply { respond = { "* F\n   gta F" } }
        val controller = controller(sender)
        controller.fetchRegions()
        assertFalse(controller.regionsLoaded)
        assertTrue(controller.regionsError)
    }

    @Test
    fun addRegionSendsNothingWhenNotLoaded() = runTest {
        val sender = ScriptedCliSender()
        val controller = controller(sender)
        assertIs<RegionOpResult.NotLoaded>(controller.addRegion("Europe"))
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun addRegionIsFloodAllowedUnderUnscoped() = runTest {
        val controller = loaded(
            sender = ScriptedCliSender().apply {
                respond = { cmd ->
                    when (cmd) {
                        "region" -> "* F\n"
                        "region put Europe" -> "OK - (flood allowed)"
                        else -> "OK"
                    }
                }
            },
        )

        assertIs<RegionOpResult.Applied>(controller.addRegion("Europe"))
        val added = controller.regions.last()
        assertEquals("Europe", added.name)
        assertEquals("*", added.parentName)
        assertEquals(1, added.depth)
        assertTrue(added.floodAllowed)
        assertTrue(controller.hasUnsavedRegionChanges)
        assertIs<RegionOpResult.AddValidationFailed>(controller.addRegion("Europe"))
    }

    @Test
    fun addRegionWithNamedParentSendsPutNameParent() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "region" -> "* F\n"
                    else -> "OK"
                }
            }
        }
        val controller = loaded(sender)
        controller.replaceRegions(
            listOf(
                RepeaterRegionEntry("*", null, 0, floodAllowed = true, isHome = false),
                RepeaterRegionEntry("on", "*", 1, floodAllowed = true, isHome = false),
            ),
        )

        assertIs<RegionOpResult.Applied>(controller.addRegion("gta", RepeaterRegionEntry.Parent.Named("on")))
        assertEquals("region put gta on", sender.sent.last())
        assertEquals("on", controller.regions.last().parentName)
        assertEquals(2, controller.regions.last().depth)
        assertTrue(controller.regions.last().floodAllowed)
    }

    @Test
    fun addRegionNamedWildcardSendsUnscopedPut() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "region" -> "* F\n"
                    else -> "OK"
                }
            }
        }
        val controller = loaded(sender)
        assertIs<RegionOpResult.Applied>(
            controller.addRegion("Europe", RepeaterRegionEntry.Parent.Named(WILDCARD_REGION_NAME)),
        )
        assertEquals("region put Europe", sender.sent.last())
        assertEquals(WILDCARD_REGION_NAME, controller.regions.last().parentName)
    }

    @Test
    fun addRegionNamedParentMissingDoesNotSend() = runTest {
        val sender = ScriptedCliSender().apply { respond = { if (it == "region") "* F\n" else "OK" } }
        val controller = loaded(sender)
        sender.sent.clear()

        assertIs<RegionOpResult.AddValidationFailed>(
            controller.addRegion("gta", RepeaterRegionEntry.Parent.Named("nope")),
        )
        assertTrue(sender.sent.isEmpty())
        assertFalse(controller.hasUnsavedRegionChanges)
    }

    @Test
    fun addRegionInsertsAfterGrandchildren() = runTest {
        val sender = ScriptedCliSender().apply { respond = { if (it == "region") "* F\n" else "OK" } }
        val controller = loaded(sender)
        controller.replaceRegions(
            listOf(
                RepeaterRegionEntry("*", null, 0, floodAllowed = true, isHome = false),
                RepeaterRegionEntry("on", "*", 1, floodAllowed = true, isHome = false),
                RepeaterRegionEntry("gta", "on", 2, floodAllowed = true, isHome = false),
                RepeaterRegionEntry("downtown", "gta", 3, floodAllowed = true, isHome = false),
                RepeaterRegionEntry("can", "*", 1, floodAllowed = true, isHome = false),
            ),
        )

        assertIs<RegionOpResult.Applied>(controller.addRegion("ottawa", RepeaterRegionEntry.Parent.Named("on")))
        assertEquals(listOf("*", "on", "gta", "downtown", "ottawa", "can"), controller.regions.map { it.name })
    }

    @Test
    fun addRegionUsesLiveParentDepthAfterPutReply() = runTest {
        val sender = ScriptedCliSender().apply { respond = { if (it == "region") "* F\n" else "OK" } }
        val controller = loaded(sender)
        controller.replaceRegions(
            listOf(
                RepeaterRegionEntry("*", null, 0, floodAllowed = true, isHome = false),
                RepeaterRegionEntry("on", "*", 1, floodAllowed = true, isHome = false),
            ),
        )
        sender.onSend = { command ->
            if (command == "region put gta on") {
                controller.replaceRegions(
                    listOf(
                        RepeaterRegionEntry("*", null, 0, floodAllowed = true, isHome = false),
                        RepeaterRegionEntry("can", "*", 1, floodAllowed = true, isHome = false),
                        RepeaterRegionEntry("on", "can", 2, floodAllowed = true, isHome = false),
                    ),
                )
            }
        }

        assertIs<RegionOpResult.Applied>(controller.addRegion("gta", RepeaterRegionEntry.Parent.Named("on")))
        assertEquals("gta", controller.regions.last().name)
        assertEquals("on", controller.regions.last().parentName)
        assertEquals(3, controller.regions.last().depth)
    }

    @Test
    fun addRegionEmptyNameDoesNotSend() = runTest {
        val sender = ScriptedCliSender().apply { respond = { if (it == "region") "* F\n" else "OK" } }
        val controller = loaded(sender)
        sender.sent.clear()
        assertIs<RegionOpResult.EmptyName>(controller.addRegion("   "))
        assertTrue(sender.sent.isEmpty())
        assertEquals(listOf("*"), controller.regions.map { it.name })
    }

    @Test
    fun toggleFloodUpdatesEntry() = runTest {
        val sender = ScriptedCliSender().apply { respond = { if (it == "region") "* F\n" else "OK" } }
        val controller = loaded(sender)
        controller.addRegion("us")
        val result = controller.toggleRegionFlood("us")
        assertIs<RegionOpResult.Applied>(result)
        assertFalse(controller.regions.single { it.name == "us" }.floodAllowed)
        assertEquals("region denyf us", sender.sent.last())
    }

    @Test
    fun toggleRegionFloodSendsNothingWhenNotLoaded() = runTest {
        val sender = ScriptedCliSender()
        val controller = controller(sender)
        controller.replaceRegions(
            listOf(RepeaterRegionEntry("us", "*", 1, floodAllowed = true, isHome = false)),
        )
        assertIs<RegionOpResult.NotLoaded>(controller.toggleRegionFlood("us"))
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun setDefaultScopeSendsRegionDefault() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "region" -> "* F\n duckburg F\n"
                    "region default" -> " default scope is <null>"
                    "region default duckburg" -> " default scope is now duckburg"
                    else -> "OK"
                }
            }
        }
        val controller = loaded(sender)
        controller.fetchDefaultScope()
        sender.sent.clear()

        assertIs<RegionOpResult.Applied>(controller.setDefaultScope("duckburg"))
        assertEquals(listOf("region default duckburg"), sender.sent)
        assertEquals("duckburg", controller.defaultScopeName)
        assertTrue(controller.regions.single { it.name == "duckburg" }.floodAllowed)
        assertFalse(controller.hasUnsavedRegionChanges)
    }

    @Test
    fun setDefaultScopeClearsWithNullToken() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "region" -> "* F\n duckburg F\n"
                    "region default" -> " default scope is duckburg"
                    "region default <null>" -> " default scope is now <null>"
                    else -> "OK"
                }
            }
        }
        val controller = loaded(sender)
        controller.fetchDefaultScope()
        sender.sent.clear()

        assertIs<RegionOpResult.Applied>(controller.setDefaultScope(null))
        assertEquals("region default <null>", sender.sent.single())
        assertNull(controller.defaultScopeName)
    }

    @Test
    fun setDefaultScopeDoesNotSendWildcardOrSameValue() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "region" -> "* F\n duckburg F\n"
                    "region default" -> " default scope is duckburg"
                    else -> "OK"
                }
            }
        }
        val controller = loaded(sender)
        controller.fetchDefaultScope()
        sender.sent.clear()

        assertIs<RegionOpResult.Applied>(controller.setDefaultScope(WILDCARD_REGION_NAME))
        assertIs<RegionOpResult.Applied>(controller.setDefaultScope("duckburg"))
        assertTrue(sender.sent.isEmpty())
        assertEquals("duckburg", controller.defaultScopeName)
    }

    @Test
    fun fetchDefaultScopeTreatsNullAndStarAsCleared() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "region" -> "* F\n"
                    "region default" -> " default scope is *"
                    else -> "OK"
                }
            }
        }
        val controller = loaded(sender)
        controller.fetchDefaultScope()
        assertTrue(controller.defaultScopeLoaded)
        assertNull(controller.defaultScopeName)
    }

    @Test
    fun removeRegionOfCurrentDefaultSendsClear() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "region" -> "* F\n duckburg F\n"
                    "region default" -> " default scope is duckburg"
                    "region remove duckburg" -> "OK"
                    "region default <null>" -> " default scope is now <null>"
                    else -> "OK"
                }
            }
        }
        val controller = loaded(sender)
        controller.fetchDefaultScope()
        sender.sent.clear()

        assertIs<RegionOpResult.Applied>(controller.removeRegion("duckburg"))
        assertEquals(listOf("region remove duckburg", "region default <null>"), sender.sent)
        assertEquals(listOf("*"), controller.regions.map { it.name })
        assertNull(controller.defaultScopeName)
    }

    @Test
    fun removeRegionOfCurrentDefaultKeepsNameWhenClearFails() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "region" -> "* F\n duckburg F\n"
                    "region default" -> " default scope is duckburg"
                    "region remove duckburg" -> "OK"
                    "region default <null>" -> "Err - save failed"
                    else -> "OK"
                }
            }
        }
        val controller = loaded(sender)
        controller.fetchDefaultScope()
        sender.sent.clear()

        assertIs<RegionOpResult.UnknownRegion>(controller.removeRegion("duckburg"))
        assertEquals(listOf("*"), controller.regions.map { it.name })
        assertEquals("duckburg", controller.defaultScopeName)
        assertTrue(controller.hasUnsavedRegionChanges)
    }

    @Test
    fun removeRegionReportsNotEmpty() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "region" -> "* F\n"
                    else -> "region not empty"
                }
            }
        }
        val controller = loaded(sender)
        assertIs<RegionOpResult.NotEmpty>(controller.removeRegion("us"))
    }

    @Test
    fun saveRegionsAfterTruncatedRefetchStillPersists() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "region" -> "* F\n"
                    else -> "OK"
                }
            }
        }
        val controller = loaded(sender)
        controller.addRegion("Europe")
        assertTrue(controller.hasUnsavedRegionChanges)

        sender.respond = { "* F\n   gta F" }
        controller.fetchRegions()
        assertFalse(controller.regionsLoaded)
        assertTrue(controller.regionsError)
        assertTrue(controller.hasUnsavedRegionChanges)
        assertTrue(controller.regions.map { it.name }.contains("Europe"))

        sender.sent.clear()
        sender.respond = { "OK" }
        assertIs<RegionOpResult.Applied>(controller.saveRegions())
        assertEquals(listOf("region save"), sender.sent)
        assertFalse(controller.hasUnsavedRegionChanges)
    }

    @Test
    fun saveRegionsClearsUnsavedFlag() = runTest {
        val sender = ScriptedCliSender().apply { respond = { if (it == "region") "* F\n" else "OK" } }
        val controller = loaded(sender)
        controller.addRegion("us")
        assertTrue(controller.hasUnsavedRegionChanges)
        assertIs<RegionOpResult.Applied>(controller.saveRegions())
        assertFalse(controller.hasUnsavedRegionChanges)
    }

    @Test
    fun handleLateResponseRoutesBehaviorThenRegions() = runTest {
        val sender = ScriptedCliSender().apply { respond = { throw RemoteNodeError.Timeout } }
        val controller = controller(sender)
        controller.fetchBehaviorSettings()
        assertTrue(controller.behaviorError)

        // "on" parses as a repeat mode and nothing else, so it is unambiguous even with all four
        // Behavior queries outstanding.
        assertTrue(controller.handleLateResponse("on"))
        assertEquals(true, controller.repeaterEnabled)
        // Three fields are still missing, so the section stays in error until they arrive, as on iOS.
        assertTrue(controller.behaviorError)

        // A bare integer, by contrast, could answer any of the three remaining queries and is refused.
        assertFalse(controller.handleLateResponse("120"))
        assertEquals(null, controller.advertIntervalMinutes)
        assertEquals(null, controller.floodMaxHops)
    }

    @Test
    fun fetchDefaultScopeSendsNothingBeforeV115() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "region" -> "* F\n duckburg F\n"
                    "region default" -> " default scope is duckburg"
                    else -> "OK"
                }
            }
        }
        val controller = loaded(sender, firmwareVersion = "v1.14.1")
        sender.sent.clear()

        controller.fetchDefaultScope()

        assertTrue(sender.sent.isEmpty())
        assertFalse(controller.supportsRegionDefaultScope)
        assertFalse(controller.defaultScopeLoaded)
        assertNull(controller.defaultScopeName)
    }

    @Test
    fun fetchDefaultScopeSendsForCliVerBannerAtV115() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "region" -> "* F\n duckburg F\n"
                    "region default" -> " default scope is duckburg"
                    else -> "OK"
                }
            }
        }
        val controller = loaded(sender, firmwareVersion = "MeshCore v1.15.0 (2025-04-18)")
        sender.sent.clear()

        controller.fetchDefaultScope()

        assertEquals(listOf("region default"), sender.sent)
        assertTrue(controller.supportsRegionDefaultScope)
        assertTrue(controller.defaultScopeLoaded)
        assertEquals("duckburg", controller.defaultScopeName)
    }

    @Test
    fun fetchDefaultScopeSendsNothingWhenFirmwareIsUnknown() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "region" -> "* F\n duckburg F\n"
                    "region default" -> " default scope is duckburg"
                    else -> "OK"
                }
            }
        }
        val controller = loaded(sender, firmwareVersion = null)
        sender.sent.clear()

        controller.fetchDefaultScope()

        assertTrue(sender.sent.isEmpty())
        assertFalse(controller.supportsRegionDefaultScope)
        assertFalse(controller.defaultScopeLoaded)
    }

    @Test
    fun setDefaultScopeSendsNothingBeforeV115() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "region" -> "* F\n duckburg F\n"
                    "region default duckburg" -> " default scope is now duckburg"
                    else -> "OK"
                }
            }
        }
        val controller = loaded(sender, firmwareVersion = "v1.14.1")
        sender.sent.clear()

        assertIs<RegionOpResult.NotLoaded>(controller.setDefaultScope("duckburg"))
        assertTrue(sender.sent.isEmpty())
        assertNull(controller.defaultScopeName)
    }

    @Test
    fun removeRegionOfCurrentDefaultSkipsClearBeforeV115() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "region" -> "* F\n duckburg F\n"
                    "region default" -> " default scope is duckburg"
                    "region remove duckburg" -> "OK"
                    "region default <null>" -> " default scope is now <null>"
                    else -> "OK"
                }
            }
        }
        val controller = loaded(sender)
        controller.fetchDefaultScope()
        controller.helper.setNodeInfo("v1.14.1", null, null)
        sender.sent.clear()

        assertIs<RegionOpResult.Applied>(controller.removeRegion("duckburg"))
        assertEquals(listOf("region remove duckburg"), sender.sent)
        assertEquals("duckburg", controller.defaultScopeName)
    }
}
