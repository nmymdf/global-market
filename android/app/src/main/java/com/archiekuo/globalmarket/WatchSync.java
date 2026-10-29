package com.archiekuo.globalmarket;

import android.content.Context;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

/** 把自選 1 的台股清單（JSON）送到配對的手錶；沒有手錶或沒有 Google Play 服務時什麼都不做。 */
final class WatchSync {
    private WatchSync() {}

    static void send(Context ctx, String json) {
        try {
            PutDataMapRequest r = PutDataMapRequest.create("/watchlist");
            r.getDataMap().putString("json", json);
            r.getDataMap().putLong("ts", System.currentTimeMillis());
            PutDataRequest req = r.asPutDataRequest().setUrgent();
            Wearable.getDataClient(ctx).putDataItem(req);
        } catch (Throwable ignored) {
        }
    }
}
