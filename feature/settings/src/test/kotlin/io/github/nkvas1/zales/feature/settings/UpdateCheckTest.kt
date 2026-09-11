// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package io.github.nkvas1.zales.feature.settings

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The comparison that goes wrong in nearly every hand-rolled updater, and goes
 * wrong silently: everybody is told they are up to date, for ever.
 */
class UpdateCheckTest {

    @Test
    fun `a later version is newer`() {
        assertTrue(UpdateCheck.isNewer("1.1.0", "1.0.9"))
        assertTrue(UpdateCheck.isNewer("2.0.0", "1.9.9"))
        assertTrue(UpdateCheck.isNewer("1.0.1", "1.0"))
    }

    @Test
    fun `ten is newer than nine, whatever a string comparison thinks`() {
        assertTrue(UpdateCheck.isNewer("1.10.0", "1.9.0"))
        assertFalse(UpdateCheck.isNewer("1.9.0", "1.10.0"))
    }

    @Test
    fun `the same version is not an update`() {
        assertFalse(UpdateCheck.isNewer("1.2.3", "1.2.3"))
        assertFalse(UpdateCheck.isNewer("1.2", "1.2.0"))
    }

    @Test
    fun `an older version is never offered`() {
        assertFalse(UpdateCheck.isNewer("1.0.0", "1.0.1"))
    }

    @Test
    fun `nonsense is not an update`() {
        assertFalse(UpdateCheck.isNewer("nightly", "1.0.0"))
        assertFalse(UpdateCheck.isNewer("", "1.0.0"))
    }
}
