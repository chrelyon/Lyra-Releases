package app.lyra.music.youtube.cache

/** Pure guard: playlist cache must belong to the currently authorized Google account. */
object GoogleLibraryAccountPolicy {
    fun mayRead(isAuthorized: Boolean, savedOwner: String?, currentOwner: String?): Boolean =
        isAuthorized && !savedOwner.isNullOrBlank() && !currentOwner.isNullOrBlank() &&
            savedOwner == currentOwner

    fun sameSession(startGeneration: Long, isAuthorized: Boolean, nowGeneration: Long): Boolean =
        isAuthorized && startGeneration == nowGeneration
}
