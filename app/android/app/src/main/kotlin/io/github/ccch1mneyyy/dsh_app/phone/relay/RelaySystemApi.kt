package io.github.ccch1mneyyy.dsh_app.phone.relay

import io.github.ccch1mneyyy.dsh_app.phone.AppEntry
import io.github.ccch1mneyyy.dsh_app.phone.IntentSpec
import io.github.ccch1mneyyy.dsh_app.phone.SysResult
import io.github.ccch1mneyyy.dsh_app.phone.SystemApi
import io.github.ccch1mneyyy.dsh_app.phone.UrlTarget

/**
 * [SystemApi] for the container: apps living in the container go through [delegate] as before, the
 * phone's own (HarmonyOS) apps are listed and started by the Mac relay. Notifications, the
 * clipboard, links and the other intents stay with the container (§2.2 D6, D7).
 */
class RelaySystemApi(
    private val delegate: SystemApi,
    private val relay: RelayApi,
    private val clock: () -> Long = System::currentTimeMillis,
    /** 刷新应用列表的执行处：读屏路径不联网也不等，缓存空了或过期了由它在别的线程补。 */
    private val background: (Runnable) -> Unit = { DEFAULT_EXECUTOR.execute(it) },
) : SystemApi {
    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile private var cache: Pair<Long, List<RelayApp>>? = null

    private fun relayApps(): List<RelayApp> {
        cache?.let { (at, list) -> if (clock() - at < CACHE_MS) return list }
        if (!relay.configured()) return emptyList()
        return when (val r = relay.apps()) {
            is RelayResult.Ok -> { cache = clock() to r.value; r.value }
            // 失败也记一次：Mac 的 apps 超时时，读屏路径不能每次再等一轮。
            is RelayResult.Err -> { val old = cache?.second.orEmpty(); cache = clock() to old; old }
        }
    }

    /** A readable name for a phone-side bundle, from the list already fetched; never goes to the Mac (it runs while a screen is being read). */
    fun labelOf(bundle: String): String? {
        val c = cache
        if (c == null || clock() - c.first >= CACHE_MS) warm()
        return c?.second?.firstOrNull { it.bundle == bundle }?.let { it.label ?: it.alias }
    }

    /** 异步刷新一次应用列表（配对成功、Mac 手启用时预热）；同一时刻只有一次在跑，失败也记 60 秒。 */
    fun warm() {
        if (!refreshing.compareAndSet(false, true)) return
        try {
            background(Runnable { try { relayApps() } finally { refreshing.set(false) } })
        } catch (e: Exception) {
            refreshing.set(false)
        }
    }


    override fun apps(): List<AppEntry> {
        val own = delegate.apps()
        val seen = own.mapTo(HashSet()) { it.packageName }
        val phone = relayApps().filter { it.bundle !in seen }.map { AppEntry(it.label ?: it.alias ?: it.bundle, it.bundle) }
        return own + phone
    }

    override fun isInstalled(pkg: String): Boolean = delegate.isInstalled(pkg)

    override fun launch(app: AppEntry): SysResult {
        if (delegate.isInstalled(app.packageName)) return delegate.launch(app)
        return when (val r = relay.launch(app.packageName)) {
            // l10n:keep agent tool result
            is RelayResult.Ok -> SysResult(true, "已请求打开「${app.label}」。", app.packageName, app.label)
            is RelayResult.Err -> SysResult(false, RelayMessages.forModel(r.error))
        }
    }

    override fun urlTarget(url: String): UrlTarget = delegate.urlTarget(url)

    override fun openUrl(url: String, target: UrlTarget): SysResult = delegate.openUrl(url, target)

    override fun intent(spec: IntentSpec, shareTo: AppEntry?): SysResult = when (spec) {
        // 容器里的时钟和日历不是用户真正在用的那个。
        is IntentSpec.Alarm, is IntentSpec.Timer, is IntentSpec.Calendar ->
            // l10n:keep agent tool result
            SysResult(false, "这台手机上的闹钟、计时器和日历在容器之外，intent 够不到。用 apps 查到「时钟」或「日历」，再用 open 打开，按界面操作。")
        else -> delegate.intent(spec, shareTo)
    }

    override fun notify(title: String, text: String): SysResult = delegate.notify(title, text)

    override fun clipboardSet(text: String): SysResult = delegate.clipboardSet(text)

    override fun clipboardGet(): SysResult = delegate.clipboardGet()

    private companion object {
        val DEFAULT_EXECUTOR: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "dsh-phone-relay-apps").apply { isDaemon = true } }
        const val CACHE_MS = 60_000L
    }
}
