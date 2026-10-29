package com.archiekuo.globalmarket.wear

import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.Wearable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class MainActivity : ComponentActivity() {

    private val st = MarketState()
    private var poller: Poller? = null
    private val listener = DataClient.OnDataChangedListener { events ->
        Sync.onEvents(this, st, events)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Sync.loadCached(this, st)
        setContent { AppRoot(st) }
    }

    override fun onResume() {
        super.onResume()
        val p = Poller(st)
        poller = p
        st.onListChanged = { p.kick() }
        p.start()
        try {
            Wearable.getDataClient(this).addListener(listener)
        } catch (t: Throwable) {
        }
        Sync.pull(this, st)
    }

    override fun onPause() {
        super.onPause()
        poller?.stop()
        poller = null
        st.onListChanged = null
        try {
            Wearable.getDataClient(this).removeListener(listener)
        } catch (t: Throwable) {
        }
    }
}

// ---------------------------------------------------------------------------
// 畫面（設計尺寸 480×480，圓形錶面；紅漲、綠跌）
// ---------------------------------------------------------------------------

private const val ROW0 = 470f
private const val ROW_H = 96f
private const val DIAG_H = 380f
private const val EMPTY_H = 150f

private const val UP = 0xFFF6465D.toInt()
private const val DN = 0xFF2EBD85.toInt()
private const val FLAT = 0xFF8A94A3.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()
private const val GRAY = 0xFF8A919C.toInt()
private const val GOLD = 0xFFD4AF37.toInt()

private fun mk(
    color: Int,
    size: Float,
    bold: Boolean = false,
    align: Paint.Align = Paint.Align.CENTER,
    skew: Float = 0f
): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    textSize = size
    textAlign = align
    textSkewX = skew
    typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
}

private class Paints {
    val label = mk(GRAY, 20f)
    val small = mk(GRAY, 15f)
    val gold = mk(GOLD, 16f, skew = -0.22f)
    val big = mk(WHITE, 64f, bold = true)
    val chg = mk(FLAT, 26f)
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF343A44.toInt()
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    val rowName = mk(WHITE, 26f, bold = true, align = Paint.Align.LEFT)
    val rowCode = mk(GRAY, 16f, align = Paint.Align.LEFT)
    val rowPrice = mk(WHITE, 30f, bold = true, align = Paint.Align.RIGHT)
    val rowPct = mk(FLAT, 20f, align = Paint.Align.RIGHT)
    val title = mk(WHITE, 26f, bold = true)
    val diag = mk(0xFFAEB4BF.toInt(), 17f)
    val diagBad = mk(0xFFFF8A8A.toInt(), 17f)
    val white = mk(WHITE, 28f, bold = true)
    val chevron = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5B616C.toInt()
        strokeWidth = 3f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
}

private fun colorOf(d: Double?): Int = when {
    d == null -> FLAT
    d > 0 -> UP
    d < 0 -> DN
    else -> FLAT
}

private fun fmt(v: Double?, d: Int): String =
    if (v == null) "--" else String.format(Locale.US, "%,." + d + "f", v)

private fun stockDec(p: Double?): Int = when {
    p == null -> 2
    p < 50 -> 2
    p < 500 -> 1
    else -> 0
}

private fun changeText(diff: Double?, pct: Double?, d: Int): String {
    if (diff == null || pct == null) return "--"
    val arrow = if (diff > 0) "▲" else if (diff < 0) "▼" else "－"
    val sign = if (pct > 0) "+" else ""
    return arrow + " " + fmt(abs(diff), d) + "　" + sign + String.format(Locale.US, "%.2f", pct) + "%"
}

private fun contentH(n: Int): Float = ROW0 + (if (n == 0) EMPTY_H else n * ROW_H) + 30f + DIAG_H
private fun maxScrollUnits(n: Int): Float = max(0f, contentH(n) - 480f)
private fun diagY(n: Int): Float = ROW0 + (if (n == 0) EMPTY_H else n * ROW_H) + 30f

private fun fitText(c: android.graphics.Canvas, text: String, x: Float, y: Float, p: Paint, maxW: Float) {
    val s = p.textSize
    val w = p.measureText(text)
    if (w > maxW) p.textSize = s * maxW / w
    c.drawText(text, x, y, p)
    p.textSize = s
}

