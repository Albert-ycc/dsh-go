package probe.zyt

import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.io.File

/** 运行在 :engine 进程：重复主进程的原生探针，结果写 report_engine.json */
class EngineService : Service() {
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        Thread {
            Native.init(File(filesDir, "progress_engine.log").path)
            val p = Probe(this, "engine")
            val out = File(filesDir, "report_engine.json")
            p.onUpdate = { out.writeText(p.toJson().toString()) }
            try {
                p.env(); p.syscalls(); p.javaTls()
                File(filesDir, "engine_done").writeText("1")
            } catch (e: Throwable) { File(filesDir, "engine_error").writeText(e.toString()) }
            out.writeText(p.toJson().toString())
        }.start()
        return START_NOT_STICKY
    }
}
