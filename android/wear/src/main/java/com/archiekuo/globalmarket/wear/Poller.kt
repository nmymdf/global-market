package com.archiekuo.globalmarket.wear

import org.json.JSONObject
import java.net.CookieHandler
import java.net.CookieManager
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 在背景定時抓行情：
 * - 大盤＋自選股：證交所／櫃買即時資訊（與手機版全球行情相同的來源）
 * - 台指期近月：期交所行情（日盤、夜盤）
 * 手錶在手機附近時，網路由手機藍牙代連。
 */
class Poller(private val st: MarketState) {
    @Volatile private var running = false
    @Volatile private var kicked = false
    private var thread: Thread? = null
    private var lastHttp = ""
    private var warmed = false

    private val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    fun start() {
        if (running) return
        if (CookieHandler.getDefault() == null) CookieHandler.setDefault(CookieManager())
        running = true
        thread = Thread { loop() }.also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    fun kick() {
        kicked = true
    }

    private fun loop() {
        while (running) {
            try {
                fetchStocks()
            } catch (e: InterruptedException) {
                return
            } catch (t: Throwable) {
                st.stockStatus = "失敗：" + (t.message ?: t.javaClass.simpleName)
            }
            try {
                fetchFutures()
            } catch (e: InterruptedException) {
                return
            } catch (t: Throwable) {
                st.futStatus = "失敗：" + (t.message ?: t.javaClass.simpleName)
            }
            try {
                fetchChart()
            } catch (e: InterruptedException) {
                return
            } catch (t: Throwable) {
                st.chartStatus = "失敗：" + (t.message ?: t.javaClass.simpleName)
            }
            val seen = st.detailId
            val delay = if (Tw.anyOpen()) 5000L else 300000L
            var waited = 0L
            try {
                while (running && waited < delay && !kicked && st.detailId == seen) {
                    Thread.sleep(500)
                    waited += 500
                }
            } catch (e: InterruptedException) {
                return
            }
            kicked = false
        }
    }

    // ---------------- HTTP ----------------

    private fun http(
        url: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap()
    ): String? {
        var c: HttpURLConnection? = null
        try {
            c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.requestMethod = method
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "application/json, text/plain, */*")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            if (code != 200) {
                lastHttp = "HTTP $code"
                return null
            }
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            lastHttp = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
            return null
        } finally {
            c?.disconnect()
        }
    }

    // ---------------- 股票與大盤 ----------------

    private fun twKey(id: String): String? {
        if (id == "^TWII") return "tse_t00.tw"
        val m = Regex("^([0-9A-Z]+)\\.(TW|TWO)$").find(id) ?: return null
        return (if (m.groupValues[2] == "TW") "tse_" else "otc_") + m.groupValues[1].lowercase(Locale.US) + ".tw"
    }

    private fun nn(s: String?): Double? {
        val v = s?.replace(",", "")?.toDoubleOrNull() ?: return null
        return if (v > 0) v else null
    }

    private fun firstLevel(s: String?): Double? {
        if (s == null) return null
        for (part in s.split("_")) {
            val v = nn(part)
            if (v != null) return v
        }
        return null
    }

