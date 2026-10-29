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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
// 畫面（設計尺寸 480×480，圓形錶面；紅漲、綠跌；大字版）
// ---------------------------------------------------------------------------

private const val ROW0 = 470f
private const val ROW_H = 118f
private const val DIAG_H = 380f
private const val EMPTY_H = 190f

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
    val label = mk(GRAY, 28f)
    val cellLabel = mk(GRAY, 24f)
    val gold = mk(GOLD, 18f, skew = -0.22f)
    val big = mk(WHITE, 76f, bold = true)
    val chg = mk(FLAT, 34f)
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF343A44.toInt()
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    val rowName = mk(WHITE, 36f, bold = true, align = Paint.Align.LEFT)
    val rowPrice = mk(WHITE, 44f, bold = true, align = Paint.Align.LEFT)
    val rowPct = mk(FLAT, 32f, align = Paint.Align.RIGHT)
    val title = mk(WHITE, 34f, bold = true)
    val hint = mk(0xFFAEB4BF.toInt(), 24f)
    val vol = mk(0xFFAEB4BF.toInt(), 24f)
    val diagTitle = mk(WHITE, 26f, bold = true)
    val diag = mk(0xFFAEB4BF.toInt(), 17f)
    val diagBad = mk(0xFFFF8A8A.toInt(), 17f)
    val white = mk(WHITE, 40f, bold = true)
    val chartLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = FLAT
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val chartDash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6B727D.toInt()
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }
    val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
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

private fun arrowOf(d: Double): String = if (d > 0) "▲" else if (d < 0) "▼" else "－"

private fun changeText(diff: Double?, pct: Double?, d: Int): String {
    if (diff == null || pct == null) return "--"
    val sign = if (pct > 0) "+" else ""
    return arrowOf(diff) + " " + fmt(abs(diff), d) + "　" + sign + String.format(Locale.US, "%.2f", pct) + "%"
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
    c.drawText("加權指數", 240f, 96f + oy, p.label)
    p.big.textSize = 76f
    p.big.color = WHITE
    fitText(c, fmt(ix?.price, 2), 240f, 170f + oy, p.big, 390f)
    p.chg.color = colorOf(ix?.diff)
    fitText(c, changeText(ix?.diff, ix?.pct, 2), 240f, 214f + oy, p.chg, 400f)

    c.drawLine(180f, 244f + oy, 300f, 244f + oy, p.line)

    fitText(c, st.futLabel, 240f, 282f + oy, p.label, 380f)
    val fx = st.fut
    p.big.textSize = 68f
    fitText(c, fmt(fx?.price, 0), 240f, 352f + oy, p.big, 340f)
    p.chg.color = colorOf(fx?.diff)
    fitText(c, changeText(fx?.diff, fx?.pct, 0), 240f, 396f + oy, p.chg, 330f)

    c.drawText("作者：ArchieKUO · v$ver", 240f, 436f + oy, p.gold)

    val path = Path().apply {
        moveTo(228f, 454f + oy); lineTo(240f, 464f + oy); lineTo(252f, 454f + oy)
    }
    c.drawPath(path, p.chevron)
}

private fun drawEmpty(c: android.graphics.Canvas, p: Paints, oy: Float) {
    val y = ROW0 + oy
    if (y > 520f || y < -220f) return
    fitText(c, "尚未收到自選股", 240f, y + 50f, p.title, 380f)
    fitText(c, "請在手機「全球行情」的", 240f, y + 100f, p.hint, 380f)
    fitText(c, "「自選 1」加入台股", 240f, y + 134f, p.hint, 380f)
    fitText(c, "並打開 App", 240f, y + 168f, p.hint, 380f)
}

