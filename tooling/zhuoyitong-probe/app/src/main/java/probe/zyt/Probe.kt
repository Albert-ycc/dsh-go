package probe.zyt

import android.app.Application
import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** 一个进程里的全部探针；结果按组累积，调用方每组之后可落盘 */
class Probe(private val ctx: Context, val label: String) {
    val groups = LinkedHashMap<String, MutableList<Pair<String, String>>>()
    val sweep = LinkedHashMap<Int, String>()
    val sweepFork = LinkedHashMap<Int, String>()
    var onUpdate: () -> Unit = {}

    private fun add(g: String, k: String, v: String) {
        synchronized(this) { groups.getOrPut(g) { mutableListOf() }.add(k to v) }
        onUpdate()
    }

    private fun addNative(g: String, text: String) {
        for (line in text.lines()) {
            if (line.isBlank()) continue
            val i = line.indexOf('\t')
            if (i > 0) add(g, line.substring(0, i), line.substring(i + 1))
        }
    }

    private fun readLines(path: String, filter: (String) -> Boolean, max: Int = 50): List<String> = try {
        File(path).readLines().filter(filter).take(max)
    } catch (e: Throwable) { listOf("读取失败 ${e.javaClass.simpleName}: ${e.message}") }

    fun env() {
        val g = "环境[$label]"
        add(g, "进程", "${Application.getProcessName()} pid=${android.os.Process.myPid()} uid=${android.os.Process.myUid()}")
        add(g, "SDK_INT", Build.VERSION.SDK_INT.toString())
        add(g, "FINGERPRINT", Build.FINGERPRINT)
        add(g, "MODEL", Build.MODEL)
        add(g, "/proc/version", readLines("/proc/version", { true }).joinToString(" "))
        for (l in readLines("/proc/self/status", { it.startsWith("Seccomp") || it.startsWith("NoNewPrivs") })) {
            val kv = l.split(":", limit = 2)
            add(g, kv[0].trim(), kv.getOrElse(1) { "" }.trim())
        }
        add(g, "cpuinfo", readLines("/proc/cpuinfo", { it.isNotBlank() }, 4).joinToString(" | "))
        add(g, "nativeLibraryDir", ctx.applicationInfo.nativeLibraryDir)
        add(g, "dataDir", ctx.applicationInfo.dataDir)
        add(g, "targetSdk", ctx.applicationInfo.targetSdkVersion.toString())
        val bins = File("/system/bin").list()?.sorted() ?: emptyList()
        add(g, "/system/bin 数量", bins.size.toString())
        add("_bin[$label]", "java list", bins.joinToString(" "))
    }

    fun syscalls() {
        val g = "系统调用[$label]"
        addNative(g, Native.suite())
    }

    fun javaTls() {
        val g = "Java网络[$label]"
        if (Native.socketBlocked()) { add(g, "HttpsURLConnection", "SKIPPED(native socket 被杀)"); return }
        val t0 = System.currentTimeMillis()
        try {
            val c = URL("https://api.deepseek.com/").openConnection() as HttpsURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            val code = c.responseCode
            val cert = c.serverCertificates.firstOrNull()?.let { (it as java.security.cert.X509Certificate).subjectX500Principal.name.take(40) }
            add(g, "HttpsURLConnection api.deepseek.com", "HTTP $code ${c.cipherSuite} ${System.currentTimeMillis() - t0}ms")
            add(g, "服务器证书", cert ?: "无")
            c.disconnect()
        } catch (e: Throwable) {
            add(g, "HttpsURLConnection api.deepseek.com", "FAIL ${e.javaClass.simpleName}: ${e.message?.take(80)}")
        }
    }

    var httpPort = 0
    fun network() {
        val g = "网络[$label]"
        addNative(g, Native.net())
        if (Native.socketBlocked()) return
        val tg = mutableListOf("192.168.31.226" to 46225, "127.0.0.1" to 46225)
        if (httpPort > 0) { tg += "192.168.31.226" to httpPort; tg += "127.0.0.1" to httpPort }
        for ((ip, port) in tg) {
            val t0 = System.currentTimeMillis()
            val r = try {
                Socket().use { it.connect(InetSocketAddress(ip, port), 4000) }; "CONNECTED"
            } catch (e: Throwable) { "FAIL ${e.javaClass.simpleName}: ${e.message?.take(60)}" }
            add(g, "Java connect $ip:$port", "$r ${System.currentTimeMillis() - t0}ms")
        }
        try {
            val l = NetworkInterface.getNetworkInterfaces().toList().flatMap { n ->
                n.inetAddresses.toList().filter { it.address.size == 4 }.map { "${n.name}=${it.hostAddress}" }
            }
            add(g, "Java NetworkInterface", l.joinToString(" "))
        } catch (e: Throwable) { add(g, "Java NetworkInterface", "FAIL ${e.message}") }
    }

