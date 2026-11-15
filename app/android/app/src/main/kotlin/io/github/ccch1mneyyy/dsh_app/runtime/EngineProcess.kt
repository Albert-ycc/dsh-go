package io.github.ccch1mneyyy.dsh_app.runtime

/**
 * What [RuntimeController] needs from a running engine child: [DshProcess] (PRoot / Direct, glibc)
 * and [BionicProcess] (Node on the phone's own libc). `start` is not here: its inputs differ by
 * family (PRoot binds and Direct flags vs. a physical-path plan), so the controller calls the
 * concrete class.
 */
interface EngineProcess {
    interface Listener {
        fun onLog(line: String, stream: String)
        fun onReady(source: EngineProcess, baseUrl: String, token: String)
        fun onExit(source: EngineProcess, code: Int, expected: Boolean)
        /** The start is busy turning PRoot's fake hard links into real files (true), or done (false). */
        fun onTidying(active: Boolean) {}
    }

    val port: Int
    val startupError: String?

    /** The launch command got the phone-control overlay. */
    val phoneMounted: Boolean

    /** Direct glibc launch; always false for bionic. */
    val direct: Boolean
    val launchDiagnostics: LaunchDiagnostics
    val executionMode: String?

    fun stop(expected: Boolean = true)

    /** Waits for cleanup and exit reporting; zero waits until completion. */
    fun awaitExit(millis: Long = 0): Boolean

    /** Preserve a startup failure through cleanup and the service's later STOP. */
    fun failStart(message: String)
}
