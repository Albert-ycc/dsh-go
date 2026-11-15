package probe.zyt

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 「Web 探针」页：容器里的系统 WebView 跑不跑得动 pdf.js、能不能长按选字。
 * 本机起一个最小 HTTP（绑 0.0.0.0，静态文件取自 assets/webprobe），WebView 打开 127.0.0.1 上的测试页；
 * 页面把结果 POST 到 /result，电脑从局域网 GET /results 取走（同时带 WebView 包名与控制台输出）。
 * 资产由 scripts/stage-webprobe.sh 备齐。
 */
class WebProbeActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var dumpBox: LinearLayout
    private var server: ServerSocket? = null
    private var webViewPkg = "?"
    private var pkgs = JSONObject()
    private var browserPkg: String? = null
    private val results = ConcurrentHashMap<String, String>()
    private val console: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = TextView(this).apply { textSize = 11f; text = "WEBPROBE 启动中" }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; textSize = 11f; setOnClickListener { f() } }
        webViewPkg = try {
            val p = WebView.getCurrentWebViewPackage()
            if (p == null) "null" else "${p.packageName} ${p.versionName}"
        } catch (e: Throwable) { "ERR $e" }
        pkgs = pkgInfo()
        WebView.setWebContentsDebuggingEnabled(true)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = WebViewClient()
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (console.size < 200) console += "${m.messageLevel()} ${m.message()} @${(m.sourceId() ?: "").substringAfterLast('/')}:${m.lineNumber()}"
                return true
            }
        }
        val macUrl = try { assets.open("webprobe/mac-url.txt").bufferedReader().use { it.readText().trim() } } catch (_: Throwable) { "" }
        bar.addView(btn("本机页") { server?.let { web.loadUrl("http://127.0.0.1:${it.localPort}/index.html?k=local") } })
        if (macUrl.isNotEmpty()) bar.addView(btn("Mac页") { web.loadUrl("$macUrl/index.html?k=mac") })
        bar.addView(btn("重载") { web.reload() })
        // 同一张测试页交给容器里装的浏览器开（结果键 browser），看换个内核是什么表现
        bar.addView(btn("浏览器页") {
            server?.let {
                try {
                    val i = Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:${it.localPort}/index.html?k=browser"))
                    browserPkg?.let { p -> i.setPackage(p) }
                    startActivity(i)
                } catch (e: Throwable) { head.text = "浏览器页打不开 $e" }
            }
        })
        // 结果的第二条出路：切块写进几行极小的原生文字，电脑用 uitest dumpLayout 读（局域网取不到时用）
        dumpBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(head); root.addView(bar); root.addView(dumpBox)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        Thread {
            try {
                val s = ServerSocket(0, 50, InetAddress.getByName("0.0.0.0"))
                server = s
                Thread {
                    while (true) {
                        val c = try { s.accept() } catch (e: Throwable) { return@Thread }
                        Thread { handle(c) }.start()
                    }
                }.start()
                val ips = try {
                    NetworkInterface.getNetworkInterfaces().toList().flatMap { n ->
                        n.inetAddresses.toList().filter { it.address.size == 4 && !it.isLoopbackAddress }.map { it.hostAddress ?: "" }
                    }
                } catch (_: Throwable) { emptyList() }
                runOnUiThread {
                    head.text = "WEBPROBE_URL http://${ips.firstOrNull() ?: "0.0.0.0"}:${s.localPort}/results  $webViewPkg"
                    web.loadUrl("http://127.0.0.1:${s.localPort}/index.html?k=local")
                }
            } catch (e: Throwable) {
                runOnUiThread { head.text = "WEBPROBE 启动失败 $e" }
            }
        }.start()
    }

    override fun onDestroy() {
        try { server?.close() } catch (_: Throwable) {}
        try { web.destroy() } catch (_: Throwable) {}
        super.onDestroy()
    }

    private fun mime(p: String) = when (p.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html; charset=utf-8"
        "mjs", "js" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json"
        "wasm" -> "application/wasm"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    /** 容器里装了哪些浏览器、谁往选字菜单里挂了入口 */
    private fun pkgInfo(): JSONObject {
        val o = JSONObject()
        val pm = packageManager
        for (p in listOf("com.android.chrome", "com.chrome.beta", "com.microsoft.emmx", "org.mozilla.firefox", "com.huawei.browser", "com.deepseek.chat", "io.github.ccch1mneyyy.dsh_app")) {
            o.put(p, try { pm.getPackageInfo(p, 0).versionName ?: "?" } catch (_: Throwable) { "未安装" })
        }
        val pt = JSONArray()
        try {
            for (r in pm.queryIntentActivities(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain"), 0)) pt.put("${r.loadLabel(pm)} | ${r.activityInfo.packageName}")
        } catch (e: Throwable) { pt.put("ERR $e") }
        o.put("PROCESS_TEXT", pt)
        val br = JSONArray()
        try {
            for (r in pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com/")), PackageManager.MATCH_ALL)) {
                br.put("${r.loadLabel(pm)} | ${r.activityInfo.packageName}")
                if (browserPkg == null || r.activityInfo.packageName == "com.android.chrome") browserPkg = r.activityInfo.packageName
            }
        } catch (e: Throwable) { br.put("ERR $e") }
        o.put("http_viewers", br)
        return o
    }

    private fun resultsJson(): JSONObject {
        val j = JSONObject()
        j.put("webview", webViewPkg)
        j.put("packages", pkgs)
        j.put("console", JSONArray(synchronized(console) { console.toList() }))
        val rs = JSONObject()
        for ((k, v) in results) {
            val parsed: Any = try { JSONObject(v) } catch (_: Throwable) { v }
            rs.put(k, parsed)
        }
        j.put("results", rs)
        return j
    }

    private fun showDump() {
        val chunks = resultsJson().toString().chunked(1500)
        runOnUiThread {
            dumpBox.removeAllViews()
            chunks.forEachIndexed { i, c ->
                dumpBox.addView(TextView(this).apply { textSize = 2f; setSingleLine(true); text = "WPDUMP ${i + 1}/${chunks.size} $c" })
            }
        }
    }

    private fun handle(c: Socket) {
        try {
            c.soTimeout = 15000
            val ins = BufferedInputStream(c.getInputStream())
            val hb = ByteArrayOutputStream()
            var st = 0
            while (st < 4) {
                val x = ins.read()
                if (x < 0) return
                hb.write(x)
                st = if (x == 13 && (st == 0 || st == 2)) st + 1 else if (x == 10 && (st == 1 || st == 3)) st + 1 else if (x == 13) 1 else 0
                if (hb.size() > 65536) return
            }
            val lines = hb.toString("ISO-8859-1").split("\r\n")
            val parts = lines[0].split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore("?")
            val query = parts[1].substringAfter("?", "")
            val len = lines.firstOrNull { it.startsWith("content-length:", ignoreCase = true) }?.substringAfter(":")?.trim()?.toIntOrNull() ?: 0
            val out = c.getOutputStream()
            fun send(code: Int, type: String, body: ByteArray) {
                out.write("HTTP/1.1 $code ${if (code == 200) "OK" else "ERR"}\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
                out.write(body); out.flush()
            }
            if (method == "POST" && path == "/result") {
                val buf = ByteArray(len.coerceIn(0, 4 shl 20))
                var off = 0
                while (off < buf.size) { val n = ins.read(buf, off, buf.size - off); if (n < 0) break; off += n }
                val k = query.substringAfter("k=", "main").substringBefore("&").filter { it.isLetterOrDigit() }.ifEmpty { "main" }
                results[k] = String(buf, 0, off, Charsets.UTF_8)
                send(200, "text/plain", "ok".toByteArray())
                showDump()
            } else if (path == "/results") {
                send(200, "application/json; charset=utf-8", resultsJson().toString(1).toByteArray())
            } else {
                val p = if (path == "/") "/index.html" else path
                val bytes = if (p.contains("..")) null else try { assets.open("webprobe$p").use { it.readBytes() } } catch (_: Throwable) { null }
                if (bytes == null) send(404, "text/plain", "not found".toByteArray()) else send(200, mime(p), bytes)
            }
        } catch (_: Throwable) {
        } finally { try { c.close() } catch (_: Throwable) {} }
    }
}