    fun execs() {
        val g = "exec子进程"
        val sh = arrayOf("sh", "-c", "echo hi; id; uname -a")
        addNative(g, Native.exec("native fork+execve /system/bin/sh", "/system/bin/sh", sh))
        try {
            val p = ProcessBuilder("/system/bin/sh", "-c", "echo hi; id; uname -a").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().replace('\n', '|')
            add(g, "Java ProcessBuilder sh", "exit=${p.waitFor()} out=[${out.take(200)}]")
        } catch (e: Throwable) { add(g, "Java ProcessBuilder sh", "FAIL ${e.message}") }

        val ls = Native.exec("ls", "/system/bin/sh", arrayOf("sh", "-c", "ls /system/bin | tr '\\n' ' '; echo; ls -ld /system/bin /system/bin/sh"))
        add("_bin[$label]", "sh ls", ls)
        val names = "sh toybox toolbox busybox ls cat id uname grep sed awk tar gzip gunzip curl wget nc ping ssh git python python3 node npm perl env sleep cp mv rm mkdir chmod find ps top kill getprop setprop am pm cmd settings input screencap uiautomator run-as logcat ip ifconfig netstat ss dd tr head tail wc sort xargs which app_process app_process64 linker64 dumpsys service sqlite3 tcpdump strace"
        val ex = Native.exec("exist", "/system/bin/sh", arrayOf("sh", "-c", "for n in $names; do [ -e /system/bin/${'$'}n ] && printf '%s ' ${'$'}n; done; echo; echo ---; toybox 2>&1 | head -c 3000"))
        add("_bin[$label]", "存在的名字", ex)
        add(g, "/system/bin 存在名字", ex.substringAfter("out=[").substringBefore("---").take(300))

        addNative(g, Native.exec("exec子进程 seccomp状态", "/system/bin/sh", arrayOf("sh", "-c", "grep -E 'Seccomp|NoNewPrivs' /proc/self/status")))

        val ni = Native.exec("netinfo", "/system/bin/sh", arrayOf("sh", "-c", "readlink /proc/self/ns/net; readlink /proc/self/ns/pid; readlink /proc/self/ns/mnt; echo ---route; cat /proc/net/route; echo ---ip; ip -4 addr; echo ---sock; ls -l /dev/socket 2>&1 | head -20; echo ---tcp; head -12 /proc/net/tcp"))
        add("_net[$label]", "netinfo", ni)
        for (k in listOf("ns/net", "ns/pid", "ns/mnt")) add(g, "readlink $k", try { java.nio.file.Files.readSymbolicLink(File("/proc/self/$k").toPath()).toString() } catch (e: Throwable) { "FAIL ${e.javaClass.simpleName}" })

        val lib = File(ctx.applicationInfo.nativeLibraryDir, "libprobeexec.so")
        add(g, "nativeLibraryDir/libprobeexec.so", "exists=${lib.exists()} size=${lib.length()} canExec=${lib.canExecute()}")
        if (lib.exists()) {
            addNative(g, Native.exec("exec nativeLibraryDir hello", lib.path, arrayOf(lib.path, "hello")))
            val copy = File(ctx.filesDir, "copy_exec")
            lib.copyTo(copy, true); copy.setExecutable(true, true)
            addNative(g, Native.exec("exec filesDir 拷贝 hello", copy.path, arrayOf(copy.path, "hello")))
            copy.delete()
            val outF = File(ctx.filesDir, "child_suite.txt"); outF.delete()
            addNative(g, Native.exec("exec 动态子进程 suite", lib.path, arrayOf(lib.path, "suite", outF.path)))
            val g2 = "动态exec子进程suite"
            if (outF.exists()) addNative(g2, outF.readText()) else add(g2, "输出文件", "不存在(子进程未跑完或被杀)")
        }
    }

