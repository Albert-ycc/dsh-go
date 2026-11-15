package io.github.ccch1mneyyy.dsh_app.phone.relay

import io.github.ccch1mneyyy.dsh_app.phone.SensitiveWords
import io.github.ccch1mneyyy.dsh_app.phone.a11y.Box
import io.github.ccch1mneyyy.dsh_app.phone.a11y.Scroll
import io.github.ccch1mneyyy.dsh_app.phone.a11y.UiNode
import io.github.ccch1mneyyy.dsh_app.phone.a11y.UiWindow
import io.github.ccch1mneyyy.dsh_app.phone.a11y.WindowKind

/** One `/v1/observe` answer, shaped for [io.github.ccch1mneyyy.dsh_app.phone.a11y.ScreenFormat]. */
class RelayScreen(
    /** Top-most first; the last one is the main (foreground application) window, as `ScreenInput` wants. */
    val windows: List<UiWindow>,
    val screen: Box,
    /** Bundle of the main window. */
    val packageName: String?,
    val locked: Boolean?,
    val screenOn: Boolean?,
    /** The relay cut the tree (`truncated`), or this mapper dropped nodes below the depth limit. */
    val truncated: Boolean,
    /** The relay named a main window but gave no tree for it. */
    val mainUnreadable: Boolean,
    val imageJpeg: ByteArray?,
    /**
     * The input method is showing its secure keyboard (a window of the input method with the text
     * 「安全键盘」, or the relay said so): the focused input is a password-type field whatever its
     * label says (P4 H3).
     */
    val secureKeyboard: Boolean = false,
)

/**
 * Relay `Node` JSON (§4.3) → the accessibility-shaped [UiNode] tree the screen reader already
 * formats. Pure Kotlin: runs in JVM tests.
 */
object RelayTreeMapper {
    private const val MAX_DEPTH = 60
    private const val MAX_WINDOWS = 4

