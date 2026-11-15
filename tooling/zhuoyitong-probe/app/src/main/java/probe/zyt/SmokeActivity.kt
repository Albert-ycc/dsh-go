package probe.zyt

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.net.NetworkInterface

/**
 * 「Node 冒烟」页：把 assets 里的 smoke.mjs 与 ext/ 拷到 filesDir/smoke，
 * 用 ProcessBuilder 执行 nativeLibraryDir/libnode.so smoke.mjs，输出写 out.log，屏幕轮询显示尾部。
 * 进程由 companion 持有：离开页面再回来不会重复启动。
 */
class SmokeActivity : Activity() {
    private lateinit var url: TextView
    private lateinit var body: TextView
    private lateinit var log: File
    @Volatile private var alive = true

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 48, 24, 24) }
        url = TextView(this).apply { textSize = 14f; text = "SMOKE_URL 等待中" }
        body = TextView(this).apply { textSize = 10f }
        root.addView(url)
        root.addView(ScrollView(this).apply { addView(body) })
        setContentView(root)
        val dir = File(filesDir, "smoke").apply { mkdirs() }
        log = File(dir, "out.log")
        synchronized(lock) {
            if (proc?.isAlive != true) {
                try { start(dir) } catch (e: Throwable) { log.appendText("START FAIL $e\n") }
            }
        }
        Thread { poll() }.start()
    }

    override fun onDestroy() { alive = false; super.onDestroy() }

    private fun copyAsset(path: String, dst: File) {
        val kids = assets.list(path) ?: emptyArray()
        if (kids.isEmpty()) {
            dst.parentFile?.mkdirs()
            assets.open(path).use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
        } else kids.forEach { copyAsset("$path/$it", File(dst, it)) }
    }

    private fun start(dir: File) {
        log.writeText("")
        File(dir, "ext").deleteRecursively()
        copyAsset("ext", File(dir, "ext"))
        File(dir, "ext/bin/rg").setExecutable(true, false)
        copyAsset("smoke.mjs", File(dir, "smoke.mjs"))
        val tmp = File(cacheDir, "smoke-tmp").apply { mkdirs() }
        val home = File(dir, "home").apply { mkdirs() }
        val node = File(applicationInfo.nativeLibraryDir, "libnode.so")
        val pb = ProcessBuilder(node.path, File(dir, "smoke.mjs").path)
            .directory(dir).redirectErrorStream(true).redirectOutput(log)
        pb.environment().apply {
            put("UV_USE_IO_URING", "0")
            put("SMOKE_EXT", File(dir, "ext").path); put("SMOKE_OUT", dir.path)
            put("TMPDIR", tmp.path); put("HOME", home.path)
        }
        log.appendText("LAUNCH ${node.path} exists=${node.exists()} size=${node.length()}\n")
        val p = pb.start()
        proc = p
        Thread {
            val code = p.waitFor()
            log.appendText("EXIT code=$code\n")
        }.start()
    }

    private fun ips(): String = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.name.startsWith("wlan") }
            .flatMap { n -> n.inetAddresses.toList().filter { it.address.size == 4 && !it.isLoopbackAddress }.map { it.hostAddress } }
            .joinToString(",")
    } catch (_: Throwable) { "" }

    private fun poll() {
        val port = Regex("SMOKE_PORT (\\d+)")
        while (alive) {
            val txt = try { log.readText() } catch (_: Throwable) { "" }
            val m = port.find(txt)
            val u = if (m != null) "SMOKE_URL http://${ips().split(",").firstOrNull().orEmpty()}:${m.groupValues[1]}/report" else "SMOKE_URL 等待中"
            val tail = txt.lines().takeLast(60).joinToString("\n") { it.take(110) }
            runOnUiThread { url.text = u; body.text = tail }
            Thread.sleep(1000)
        }
    }

    companion object {
        private val lock = Any()
        @Volatile var proc: Process? = null
    }
}