private fun drawHeader(
    c: android.graphics.Canvas, p: Paints, st: MarketState, oy: Float, ver: String
) {
    if (oy < -480f) return
    val ix = st.index
    c.drawText("加權指數", 240f, 104f + oy, p.label)
    p.big.textSize = 64f
    p.big.color = WHITE
    fitText(c, fmt(ix?.price, 2), 240f, 170f + oy, p.big, 330f)
    p.chg.color = colorOf(ix?.diff)
    c.drawText(changeText(ix?.diff, ix?.pct, 2), 240f, 210f + oy, p.chg)

    c.drawLine(190f, 238f + oy, 290f, 238f + oy, p.line)

    c.drawText(st.futLabel, 240f, 272f + oy, p.label)
    val fx = st.fut
    p.big.textSize = 56f
    fitText(c, fmt(fx?.price, 0), 240f, 330f + oy, p.big, 300f)
    p.chg.color = colorOf(fx?.diff)
    c.drawText(changeText(fx?.diff, fx?.pct, 0), 240f, 368f + oy, p.chg)

    c.drawText("更新 " + st.updateText, 240f, 410f + oy, p.small)
    c.drawText("作者：ArchieKUO · v$ver", 240f, 438f + oy, p.gold)

    val path = Path().apply {
        moveTo(228f, 457f + oy); lineTo(240f, 466f + oy); lineTo(252f, 457f + oy)
    }
    c.drawPath(path, p.chevron)
}

private fun drawEmpty(c: android.graphics.Canvas, p: Paints, oy: Float) {
    val y = ROW0 + oy
    if (y > 520f || y < -200f) return
    c.drawText("尚未收到自選股", 240f, y + 36f, p.title)
    c.drawText("請在手機「全球行情」的", 240f, y + 72f, p.diag)
    c.drawText("「自選 1」加入台股，並打開 App", 240f, y + 98f, p.diag)
}

private fun drawRow(c: android.graphics.Canvas, p: Paints, item: WatchItem, q: Quote?, top: Float) {
    if (top > 480f || top + ROW_H < 0f) return
    val cy = top + ROW_H / 2f
    val dist = min(1f, abs(cy - 240f) / 260f)
    val a = (255f * (1f - dist.pow(2) * 0.8f)).toInt().coerceIn(40, 255)
    val col = colorOf(q?.diff)

    p.rowName.alpha = a
    fitText(c, q?.name ?: item.name, 92f, cy - 6f, p.rowName, 150f)
    p.rowName.alpha = 255

    p.rowCode.alpha = a
    c.drawText(item.id.substringBefore('.'), 92f, cy + 22f, p.rowCode)
    p.rowCode.alpha = 255

    p.rowPrice.color = WHITE
    p.rowPrice.alpha = a
    c.drawText(fmt(q?.price, stockDec(q?.price)), 388f, cy - 4f, p.rowPrice)

    p.rowPct.color = col
    p.rowPct.alpha = a
    val pc = q?.pct
    c.drawText(if (pc == null) "--" else (if (pc > 0) "+" else "") + String.format(Locale.US, "%.2f", pc) + "%", 388f, cy + 24f, p.rowPct)
    p.rowPct.alpha = 255

    val old = p.line.alpha
    p.line.alpha = (a * 0.5f).toInt()
    c.drawLine(92f, top + ROW_H - 1f, 388f, top + ROW_H - 1f, p.line)
    p.line.alpha = old
}

private fun clock(ms: Long): String {
    if (ms <= 0L) return "尚未收到"
    val f = SimpleDateFormat("M/d HH:mm", Locale.US)
    f.timeZone = TimeZone.getTimeZone("Asia/Taipei")
    return f.format(Date(ms))
}

private fun drawDiag(c: android.graphics.Canvas, p: Paints, st: MarketState, top: Float, ver: String) {
    if (top > 480f || top + DIAG_H < 0f) return
    c.drawText("資料狀態", 240f, top + 60f, p.title)
    val lines = listOf(
        "股票　" + st.stockStatus,
        "台指期　" + st.futStatus,
        "清單　" + clock(st.syncAt) + "（" + st.items.size + " 檔）",
        "來源　證交所／期交所"
    )
    var y = top + 112f
    for (l in lines) {
        val bad = l.contains("失敗")
        fitText(c, l, 240f, y, if (bad) p.diagBad else p.diag, 330f)
        y += 34f
    }
    c.drawText("作者：ArchieKUO · v$ver", 240f, top + 300f, p.gold)
}