private fun drawRow(c: android.graphics.Canvas, p: Paints, item: WatchItem, q: Quote?, top: Float) {
    if (top > 480f || top + ROW_H < 0f) return
    val cy = top + ROW_H / 2f
    val dist = min(1f, abs(cy - 240f) / 260f)
    val a = (255f * (1f - dist.pow(2) * 0.8f)).toInt().coerceIn(40, 255)
    val col = colorOf(q?.diff)

    p.rowName.alpha = a
    fitText(c, q?.name ?: item.name, 84f, top + 51f, p.rowName, 312f)
    p.rowName.alpha = 255

    p.rowPrice.color = WHITE
    p.rowPrice.alpha = a
    c.drawText(fmt(q?.price, stockDec(q?.price)), 84f, top + 97f, p.rowPrice)
    p.rowPrice.alpha = 255

    p.rowPct.color = col
    p.rowPct.alpha = a
    val d = q?.diff
    val pc = q?.pct
    val txt = if (d == null || pc == null) "--"
    else arrowOf(d) + " " + (if (pc > 0) "+" else "") + String.format(Locale.US, "%.2f", pc) + "%"
    c.drawText(txt, 396f, top + 97f, p.rowPct)
    p.rowPct.alpha = 255

    val old = p.line.alpha
    p.line.alpha = (a * 0.5f).toInt()
    c.drawLine(84f, top + ROW_H - 1f, 396f, top + ROW_H - 1f, p.line)
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
    c.drawText("資料狀態", 240f, top + 60f, p.diagTitle)
    val lines = listOf(
        "股票　" + st.stockStatus,
        "台指期　" + st.futStatus,
        "走勢圖　" + st.chartStatus,
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

private fun drawChart(c: android.graphics.Canvas, p: Paints, q: Quote?, chart: Chart?, msg: String) {
    val x0 = 70f
    val y0 = 214f
    val w = 340f
    val h = 112f
    val pts = chart?.pts
    if (chart == null || pts == null || pts.size < 2) {
        c.drawText(if (msg.isNotEmpty()) msg else "走勢圖載入中…", 240f, y0 + h / 2f + 8f, p.hint)
        return
    }
    val live = q?.price
    val series = if (live != null) pts.dropLast(1) + live else pts
    val prev = q?.prev ?: chart.prev
    var lo = series.min()
    var hi = series.max()
    if (prev != null) {
        lo = min(lo, prev)
        hi = max(hi, prev)
    }
    if (hi - lo < 1e-9) {
        hi += 1.0
        lo -= 1.0
    }
    val pad = (hi - lo) * 0.08
    lo -= pad
    hi += pad
    val total = max(54, series.size)
    fun px(i: Int): Float = x0 + i.toFloat() / (total - 1) * w
    fun py(v: Double): Float = (y0 + (hi - v) / (hi - lo) * h).toFloat()

    if (prev != null) c.drawLine(x0, py(prev), x0 + w, py(prev), p.chartDash)
    val path = Path()
    for ((i, v) in series.withIndex()) {
        if (i == 0) path.moveTo(px(i), py(v)) else path.lineTo(px(i), py(v))
    }
    p.chartLine.color = colorOf(if (prev != null) series.last() - prev else null)
    c.drawPath(path, p.chartLine)

    val iMax = series.indexOf(series.max())
    val iMin = series.indexOf(series.min())
    p.dot.color = UP
    c.drawCircle(px(iMax), py(series[iMax]), 6f, p.dot)
    p.dot.color = DN
    c.drawCircle(px(iMin), py(series[iMin]), 6f, p.dot)
}

private fun drawDetail(
    c: android.graphics.Canvas, p: Paints, q: Quote?, item: WatchItem?,
    chart: Chart?, msg: String, page: Int, ver: String
) {
    c.drawColor(0xFF000000.toInt())
    val name = q?.name ?: item?.name ?: ""
    p.white.textSize = 38f
    p.white.color = WHITE
    fitText(c, name, 240f, 84f, p.white, 300f)
    val d = stockDec(q?.price)

    fun cell(label: String, v: String, cx: Float, y: Float, color: Int) {
        c.drawText(label, cx, y, p.cellLabel)
        p.white.textSize = 38f
        p.white.color = color
        fitText(c, v, cx, y + 40f, p.white, 140f)
        p.white.color = WHITE
    }

    fun row(label: String, v: String, y: Float, color: Int) {
        c.drawText(label, 240f, y, p.cellLabel)
        p.white.textSize = 52f
        p.white.color = color
        fitText(c, v, 240f, y + 50f, p.white, 300f)
        p.white.color = WHITE
    }

    if (page == 0) {
        p.big.textSize = 68f
        p.big.color = WHITE
        fitText(c, fmt(q?.price, d), 240f, 156f, p.big, 340f)
        p.chg.color = colorOf(q?.diff)
        fitText(c, changeText(q?.diff, q?.pct, d), 240f, 196f, p.chg, 340f)

        drawChart(c, p, q, chart, msg)

        val pts = chart?.pts
        val hi = q?.high ?: pts?.maxOrNull()
        val lo = q?.low ?: pts?.minOrNull()
        cell("最高", fmt(hi, d), 165f, 362f, UP)
        cell("最低", fmt(lo, d), 315f, 362f, DN)
    } else {
        row("開盤", fmt(q?.open, d), 140f, WHITE)
        row("昨收", fmt(q?.prev, d), 240f, WHITE)
        val v = q?.volume?.let { String.format(Locale.US, "%,d", it) } ?: "--"
        row("成交量（張）", v, 340f, WHITE)
    }
    c.drawText("作者：ArchieKUO · v$ver", 240f, 446f, p.gold)
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

    BackHandler(enabled = st.detailId != null) {
        st.detailId = null
        st.detailPage = 0
    }

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
            .then(
                if (st.detailId != null) {
                    Modifier.pointerInput(Unit) {
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = {
                                val id = st.detailId
                                if (id != null) {
                                    val i = st.items.indexOfFirst { it.id == id }
                                    val j = if (total < -60f) i + 1 else if (total > 60f) i - 1 else i
                                    if (i >= 0 && j != i && j in st.items.indices) {
                                        st.chartMsg = ""
                                        st.detailId = st.items[j].id
                                    }
                                }
                            },
                            onDragCancel = { total = 0f },
                            onVerticalDrag = { _, dy -> total += dy }
                        )
                    }
                } else Modifier
            )
            .pointerInput(Unit) {
                detectTapGestures { off ->
                    val s = min(sz.width, sz.height) / 480f
                    if (s <= 0f) return@detectTapGestures
                    if (st.detailId != null) {
                        st.detailPage = 1 - st.detailPage
                        return@detectTapGestures
                    }
                    val oy = (sz.height - 480f * s) / 2f
                    val contentY = (off.y - oy) / s + st.scrollPx / s
                    val count = st.items.size
                    if (count > 0 && contentY >= ROW0 && contentY < ROW0 + count * ROW_H) {
                        val i = ((contentY - ROW0) / ROW_H).toInt()
                        if (i in 0 until count) {
                            st.detailPage = 0
                            st.chartMsg = ""
                            st.detailId = st.items[i].id
                        }
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
                drawDetail(nc, paints, st.quotes[did], item, st.charts[did], st.chartMsg, st.detailPage, ver)
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
