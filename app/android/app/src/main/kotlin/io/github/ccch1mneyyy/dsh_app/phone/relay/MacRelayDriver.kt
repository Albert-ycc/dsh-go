package io.github.ccch1mneyyy.dsh_app.phone.relay

import io.github.ccch1mneyyy.dsh_app.phone.ActionResult
import io.github.ccch1mneyyy.dsh_app.phone.Availability
import io.github.ccch1mneyyy.dsh_app.phone.CollectResult
import io.github.ccch1mneyyy.dsh_app.phone.PhoneAction
import io.github.ccch1mneyyy.dsh_app.phone.PhoneDriver
import io.github.ccch1mneyyy.dsh_app.phone.PhoneRegistry
import io.github.ccch1mneyyy.dsh_app.phone.PhoneScreen
import io.github.ccch1mneyyy.dsh_app.phone.PhoneUnlocks
import io.github.ccch1mneyyy.dsh_app.phone.ScreenSnapshot
import io.github.ccch1mneyyy.dsh_app.phone.TargetInfo
import io.github.ccch1mneyyy.dsh_app.phone.a11y.Box
import io.github.ccch1mneyyy.dsh_app.phone.a11y.CollectedItems
import io.github.ccch1mneyyy.dsh_app.phone.a11y.ElementKind
import io.github.ccch1mneyyy.dsh_app.phone.a11y.FormattedScreen
import io.github.ccch1mneyyy.dsh_app.phone.a11y.ImageGeometry
import io.github.ccch1mneyyy.dsh_app.phone.a11y.IndexedElement
import io.github.ccch1mneyyy.dsh_app.phone.a11y.MainScreenDriver
import io.github.ccch1mneyyy.dsh_app.phone.a11y.ProtectedPages
import io.github.ccch1mneyyy.dsh_app.phone.a11y.ScreenDigest
import io.github.ccch1mneyyy.dsh_app.phone.a11y.ScreenElement
import io.github.ccch1mneyyy.dsh_app.phone.a11y.ScreenFormat
import io.github.ccch1mneyyy.dsh_app.phone.a11y.ScreenInput
import io.github.ccch1mneyyy.dsh_app.phone.a11y.ScreenObservation
import io.github.ccch1mneyyy.dsh_app.phone.a11y.ScreenShooter
import io.github.ccch1mneyyy.dsh_app.phone.a11y.ScreenVerdict
import io.github.ccch1mneyyy.dsh_app.phone.a11y.UiNode
import io.github.ccch1mneyyy.dsh_app.phone.a11y.WindowContent
import java.util.concurrent.atomic.AtomicInteger

/** Where the relay link stands, in the words of `PhoneStatus.relay.state` (§6.3). */
class RelayStatus(val state: String, val device: RelayDevice?, val error: RelayError?) {
    val ok: Boolean get() = state == OK

    companion object {
        const val OK = "ok"
        const val UNREACHABLE = "unreachable"
        const val UNAUTHORIZED = "unauthorized"
        const val HALTED = "halted"
        const val DEVICE_OFFLINE = "device_offline"
        const val DEVICE_UNAUTHORIZED = "device_unauthorized"
        const val LOCKED = "locked"
        const val UNCONFIGURED = "unconfigured"
        const val STORE_UNREADABLE = "store_unreadable"

        // 中继 device.state 的取值方案没写死：这几个算在线，其余按离线。
        private val ONLINE = setOf("online", "connected", "ok", "ready")

        fun of(relay: RelayApi, maxAgeMs: Long = RelayClient.DEVICE_CACHE_MS): RelayStatus {
            if (!relay.configured()) {
                return if (relay.storeUnreadable()) RelayStatus(STORE_UNREADABLE, null, RelayError(RelayCodes.STORE_UNREADABLE, ""))
                else RelayStatus(UNCONFIGURED, null, RelayError(RelayCodes.UNCONFIGURED, ""))
            }
            return when (val r = relay.device(maxAgeMs)) {
                is RelayResult.Err -> RelayStatus(
                    when (r.error.code) {
                        RelayCodes.UNAUTHORIZED, RelayCodes.FORBIDDEN_SOURCE, RelayCodes.PIN_MISMATCH -> UNAUTHORIZED
                        RelayCodes.HALTED -> HALTED
                        RelayCodes.DEVICE_OFFLINE, RelayCodes.DEVICE_TIMEOUT -> DEVICE_OFFLINE
                        RelayCodes.DEVICE_UNAUTHORIZED -> DEVICE_UNAUTHORIZED
                        else -> UNREACHABLE
                    },
                    null, r.error,
                )
                is RelayResult.Ok -> {
                    val info = r.value
                    val d = info.device
                    when {
                        info.halted -> RelayStatus(HALTED, d, RelayError(RelayCodes.HALTED, ""))
                        d?.state == "unauthorized" -> RelayStatus(DEVICE_UNAUTHORIZED, d, RelayError(RelayCodes.DEVICE_UNAUTHORIZED, ""))
                        d == null || d.state !in ONLINE -> RelayStatus(DEVICE_OFFLINE, d, RelayError(RelayCodes.DEVICE_OFFLINE, ""))
                        d.locked == true -> RelayStatus(LOCKED, d, RelayError(RelayCodes.DEVICE_LOCKED, ""))
                        else -> RelayStatus(OK, d, null)
                    }
                }
            }
        }

        /**
         * What may stop a tool call: no pairing, relay unreachable, token or pin refused, relay halted, the
         * phone has not authorised the Mac. `device.state` offline and `locked` come from the relay's cache
         * (it only refreshes them when a device command runs), so they are for display only here:
         * a real offline or locked phone is reported by the observe / act that finds it.
         */
        fun gate(relay: RelayApi): RelayStatus {
            val s = of(relay)
            return if (s.state == DEVICE_OFFLINE || s.state == LOCKED) RelayStatus(OK, s.device, null) else s
        }

        /**
         * 「测试连接」：中继被问到 device 不在线时自己去连，所以不在线时再问几次（每次隔 [gapMs]）。
         */
        fun settle(relay: RelayApi, tries: Int = 3, gapMs: Long = 2_000, pause: (Long) -> Unit = Thread::sleep): RelayStatus {
            var s = of(relay, 0)
            var left = tries
            while (s.state == DEVICE_OFFLINE && left-- > 0) {
                pause(gapMs)
                s = of(relay, 0)
            }
            return s
        }
    }
}

