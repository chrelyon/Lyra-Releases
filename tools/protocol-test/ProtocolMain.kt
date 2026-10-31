package app.lyra.music.protocoltest

import app.lyra.music.personalization.ServerPlaylistClient
import app.lyra.music.personalization.ServerQueuePolicy
import app.lyra.music.personalization.ListeningEventStore
import app.lyra.music.cloud.CloudProfileSync as Wire
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.yield
import java.util.UUID

fun main() = runBlocking {
    var clock=System.currentTimeMillis()
    val a=ServerPlaylistClient("artist","Rammstein","Synthetic",now={clock})
    Wire.failAfterSuffix="/next"
    check(runCatching { a.next(emptySet()) }.isFailure)
    val pending=Wire.saved!!
    clock+=31_000
    val b=ServerPlaylistClient("artist","Rammstein","Synthetic",restore=true,now={clock})
    check(Wire.saved==pending)
    val first=b.next(emptySet())
    check(first.size==12 && first.all { it.title.startsWith("Song") && it.artist=="Rammstein" })
    val second=b.next(first.map { it.id }.toSet())
    check(first.none { f -> second.any { it.id==f.id } })
    println("PASS lost refill reply, process restore, ID order, metadata, no duplicates")

    val current=first.first().id
    val ids=(first+second).map { it.id }
    val revision=ServerPlaylistClient.revision(ids,"Synthetic","OFF",false)
    b.localFallback();Wire.offline=true;clock+=31_000
    b.prepare(current,ids,revision,true)
    check(!b.authoritative)
    Wire.offline=false;clock+=31_000
    ListeningEventStore.pending.add(ListeningEventStore.Event(UUID.randomUUID().toString(),current))
    b.prepare(current,ids,revision,true)
    check(ListeningEventStore.pending.isEmpty())
    check(b.boundary("wrongTrack01",revision,true)==null)
    check(b.boundary(current,"changed-manual-queue",true)==null)
    check(b.boundary(current,revision,false)==null)
    Wire.failAfterSuffix="/commit"
    check(b.boundary(current,revision,true)==null)
    clock+=31_000
    val future=b.boundary(current,revision,true)!!
    check(future.none { it.id in ids })
    val newCurrent=ids[1]
    val merged=ServerQueuePolicy.replaceFuture(ids,newCurrent,future.map { it.id })
    check(merged.take(2)==ids.take(2) && merged.drop(2)==future.map { it.id })
    b.applied();check(b.authoritative && !b.needsResync)
    println("PASS offline feedback, boundary/repeat/manual guards, lost commit reply, preserved current track")

    b.feedbackChanged()
    val hold=CompletableDeferred<Unit>();Wire.hold=hold
    val preparer=launch { b.prepare(newCurrent,merged,ServerPlaylistClient.revision(merged,"Synthetic","OFF",false),true) }
    yield()
    val started=System.nanoTime()
    check(runCatching { b.next(merged.toSet()) }.isFailure)
    check((System.nanoTime()-started)/1_000_000<500)
    hold.complete(Unit);preparer.join();Wire.hold=null
    println("PASS background reconnection cannot block local refill")

    val before=Wire.requests
    clock+=31_000
    Wire.owner="other-profile"
    check(runCatching { b.next(emptySet()) }.isFailure)
    check(Wire.requests==before)
    println("PASS account change blocks stale credentials/session")
}
