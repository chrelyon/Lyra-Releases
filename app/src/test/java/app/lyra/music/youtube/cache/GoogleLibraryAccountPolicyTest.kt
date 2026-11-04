package app.lyra.music.youtube.cache

import org.junit.Assert.*
import org.junit.Test

class GoogleLibraryAccountPolicyTest {
    @Test fun noGoogleAccountNeverReadsCachedYouTubePlaylists() {
        assertFalse(GoogleLibraryAccountPolicy.mayRead(false,"accountA","accountA"))
        assertFalse(GoogleLibraryAccountPolicy.mayRead(false,null,null))
    }

    @Test fun accountBSwitchNeverReadsAccountAPlaylistCache() {
        assertFalse(GoogleLibraryAccountPolicy.mayRead(true,"accountA","accountB"))
        assertFalse(GoogleLibraryAccountPolicy.mayRead(true,null,"accountB"))
        assertFalse(GoogleLibraryAccountPolicy.mayRead(true,"accountA",null))
        assertTrue(GoogleLibraryAccountPolicy.mayRead(true,"accountA","accountA"))
    }

    @Test fun inFlightResultFromPreviousAccountIsRejected() {
        assertFalse(GoogleLibraryAccountPolicy.sameSession(4,true,5))
        assertFalse(GoogleLibraryAccountPolicy.sameSession(4,false,4))
        assertTrue(GoogleLibraryAccountPolicy.sameSession(4,true,4))
    }
}
