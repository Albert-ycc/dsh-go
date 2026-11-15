package probe.zyt

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.net.NetworkInterface

class MainActivity : Activity() {
    private lateinit var probe: Probe
    private val lines = mutableListOf<String>()
    private var page = 0
    private val perPage = 24
    private lateinit var tv: Array<TextView>
    private lateinit var head: TextView
    private var addr = "HTTP 未启动"
    @Volatile private var running = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 48, 24, 24) }
        head = TextView(this).apply { textSize = 13f }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }
        bar.addView(btn("上一页") { page = maxOf(0, page - 1); render() })
        bar.addView(btn("下一页") { page = minOf(pages() - 1, page + 1); render() })
        bar.addView(btn("重跑") { start(true) })
        bar.addView(btn("可达性") { start(false) })
        bar.addView(btn("无障碍设置") { openA11ySettings() })
        val bar2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar2.addView(btn("设置尝试") { tryMore() })
        bar2.addView(btn("Node 冒烟") { startActivity(Intent(this, SmokeActivity::class.java)) })
        bar2.addView(btn("Web 探针") { startActivity(Intent(this, WebProbeActivity::class.java)) })
        bar2.addView(btn("无障碍结果") { page = pages() - 1; rebuild(engineJson()) })
        val bar3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar3.addView(btn("S-OVL") { UiProbe.overlay(this); render() })
        bar3.addView(btn("S-NTF") { UiProbe.notify(this); render() })
        bar3.addView(btn("S-FGS") { UiProbe.scheduleForeground(this, 20); render() })
        bar3.addView(btn("S-OVL权限页") { UiProbe.openOverlayPermissionPage(this) })
        root.addView(head); root.addView(bar); root.addView(bar2); root.addView(bar3)
        tv = Array(perPage) { TextView(this).apply { textSize = 11f; setSingleLine(true) } }
        tv.forEach { root.addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        setContentView(root)
        start(false)
    }

    private fun note(s: String) { try { File(filesDir, "lifecycle.log").appendText("${System.currentTimeMillis() % 1000000} $s\n") } catch (_: Throwable) {} }
    override fun onPause() { note("onPause"); super.onPause() }
    override fun onResume() { super.onResume(); note("onResume") }
    override fun onWindowFocusChanged(f: Boolean) { super.onWindowFocusChanged(f); note("focus=$f") }

    private fun tryMore() {
        Thread {
            val steps = listOf<Pair<String, () -> Unit>>(
                "I ACCESSIBILITY_DETAILS_SETTINGS" to { startActivity(Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").putExtra("android.intent.extra.COMPONENT_NAME", "probe.zyt/probe.zyt.ProbeA11yService").setData(android.net.Uri.parse("package:probe.zyt")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                "J SubSettings AccessibilitySettings" to { startActivity(Intent(Intent.ACTION_MAIN).setClassName("com.android.settings", "com.android.settings.SubSettings").putExtra(":settings:show_fragment", "com.android.settings.accessibility.AccessibilitySettings").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                "K SubSettings ToggleService" to { startActivity(Intent(Intent.ACTION_MAIN).setClassName("com.android.settings", "com.android.settings.SubSettings").putExtra(":settings:show_fragment", "com.android.settings.accessibility.ToggleAccessibilityServicePreferenceFragment").putExtra("preference_key", "probe.zyt/probe.zyt.ProbeA11yService").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
            )
            for ((n, f) in steps) {
                note("STEP $n")
                try { f() } catch (e: Throwable) { note("STEP $n FAIL ${e.javaClass.simpleName}: ${e.message?.take(100)}") }
                Thread.sleep(9000)
            }
            note("STEPS done")
        }.start()
    }

    private fun openA11ySettings() {
        note("click a11y button")
        val i = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
        val res = packageManager.queryIntentActivities(i, 0).map { it.activityInfo.packageName + "/" + it.activityInfo.name }
        val sec = try {
            "enabled=" + android.provider.Settings.Secure.getString(contentResolver, "accessibility_enabled") +
                " services=" + android.provider.Settings.Secure.getString(contentResolver, "enabled_accessibility_services")
        } catch (e: Throwable) { "FAIL $e" }
        val r = try { startActivity(i); "startActivity ok" } catch (e: Throwable) { "startActivity FAIL ${e.javaClass.simpleName}: ${e.message?.take(80)}" }
        val msg = "a11y设置页 resolve=${res} | $r | $sec"
        File(filesDir, "a11y_ui.txt").writeText(msg)
        synchronized(lines) { lines += "## 无障碍按钮"; msg.chunked(58).forEach { lines += it } }
        render()
    }

    private fun pages() = maxOf(1, (lines.size + perPage - 1) / perPage)

    private fun render() {
        runOnUiThread {
            val ls = synchronized(lines) { lines.toList() }
            head.text = "探针 页${page + 1}/${pages()} 行${ls.size} ${if (running) "运行中" else "完成"}\n$addr"
            for (i in 0 until perPage) tv[i].text = ls.getOrNull(page * perPage + i) ?: ""
        }
    }

    private fun rebuild(engine: JSONObject?) {
        val all = mutableListOf<String>()
        fun dump(o: JSONObject) {
            val gs = o.getJSONObject("groups")
            for (g in gs.keys()) {
                if (g.startsWith("_")) continue
                all += "## $g"
                val a = gs.getJSONArray(g)
                for (i in 0 until a.length()) {
                    val kv = a.getJSONArray(i)
                    val s = "${kv.getString(0)}: ${kv.getString(1)}"
                    s.chunked(58).forEachIndexed { j, c -> all += if (j == 0) c else "   $c" }
                }
            }
        }
        dump(probe.toJson())
        a11yJson()?.let { all += "## 无障碍"; all += "窗口数 ${it.optInt("windowCount")} activeRoot ${it.optString("activeRootPkg")}"
            val ws = it.optJSONArray("windows"); for (i in 0 until (ws?.length() ?: 0)) { val w = ws!!.getJSONObject(i); all += "w${i} type=${w.optInt("type")} pkg=${w.optString("rootPkg")} t=${w.optString("title")}".take(58) }
            all += "events " + it.optJSONObject("eventCountByPkg").toString().take(160).chunked(58).joinToString("\n   ").split("\n")[0]
            all += "texts " + it.optJSONArray("activeTexts").toString().take(160) }
        if (engine != null) dump(engine)
        synchronized(lines) { lines.clear(); lines.addAll(all) }
        render()
    }

    private fun mergedJson(): String {
        val m = probe.toJson()
        engineJson()?.let { m.put("engine", it) }
        a11yJson()?.let { m.put("a11y", it) }
        File(filesDir, "a11y_ui.txt").takeIf { it.exists() }?.let { m.put("a11y_ui", it.readText()) }
        m.put("ui_probe", UiProbe.readLog(this))
        File(filesDir, "lifecycle.log").takeIf { it.exists() }?.let { m.put("lifecycle", it.readText()) }
        m.put("progress_main", File(filesDir, "progress_main.log").takeIf { it.exists() }?.readText() ?: "")
        return m.toString(1)
    }

    private fun a11yJson(): JSONObject? = try {
        File(filesDir, "a11y.json").takeIf { it.exists() }?.readText()?.let { JSONObject(it) }
    } catch (_: Throwable) { null }

    private fun engineJson(): JSONObject? = try {
        File(filesDir, "report_engine.json").takeIf { it.exists() }?.readText()?.let { JSONObject(it) }
    } catch (_: Throwable) { null }

    private fun start(full: Boolean) {
        if (running) return
        running = true
        File(filesDir, "progress_main.log").delete()
        File(filesDir, "report_engine.json").delete()
        File(filesDir, "engine_done").delete()
        Native.init(File(filesDir, "progress_main.log").path)
        probe = Probe(this, "main")
        val save = {
            val j = mergedJson()
            File(filesDir, "report.json").writeText(j)
            getExternalFilesDir(null)?.let { File(it, "report.json").writeText(j) }
        }
        probe.onUpdate = { rebuild(engineJson()) }
        Thread {
            try {
                if (!::srv.isInitialized) {
                    srv = ReportServer { mergedJson() }; srv.start()
                    val ips = NetworkInterface.getNetworkInterfaces().toList()
                        .flatMap { n -> n.inetAddresses.toList().filter { it.address.size == 4 && !it.isLoopbackAddress }.map { "${n.name}:${it.hostAddress}" } }
                    probe.httpPort = srv.port
                    addr = "HTTP ${ips.joinToString(",")} 端口 ${srv.port} /report"
                    File(filesDir, "http_addr.txt").writeText(addr)
                    render()
                }
                probe.httpPort = srv.port
                if (!full) { probe.a11yInfo(); probe.reach(); save(); return@Thread }
                probe.env(); save()
                probe.syscalls(); save()
                // 第二进程：此时 socket 等已测过，engine 与主进程并行不会互相干扰
                startService(Intent(this, EngineService::class.java))
                probe.javaTls(); save()
                probe.network(); save()
                probe.execs(); save()
                probe.sweepAll(); save()
                // 等 engine 结果最多 20 秒
                for (i in 0 until 40) { if (File(filesDir, "engine_done").exists()) break; Thread.sleep(500) }
                rebuild(engineJson()); save()
            } catch (e: Throwable) {
                synchronized(lines) { lines += "ERROR $e" }
            } finally { running = false; render() }
        }.start()
    }

    private lateinit var srv: ReportServer
}
