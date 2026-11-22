package tw.bard.carradar

import android.app.Application
import tw.bard.carradar.data.CrashLogStore

class AppApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val store = CrashLogStore(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            store.recordThrowable("UNCAUGHT(${thread.name})", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }
}
