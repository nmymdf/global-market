package com.archiekuo.globalmarket.wear

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Calendar
import java.util.TimeZone

data class WatchItem(val id: String, val name: String)

data class Quote(
    val id: String,
    val name: String,
    val code: String,
    val price: Double?,
    val prev: Double?,
    val open: Double?,
    val high: Double?,
    val low: Double?,
    val volume: Long?,
    val time: String,
    val at: Long
) {
    val diff: Double?
        get() {
            val p = price ?: return null
            val v = prev ?: return null
            return p - v
        }
    val pct: Double?
        get() {
            val d = diff ?: return null
            val v = prev ?: return null
            if (v == 0.0) return null
            return d / v * 100.0
        }
}

class MarketState {
    var items by mutableStateOf<List<WatchItem>>(emptyList())
    var quotes by mutableStateOf<Map<String, Quote>>(emptyMap())
    var index by mutableStateOf<Quote?>(null)
    var fut by mutableStateOf<Quote?>(null)
    var futLabel by mutableStateOf("台指期")
    var updateText by mutableStateOf("--")
    var syncAt by mutableLongStateOf(0L)
    var stockStatus by mutableStateOf("尚未更新")
    var futStatus by mutableStateOf("尚未更新")
    var detailId by mutableStateOf<String?>(null)
    var scrollPx by mutableFloatStateOf(0f)
    var onListChanged: (() -> Unit)? = null
}

/** 台灣時間的開盤判斷（不含國定假日）。 */
object Tw {
    private fun now(): Calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Taipei"))

    private fun dm(): Pair<Int, Int> {
        val c = now()
        return Pair(c.get(Calendar.DAY_OF_WEEK), c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE))
    }

    // 週日=1 週一=2 … 週六=7
    fun stockOpen(): Boolean {
        val (d, m) = dm()
        return d in 2..6 && m in 535..820 // 08:55–13:40
    }

    fun futDayOpen(): Boolean {
        val (d, m) = dm()
        return d in 2..6 && m in 525..825 // 08:45–13:45
    }

    fun futNightOpen(): Boolean {
        val (d, m) = dm()
        return (d in 2..6 && m >= 900) || (d in 3..7 && m <= 300) // 15:00–隔天 05:00
    }

    fun anyOpen(): Boolean = stockOpen() || futDayOpen() || futNightOpen()

    /** 台指期該查日盤還是夜盤的資料（收盤後到下一個時段前，顯示最後一個時段）。 */
    fun futUseNight(): Boolean {
        val (d, m) = dm()
        return (d in 2..6 && m >= 900) || (d in 3..7 && m < 525)
    }

    fun year(): Int = now().get(Calendar.YEAR)

    /** 指定年月第三個週三 13:30 的時間（台指期結算）。 */
    fun settleMillis(year: Int, month: Int): Long {
        val c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Taipei"))
        c.clear()
        c.set(year, month - 1, 1, 13, 30, 0)
        var wed = 0
        while (true) {
            if (c.get(Calendar.DAY_OF_WEEK) == Calendar.WEDNESDAY) {
                wed++
                if (wed == 3) break
            }
            c.add(Calendar.DAY_OF_MONTH, 1)
        }
        return c.timeInMillis
    }
}
