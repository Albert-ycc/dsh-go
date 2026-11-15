package io.github.ccch1mneyyy.dsh_app.phone.relay

import java.util.concurrent.CopyOnWriteArrayList

/** A recorded request of the fake relay. */
class Seen(val method: String, val path: String, val headers: Map<String, String>, val body: String)

/** The fake relay on [FakeRelayServer]; [handler] answers every request, [seen] records them. */
class FakeRelay(handler: (Seen, FakeRelayServer.Exchange) -> Unit) : AutoCloseable {
    val seen = CopyOnWriteArrayList<Seen>()
    private val server = FakeRelayServer { method, path, headers, body, ex ->
        val s = Seen(method, path, headers, body)
        seen += s
        handler(s, ex)
    }

    val fp: String get() = RelayHttp.fingerprint(server.certDer())
    val addr: String get() = server.addr()

    override fun close() = server.close()

    companion object {
        fun reply(ex: FakeRelayServer.Exchange, status: Int, json: String) = ex.reply(status, json)
    }
}