    private fun fetchStocks() {
        val items = st.items
        val map = HashMap<String, String>() // 證交所代號 -> 我們的 id
        map["tse_t00.tw"] = "^TWII"
        for (it in items) {
            val k = twKey(it.id) ?: continue
            map[k] = it.id
        }
        val keys = map.keys.toList()
        val url = "https://mis.twse.com.tw/stock/api/getStockInfo.jsp?ex_ch=" +
            URLEncoder.encode(keys.joinToString("|"), "UTF-8") +
            "&json=1&delay=0&_=" + System.currentTimeMillis()
        val hdr = mapOf("Referer" to "https://mis.twse.com.tw/stock/index.jsp")

        var body = http(url, headers = hdr)
        var arr = body?.let { parseArray(it) }
        if (arr == null && !warmed) {
            // 第一次需要先開首頁取得 cookie
            warmed = true
            http("https://mis.twse.com.tw/stock/index.jsp", headers = hdr)
            body = http(url, headers = hdr)
            arr = body?.let { parseArray(it) }
        }
        if (arr == null) {
            st.stockStatus = "失敗：" + (if (body == null) lastHttp else "回應格式不符")
            return
        }

        val old = st.quotes
        val out = HashMap<String, Quote>(old)
        var got = 0
        val names = HashMap<String, String>()
        for (it in items) names[it.id] = it.name
        var idx: Quote? = st.index

        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val key = (m.optString("ex") + "_" + m.optString("c") + ".tw").lowercase(Locale.US)
            val id = map[key] ?: continue
            val prevQ = if (id == "^TWII") idx else old[id]
            val price = nn(m.optString("z")) ?: firstLevel(m.optString("b")) ?: firstLevel(m.optString("a")) ?: prevQ?.price
            val nm = if (id == "^TWII") "加權指數" else m.optString("n").ifEmpty { names[id] ?: m.optString("c") }
            val q = Quote(
                id = id,
                name = nm,
                code = m.optString("c"),
                price = price,
                prev = nn(m.optString("y")) ?: prevQ?.prev,
                open = nn(m.optString("o")) ?: prevQ?.open,
                high = nn(m.optString("h")) ?: prevQ?.high,
                low = nn(m.optString("l")) ?: prevQ?.low,
                volume = m.optString("v").toLongOrNull() ?: prevQ?.volume,
                time = m.optString("t"),
                at = System.currentTimeMillis()
            )
            if (id == "^TWII") idx = q else out[id] = q
            got++
        }
        st.quotes = out
        st.index = idx
        if (idx != null && idx.time.isNotEmpty()) st.updateText = idx.time
        st.stockStatus = "OK $got/${keys.size}　" + hms()
    }

    private fun parseArray(body: String): org.json.JSONArray? {
        return try {
            val j = JSONObject(body)
            if (j.has("msgArray")) j.getJSONArray("msgArray") else null
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- 當天走勢圖（只在打開某一檔時抓）----------------

    private var chartId: String? = null
    private var chartAt = 0L

    private fun fetchChart() {
        val id = st.detailId
        if (id == null) {
            chartId = null
            return
        }
        val now = System.currentTimeMillis()
        if (id == chartId && now - chartAt < 55000L) return
        chartId = id
        chartAt = now
        val url = "https://query1.finance.yahoo.com/v8/chart/" + URLEncoder.encode(id, "UTF-8") +
            "?range=1d&interval=5m&includePrePost=false"
        val body = http(url)
        if (body == null) {
            st.chartMsg = "走勢圖暫時無法取得"
            st.chartStatus = "失敗：$lastHttp"
            return
        }
        try {
            val r = JSONObject(body).getJSONObject("chart").getJSONArray("result").getJSONObject(0)
            val meta = r.optJSONObject("meta")
            val prev = meta?.optDouble("chartPreviousClose", Double.NaN)?.takeIf { !it.isNaN() && it > 0 }
            val closes = r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0).getJSONArray("close")
            val list = ArrayList<Double>()
            for (i in 0 until closes.length()) {
                if (!closes.isNull(i)) list.add(closes.getDouble(i))
            }
            st.charts = st.charts + (id to Chart(list, prev, now))
            if (list.size < 2) {
                st.chartMsg = "走勢圖暫時無法取得"
                st.chartStatus = "資料點不足（" + list.size + "）"
            } else {
                st.chartMsg = ""
                st.chartStatus = "OK " + list.size + " 點　" + hms()
            }
        } catch (e: Exception) {
            st.chartMsg = "走勢圖暫時無法取得"
            st.chartStatus = "失敗：解析錯誤（" + body.take(50) + "）"
        }
    }

    // ---------------- 台指期（近月，日盤＋夜盤）----------------

    private fun fetchFutures() {
        val night = Tw.futUseNight()
        val reqBody = "{\"MarketType\":\"" + (if (night) "1" else "0") +
            "\",\"SymbolType\":\"F\",\"KindID\":\"1\",\"CID\":\"TXF\",\"ExpireMonth\":\"\"," +
            "\"RowSize\":\"全部\",\"PageNo\":\"\",\"SortColumn\":\"\",\"AscDesc\":\"A\"}"
        val resp = http(
            "https://mis.taifex.com.tw/futures/api/getQuoteList", "POST", reqBody,
            mapOf(
                "Content-Type" to "application/json;charset=UTF-8",
                "Referer" to "https://mis.taifex.com.tw/futures/",
                "Origin" to "https://mis.taifex.com.tw"
            )
        )
        if (resp == null) {
            st.futStatus = "失敗：$lastHttp"
            return
        }
        try {
            val list = JSONObject(resp).getJSONObject("RtData").getJSONArray("QuoteList")
            val re = Regex("^TXF([A-L])(\\d)-[A-Z]$")
            val nowMs = System.currentTimeMillis()
            val curYear = Tw.year()
            var best: JSONObject? = null
            var bestKey = Long.MAX_VALUE
            var bestMonth = 0
            for (i in 0 until list.length()) {
                val o = list.getJSONObject(i)
                val mm = re.find(o.optString("SymbolID")) ?: continue
                val month = mm.groupValues[1][0] - 'A' + 1
                var year = (curYear / 10) * 10 + mm.groupValues[2].toInt()
                if (year < curYear - 1) year += 10
                val settle = Tw.settleMillis(year, month)
                if (settle <= nowMs) continue // 已結算，不是近月
                if (settle < bestKey) {
                    bestKey = settle
                    best = o
                    bestMonth = month
                }
            }
            val o = best
            if (o == null) {
                st.futStatus = "失敗：找不到近月合約（" + resp.take(60) + "）"
                return
            }
            val q = Quote(
                id = "TXF",
                name = "台指期",
                code = o.optString("SymbolID"),
                price = nn(o.optString("CLastPrice")),
                prev = nn(o.optString("CRefPrice")),
                open = nn(o.optString("COpenPrice")),
                high = nn(o.optString("CHighPrice")),
                low = nn(o.optString("CLowPrice")),
                volume = o.optString("CTotalVolume").replace(",", "").toLongOrNull(),
                time = fmtTime(o.optString("CTime")),
                at = System.currentTimeMillis()
            )
            st.fut = q
            st.futLabel = "台指期 " + bestMonth + "月 · " + (if (night) "夜盤" else "日盤")
            st.futStatus = (if (q.price == null) "無成交價　" else "OK　") + hms()
        } catch (e: Exception) {
            st.futStatus = "失敗：解析錯誤（" + resp.take(60) + "）"
        }
    }

    private fun fmtTime(t: String): String {
        val d = t.filter { it.isDigit() }
        return if (d.length >= 6) d.substring(0, 2) + ":" + d.substring(2, 4) + ":" + d.substring(4, 6) else t
    }

    private fun hms(): String {
        val f = SimpleDateFormat("HH:mm:ss", Locale.US)
        f.timeZone = TimeZone.getTimeZone("Asia/Taipei")
        return f.format(Date())
    }
}
