package probe.zyt

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/** 绑 0.0.0.0 的最小 HTTP：任何 GET 返回报告 JSON，同时是"容器端口对外可见"的探针 */
class ReportServer(private val body: () -> String) {
    val server = ServerSocket(0, 20, InetAddress.getByName("0.0.0.0"))
    val port get() = server.localPort
    fun start() {
        Thread {
            while (true) {
                val c = try { server.accept() } catch (e: Throwable) { return@Thread }
                Thread { handle(c) }.start()
            }
        }.start()
    }
    private fun handle(c: Socket) {
        try {
            c.soTimeout = 3000
            val first = c.getInputStream().bufferedReader().readLine() ?: return
            val b = if (first.contains("/report")) body() else "zyt probe ok\n"
            val bytes = b.toByteArray()
            c.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(bytes); flush()
            }
        } catch (_: Throwable) {} finally { try { c.close() } catch (_: Throwable) {} }
    }
}
