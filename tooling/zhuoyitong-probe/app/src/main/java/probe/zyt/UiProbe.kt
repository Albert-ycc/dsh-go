package probe.zyt

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import java.io.File

// P2-d1：容器内 UI 能力探针（悬浮窗、通知、拉前台）。每个动作都往 filesDir/ui-probe.log 追加一行，
// 经 ReportServer 的 ui_probe 字段读出，结果不靠截屏转述。
object UiProbe {
    const val CHANNEL = "zyt_ui_probe"
    const val ACTION_NTF = "probe.zyt.NTF_ACTION"
    private var overlay: TextView? = null

    fun log(ctx: Context, s: String) {
        try { File(ctx.filesDir, "ui-probe.log").appendText("${System.currentTimeMillis() % 100000000} $s\n") } catch (_: Throwable) {}
    }

    fun readLog(ctx: Context): String =
        File(ctx.filesDir, "ui-probe.log").takeIf { it.exists() }?.readText() ?: ""

    // S-OVL：先问权限，再无论结果如何都尝试 addView（容器可能不执行权限检查，也可能抛 BadTokenException）
    fun overlay(a: Activity) {
        log(a, "OVL sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE} model=${Build.MODEL}")
        val can = try { Settings.canDrawOverlays(a) } catch (e: Throwable) { log(a, "OVL canDrawOverlays FAIL $e"); null }
        log(a, "OVL canDrawOverlays=$can")
        val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${a.packageName}"))
        val res = a.packageManager.queryIntentActivities(i, 0).map { it.activityInfo.packageName + "/" + it.activityInfo.name }
        log(a, "OVL permissionPage resolve=$res")
        try {
            val wm = a.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            overlay?.let { try { wm.removeView(it) } catch (_: Throwable) {} }
            val tv = TextView(a).apply { text = "ZYT-OVL"; textSize = 28f; setTextColor(Color.WHITE); setBackgroundColor(Color.RED); gravity = Gravity.CENTER }
            val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
            val lp = WindowManager.LayoutParams(420, 200, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
            lp.gravity = Gravity.TOP or Gravity.START; lp.x = 300; lp.y = 600
            wm.addView(tv, lp)
            overlay = tv
            log(a, "OVL addView ok type=$type")
        } catch (e: Throwable) {
            log(a, "OVL addView FAIL ${e.javaClass.simpleName}: ${e.message?.take(120)}")
        }
    }

    fun removeOverlay(a: Activity) {
        try { overlay?.let { (a.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(it) }; overlay = null; log(a, "OVL removed") } catch (e: Throwable) { log(a, "OVL remove FAIL $e") }
    }

    // 打开悬浮窗权限页（只看页面，不改开关）
    fun openOverlayPermissionPage(a: Activity) {
        val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${a.packageName}"))
        try { a.startActivity(i); log(a, "OVL permissionPage startActivity ok") }
        catch (e: Throwable) { log(a, "OVL permissionPage FAIL ${e.javaClass.simpleName}: ${e.message?.take(120)}") }
    }

    // S-NTF：带按钮的通知，按钮是 getBroadcast
    fun notify(a: Activity) {
        val nm = a.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        log(a, "NTF areNotificationsEnabled=${nm.areNotificationsEnabled()}")
        try {
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "ZYT UI probe", NotificationManager.IMPORTANCE_HIGH))
            val pi = PendingIntent.getBroadcast(a, 1, Intent(ACTION_NTF).setPackage(a.packageName), PendingIntent.FLAG_UPDATE_CURRENT)
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(a, CHANNEL) else Notification.Builder(a)
            b.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("ZYT-NTF-PROBE").setContentText("容器通知探针")
                .addAction(Notification.Action.Builder(null, "ZYT-ACTION", pi).build())
            nm.notify(7001, b.build())
            log(a, "NTF notify ok")
        } catch (e: Throwable) { log(a, "NTF notify FAIL ${e.javaClass.simpleName}: ${e.message?.take(120)}") }
    }

    // S-FGS：起一个后台 Service，等 delay 秒（让脚本把 App 切到后台）后从 Service 里 startActivity 回前台
    fun scheduleForeground(a: Activity, delaySec: Int) {
        log(a, "FGS schedule delay=${delaySec}s")
        a.startService(Intent(a, FgsService::class.java).putExtra("delay", delaySec))
    }
}

class NtfReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { UiProbe.log(c, "NTF broadcast received action=${i.action}") }
}

class FgsService : Service() {
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        val delay = i?.getIntExtra("delay", 20) ?: 20
        Thread {
            Thread.sleep(delay * 1000L)
            try {
                startActivity(Intent(this, FgsTargetActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                UiProbe.log(this, "FGS service startActivity returned ok")
            } catch (e: Throwable) { UiProbe.log(this, "FGS service startActivity FAIL ${e.javaClass.simpleName}: ${e.message?.take(120)}") }
            stopSelf()
        }.start()
        return START_NOT_STICKY
    }
}

class FgsTargetActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(TextView(this).apply { text = "FGS-TARGET"; textSize = 32f; setPadding(40, 300, 40, 40) })
        UiProbe.log(this, "FGS target onCreate")
    }
    override fun onResume() { super.onResume(); UiProbe.log(this, "FGS target onResume") }
}
