package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.File

/**
 * Maps the guest paths the app writes for the engine and the model (`/root`, `/opt/dsh-app`, ...)
 * to physical ones. The bionic engine runs in a physical-path world with no `/root` view, so every
 * guest text it reads (overlay YAML, AGENTS.md, safe-mode briefing) is rewritten on the way out.
 * [IDENTITY] is the glibc world (PRoot, Direct): text passes through untouched.
 *
 * Pure and stateless: [text] is idempotent, so a text may pass through twice without harm.
 */
class GuestPaths private constructor(private val prefix: String?) {
    /** The physical rootfs directory, or null for [IDENTITY]. */
    val rootfs: String? get() = prefix

    val isIdentity: Boolean get() = prefix == null

    // 前一个字符不能是路径字符，所以 <rootfs>/root 里的 /root 不会被二次改写；
    // rootfs 自身作为第一个分支原样吞掉，rootfs 恰好以 /tmp 等前缀开头时也保持幂等。
    private val pattern: Regex? = prefix?.let {
        Regex("(?<![\\w./~-])(?:${Regex.escape(it)}(?![\\w-])|(${MAPPED.joinToString("|") { p -> Regex.escape(p) }})(?![\\w-]))")
    }

    /** Rewrites every guest-rooted absolute path in [s]; everything else is left as is. */
    fun text(s: String): String {
        val re = pattern ?: return s
        val root = prefix ?: return s
        return re.replace(s) { m -> if (m.groupValues[1].isEmpty()) m.value else root + m.groupValues[1] }
    }

    /** One guest path (`/root/.dsh`) as a physical path. */
    fun path(guestPath: String): String = text(guestPath)

    companion object {
        /** glibc world: guest paths are real inside PRoot, nothing to rewrite. */
        val IDENTITY = GuestPaths(null)

        /** Longest first, so `/opt/dsh-app` is never read as `/opt/dsh` plus a suffix. */
        private val MAPPED = listOf(
            "/opt/dsh-app", "/opt/dsh-go", "/opt/dsh", "/opt/pnpm",
            "/etc/dsh-app", "/var/lib/dsh-app", "/root", "/tmp",
        )

        /** The bionic engine's rootfs; canonical, like the Direct engine's AGENTS.md paths. */
        fun bionic(rootfs: File): GuestPaths = GuestPaths(rootfs.canonicalPath.trimEnd('/'))
    }
}
