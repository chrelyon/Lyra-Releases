package app.lyra.music.personalization
import org.junit.Assert.*
import org.junit.Test
class ServerQueuePolicyTest {
    @Test fun preservesCurrentAndPlayedPrefix() {
        assertEquals(listOf("played","current","server1","server2"),
            ServerQueuePolicy.replaceFuture(listOf("played","current","old1"),"current",listOf("server1","server2")))
    }
    @Test fun staleQueueCannotReplaceAnotherSession() {
        val queue=listOf("other1","other2")
        assertEquals(queue,ServerQueuePolicy.replaceFuture(queue,"missing",listOf("server1")))
    }
    @Test fun rejectsWrongBoundaryManualEditAndRepeat() {
        assertFalse(ServerQueuePolicy.canCommit("a","b","revision1","revision1",true))
        assertFalse(ServerQueuePolicy.canCommit("a","a","revision1","revision2",true))
        assertFalse(ServerQueuePolicy.canCommit("a","a","revision1","revision1",false))
        assertTrue(ServerQueuePolicy.canCommit("a","a","revision1","revision1",true))
    }
    @Test fun exactArtistCreditExcludesTributesAndPartialGroupNames() {
        assertTrue(RequestedArtistPolicy.matches("Beyoncé",listOf("Beyonce")))
        assertFalse(RequestedArtistPolicy.matches("Simon & Garfunkel",listOf("Simon")))
        assertFalse(RequestedArtistPolicy.matches("Rammstein",listOf("Rammstein Tribute")))
        assertTrue(RequestedArtistPolicy.matches("Simon & Garfunkel",listOf("Simon & Garfunkel")))
    }
}