private fun drawDetail(c: android.graphics.Canvas, p: Paints, q: Quote?, item: WatchItem?, ver: String) {
    c.drawRect(0f, 0f, 480f, 480f, Paint().apply { color = 0xFF000000.toInt() })
    val name = q?.name ?: item?.name ?: ""
    val code = (item?.id ?: q?.id ?: "").substringBefore('.')
    p.white.textSize = 30f
    fitText(c, name, 240f, 98f, p.white, 280f)
    c.drawText(code, 240f, 124f, p.label)

    val d = stockDec(q?.price)
    p.big.textSize = 64f
    p.big.color = WHITE
    fitText(c, fmt(q?.price, d), 240f, 190f, p.big, 320f)
    p.chg.color = colorOf(q?.diff)
    c.drawText(changeText(q?.diff, q?.pct, d), 240f, 228f, p.chg)

    fun cell(label: String, v: String, cx: Float, y: Float, color: Int) {
        c.drawText(label, cx, y, p.label)
        p.white.textSize = 28f
        p.white.color = color
        fitText(c, v, cx, y + 32f, p.white, 130f)
        p.white.color = WHITE
    }
    cell("開盤", fmt(q?.open, d), 168f, 268f, WHITE)
    cell("最高", fmt(q?.high, d), 312f, 268f, UP)
    cell("最低", fmt(q?.low, d), 168f, 330f, DN)
    cell("昨收", fmt(q?.prev, d), 312f, 330f, WHITE)
    c.drawText("成交量 " + (q?.volume?.let { String.format(Locale.US, "%,d", it) } ?: "--") + " 張", 240f, 392f, p.diag)
    c.drawText("作者：ArchieKUO · v$ver", 240f, 424f, p.gold)
}

@Composable
fun AppRoot(st: MarketState) {
    val ctx = LocalContext.current
    val paints = remember { Paints() }
    val ver = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "1.5"
    }
    var sz by remember { mutableStateOf(IntSize.Zero) }
    val scale = if (sz.width > 0) min(sz.width, sz.height) / 480f else 1f
    val n = st.items.size
    val maxPx = maxScrollUnits(n) * scale

    val scrollState = rememberScrollableState { delta ->
        val next = (st.scrollPx - delta).coerceIn(0f, maxPx)
        val used = st.scrollPx - next
        st.scrollPx = next
        used
    }

    BackHandler(enabled = st.detailId != null) { st.detailId = null }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { sz = it }
            .scrollable(
                state = scrollState,
                orientation = Orientation.Vertical,
                enabled = st.detailId == null
            )
            .pointerInput(Unit) {
                detectTapGestures { off ->
                    val s = min(sz.width, sz.height) / 480f
                    if (s <= 0f) return@detectTapGestures
                    if (st.detailId != null) {
                        st.detailId = null
                        return@detectTapGestures
                    }
                    val oy = (sz.height - 480f * s) / 2f
                    val contentY = (off.y - oy) / s + st.scrollPx / s
                    val count = st.items.size
                    if (count > 0 && contentY >= ROW0 && contentY < ROW0 + count * ROW_H) {
                        val i = ((contentY - ROW0) / ROW_H).toInt()
                        if (i in 0 until count) st.detailId = st.items[i].id
                    }
                }
            }
    ) {
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            val s = min(size.width, size.height) / 480f
            val ox = (size.width - 480f * s) / 2f
            val oy = (size.height - 480f * s) / 2f
            nc.save()
            nc.translate(ox, oy)
            nc.scale(s, s)
            val did = st.detailId
            if (did != null) {
                val item = st.items.firstOrNull { it.id == did }
                drawDetail(nc, paints, st.quotes[did], item, ver)
            } else {
                val scroll = st.scrollPx / s
                val items = st.items
                drawHeader(nc, paints, st, -scroll, ver)
                if (items.isEmpty()) {
                    drawEmpty(nc, paints, -scroll)
                } else {
                    val quotes = st.quotes
                    for ((i, item) in items.withIndex()) {
                        drawRow(nc, paints, item, quotes[item.id], ROW0 + i * ROW_H - scroll)
                    }
                }
                drawDiag(nc, paints, st, diagY(items.size) - scroll, ver)
            }
            nc.restore()
        }
    }
}
