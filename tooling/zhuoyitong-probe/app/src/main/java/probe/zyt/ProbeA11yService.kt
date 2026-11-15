package probe.zyt

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 最小无障碍服务：记录窗口列表、前台根节点包名、事件来源包名，写 filesDir/a11y.json */
class ProbeA11yService : AccessibilityService() {
    private val evCount = HashMap<String, Int>()
    private val evSample = ArrayList<String>()
    @Volatile private var alive = true
    private var connectedAt = 0L

    override fun onServiceConnected() {
        connectedAt = System.currentTimeMillis()
        Thread {
            while (alive) {
                try { snapshot() } catch (e: Throwable) { File(filesDir, "a11y_err.txt").writeText(e.toString()) }
                Thread.sleep(1500)
            }
        }.start()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        synchronized(evCount) {
            val p = e.packageName?.toString() ?: "null"
            evCount[p] = (evCount[p] ?: 0) + 1
            if (evSample.size < 40) evSample += "${AccessibilityEvent.eventTypeToString(e.eventType)} ${p} ${e.className}"
        }
    }

    override fun onInterrupt() {}
    override fun onDestroy() { alive = false; super.onDestroy() }

    private fun snapshot() {
        val o = JSONObject().put("connectedAt", connectedAt).put("time", System.currentTimeMillis())
        val ws = JSONArray()
        val list = windows
        o.put("windowCount", list?.size ?: -1)
        list?.forEach { w ->
            val r = w.root
            ws.put(JSONObject().put("type", w.type).put("title", w.title?.toString()).put("layer", w.layer)
                .put("active", w.isActive).put("focused", w.isFocused)
                .put("rootPkg", r?.packageName?.toString()).put("rootChildren", r?.childCount ?: -1))
        }
        o.put("windows", ws)
        val root = rootInActiveWindow
        o.put("activeRootPkg", root?.packageName?.toString())
        val texts = JSONArray()
        if (root != null) collect(root, texts, 0)
        o.put("activeTexts", texts)
        synchronized(evCount) {
            o.put("eventCountByPkg", JSONObject(evCount as Map<*, *>))
            o.put("eventSample", JSONArray(evSample))
        }
        File(filesDir, "a11y.json").writeText(o.toString())
    }

    private fun collect(n: AccessibilityNodeInfo, out: JSONArray, depth: Int) {
        if (out.length() >= 12 || depth > 8) return
        val t = n.text?.toString()
        if (!t.isNullOrBlank()) out.put(t.take(30))
        for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it, out, depth + 1) }
    }
}
