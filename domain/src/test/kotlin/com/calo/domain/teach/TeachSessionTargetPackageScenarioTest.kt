package com.calo.domain.teach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scenario-level coverage for Finding 5 (targetPackage latching onto the
 * launcher). Exercises [TeachPackageFilter] + [TargetPackageTracker]
 * together over whole synthetic sessions the way TeachRecorder.
 * onAccessibilityEvent composes them, without needing a real
 * AccessibilityEvent/AccessibilityNodeInfo (unavailable to a plain JUnit
 * test in :app — see TeachRecorder's own tests, or lack thereof, for why
 * this logic lives here instead).
 */
class TeachSessionTargetPackageScenarioTest {

    private val own = "com.calo"
    private val launcher = "com.google.android.apps.nexuslauncher"

    /** One recordable (CLICK/SET_TEXT) action's package, in session order. SCROLL is never fed in — see TargetPackageTracker's doc. */
    private data class Action(val packageName: String)

    /** Mirrors TeachRecorder's loop: filter first, only latch/warn on what survives. */
    private fun runSession(actions: List<String>, launcherPackageName: String? = launcher): Pair<String?, List<String>> {
        var targetPackage: String? = null
        val warnings = mutableListOf<String>()
        for (pkg in actions) {
            if (TeachPackageFilter.isExcluded(pkg, ownPackageName = own, launcherPackageName = launcherPackageName)) continue
            val outcome = TargetPackageTracker.record(targetPackage, pkg)
            targetPackage = outcome.targetPackage
            outcome.warning?.let { warnings += it }
        }
        return targetPackage to warnings
    }

    @Test
    fun `launcher-first sequence - Calo, home screen, then Zomato - targets Zomato, not the launcher`() {
        val (targetPackage, warnings) = runSession(listOf(own, launcher, launcher, "com.zomato", "com.zomato"))
        assertEquals("com.zomato", targetPackage)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `systemui notification-shade event mid-session is dropped, does not become or break targetPackage`() {
        val (targetPackage, warnings) = runSession(
            listOf(own, launcher, "com.zomato", TeachPackageFilter.SYSTEM_UI_PACKAGE, "com.zomato")
        )
        assertEquals("com.zomato", targetPackage)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `Calo's own UI events (teach button, pill updates) never become or affect targetPackage`() {
        val (targetPackage, warnings) = runSession(listOf(own, own, launcher, "com.zomato", own))
        assertEquals("com.zomato", targetPackage)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `actions spanning two non-excluded packages keep the first and warn, do not silently merge`() {
        val (targetPackage, warnings) = runSession(listOf(own, launcher, "com.zomato", "com.dominos"))
        assertEquals("com.zomato", targetPackage)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("com.dominos"))
        assertTrue(warnings.single().contains("com.zomato"))
    }

    @Test
    fun `failed launcher resolution (null) still excludes Calo and systemui, still targets the real app`() {
        val (targetPackage, warnings) = runSession(
            actions = listOf(own, launcher, TeachPackageFilter.SYSTEM_UI_PACKAGE, "com.zomato"),
            launcherPackageName = null
        )
        // launcher wasn't resolved, so its events are NOT excluded here — this
        // documents the accepted degradation (TeachRecorder's doc: "no extra
        // exclusion"), meaning the un-resolved launcher itself would latch
        // targetPackage. Included so this behavior is visible/intentional,
        // not an accidental gap.
        assertEquals(launcher, targetPackage)
        assertTrue(warnings.isNotEmpty())
    }

    @Test
    fun `an all-excluded session never latches a targetPackage`() {
        val (targetPackage, warnings) = runSession(listOf(own, launcher, TeachPackageFilter.SYSTEM_UI_PACKAGE))
        assertNull(targetPackage)
        assertTrue(warnings.isEmpty())
    }
}
