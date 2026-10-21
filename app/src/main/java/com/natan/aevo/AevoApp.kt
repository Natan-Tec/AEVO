package com.natan.aevo

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlin.system.exitProcess

/** Se o app fechar por erro, abre a CrashActivity com o motivo para a pessoa mandar um print. */
class AevoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 28 && getProcessName().endsWith(":crash")) return

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val report = "Thread: ${thread.name}\n" +
                    "Aparelho: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
                    "ABI ${Build.SUPPORTED_ABIS.joinToString()}\n\n" +
                    Log.getStackTraceString(error)
                startActivity(
                    Intent(this, CrashActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .putExtra(CrashActivity.EXTRA_REPORT, report)
                )
                Thread.sleep(300)
            } catch (_: Throwable) {
                previous?.uncaughtException(thread, error)
            }
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(10)
        }
    }
}