    fun map(observe: Map<String, Any?>): RelayScreen {
        val display = observe["display"] as? Map<*, *>
        val w = (display?.get("width") as? Number)?.toInt() ?: 0
        val h = (display?.get("height") as? Number)?.toInt() ?: 0
        val screen = Box(0, 0, w.coerceAtLeast(1), h.coerceAtLeast(1))
        val device = observe["device"] as? Map<*, *>
        var truncated = observe["truncated"] == true

        val raw = (observe["windows"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        val mainIndex = ((observe["main"] as? Number)?.toInt() ?: raw.indexOfFirst { it["kind"] == "app" }).takeIf { it in raw.indices }

        val secureKeyboard = observe["secureKeyboard"] == true ||
            raw.any { w -> (w["bundle"] as? String)?.contains("inputmethod") == true && (w["root"] as? Map<*, *>)?.let(::mentionsSecureKeyboard) == true }

        var mainWindow: UiWindow? = null
        var mainUnreadable = false
        val others = ArrayList<UiWindow>()
        for ((index, win) in raw.withIndex()) {
            val kind = if (win["kind"] == "system") WindowKind.SYSTEM else WindowKind.APP
            val bounds = box(win["bounds"]) ?: continue
            val share = bounds.intersect(screen).area.toDouble() / screen.area
            val isMain = index == mainIndex
            // 状态栏、导航条一类的小系统窗口是界面边框，不是内容。
            if (!isMain && (share <= 0 || (kind == WindowKind.SYSTEM && share < 0.5))) continue
            val bundle = (win["bundle"] as? String)?.takeIf { it.isNotEmpty() }
            val rootJson = win["root"] as? Map<*, *>
            val depthCut = booleanArrayOf(false)
            val root = rootJson?.let { node(it, 0, depthCut) }
            if (depthCut[0]) truncated = true
            val ability = win["ability"] as? String
            val title = (win["page"] as? String)?.takeIf { it.isNotBlank() }
            // 窗口号要在两次读屏之间保持不变（指纹里有它），所以用 bundle/ability 而不是数组下标。
            val ui = UiWindow(stableId(bundle, ability, kind), kind, bundle, title, bounds, root)
            if (isMain) {
                mainWindow = ui
                if (root == null) mainUnreadable = true
            } else if (root != null && others.size < MAX_WINDOWS - 1) {
                others += ui
            }
        }
        val windows = if (mainWindow != null && !mainUnreadable) others + mainWindow else others
        val image = (observe["image"] as? Map<*, *>)?.get("b64")?.let { it as? String }?.let { runCatching { java.util.Base64.getMimeDecoder().decode(it) }.getOrNull() }
        return RelayScreen(
            windows = windows,
            screen = screen,
            packageName = mainWindow?.packageName,
            locked = device?.get("locked") as? Boolean,
            screenOn = device?.get("screenOn") as? Boolean,
            truncated = truncated,
            mainUnreadable = mainUnreadable,
            imageJpeg = image,
            secureKeyboard = secureKeyboard,
        )
    }

    private const val SECURE_KEYBOARD = "安全键盘"
    private const val LABEL_MAX = 10

    private fun isSecretLabel(n: Map<*, *>): Boolean {
        val flags = (n["flags"] as? List<*>).orEmpty()
        if ("click" in flags || "editable" in flags) return false
        val text = (n["text"] as? String)?.trim() ?: return false
        return text.isNotEmpty() && text.length <= LABEL_MAX && SensitiveWords.secretHit(text, null, null) != null
    }

    private fun mentionsSecureKeyboard(n: Map<*, *>): Boolean =
        (n["text"] as? String)?.contains(SECURE_KEYBOARD) == true ||
            (n["children"] as? List<*>).orEmpty().any { (it as? Map<*, *>)?.let(::mentionsSecureKeyboard) == true }

    private fun stableId(bundle: String?, ability: String?, kind: WindowKind): Int =
        ("$bundle/$ability/$kind").hashCode() and 0x7fffffff

    private fun box(v: Any?): Box? {
        val l = (v as? List<*>)?.map { (it as? Number)?.toInt() ?: return null } ?: return null
        return if (l.size == 4) Box(l[0], l[1], l[2], l[3]) else null
    }

    private fun node(j: Map<*, *>, depth: Int, cut: BooleanArray, labelledSecret: Boolean = false): UiNode? {
        val bounds = box(j["bounds"]) ?: return null
        val flags = (j["flags"] as? List<*>).orEmpty().mapNotNull { it as? String }.toSet()
        val children = ArrayList<UiNode>()
        val kids = (j["children"] as? List<*>).orEmpty()
        if (depth < MAX_DEPTH) {
            // 标签在相邻节点的输入框：同一父节点下有个短的、不可点的「密码」「验证码」文字，兄弟输入框按密码框处理。
            val secretLabel = kids.any { k -> (k as? Map<*, *>)?.let(::isSecretLabel) == true }
            for (c in kids) (c as? Map<*, *>)?.let { node(it, depth + 1, cut, secretLabel) }?.let(children::add)
        } else if (kids.isNotEmpty()) cut[0] = true
        val scrollable = "scroll" in flags
        return UiNode(
            className = (j["cls"] as? String) ?: (j["raw"] as? String).orEmpty(),
            text = (j["text"] as? String)?.takeIf { it.isNotEmpty() },
            desc = (j["desc"] as? String)?.takeIf { it.isNotEmpty() },
            hint = (j["hint"] as? String)?.takeIf { it.isNotEmpty() },
            viewId = (j["viewId"] as? String)?.takeIf { it.isNotEmpty() },
            bounds = bounds,
            clickable = "click" in flags,
            longClickable = "long" in flags,
            editable = "editable" in flags,
            scrollable = scrollable,
            checkable = "check" in flags,
            checked = "checked" in flags,
            selected = "selected" in flags,
            focused = "focused" in flags,
            enabled = j["enabled"] != false,
            password = "password" in flags || (labelledSecret && "editable" in flags),
            // 中继只说「能滚」，不说方向：按纵向列表处理，横向靠子节点横排识别。
            scroll = if (scrollable) Scroll.UP or Scroll.DOWN or Scroll.FORWARD or Scroll.BACKWARD else 0,
            roleHint = (j["role"] as? String)?.lowercase()?.takeIf { it.isNotEmpty() },
            children = children,
        )
    }
}
