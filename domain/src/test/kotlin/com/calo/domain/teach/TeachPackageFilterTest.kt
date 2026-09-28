package com.calo.domain.teach

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeachPackageFilterTest {

    private val own = "com.calo"
    private val launcher = "com.google.android.apps.nexuslauncher"

    @Test
    fun `own package is excluded`() {
        assertTrue(TeachPackageFilter.isExcluded(own, ownPackageName = own, launcherPackageName = launcher))
    }

    @Test
    fun `resolved launcher package is excluded`() {
        assertTrue(TeachPackageFilter.isExcluded(launcher, ownPackageName = own, launcherPackageName = launcher))
    }

    @Test
    fun `systemui is excluded even when launcher resolution succeeded`() {
        assertTrue(
            TeachPackageFilter.isExcluded(
                TeachPackageFilter.SYSTEM_UI_PACKAGE, ownPackageName = own, launcherPackageName = launcher
            )
        )
    }

    @Test
    fun `systemui is excluded even when launcher resolution failed (null)`() {
        assertTrue(
            TeachPackageFilter.isExcluded(
                TeachPackageFilter.SYSTEM_UI_PACKAGE, ownPackageName = own, launcherPackageName = null
            )
        )
    }

    @Test
    fun `null event packageName is excluded`() {
        assertTrue(TeachPackageFilter.isExcluded(null, ownPackageName = own, launcherPackageName = launcher))
    }

    @Test
    fun `failed launcher resolution does not exclude every other package`() {
        assertFalse(TeachPackageFilter.isExcluded("com.zomato", ownPackageName = own, launcherPackageName = null))
    }

    @Test
    fun `the on-screen keyboard's own events are excluded`() {
        val gboard = "com.google.android.inputmethod.latin"
        assertTrue(TeachPackageFilter.isExcluded(gboard, ownPackageName = own, launcherPackageName = launcher, keyboardPackageName = gboard))
        assertFalse(TeachPackageFilter.isExcluded("com.zomato", ownPackageName = own, launcherPackageName = launcher, keyboardPackageName = gboard))
    }

    @Test
    fun `real target app package is never excluded`() {
        assertFalse(TeachPackageFilter.isExcluded("com.zomato", ownPackageName = own, launcherPackageName = launcher))
    }
}
