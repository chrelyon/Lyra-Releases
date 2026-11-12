package app.lyra.music.cloud
import android.content.Context
object CloudBackupWork {
 fun schedulePeriodic(context:Context){}
 fun scheduleOneShot(context:Context,delayMs:Long=4000L){}
 fun cancelPeriodic(context:Context){}
 fun cancelOneShot(context:Context){}
}
