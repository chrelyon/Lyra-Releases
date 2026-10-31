package com.metrolist.innertube
import com.metrolist.innertube.models.*
import kotlinx.coroutines.CompletableDeferred
object YouTube {
    object SearchFilter { const val FILTER_SONG="songs" }
    data class SearchPage(val items: List<SongItem>)
    data class NextPage(val items: List<SongItem>,val endpoint: WatchEndpoint,val continuation: String?=null)
    var hold: CompletableDeferred<Unit>?=null
    var holdNext: CompletableDeferred<Unit>?=null
    var searchCount=0
    var sequence=0
    private fun items()=(0 until 8).map { SongItem("local%06d".format(it),"Local $it",listOf(Artist(if(it%2==0) "Rammstein" else "Other")),200,null) }
    suspend fun search(query: String,filter: String): Result<SearchPage> {
        searchCount++;hold?.await();return Result.success(SearchPage(items()))
    }
    suspend fun next(endpoint: WatchEndpoint,continuation: String?=null): Result<NextPage> {
        holdNext?.await()
        sequence++
        return Result.success(NextPage(items().map { it.copy(id="fresh%06d".format(sequence*10+it.id.takeLast(1).toInt())) },endpoint))
    }
}
