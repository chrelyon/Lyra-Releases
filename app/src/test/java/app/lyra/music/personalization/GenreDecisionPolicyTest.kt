package app.lyra.music.personalization

import app.lyra.music.genre.GenreDecisionPolicy
import org.junit.Assert.*
import org.junit.Test

class GenreDecisionPolicyTest {
    @Test fun unknownNeverExcluded() {
        assertFalse(GenreDecisionPolicy.shouldExclude(false, "rejected"))
        assertFalse(GenreDecisionPolicy.shouldExclude(true, "unknown"))
        assertFalse(GenreDecisionPolicy.shouldExclude(true, "uncertain"))
        assertFalse(GenreDecisionPolicy.shouldExclude(true, "accepted"))
        assertTrue(GenreDecisionPolicy.shouldExclude(true, "rejected"))
    }
    @Test fun neverEmptyPlaybackQueue() {
        assertEquals(listOf("a"), GenreDecisionPolicy.keepQueue(listOf("a","b"), setOf("b")))
        assertEquals(listOf("a","b"), GenreDecisionPolicy.keepQueue(listOf("a","b"), setOf("a","b")))
        assertEquals(emptyList<String>(), GenreDecisionPolicy.keepQueue(emptyList(), setOf("a")))
    }
    @Test fun onlyPilotPresetsSupported() {
        assertTrue(GenreDecisionPolicy.supported("house"))
        assertTrue(GenreDecisionPolicy.supported("ROCK"))
        assertFalse(GenreDecisionPolicy.supported("pop"))
        assertFalse(GenreDecisionPolicy.supported(null))
    }
    @Test fun publicEndpointMustBeFunnel10000() {
        assertTrue(GenreDecisionPolicy.validEndpoint("https://example.tail123.ts.net:10000"))
        assertFalse(GenreDecisionPolicy.validEndpoint("http://example.tail123.ts.net:10000"))
        assertFalse(GenreDecisionPolicy.validEndpoint("https://evil.example.com:10000"))
        assertFalse(GenreDecisionPolicy.validEndpoint("https://example.tail123.ts.net:8443"))
        assertFalse(GenreDecisionPolicy.validEndpoint("https://example.tail123.ts.net:10000/evil"))
        assertFalse(GenreDecisionPolicy.validEndpoint("https://example.tail123.ts.net:10000@evil.com"))
    }
}