/**
 * The main screen of a phone whose accessibility service we cannot use (the Pura X container),
 * read and driven through the Mac relay (§6.1). Reading, numbering, masking, `⚠` marks and the
 * `+` diff are the same [ScreenFormat] the accessibility driver uses; what differs is that the
 * relay can only tap coordinates, so every write re-reads the screen first, finds the element again
 * by fingerprint and taps the centre of where it is now.
 *
 * The snapshot bookkeeping is the second copy of [MainScreenDriver]'s (`publish`, `resolve`,
 * `after`); the third user earns a shared one, and the copy keeps the driver verified on Android
 * phones untouched.
 */
internal class MacRelayDriver(
    private val relay: RelayApi,
    private val ownPackage: String,
    /** App name for a package (container PackageManager, relay aliases). */
    private val labelOf: (String) -> String? = { null },
    /** What the person is told for a [RelayStatus] state (app strings); null → the model sentence. */
    private val userText: (String) -> String? = { null },
    private val unlocks: () -> PhoneUnlocks = { PhoneRegistry.unlocks() },
    private val shots: RelayShots = AndroidRelayShots,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val uptime: () -> Long = System::currentTimeMillis,
) : PhoneDriver {
    override val screen = PhoneScreen.MAIN

    private val lock = Any()
    private var latest: Snapshot? = null
    @Volatile private var lastFront: Pair<String?, Long>? = null

    /** Bumped by [stop]: loops and waits that started before it give up. */
    @Volatile private var abortGen = 0L

    private class Failure(val error: RelayError) : Exception(error.code)

    private class Snapshot(
        val id: String,
        val screen: FormattedScreen,
        val packageName: String?,
        val appLabel: String?,
        val protectedPage: Boolean,
        val ownApp: Boolean,
        val hidden: Boolean,
        val unreadable: Boolean,
        val observation: ScreenObservation,
        val noteKeys: Set<String>,
    ) {
        @Volatile var image: ImageGeometry? = null
    }

    private class Resolved(val snap: Snapshot, val el: ScreenElement, val cap: RelayScreen, val found: IndexedElement)

    private val lifted: PhoneUnlocks get() = unlocks()

    /** Stop pressed: give up what is running and have the Mac cancel the task's in-flight commands. */
    fun stop(task: Long) {
        abortGen++
        relay.stop(task)
    }

    // ---- PhoneDriver ------------------------------------------------------------------------------

    override fun availability(): Availability {
        val s = RelayStatus.gate(relay)
        if (s.ok) return Availability(true)
        val e = s.error ?: RelayError(RelayCodes.UNREACHABLE, "")
        return Availability(false, RelayMessages.forModel(e), userText(s.state))
    }

    /** The foreground bundle of the last read, without asking the Mac. */
    fun lastForeground(): String? = lastFront?.first

    override fun foregroundPackage(): String? {
        lastFront?.let { (pkg, at) -> if (uptime() - at < FRONT_FRESH_MS) return pkg }
        return try { capture(false).packageName } catch (_: Failure) { null }
    }

    override fun observe(detail: Boolean, image: Boolean): ScreenSnapshot = synchronized(lock) {
        availability().let { if (!it.ok) return blank(it.reason!!) }
        val cap = try { capture(image) } catch (f: Failure) { return blank(say(f.error)) }
        // l10n:keep agent screen result
        if (cap.locked == true) return blank("手机已锁屏。请用户解锁后再继续。")
        val snap = publish(cap, detail)
        val text = StringBuilder(snap.screen.text)
        var bytes: ByteArray? = null
        var mime: String? = null
        var secure = false
        if (image) {
            when {
                // l10n:keep agent screen result
                snap.ownApp && !lifted.ownApp -> text.append("\n提示：DSH 自己的界面不截图。")
                // l10n:keep agent screen result
                ScreenFormat.index(cap.windows).any { it.fullText.contains("«银行卡") || it.fullText.contains("«身份证") } ->
                    // l10n:keep agent screen result
                    text.append("\n提示：本屏有银行卡号或身份证号，这次没有附截图。")
                // l10n:keep agent screen result
                cap.imageJpeg == null -> text.append("\n提示：这次没有拿到截图。")
                else -> {
                    val shot = shots.compose(cap.imageJpeg, snap.screen.elements, ScreenShooter.sensitiveBounds(cap.windows), cap.screen)
                    secure = shot.secure
                    if (shot.bytes != null && shot.geometry != null) {
                        bytes = shot.bytes
                        mime = "image/jpeg"
                        snap.image = shot.geometry
                        // l10n:keep agent screen result
                        text.append("\n截图上的数字是元素编号；tap_xy 用截图像素（${shot.geometry.width}×${shot.geometry.height}）。")
                    // l10n:keep agent screen result
                    } else text.append("\n提示：${shot.error ?: "截图失败"}。")
                }
            }
        }
        return ScreenSnapshot(snap.id, text.toString(), snap.appLabel, snap.packageName, secure || snap.hidden, snap.screen.poorTree, bytes, mime)
    }

    override fun target(screenId: String, elementId: Int): TargetInfo? = synchronized(lock) {
        val snap = latest ?: return null
        if (snap.id != screenId) return null
        val el = snap.screen.element(elementId) ?: return null
        if (!availability().ok) return null
        val cap = try { capture(false) } catch (_: Failure) { return null }
        if (locate(cap, el) == null) return null
        // 输入法亮着安全键盘：此刻的输入框按密码框算，不管它的标签怎么写（P4 H3）。SafetyGate 据此拒绝并交还用户。
        info(screenId, el, secure = cap.secureKeyboard && el.editable)
    }

    private fun info(screenId: String, el: ScreenElement, secure: Boolean = false) = TargetInfo(
        screenId = screenId, elementId = el.id, role = el.role, label = el.label,
        packageName = el.packageName, appLabel = el.packageName?.let(labelOf), isPassword = el.password,
        isSecretField = el.secret || secure, isEditable = el.editable, viewId = el.viewId,
    )

    override fun perform(action: PhoneAction): ActionResult = synchronized(lock) {
        availability().let { if (!it.ok) return ActionResult(false, it.reason!!) }
        val gen = abortGen
        try {
            when (action) {
                is PhoneAction.Key -> key(action, gen)
                is PhoneAction.Tap -> tap(action, gen)
                is PhoneAction.Type -> type(action, gen)
                is PhoneAction.Scroll -> scroll(action, gen)
                is PhoneAction.TapXY -> tapXY(action, gen)
            }
        } catch (f: Failure) {
            ActionResult(false, say(f.error))
        }
    }

    override fun open(packageName: String): ActionResult = synchronized(lock) {
        availability().let { if (!it.ok) return ActionResult(false, it.reason!!) }
        // l10n:keep agent screen result
        if (packageName == ownPackage && !lifted.ownApp) return ActionResult(false, "DSH 自己不需要打开。")
        val name = labelOf(packageName) ?: packageName
        val gen = abortGen
        try {
            when (val r = relay.launch(packageName)) {
                is RelayResult.Err -> return ActionResult(false, say(r.error))
                is RelayResult.Ok -> {}
            }
            val deadline = uptime() + OPEN_MS
            var front: String? = null
            var last: RelayScreen? = null
            while (uptime() < deadline && !cancelled(gen)) {
                last = capture(false)
                front = last.packageName
                if (front == packageName) break
                sleep(300)
            }
            val snap = if (last != null && front == packageName) toSnapshot(publish(last, false)) else observeQuiet()
            // l10n:keep agent screen result
            return if (front == packageName) ActionResult(true, "已打开「$name」。", snap)
            // l10n:keep agent screen result
            else ActionResult(false, "已请求打开「$name」，但 6 秒内它没有到前台（现在是「${front?.let(labelOf) ?: front ?: "未知"}」）。", snap)
        } catch (f: Failure) {
            return ActionResult(false, say(f.error))
        }
    }

    override fun collect(screenId: String, listElementId: Int, maxPages: Int, until: String?): CollectResult = synchronized(lock) {
        availability().let { if (!it.ok) return CollectResult(emptyList(), 0, it.reason!!, null) }
        val snap = latest
        if (snap == null || snap.id != screenId) {
            // l10n:keep agent screen result
            return CollectResult(emptyList(), 0, "屏幕编号 $screenId 已过期，请先看新屏幕再 collect", observeQuiet())
        }
        // l10n:keep agent screen result
        if (snap.ownApp && !lifted.ownApp) return CollectResult(emptyList(), 0, "这是 DSH 自己的界面，不读取", null)
        var el = snap.screen.element(listElementId)
            // l10n:keep agent screen result
            ?: return CollectResult(emptyList(), 0, "屏幕 $screenId 里没有编号 $listElementId", null)
        if (el.kind != ElementKind.LIST) {
            el = el.listId?.let { snap.screen.element(it) }
                // l10n:keep agent screen result
                ?: return CollectResult(emptyList(), 0, "[$listElementId] 不是列表，也不在列表里", null)
        }
        val pages = maxPages.coerceIn(1, 30)
        val wanted = until?.trim()?.takeIf { it.isNotEmpty() }
        val gen = abortGen
        val deadline = uptime() + COLLECT_MS
        val items = CollectedItems()
        // l10n:keep agent screen result
        var stop = "未能读取列表"
        var read = 0
        var misses = 0
        val direction = if (el.horizontal) PhoneAction.Direction.RIGHT else PhoneAction.Direction.DOWN
        var lastBounds = el.bounds
        try {
            while (true) {
                val cap = capture(false)
                if (unavailable(cap)) {
                    // l10n:keep agent screen result
                    stop = "这一屏读不到内容（可能受敏感视图策略限制）"
                    break
                }
                val list = ScreenFormat.followList(ScreenFormat.index(cap.windows), el.fingerprint, lastBounds)
                    // l10n:keep agent screen result
                    ?: run { stop = "列表不见了（界面变了）"; null } ?: break
                val page = items.readPage(list.itemTexts, wanted, cap.truncated)
                if (list.itemTexts.isNotEmpty()) read++
                if (page.stopReason != null) { stop = page.stopReason; break }
                if (list.itemTexts.isEmpty()) {
                    // l10n:keep agent screen result
                    stop = "列表没有可读条目，无法确认是否到底"
                    break
                }
                lastBounds = list.bounds
                // l10n:keep agent screen result
                if (page.found) { stop = "找到了「$wanted」"; break }
                // l10n:keep agent screen result
                if (items.chars > COLLECT_CHARS) { stop = "内容太多，先停在这里"; break }
                // l10n:keep agent screen result
                if (read > 1 && page.added == 0) { if (++misses >= 2) { stop = "没有读到更多条目，可能已经到底"; break } } else misses = 0
                // l10n:keep agent screen result
                if (read >= pages) { stop = "达到页数上限（$pages 页）"; break }
                // l10n:keep agent screen result
                if (cancelled(gen)) { stop = "已停止"; break }
                // l10n:keep agent screen result
                if (uptime() > deadline) { stop = "超时"; break }
                swipe(list.bounds, cap.screen, direction)
                sleep(SETTLE_MS)
            }
        } catch (f: Failure) {
            stop = say(f.error)
        }
        return CollectResult(items.list(), read, stop, observeQuiet())
    }

    // ---- actions ----------------------------------------------------------------------------------

    /** The element of the latest snapshot, re-found on a fresh read; or the refusal. */
    private fun resolve(screenId: String, elementId: Int): Any {
        val snap = latest
        if (snap == null || snap.id != screenId) {
            val now = observeQuiet()
            // l10n:keep agent screen result
            val msg = if (snap == null) "还没有读过屏幕，先用 screen 看一下。"
            // l10n:keep agent screen result
            else "屏幕编号 $screenId 过期了（最新 ${now?.id ?: snap.id}），没有执行。按新屏幕的编号来。"
            return ActionResult(false, msg, now)
        }
        val el = snap.screen.element(elementId)
            // l10n:keep agent screen result
            ?: return ActionResult(false, "屏幕 $screenId 里没有编号 $elementId（共 ${snap.screen.elements.size} 项）。")
        val cap = capture(false)
        val hit = locate(cap, el)
        if (hit == null) {
            val now = toSnapshot(publish(cap, false))
            // l10n:keep agent screen result
            return ActionResult(false, "目标 [$elementId]「${short(el.label.ifEmpty { el.role })}」变了，没有执行；看新屏幕再选。", now)
        }
        return Resolved(snap, el, cap, hit)
    }

    private fun refuseWrite(r: Resolved): ActionResult? {
        val lifted = lifted
        return when {
            // l10n:keep agent screen result
            (r.el.packageName == ownPackage || r.snap.ownApp) && !lifted.ownApp -> ActionResult(false, "这是 DSH 自己的界面，不操作。")
            // l10n:keep agent screen result
            r.snap.protectedPage && !lifted.systemPages -> ActionResult(false, "这是系统权限 / 安全相关页面，DSH 不在这里点击或输入。需要的话请用 handoff 交给用户。")
            // l10n:keep agent screen result
            (r.el.password || r.el.secret) && !lifted.secretFields -> ActionResult(false, "这是密码 / 验证码一类的输入框，DSH 不替用户点或填。请用 handoff 让用户自己来。")
            else -> null
        }
    }

    /** The point to tap for [found]: the centre of the part of it that is on the display. */
    private fun tapPoint(found: IndexedElement, screen: Box): Pair<Int, Int>? {
        val visible = found.bounds.intersect(screen)
        return if (visible.isEmpty) null else visible.centerX to visible.centerY
    }

    private fun tap(a: PhoneAction.Tap, gen: Long): ActionResult {
        val resolved = resolve(a.screenId, a.elementId)
        val r = resolved as? Resolved ?: return resolved as ActionResult
        refuseWrite(r)?.let { return it }
        if (r.el.kind == ElementKind.LIST) {
            // l10n:keep agent screen result
            return ActionResult(false, "[${a.elementId}] 是列表本身，不能点。看更多用 scroll，整理全部条目用 collect。")
        }
        val what = short(r.el.label.ifEmpty { r.el.role })
        // l10n:keep agent screen result
        val (x, y) = tapPoint(r.found, r.cap.screen) ?: return ActionResult(false, "「$what」不在屏幕范围内，没有点击。先滚动到它。", observeQuiet())
        act(mapOf("op" to if (a.long) "longClick" else "click", "x" to x, "y" to y))?.let { return it }
        // l10n:keep agent screen result
        return after(r.snap, gen, "已提交${if (a.long) "长按" else "点击"}「$what」。")
    }

    private fun type(a: PhoneAction.Type, gen: Long): ActionResult {
        val resolved = resolve(a.screenId, a.elementId)
        val r = resolved as? Resolved ?: return resolved as ActionResult
        refuseWrite(r)?.let { return it }
        if (r.cap.secureKeyboard && !lifted.secretFields) {
            // l10n:keep agent screen result
            return ActionResult(false, "输入法正显示安全键盘，这是密码 / 验证码一类的输入框，DSH 不替用户填。请用 handoff 让用户自己来。")
        }
        if (r.el.kind != ElementKind.INPUT) {
            // l10n:keep agent screen result
            return ActionResult(false, "[${a.elementId}]「${short(r.el.label)}」不是输入框：先点它，等输入框出现再输入。")
        }
        // l10n:keep agent screen result
        if (a.text.length > MAX_TEXT) return ActionResult(false, "Mac 中继一次最多输入 $MAX_TEXT 字，请分段输入。")
        // l10n:keep agent screen result
        if (a.text.any { it.isISOControl() }) return ActionResult(false, "Mac 中继输入不支持换行和控制字符。换行请分段输入，再用 submit 提交。")
        val (x, y) = tapPoint(r.found, r.cap.screen)
            // l10n:keep agent screen result
            ?: return ActionResult(false, "输入框不在屏幕范围内，没有输入。先滚动到它。", observeQuiet())
        act(mapOf("op" to "replaceText", "x" to x, "y" to y, "text" to a.text))?.let { return it }
        var got = readBack(r.found.bounds, a.text, gen)
        // 拼音输入法把字母先放在组词条里，回车才提交进输入框：回读不一致时补一次回车再读。
        if (!sameText(got, a.text)) {
            act(mapOf("op" to "key", "name" to "enter"))?.let { return it }
            got = readBack(r.found.bounds, a.text, gen)
        }
        // l10n:keep agent screen result
        val name = short(r.el.label.ifEmpty { "输入框" })
        if (!sameText(got, a.text)) {
            sleep(SETTLE_MS)
            return ActionResult(false, MainScreenDriver.readBackMismatch(got, info(r.snap.id, r.el)), observeQuiet())
        }
        // l10n:keep agent screen result
        var message = "已在「$name」输入，回读一致。"
        if (a.submit) {
            act(mapOf("op" to "key", "name" to "enter"))?.let { return it }
            // l10n:keep agent screen result
            message += "已提交键盘上的发送 / 搜索操作。"
        }
        return after(r.snap, gen, message)
    }

    private fun scroll(a: PhoneAction.Scroll, gen: Long): ActionResult {
        val snap = latest
        val dirWord = when (a.direction) {
            // l10n:keep agent screen result
            PhoneAction.Direction.DOWN -> "向下"; PhoneAction.Direction.UP -> "向上"
            // l10n:keep agent screen result
            PhoneAction.Direction.LEFT -> "向左"; PhoneAction.Direction.RIGHT -> "向右"
        }
        var targetId: Int? = a.elementId
        if (targetId != null && snap != null && snap.id == a.screenId) {
            val el = snap.screen.element(targetId)
            if (el != null && el.kind != ElementKind.LIST) targetId = el.listId ?: targetId
        } else if (targetId == null && snap != null && snap.id == a.screenId) {
            targetId = snap.screen.elements.filter { it.kind == ElementKind.LIST }.maxByOrNull { it.bounds.area }?.id
        }
        if (targetId == null) {
            // l10n:keep agent screen result
            if (snap == null || snap.id != a.screenId) return ActionResult(false, "屏幕编号 ${a.screenId} 已过期，请先看新屏幕再滚动。", observeQuiet())
            val cap = capture(false)
            // l10n:keep agent screen result
            if (cap.packageName == ownPackage && !lifted.ownApp) return ActionResult(false, "这是 DSH 自己的界面，不操作。")
            // l10n:keep agent screen result
            if (snap.protectedPage && !lifted.systemPages) return ActionResult(false, "这是系统权限 / 安全相关页面，DSH 不在这里滚动。需要的话请用 handoff 交给用户。")
            // l10n:keep agent screen result
            if (unavailable(cap)) return ActionResult(false, "这一屏读不到内容（可能受敏感视图策略限制），不盲滑。请 handoff。", toSnapshot(publish(cap, false)))
            // l10n:keep agent screen result
            if (!observation(cap).reusableCoordinates(snap.observation)) return ActionResult(false, "无法确认当前界面仍与上一屏一致，没有滚动。请先看新屏幕。", toSnapshot(publish(cap, false)))
            val s = cap.screen
            swipe(Box(s.left, s.top + s.height / 5, s.right, s.bottom - s.height / 6), s, a.direction)?.let { return it }
            // l10n:keep agent screen result
            return after(snap, gen, "已提交${dirWord}滑动屏幕。", scrolled = true)
        }
        val resolved = resolve(a.screenId, targetId)
        val r = resolved as? Resolved ?: return resolved as ActionResult
        // l10n:keep agent screen result
        if ((r.el.packageName == ownPackage || r.snap.ownApp) && !lifted.ownApp) return ActionResult(false, "这是 DSH 自己的界面，不操作。")
        // l10n:keep agent screen result
        if (r.snap.protectedPage && !lifted.systemPages) return ActionResult(false, "这是系统权限 / 安全相关页面，DSH 不在这里滚动。需要的话请用 handoff 交给用户。")
        swipe(r.found.bounds, r.cap.screen, a.direction)?.let { return it }
        // l10n:keep agent screen result
        return after(r.snap, gen, "已提交${dirWord}滚动${if (r.el.kind == ElementKind.LIST) "列表 [${r.el.id}]" else ""}。", scrolled = true)
    }

    private fun key(a: PhoneAction.Key, gen: Long): ActionResult {
        val name = when (a.key) {
            PhoneAction.GlobalKey.BACK -> "back"
            PhoneAction.GlobalKey.HOME -> "home"
            PhoneAction.GlobalKey.RECENTS -> "recents"
            PhoneAction.GlobalKey.NOTIFICATIONS -> "notifications"
        }
        act(mapOf("op" to "key", "name" to name))?.let { return it }
        val what = when (a.key) {
            // l10n:keep agent screen result
            PhoneAction.GlobalKey.BACK -> "已提交返回键操作。"
            // l10n:keep agent screen result
            PhoneAction.GlobalKey.HOME -> "已提交回到桌面的按键操作。"
            // l10n:keep agent screen result
            PhoneAction.GlobalKey.RECENTS -> "已提交打开最近任务的按键操作。"
            // l10n:keep agent screen result
            PhoneAction.GlobalKey.NOTIFICATIONS -> "已提交打开通知栏的按键操作。"
        }
        return after(latest, gen, what)
    }

    private fun tapXY(a: PhoneAction.TapXY, gen: Long): ActionResult {
        val snap = latest
        if (snap == null || snap.id != a.screenId) {
            val now = observeQuiet()
            // l10n:keep agent screen result
            return ActionResult(false, "屏幕编号 ${a.screenId} 已过期，坐标对不上了。请重新 screen 加 image 再点。", now)
        }
        // l10n:keep agent screen result
        val geometry = snap.image ?: return ActionResult(false, "屏幕 ${a.screenId} 没有截图。坐标点击要先用 screen 加 image 拿一张标号截图。")
        val point = geometry.toScreen(a.x, a.y)
            // l10n:keep agent screen result
            ?: return ActionResult(false, "坐标 (${a.x}, ${a.y}) 不在这张截图里（${geometry.width}×${geometry.height}）。")
        // l10n:keep agent screen result
        if (snap.ownApp && !lifted.ownApp) return ActionResult(false, "这是 DSH 自己的界面，不操作。")
        // l10n:keep agent screen result
        if (snap.protectedPage && !lifted.systemPages) return ActionResult(false, "这是系统权限 / 安全相关页面，DSH 不在这里点击。需要的话请用 handoff 交给用户。")
        // l10n:keep agent screen result
        if (snap.hidden || snap.unreadable) return ActionResult(false, "这一屏读不到内容（可能受敏感视图策略限制），不盲点。请 handoff。", observeQuiet())
        val cap = capture(false)
        // l10n:keep agent screen result
        if (unavailable(cap)) return ActionResult(false, "这一屏读不到内容（可能受敏感视图策略限制），不盲点。请 handoff。", toSnapshot(publish(cap, false)))
        // l10n:keep agent screen result
        if (!observation(cap).reusableCoordinates(snap.observation)) return ActionResult(false, "无法确认当前界面仍与截图一致，没有点击。请重新看屏幕。", toSnapshot(publish(cap, false)))
        act(mapOf("op" to "click", "x" to point.first, "y" to point.second))?.let { return it }
        // l10n:keep agent screen result
        return after(snap, gen, "已提交点击截图上的 (${a.x}, ${a.y})。")
    }

    /** Sends one write to the relay; null when it went through, else the refusal for the model. */
    private fun act(body: Map<String, Any?>): ActionResult? = when (val r = relay.act(body)) {
        is RelayResult.Ok -> null
        is RelayResult.Err -> ActionResult(false, say(r.error), observeQuiet())
    }

    /** A swipe inside [area] that shows more in [direction] (DOWN: the finger moves up), clear of the bars. */
    private fun swipe(area: Box, screen: Box, direction: PhoneAction.Direction): ActionResult? {
        val box = area.intersect(Box(screen.left, screen.top + screen.height / 20, screen.right, screen.bottom - screen.height * 6 / 100))
        // l10n:keep agent screen result
        if (box.isEmpty || box.height < 40 || box.width < 40) return ActionResult(false, "没能滚动：可滚动的区域太小。", observeQuiet())
        val cx = box.centerX
        val cy = box.centerY
        val dy = box.height * 3 / 10
        val dx = box.width * 3 / 10
        val (x1, y1, x2, y2) = when (direction) {
            PhoneAction.Direction.DOWN -> listOf(cx, cy + dy, cx, cy - dy)
            PhoneAction.Direction.UP -> listOf(cx, cy - dy, cx, cy + dy)
            PhoneAction.Direction.RIGHT -> listOf(cx + dx, cy, cx - dx, cy)
            PhoneAction.Direction.LEFT -> listOf(cx - dx, cy, cx + dx, cy)
        }
        return act(mapOf("op" to "swipe", "x1" to x1, "y1" to y1, "x2" to x2, "y2" to y2))
    }

    /**
     * Waits for the screen to settle, reads it (`+` marks) and says whether anything changed.
     * A page still drawing (almost no text) is read again after a pause, at most twice.
     */
    private fun after(previous: Snapshot?, gen: Long, message: String, scrolled: Boolean = false): ActionResult {
        sleep(SETTLE_MS)
        val started = uptime()
        var cap = try { capture(false) } catch (f: Failure) {
            // l10n:keep agent screen result
            return ActionResult(true, "$message（动作已提交，但没能读到新屏幕，无法验证结果。）")
        }
        var tries = 0
        while (tries++ < 2 && !cancelled(gen) && looksUnfinished(cap, previous) && uptime() - started < UNFINISHED_MS) {
            sleep(UNFINISHED_PAUSE_MS)
            cap = try { capture(false) } catch (_: Failure) { return ActionResult(true, "$message（动作已提交，但没能读到新屏幕，无法验证结果。）") }
        }
        val snap = publish(cap, false)
        val old = previous?.observation
        val observation = snap.observation
        val comparable = old != null && observation.comparableTo(old)
        val changed = old != null && observation.changedFrom(old)
        val keyboard = old?.let { observation.keyboardChange(it) }
        val note = ScreenVerdict.note(comparable = comparable, changed = changed, keyboard = keyboard, scrolled = scrolled)
        val canReuse = comparable && observation.sameContext(old) && observation.keyboardShown == old.keyboardShown
        val text = ScreenDigest.text(previous?.id, previous?.screen, snap.id, snap.screen, canReuse = canReuse, observationChanged = changed)
        return ActionResult(true, message + note, toSnapshot(snap, text))
    }

    private fun looksUnfinished(cap: RelayScreen, previous: Snapshot?): Boolean =
        !cap.mainUnreadable && (cap.windows.isEmpty() || (ScreenFormat.textCount(cap.windows) < ScreenFormat.POOR_TREE && previous?.screen?.poorTree != true))

    /** What the input at [bounds] holds now, polled for up to ~2 s; the relay has no node to refresh. */
    private fun readBack(bounds: Box, expected: String, gen: Long): String? {
        var got: String? = null
        for (attempt in 0 until 2) {
            if (cancelled(gen)) return got
            sleep(if (attempt == 0) 200 else 500)
            val cap = try { capture(false) } catch (_: Failure) { return got }
            got = inputText(cap, bounds) ?: got
            if (sameText(got, expected)) return got
        }
        return got
    }

    private fun inputText(cap: RelayScreen, bounds: Box): String? {
        var best: UiNode? = null
        fun walk(n: UiNode) {
            if (n.editable && n.bounds.contains(bounds.centerX, bounds.centerY) && (best == null || n.bounds.area < best!!.bounds.area)) best = n
            n.children.forEach(::walk)
        }
        cap.windows.forEach { w -> w.root?.let(::walk) }
        return best?.let { if (it.showingHint) "" else it.text.orEmpty() }
    }

    /** Equal, or equal once the field's own formatting (spaces, dashes) is ignored. */
    private fun sameText(got: String?, expected: String): Boolean {
        if (got == expected) return true
        if (got == null) return false
        fun norm(s: String) = s.filterNot { it.isWhitespace() || it == '-' || it == '​' }
        return norm(got) == norm(expected)
    }

    // ---- snapshots --------------------------------------------------------------------------------

    private fun capture(image: Boolean): RelayScreen {
        val r = relay.observe(image)
        if (r is RelayResult.Err) throw Failure(r.error)
        val cap = RelayTreeMapper.map((r as RelayResult.Ok).value)
        lastFront = cap.packageName to uptime()
        return cap
    }

    private fun mainWindow(cap: RelayScreen) = cap.windows.lastOrNull()

    /** Front window is there but gave only empty layout shells; mirrors what TreeCapture reports as unreadable. */
    private fun unreadableTitle(cap: RelayScreen): String? {
        val main = mainWindow(cap) ?: return null
        return if (main.kind == io.github.ccch1mneyyy.dsh_app.phone.a11y.WindowKind.APP && !cap.truncated && !WindowContent.usable(main.root)) main.title ?: "" else null
    }

    private fun unavailable(cap: RelayScreen) = WindowContent.unavailable(cap.mainUnreadable, unreadableTitle(cap) != null, cap.windows)

    private fun observation(cap: RelayScreen) = ScreenObservation(
        // 中继读不到键盘：恒为 false，既不会产生「键盘弹出/收起」的判断，也不挡 tap_xy 对坐标可复用的检查。
        cap.packageName, cap.windows, cap.screen, false, unavailable(cap), cap.truncated,
        ScreenFormat.observationKeys(cap.windows),
    )

    private fun locate(cap: RelayScreen, el: ScreenElement): IndexedElement? =
        ScreenFormat.index(cap.windows).firstOrNull { it.fingerprint == el.fingerprint }

    private fun publish(cap: RelayScreen, detail: Boolean): Snapshot {
        val id = "${PhoneScreen.MAIN.prefix}${counter.incrementAndGet()}"
        val pkg = cap.packageName
        val appLabel = pkg?.let(labelOf)
        val titles = cap.windows.mapNotNull { it.title } + ProtectedPages.titleTexts(cap.windows.lastOrNull()?.root, cap.screen.height)
        val protectedPage = ProtectedPages.isProtected(pkg, titles)
        val ownFront = pkg == ownPackage
        val seen = if (latest?.packageName == pkg) latest?.noteKeys ?: emptySet() else emptySet()
        val notes = mutableListOf<Pair<String, String>>()
        fun note(key: String, text: String) { if (key !in seen) notes += key to text }
        val lifted = lifted
        val unreadable = unreadableTitle(cap)
        // l10n:keep agent screen result
        if (ownFront && !lifted.ownApp) note("ownApp", "提示：这是 DSH 自己的界面，DSH 不操作它。")
        // l10n:keep agent screen result
        if (protectedPage && !lifted.systemPages) note("protected", "提示：这是系统权限 / 安全相关页面，DSH 只看不操作；需要的话用 handoff 交给用户。")
        // l10n:keep agent screen result
        if (cap.truncated) note("truncated", "提示：页面元素太多，只读了一部分。")
        if (cap.mainUnreadable) {
            // l10n:keep agent screen result
            note("hidden", "提示：当前无法读取前台页面（可能受敏感视图策略限制）。读不到也点不了，需要就 handoff。")
        } else if (unreadable != null) {
            val name = unreadable.takeIf { it.isNotBlank() }?.let { "「$it」" } ?: ""
            // l10n:keep agent screen result
            note("unreadable", "提示：当前无法读取这一屏${name}的有效内容，可能受敏感视图策略限制。读不到也点不了；能看图的模型可以 screen 加 image，否则请 handoff。")
        // l10n:keep agent screen result
        } else if (cap.windows.isEmpty()) note("empty", "提示：读不到前台界面（可能是锁屏、安全页面或系统界面）。")
        val previous = latest?.takeIf { it.packageName == pkg }?.screen?.keys
        fun format(notes: List<Pair<String, String>>) = ScreenFormat.format(
            ScreenInput(
                screenId = id, appLabel = appLabel, packageName = pkg,
                screenWidth = cap.screen.width, screenHeight = cap.screen.height, keyboardShown = null,
                windows = cap.windows, detail = detail, previousKeys = previous, notes = notes.map { it.second },
                unreadable = unreadable != null,
            ),
        )
        var formatted = format(notes)
        var noteKeys = notes.map { it.first }.toSet()
        if (lifted.secretFields && "secretFields" !in seen && formatted.elements.any { it.password || it.secret }) {
            // l10n:keep agent screen result
            notes += "secretFields" to "提示：用户已允许 DSH 填写密码和验证码，标着「不可代填」的输入框也可以输入。"
            noteKeys = noteKeys + "secretFields"
            formatted = format(notes)
        }
        val snap = Snapshot(id, formatted, pkg, appLabel, protectedPage, ownFront, cap.mainUnreadable, unreadable != null, observation(cap), noteKeys)
        latest = snap
        return snap
    }

    private fun toSnapshot(s: Snapshot, text: String = s.screen.text) =
        ScreenSnapshot(s.id, text, s.appLabel, s.packageName, secure = s.hidden, poorTree = s.screen.poorTree)

    private fun observeQuiet(): ScreenSnapshot? {
        if (!availability().ok) return null
        return try { toSnapshot(publish(capture(false), false)) } catch (_: Failure) { null }
    }

    // l10n:keep agent screen result
    private fun blank(reason: String) = ScreenSnapshot("${PhoneScreen.MAIN.prefix}0", "屏幕读不到：$reason", null, null)

    private fun say(e: RelayError) = RelayMessages.forModel(e)

    private fun cancelled(gen: Long) = abortGen != gen || Thread.currentThread().isInterrupted

    private fun short(s: String, max: Int = 24) = ScreenFormat.clip(s, max)

    companion object {
        /** Process-wide: a rebuilt driver must never hand out an id the model still holds. */
        private val counter = AtomicInteger()
        private const val SETTLE_MS = 600L
        private const val UNFINISHED_PAUSE_MS = 800L
        private const val UNFINISHED_MS = 9_000L
        private const val FRONT_FRESH_MS = 2_000L
        private const val OPEN_MS = 6_000L
        private const val COLLECT_MS = 100_000L
        private const val COLLECT_CHARS = 60_000
        const val MAX_TEXT = 500
    }
}
