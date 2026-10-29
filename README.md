# 全球行情

全球股市指數、匯率（含換算器）、虛擬貨幣、商品、債券殖利率的即時行情 App。
網頁版與 Android 版共用同一份畫面：`web/index.html`。

- 推到 `main` 後，GitHub Actions 會自動編譯 APK，放到 `apk` 分支。
- 手機下載：https://github.com/nmymdf/global-market/raw/apk/GlobalMarket-v1.5.3.apk
- 手錶版（Galaxy Watch 台股行情）：https://github.com/nmymdf/global-market/raw/apk/GlobalMarket-Watch-v1.5.3.apk
- 網頁版：https://nmymdf.github.io/global-market/

資料來源：Binance（虛擬貨幣，即時推送）、Yahoo Finance（股市約延遲 15 分鐘，匯率約每分鐘）、alternative.me（恐懼貪婪指數）。

*作者：ArchieKUO*　v1.5.3

## 自動測試

`python3 test/regress.py`：用手機尺寸（360、412 寬）加觸控模擬，配合 `test/mock.js` 假資料，把所有功能按過一遍（約 100 項）。每次出新版前都會先跑。

## 手錶版（Wear OS，`android/wear/`）

- 看加權指數、台指期近月（日盤＋夜盤）和「自選 1」裡的台股（最多 20 檔），紅漲綠跌。
- 手機版全球行情改「自選 1」時，會自動把台股清單同步到手錶（需同套件名稱、同簽名）。
- 手錶在手機附近時，網路由手機藍牙代連。
- 向上推捲動，點一檔看當天走勢圖與最高最低（再點一下看開盤、昨收、成交量），在明細畫面上下滑換檔，最底下是「資料狀態」頁。
