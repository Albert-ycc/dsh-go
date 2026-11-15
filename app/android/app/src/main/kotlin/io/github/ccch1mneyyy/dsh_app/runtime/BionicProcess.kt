package io.github.ccch1mneyyy.dsh_app.runtime

import android.content.Context
import android.os.Process as AndroidProcess
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import kotlin.concurrent.thread

/**
 * Runs `dsh web` as a bionic Node child of the app (see [BionicLaunch]). Parallel to [DshProcess]:
 * the pump, waiter and token/ready parts are the same shape (second copy; abstract on the third),
 * the process cleanup shares [RuntimeProcessOwnership]. Reports ready after the token and socket.
 */
class BionicProcess(
    private val context: Context,
    private val manifest: RuntimeManifest,
    private val rootfs: File,
    private val listener: EngineProcess.Listener,
) : EngineProcess {
    @Volatile private var process: Process? = null
    @Volatile private var stopping = false
    @Volatile private var expectedExit = false
    @Volatile private var readyReported = false
    @Volatile private var waiter: Thread? = null
    @Volatile private var stopper: Thread? = null
    private val cleanupState by lazy {
        RuntimeProcessOwnership.cleanupState(rootfs.canonicalPath, AndroidProcess.myUid())
    }
    private val cleanupKnown get() = cleanupState.known

    override var port: Int = DshProcess.DEFAULT_PORT
        private set
    @Volatile override var startupError: String? = null
        private set
    override var phoneMounted: Boolean = false
        private set
    override val direct: Boolean get() = false
    override val launchDiagnostics = LaunchDiagnostics()
    override val executionMode: String? get() = launchDiagnostics.currentMode(process?.isAlive == true)

    /** The Node executable: a file in nativeLibraryDir, which is where targetSdk 28 may exec from. */
    private val nodePath: String
        get() = File(context.applicationInfo.nativeLibraryDir, manifest.bionicNode ?: "libdsh_node.so").path

    /**
     * [callerEnv] is the controller's composed environment; [safe] boots the rescue profile;
     * [phone] says the phone-control overlay was written and may be passed.
     */
    fun start(callerEnv: Map<String, String>, safe: Boolean, phone: Boolean, paths: GuestPaths) {
        check(process == null) { "already started" }
        val cleanup = reapStrays(allowObservationGap = true)
        check(cleanup.complete || !cleanup.ownedRemaining) { "runtime cleanup incomplete; stop the engine and retry" }
        if (!cleanup.complete) listener.onLog("[dsh-app] starting the bionic engine with incomplete process observation; no captured runtime identities remain", "system")
        val node = File(nodePath)
        check(node.isFile) { "bionic engine: ${node.name} missing from the app's native library directory" }
        launchDiagnostics.selected("bionic", null)

        val pidFile = File(rootfs, BionicLaunch.PID_FILE)
        pidFile.delete() // also removes a stale/dangling symlink; never reuse a previous run's PID
        check(!Files.exists(pidFile.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "cannot remove stale runtime pid file" }
        port = DshProcess.pickPort()
        BionicLaunch.prepare(rootfs, node.path)
        phoneMounted = phone && !safe
        val plan = BionicLaunch.plan(
            rootfs, context.applicationInfo.nativeLibraryDir, manifest.launchArgv, manifest.launchEnv, callerEnv,
            port, safe, BionicLaunch.extraPatches(rootfs, safe, phone, paths),
        )
        val builder = ProcessBuilder(plan.argv).directory(plan.cwd)
        builder.environment().apply { clear(); putAll(plan.environment) }

        readyReported = false
        listener.onLog("[dsh-app] launch method: bionic${if (safe) " (safe mode)" else ""}", "system")
        listener.onLog("[dsh-app] launching ${plan.argv.joinToString(" ") { redact(it) }}", "system")
        // stop() came in while this was being prepared: don't launch at all.
        if (stopping) {
            listener.onExit(this, 0, true)
            return
        }
        val p = builder.start()
        process = p
        launchDiagnostics.spawned()
        val out = pump(p.inputStream.bufferedReader(), "stdout")
        val err = pump(p.errorStream.bufferedReader(), "stderr")
        waiter = thread(name = "dsh-bionic-wait", isDaemon = true) {
            val code = try { p.waitFor() } catch (_: InterruptedException) { -1 }
            // The fatal message is usually the last output: let the pumps drain it before reporting.
            try {
                out.join(2000)
                err.join(2000)
            } catch (_: InterruptedException) {
            }
            val stopThread = synchronized(this) {
                process = null
                stopper
            }
            stopThread?.join()
            // An unexpected parent exit can leave bash-tool children behind: sweep before releasing ownership.
            if (stopThread == null) killStrays()
            listener.onExit(this, code, expectedExit)
        }
        if (stopping) stop()
    }

    override fun awaitExit(millis: Long): Boolean {
        val ending = waiter ?: stopper
        ending?.join(millis)
        return ending?.isAlive != true
    }

    @Synchronized override fun failStart(message: String) {
        if (stopping) return
        startupError = message
        listener.onLog("[dsh-app] start failed: $message", "system")
        stop(expected = false)
    }

    /** SIGTERM to the pid the wrapper wrote (it is node's pid after `exec`), forced kill after 5 s, then the tree sweep. */
    @Synchronized override fun stop(expected: Boolean) {
        stopping = true
        if (expected) expectedExit = true
        if (stopper != null) return
        val p = process ?: return
        stopper = thread(name = "dsh-bionic-stop", isDaemon = true) {
            try {
                val node = if (p.isAlive) guestRecord() else null
                if (node != null) signalOwned(node, OsConstants.SIGTERM) else if (p.isAlive) p.destroy()
                waitExit(p, 5000)
            } finally {
                try {
                    if (p.isAlive) p.destroyForcibly()
                } finally {
                    killStrays()
                    p.waitFor()
                }
            }
        }
    }

    private fun waitExit(p: Process, millis: Long): Boolean {
        val deadline = System.nanoTime() + millis * 1_000_000L
        while (p.isAlive && System.nanoTime() < deadline) Thread.sleep(100)
        return !p.isAlive
    }

    @Synchronized private fun guestRecord(): RuntimeProcessRecord? = synchronized(cleanupState) {
        try {
            val pid = File(rootfs, BionicLaunch.PID_FILE).takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: return null
            val owned = RuntimeProcessOwnership.owned(processSnapshot().records, setOf(rootfs.absolutePath, rootfs.canonicalPath), AndroidProcess.myPid())
            owned.forEach { cleanupKnown[it.pid] = it.started }
            owned.firstOrNull { it.pid == pid }
        } catch (_: Exception) {
            null
        }
    }

    private fun signalOwned(record: RuntimeProcessRecord, value: Int) = runCatching {
        val current = RuntimeProcessOwnership.stat(File("/proc/${record.pid}/stat").readText())
        if (current?.second == record.started && Os.stat("/proc/${record.pid}").st_uid == AndroidProcess.myUid()) {
            Os.kill(record.pid, value)
        }
    }

    private fun pump(reader: java.io.BufferedReader, stream: String) = thread(name = "dsh-bionic-$stream", isDaemon = true) {
        try {
            reader.useLines { lines ->
                lines.forEach { line ->
                    listener.onLog(line, stream)
                    if (!readyReported) detectToken(line)
                }
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized private fun detectToken(line: String) {
        if (readyReported || stopping) return
        val match = TOKEN_URL.find(line) ?: return
        val token = TOKEN_PARAM.find(match.value)?.groupValues?.get(1) ?: return
        readyReported = true
        val base = "http://127.0.0.1:$port"
        thread(name = "dsh-bionic-ready", isDaemon = true) {
            val deadline = System.currentTimeMillis() + 90_000
            while (System.currentTimeMillis() < deadline && !stopping && process?.isAlive == true) {
                if (DshProcess.canConnect(port)) {
                    listener.onReady(this, base, token)
                    return@thread
                }
                Thread.sleep(250)
            }
            if (!stopping && process?.isAlive == true) failStart("token seen but port $port never accepted connections")
        }
    }

    private val processReader = object : RuntimeProcessReader {
        override fun pids(): List<Int> = (File("/proc").listFiles() ?: throw IOException("cannot enumerate /proc"))
            .mapNotNull { it.name.toIntOrNull() }
        override fun uid(pid: Int): Int? = try {
            Os.stat("/proc/$pid").st_uid
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ENOENT || error.errno == OsConstants.ESRCH) null else throw error
        } catch (_: NoSuchFileException) { null }
        private fun text(pid: Int, name: String): String = Files.readAllBytes(File("/proc/$pid/$name").toPath()).toString(Charsets.UTF_8)
        override fun stat(pid: Int): String = text(pid, "stat")
        override fun command(pid: Int): String = text(pid, "cmdline")
        override fun environment(pid: Int): String = text(pid, "environ")
        override fun cwd(pid: Int): String = Os.readlink("/proc/$pid/cwd")
    }

    private fun processSnapshot(known: Set<Int> = emptySet()): RuntimeProcessSnapshot =
        RuntimeProcessOwnership.scan(processReader, AndroidProcess.myUid(), AndroidProcess.myPid(), known)

    private fun pidHint(): Int? = try {
        File(rootfs, BionicLaunch.PID_FILE).readText().trim().toIntOrNull()?.takeIf { it > 0 }
    } catch (_: Exception) { null }

    private fun remember(snapshot: RuntimeProcessSnapshot) {
        snapshot.retired.forEach { cleanupKnown.remove(it) }
        for (record in snapshot.records) {
            if (cleanupKnown[record.pid]?.let { it != record.started } == true) cleanupKnown.remove(record.pid)
        }
    }

    /** Reaps every captured process of this rootfs; true when none is left and /proc was fully observed. */
    @Synchronized fun killStrays(): Boolean = reapStrays().complete

    private data class CleanupResult(val complete: Boolean, val ownedRemaining: Boolean)

    /** [allowObservationGap]: a /proc that cannot be fully read is not a reason to wait when nothing owned remains. */
    private fun reapStrays(allowObservationGap: Boolean = false): CleanupResult = synchronized(cleanupState) {
        reapStraysLocked(allowObservationGap)
    }

    private fun reapStraysLocked(allowObservationGap: Boolean): CleanupResult {
        val myPid = AndroidProcess.myPid()
        val roots = setOf(rootfs.absolutePath, rootfs.canonicalPath)
        val hinted = pidHint()?.let { setOf(it) }.orEmpty()
        val deadline = System.nanoTime() + 2_000_000_000L
        while (true) {
            val snapshot = processSnapshot(cleanupKnown.keys + hinted)
            remember(snapshot)
            val owned = RuntimeProcessOwnership.owned(snapshot.records, roots, myPid, cleanupKnown)
            owned.forEach { cleanupKnown[it.pid] = it.started }
            val ownedRemaining = owned.isNotEmpty() || cleanupKnown.isNotEmpty()
            if (!ownedRemaining && snapshot.observed) return CleanupResult(true, false)
            owned.forEach { signalOwned(it, OsConstants.SIGKILL) }
            if ((!ownedRemaining && allowObservationGap) || System.nanoTime() >= deadline) {
                listener.onLog("[dsh-app] cleanup incomplete: ${owned.size} owned process(es) remain; " +
                    "${snapshot.uncertain.size} process observation(s) unavailable" +
                    if (snapshot.enumerationFailed) "; /proc enumeration unavailable" else "", "system")
                return CleanupResult(false, ownedRemaining)
            }
            Thread.sleep(25)
        }
    }

    private fun redact(arg: String): String {
        val eq = arg.indexOf('=')
        if (eq <= 0) return arg
        return if (SECRET_KEY.containsMatchIn(arg.substring(0, eq))) arg.substring(0, eq) + "=***" else arg
    }

    private companion object {
        val TOKEN_URL = Regex("""https?://[^\s'"]*[?&#]token=[A-Za-z0-9._~%-]+""")
        val TOKEN_PARAM = Regex("""[?&#]token=([A-Za-z0-9._~%-]+)""")
        val SECRET_KEY = Regex("(?i)(key|token|secret|password)")
    }
}
