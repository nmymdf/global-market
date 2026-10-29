package com.archiekuo.globalmarket.wear

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject

/** 從手機版全球行情接收「自選 1」的台股清單，並在手錶上留一份。 */
object Sync {
    private const val PREFS = "wl"
    private const val KEY = "json"

    fun loadCached(ctx: Context, st: MarketState) {
        try {
            val json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
            st.items = parse(json)
        } catch (t: Throwable) {
        }
    }

    /** 啟動時主動向 Data Layer 要一次目前的清單。 */
    fun pull(ctx: Context, st: MarketState) {
        try {
            Wearable.getDataClient(ctx).getDataItems(Uri.parse("wear://*/watchlist"))
                .addOnSuccessListener { buf ->
                    try {
                        for (item in buf) {
                            if (item.uri.path == "/watchlist") {
                                val s = DataMapItem.fromDataItem(item).dataMap.getString("json")
                                if (s != null) apply(ctx, st, s)
                            }
                        }
                    } catch (t: Throwable) {
                    } finally {
                        buf.release()
                    }
                }
        } catch (t: Throwable) {
        }
    }

    fun onEvents(ctx: Context, st: MarketState, events: DataEventBuffer) {
        try {
            for (e in events) {
                if (e.type == DataEvent.TYPE_CHANGED && e.dataItem.uri.path == "/watchlist") {
                    val s = DataMapItem.fromDataItem(e.dataItem).dataMap.getString("json")
                    if (s != null) apply(ctx, st, s)
                }
            }
        } catch (t: Throwable) {
        }
    }

    private fun apply(ctx: Context, st: MarketState, json: String) {
        val list = parse(json)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json).apply()
        st.items = list
        st.syncAt = System.currentTimeMillis()
        st.onListChanged?.invoke()
    }

    private fun parse(json: String): List<WatchItem> {
        val out = ArrayList<WatchItem>()
        try {
            val arr = JSONObject(json).getJSONArray("items")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id")
                if (id.isNotEmpty() && out.size < 20) out.add(WatchItem(id, o.optString("n", id)))
            }
        } catch (t: Throwable) {
        }
        return out
    }
}