    /** 第二轮：容器到宿主可达性。非 root 读不到 /proc/net，靠 sh、Java 接口与候选地址逐个 connect */
    fun reach() {
        val g = "可达性[$label]"
        add(g, "进程", "${Application.getProcessName()} ns/net=" + try { java.nio.file.Files.readSymbolicLink(File("/proc/self/ns/net").toPath()) } catch (e: Throwable) { "FAIL" })
        val cands = LinkedHashMap<String, Int>()   // 地址 -> 端口
        try {
            for (n in NetworkInterface.getNetworkInterfaces().toList()) {
                val ia = n.interfaceAddresses.joinToString(" ") { "${it.address.hostAddress}/${it.networkPrefixLength}" }
                add(g, "if ${n.name}", "up=${n.isUp} mtu=${n.mtu} ptp=${n.isPointToPoint} lo=${n.isLoopback} $ia")
                for (a in n.inetAddresses) {
                    val h = a.hostAddress ?: continue
                    if (n.name == "lo" && a.address.size == 4) continue
                    if (a.address.size == 4 && n.name == "wlan0") continue
                    cands[h] = 46225
                }
            }
        } catch (e: Throwable) { add(g, "NetworkInterface", "FAIL ${e.message}") }
        val sh = fun(key: String, cmd: String) {
            val r = Native.exec(key, "/system/bin/sh", arrayOf("sh", "-c", cmd))
            add("_reach[$label]", key, r)
            add(g, key, r.substringAfter("\t").take(220))
        }
        sh("ip addr", "ip addr 2>&1 | head -40")
        sh("ip route", "ip route 2>&1 | head -20; ip -6 route 2>&1 | head -20")
        sh("ip neigh", "ip neigh 2>&1 | head -20; ip rule 2>&1 | head -10")
        sh("proc net", "for f in route arp fib_trie if_inet6 dev; do echo \"== ${'$'}f\"; head -40 /proc/net/${'$'}f 2>&1; done")
        sh("hosts", "cat /etc/hosts 2>&1 | head -10; cat /system/etc/hosts 2>&1 | head -10")
        sh("getprop net", "getprop 2>&1 | grep -i 'dns\\|gateway\\|net\\.' | head -40")
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val lp = cm.getLinkProperties(cm.activeNetwork)
            if (lp == null) add(g, "LinkProperties", "null") else {
                add(g, "LinkProperties if", "${lp.interfaceName} mtu=${lp.mtu}")
                lp.linkAddresses.forEach { add(g, "LinkProperties addr", it.toString()) }
                lp.routes.forEach { r ->
                    add(g, "LinkProperties route", r.toString())
                    r.gateway?.hostAddress?.let { if (it != "0.0.0.0" && it != "::") cands.putIfAbsent(it, 46225) }
                }
                lp.dnsServers.forEach { add(g, "LinkProperties dns", it.hostAddress ?: ""); cands.putIfAbsent(it.hostAddress ?: "", 46225) }
            }
        } catch (e: Throwable) { add(g, "LinkProperties", "FAIL ${e.javaClass.simpleName}: ${e.message?.take(80)}") }
        // 宿主 hdc shell 的 ifconfig 里有 ancowlan0=172.17.1.1/24，容器邻居 172.17.1.2；作为候选
        for (a in listOf("172.17.1.1", "172.17.1.2")) cands.putIfAbsent(a, 46225)
        for (p in listOf(40251, 33917, 62110, 39347)) cands["172.17.1.1:p$p"] = p
        // 候选：默认网关常见值、容器常见桥地址、Mac 对照
        for (a in listOf("192.168.31.1", "10.0.2.2", "10.0.3.1", "172.17.0.1", "192.168.1.1", "100.64.0.1", "::1")) cands.putIfAbsent(a, 46225)
        cands["192.168.31.182:ctl"] = 22
        for ((k, port) in cands) {
            val host = k.removeSuffix(":ctl").substringBefore(":p").let { if (k.contains(":p")) it else k.removeSuffix(":ctl") }
            val t0 = System.currentTimeMillis()
            val r = try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, port), 2500)
                    s.soTimeout = 1500
                    val b = ByteArray(64)
                    val n = try { s.getInputStream().read(b) } catch (e: java.net.SocketTimeoutException) { -2 }
                    "CONNECTED 首包=" + (if (n > 0) b.take(n).joinToString("") { "%02x".format(it) } + " " + String(b, 0, n, Charsets.ISO_8859_1).filter { it.code in 32..126 } else if (n == -2) "无(1.5s)" else "EOF")
                }
            }
                catch (e: java.net.SocketTimeoutException) { "TIMEOUT" }
                catch (e: Throwable) { "FAIL ${e.javaClass.simpleName}: ${e.message?.take(60)}" }
            add(g, "connect $host:$port${if (k.endsWith(":ctl")) " (Mac对照)" else ""}", "$r ${System.currentTimeMillis() - t0}ms")
        }
    }

    fun a11yInfo() {
        val g = "无障碍[$label]"
        try {
            val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
            add(g, "isEnabled", am.isEnabled.toString())
            add(g, "touchExploration", am.isTouchExplorationEnabled.toString())
            val inst = am.installedAccessibilityServiceList.map { it.id }
            add(g, "installed 数量", inst.size.toString())
            inst.forEach { add(g, "installed", it) }
            val en = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK).map { it.id }
            add(g, "enabled", en.joinToString(" ").ifEmpty { "无" })
        } catch (e: Throwable) { add(g, "AccessibilityManager", "FAIL ${e.javaClass.simpleName}: ${e.message?.take(80)}") }
        add(g, "Secure.accessibility_enabled", android.provider.Settings.Secure.getString(ctx.contentResolver, "accessibility_enabled") ?: "null")
        add(g, "Secure.enabled_accessibility_services", android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_accessibility_services") ?: "null")
        val svc = File(ctx.filesDir, "a11y.json")
        add(g, "服务回调文件 a11y.json", if (svc.exists()) "存在" else "不存在(服务从未 onServiceConnected)")
    }

    fun sweepAll() {
        val g = "静态ELF扫描"
        val elf = File(ctx.filesDir, "elfprobe").path
        val raw = Native.sweep(elf, 0, 460)
        val killed = mutableListOf<Int>(); val timeouts = mutableListOf<Int>(); val fails = mutableListOf<String>()
        var ok = 0; var skip = 0
        for (line in raw.lines()) {
            val i = line.indexOf('\t'); if (i < 0) continue
            val nr = line.substring(0, i).toInt(); val r = line.substring(i + 1)
            sweep[nr] = r
            when {
                r.startsWith("KILLED") -> killed += nr
                r.startsWith("TIMEOUT") -> timeouts += nr
                r.startsWith("SKIP") -> skip++
                r.startsWith("exit") -> ok++
                else -> fails += "$nr:$r"
            }
        }
        // 对账：fork 子进程（继承 App 进程过滤器）逐号重跑，与 exec 静态 ELF 比
        val forkRes = HashMap<Int, String>()
        for (line in Native.sweepFork(0, 460).lines()) {
            val i = line.indexOf('\t'); if (i > 0) forkRes[line.substring(0, i).toInt()] = line.substring(i + 1)
        }
        fun cls(r: String) = when { r.startsWith("KILLED sig=31") -> "SIGSYS"; r.startsWith("SKIP") -> "SKIP"; else -> "放行" }
        val diff = sweep.keys.filter { cls(sweep[it]!!) != cls(forkRes[it] ?: "?") }
        synchronized(this) { forkRes.forEach { (k, v) -> sweepFork[k] = v } }
        add(g, "fork对账", "fork子进程与exec静态ELF分类差异 ${diff.size} 项")
        diff.chunked(6).forEachIndexed { i, c -> add(g, "差异${i}", c.joinToString(" ") { "$it:${sweep[it]}/${forkRes[it]}" }) }
        add(g, "总数", "${sweep.size} 放行=$ok 被杀=${killed.size} 阻塞=${timeouts.size} 跳过=${skip} 其它=${fails.size}")
        killed.chunked(12).forEachIndexed { i, c -> add(g, "被杀${i}", c.joinToString(" ")) }
        timeouts.chunked(12).forEachIndexed { i, c -> add(g, "阻塞${i}", c.joinToString(" ")) }
        fails.chunked(3).forEachIndexed { i, c -> add(g, "其它${i}", c.joinToString(" ")) }
    }

    fun toJson(): JSONObject {
        val o = JSONObject().put("label", label).put("time", System.currentTimeMillis())
        val gs = JSONObject()
        synchronized(this) {
            for ((g, l) in groups) {
                val a = JSONArray()
                l.forEach { a.put(JSONArray().put(it.first).put(it.second)) }
                gs.put(g, a)
            }
        }
        o.put("groups", gs)
        val sw = JSONObject()
        synchronized(this) { sweep.forEach { (k, v) -> sw.put("%03d".format(k), v) } }
        val sf = JSONObject()
        synchronized(this) { sweepFork.forEach { (k, v) -> sf.put("%03d".format(k), v) } }
        return o.put("sweep", sw).put("sweepFork", sf)
    }
}
