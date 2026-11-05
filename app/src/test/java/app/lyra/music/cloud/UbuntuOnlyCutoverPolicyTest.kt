package app.lyra.music.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UbuntuOnlyCutoverPolicyTest {
    private val ubuntu = "https://lyra-test.tail00000.ts.net:10000"
    private val oldWorker = "https://lyra-profile-sync.example.workers.dev"

    @Test fun routesOnlyToAlreadyConfiguredFunnel() {
        assertEquals(ubuntu, UbuntuOnlyCutoverPolicy.endpoint(ubuntu, oldWorker))
        assertEquals(ubuntu, UbuntuOnlyCutoverPolicy.endpoint("", ubuntu))
        assertEquals("", UbuntuOnlyCutoverPolicy.endpoint("", oldWorker))
        assertEquals("", UbuntuOnlyCutoverPolicy.endpoint(oldWorker, oldWorker))
    }

    @Test fun rejectsInvalidDestinations() {
        assertEquals("", UbuntuOnlyCutoverPolicy.endpoint("http://lyra-test.tail00000.ts.net:10000", ""))
        assertEquals("", UbuntuOnlyCutoverPolicy.endpoint("https://lyra-test.tail00000.ts.net:8443", ""))
        assertEquals(ubuntu, UbuntuOnlyCutoverPolicy.endpoint("$ubuntu/", ""))
    }

    @Test fun protectsUbuntuDataUntilRestored() {
        assertFalse(UbuntuOnlyCutoverPolicy.canUpload(true, false, ubuntu))
        assertFalse(UbuntuOnlyCutoverPolicy.canUpload(false, true, ubuntu))
        assertFalse(UbuntuOnlyCutoverPolicy.canUpload(true, true, oldWorker))
        assertTrue(UbuntuOnlyCutoverPolicy.canUpload(true, true, ubuntu))
    }
}
