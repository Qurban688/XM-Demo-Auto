package az.qurban.xmdemoaccess;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class XmAccessibilityService extends AccessibilityService {
    private static final String XM_PACKAGE = "com.xm.webapp";
    private static final long COOLDOWN_MS = 90L * 1000L;
    private static final int MAX_TRADES = 5;
    private static final long NAV_GAP_MS = 1200L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ScheduledExecutorService scheduler;
    private long lastNavAction = 0L;

    private final Runnable uiLoop = new Runnable() {
        @Override public void run() {
            try {
                if (isRunning()) inspectAndAct();
            } finally {
                handler.postDelayed(this, 1200L);
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        info.flags |= AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        setServiceInfo(info);

        handler.removeCallbacks(uiLoop);
        handler.postDelayed(uiLoop, 700L);

        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::analyzeMarketSafe, 1, 20, TimeUnit.SECONDS);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        if (!XM_PACKAGE.contentEquals(event.getPackageName())) return;
        if (!isRunning()) return;
        handler.postDelayed(this::inspectAndAct, 250L);
    }

    private void analyzeMarketSafe() {
        if (!isRunning()) return;
        try {
            Analysis a = fetchAndAnalyze();
            prefs().edit()
                    .putString("signal", a.signal)
                    .putString("reason", a.reason)
                    .putString("price", String.format(Locale.US, "%.2f", a.price))
                    .putString("sl", String.format(Locale.US, "%.2f", a.sl))
                    .putString("tp", String.format(Locale.US, "%.2f", a.tp))
                    .putString("last_analysis", new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()))
                    .apply();
        } catch (Exception ex) {
            prefs().edit()
                    .putString("signal", "WAIT")
                    .putString("reason", "Qızıl analiz xətası: " + safe(ex.getMessage()))
                    .apply();
        }
    }

    private void inspectAndAct() {
        if (!isRunning()) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null || !XM_PACKAGE.contentEquals(root.getPackageName())) {
            prefs().edit().putString("nav_state", "XM aktiv ekran gözlənilir").apply();
            return;
        }

        List<String> texts = new ArrayList<>();
        collectText(root, texts, 0);
        String joined = String.join(" | ", texts).toLowerCase(Locale.ROOT);
        String sample = joined.length() > 1200 ? joined.substring(0, 1200) : joined;

        boolean demoCurrent = containsAny(joined, "demo", "practice", "virtual");
        boolean realDetected = containsAny(joined, "real account", "live account", "real hesab", "canlı hesab");
        boolean goldText = containsAny(joined, "xauusd", "xau/usd", "gold", "qızıl");
        boolean buyFound = hasContains(root, "buy") || hasExact(root, "al");
        boolean sellFound = hasContains(root, "sell") || hasExact(root, "sat");
        boolean searchContext = containsAny(joined, "search", "axtar", "find") || findEditable(root) != null;

        prefs().edit()
                .putBoolean("demo_detected", demoCurrent)
                .putBoolean("demo_seen", prefs().getBoolean("demo_seen", false) || demoCurrent)
                .putBoolean("gold_detected", goldText)
                .putBoolean("buy_found", buyFound)
                .putBoolean("sell_found", sellFound)
                .putString("screen_sample", sample)
                .apply();

        if (realDetected) {
            prefs().edit()
                    .putBoolean(MainActivity.KEY_RUNNING, false)
                    .putString("nav_state", "REAL/LIVE hesab aşkarlandı — dayandırıldı")
                    .putString("reason", "Bu APK yalnız DEMO üçündür")
                    .apply();
            return;
        }

        if (!prefs().getBoolean("demo_user_confirmed", false)) {
            prefs().edit().putString("nav_state", "DEMO təsdiqi yoxdur").apply();
            return;
        }

        // Pending order confirmation stage.
        String pending = prefs().getString("pending_signal", "");
        long pendingSince = prefs().getLong("pending_since", 0L);
        if (!pending.isEmpty()) {
            if (confirmPending(root, joined, pending, pendingSince)) return;
        }

        boolean goldSelected = prefs().getBoolean("gold_selected_by_bot", false);

        // If search results are visible, choose the actual GOLD row instead of mistaking the search text for active symbol.
        if (goldText && searchContext && !goldSelected) {
            if (clickContainsBelow(root, "xauusd", 0.16f) || clickContainsBelow(root, "xau/usd", 0.16f) || clickContainsBelow(root, "gold", 0.16f)) {
                prefs().edit().putBoolean("gold_selected_by_bot", true).putString("nav_state", "GOLD nəticəsi seçildi").apply();
                markNavAction();
                return;
            }
        }

        // Active GOLD screen: either BUY/SELL are present, or bot already selected GOLD.
        if ((goldText && (buyFound || sellFound)) || goldSelected) {
            prefs().edit().putBoolean("gold_selected_by_bot", true).apply();
            if (handleGoldTradeScreen(root, joined, buyFound, sellFound)) return;
        }

        // Text-based navigation first.
        if (!tooSoon()) {
            if (clickContains(root, "markets") || clickExact(root, "market") || clickContains(root, "quotes")) {
                prefs().edit().putString("nav_state", "Markets açıldı").apply();
                markNavAction();
                return;
            }
            if (clickContains(root, "trade") || clickContains(root, "ticarət")) {
                prefs().edit().putString("nav_state", "Trade bölməsi açıldı").apply();
                markNavAction();
                return;
            }
            if (clickContains(root, "search") || clickContains(root, "axtar") || clickContains(root, "find")) {
                prefs().edit().putString("nav_state", "Search açıldı").apply();
                markNavAction();
                return;
            }
        }

        AccessibilityNodeInfo edit = findEditable(root);
        if (edit != null && !tooSoon()) {
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "GOLD");
            if (edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                prefs().edit().putString("nav_state", "Search sahəsinə GOLD yazıldı").apply();
                markNavAction();
                return;
            }
        }

        if (goldText && !goldSelected && !tooSoon()) {
            if (clickContainsBelow(root, "xauusd", 0.12f) || clickContainsBelow(root, "gold", 0.12f)) {
                prefs().edit().putBoolean("gold_selected_by_bot", true).putString("nav_state", "GOLD seçildi").apply();
                markNavAction();
                return;
            }
        }

        // Gesture fallback for the new XM UI / WebView where labels may not be exposed.
        if (!tooSoon()) runGestureFallback(joined);
    }

    private boolean handleGoldTradeScreen(AccessibilityNodeInfo root, String joined, boolean buyFound, boolean sellFound) {
        SharedPreferences p = prefs();
        int count = p.getInt("trade_count", 0);
        if (count >= MAX_TRADES) {
            p.edit().putString("nav_state", "5/5 demo trade tamamlandı").apply();
            return true;
        }

        if (!buyFound && !sellFound) {
            if (!tooSoon() && (clickContains(root, "trade") || clickContains(root, "new order") || clickContains(root, "order") || clickContains(root, "ticarət"))) {
                p.edit().putString("nav_state", "GOLD order ekranı açılır").apply();
                markNavAction();
                return true;
            }
            if (!tooSoon()) {
                // On current XM versions the instrument page commonly has a large trade action near the lower area.
                tapPct(0.50f, 0.84f);
                p.edit().putString("nav_state", "GOLD trade düyməsinə gesture cəhdi").apply();
                markNavAction();
                return true;
            }
            return false;
        }

        long lastTrade = p.getLong("last_trade_time", 0L);
        long now = System.currentTimeMillis();
        if (now - lastTrade < COOLDOWN_MS) {
            long left = Math.max(0L, (COOLDOWN_MS - (now - lastTrade)) / 1000L);
            p.edit().putString("nav_state", "Növbəti trade üçün " + left + " san").apply();
            return true;
        }

        String signal = p.getString("signal", "WAIT");
        if (!"BUY".equals(signal) && !"SELL".equals(signal)) {
            p.edit().putString("nav_state", "GOLD hazırdır, analiz WAIT verir").apply();
            return true;
        }

        boolean clicked;
        if ("BUY".equals(signal)) clicked = clickBestTradeButton(root, true);
        else clicked = clickBestTradeButton(root, false);

        if (!clicked) {
            ClickPair pair = findLargeTradePair(root);
            if (pair != null) {
                AccessibilityNodeInfo target = "BUY".equals(signal) ? pair.right : pair.left;
                clicked = clickNode(target) || tapNodeCenter(target);
            }
        }

        if (clicked) {
            p.edit()
                    .putString("pending_signal", signal)
                    .putLong("pending_since", now)
                    .putString("nav_state", "GOLD " + signal + " basıldı, order təsdiqi yoxlanır")
                    .apply();
            markNavAction();
            return true;
        }

        p.edit().putString("nav_state", signal + " düyməsi görünür, klik alınmadı").apply();
        return true;
    }

    private boolean confirmPending(AccessibilityNodeInfo root, String joined, String pending, long pendingSince) {
        SharedPreferences p = prefs();
        long age = System.currentTimeMillis() - pendingSince;

        if (age < 500L) return true;

        String[] confirmations = new String[]{"place order", "confirm", "submit", "təsdiq", "sifariş ver", "open position", "execute"};
        for (String c : confirmations) {
            if (clickContains(root, c)) {
                finalizeTradeAttempt(pending, "order təsdiqi klikləndi");
                return true;
            }
        }

        boolean orderTicket = containsAny(joined, "volume", "lot", "lots", "stop loss", "take profit", "market execution", "order type", "əmr", "həcm");
        if (orderTicket && age > 700L) {
            boolean clicked = "BUY".equals(pending) ? clickBestTradeButton(root, true) : clickBestTradeButton(root, false);
            if (clicked) {
                finalizeTradeAttempt(pending, "order ticketdə " + pending + " təsdiqləndi");
                return true;
            }
        }

        // One-click trading may execute immediately and keep the same chart visible.
        if (age > 2200L) {
            finalizeTradeAttempt(pending, "one-click/ilk klik nəticəsi qəbul edildi");
            return true;
        }
        return true;
    }

    private void finalizeTradeAttempt(String signal, String note) {
        SharedPreferences p = prefs();
        int newCount = Math.min(MAX_TRADES, p.getInt("trade_count", 0) + 1);
        p.edit()
                .putInt("trade_count", newCount)
                .putLong("last_trade_time", System.currentTimeMillis())
                .putString("pending_signal", "")
                .putLong("pending_since", 0L)
                .putString("nav_state", "GOLD " + signal + " cəhdi tamamlandı — " + newCount + "/5")
                .putString("reason", p.getString("reason", "") + " • " + note)
                .apply();
    }

    private void runGestureFallback(String joined) {
        SharedPreferences p = prefs();
        int stage = p.getInt("gesture_stage", 0);

        // Try all bottom-navigation slots because the August 2026 XM update changed the bottom layout.
        if (stage < 5) {
            float x = 0.10f + 0.20f * stage;
            tapPct(x, 0.92f);
            p.edit().putInt("gesture_stage", stage + 1).putString("nav_state", "Alt menyu " + (stage + 1) + "/5 yoxlanır").apply();
            markNavAction();
            return;
        }

        if (stage == 5) {
            tapPct(0.90f, 0.085f);
            p.edit().putInt("gesture_stage", 6).putString("nav_state", "Yuxarı Search ikonuna gesture").apply();
            markNavAction();
            return;
        }

        if (stage == 6) {
            tapPct(0.22f, 0.09f);
            p.edit().putInt("gesture_stage", 7).putString("nav_state", "Trade ekranında alət seçicisinə gesture").apply();
            markNavAction();
            return;
        }

        if (stage == 7) {
            tapPct(0.90f, 0.12f);
            p.edit().putInt("gesture_stage", 0).putString("nav_state", "Search fallback yenidən yoxlanır").apply();
            markNavAction();
        }
    }

    private boolean clickBestTradeButton(AccessibilityNodeInfo root, boolean buy) {
        String target = buy ? "buy" : "sell";
        AccessibilityNodeInfo n = findLowestContains(root, target);
        if (n == null) n = findExact(root, buy ? "al" : "sat");
        return clickNode(n) || tapNodeCenter(n);
    }

    private ClickPair findLargeTradePair(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectClickable(root, nodes, 0);
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        AccessibilityNodeInfo left = null, right = null;
        int bestY = -1;

        for (AccessibilityNodeInfo a : nodes) {
            Rect ra = new Rect(); a.getBoundsInScreen(ra);
            if (ra.width() < w * 0.20f || ra.height() < h * 0.045f || ra.centerY() < h * 0.45f) continue;
            for (AccessibilityNodeInfo b : nodes) {
                if (a == b) continue;
                Rect rb = new Rect(); b.getBoundsInScreen(rb);
                if (rb.width() < w * 0.20f || rb.height() < h * 0.045f) continue;
                if (Math.abs(ra.centerY() - rb.centerY()) > h * 0.06f) continue;
                AccessibilityNodeInfo l = ra.centerX() < rb.centerX() ? a : b;
                AccessibilityNodeInfo r = ra.centerX() < rb.centerX() ? b : a;
                Rect rl = new Rect(); l.getBoundsInScreen(rl);
                Rect rr = new Rect(); r.getBoundsInScreen(rr);
                if (rl.centerX() < w * 0.48f && rr.centerX() > w * 0.52f && rl.centerY() > bestY) {
                    left = l; right = r; bestY = rl.centerY();
                }
            }
        }
        return left != null && right != null ? new ClickPair(left, right) : null;
    }

    private boolean clickContainsBelow(AccessibilityNodeInfo root, String target, float minYFrac) {
        AccessibilityNodeInfo n = findContainsBelow(root, target, minYFrac);
        return clickNode(n) || tapNodeCenter(n);
    }

    private AccessibilityNodeInfo findContainsBelow(AccessibilityNodeInfo node, String target, float minYFrac) {
        if (node == null) return null;
        Rect r = new Rect(); node.getBoundsInScreen(r);
        int h = getResources().getDisplayMetrics().heightPixels;
        String t = norm(node.getText());
        String d = norm(node.getContentDescription());
        if ((t.contains(target) || d.contains(target)) && r.centerY() >= h * minYFrac && !node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findContainsBelow(node.getChild(i), target, minYFrac);
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo findLowestContains(AccessibilityNodeInfo root, String target) {
        List<AccessibilityNodeInfo> matches = new ArrayList<>();
        collectContains(root, target, matches, 0);
        AccessibilityNodeInfo best = null;
        int bestY = -1;
        for (AccessibilityNodeInfo n : matches) {
            Rect r = new Rect(); n.getBoundsInScreen(r);
            if (r.centerY() > bestY) { best = n; bestY = r.centerY(); }
        }
        return best;
    }

    private void collectContains(AccessibilityNodeInfo node, String target, List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 40) return;
        if (norm(node.getText()).contains(target) || norm(node.getContentDescription()).contains(target)) out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) collectContains(node.getChild(i), target, out, depth + 1);
    }

    private void collectClickable(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 40) return;
        if (node.isClickable()) out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) collectClickable(node.getChild(i), out, depth + 1);
    }

    private boolean tapNodeCenter(AccessibilityNodeInfo n) {
        if (n == null) return false;
        Rect r = new Rect(); n.getBoundsInScreen(r);
        if (r.isEmpty()) return false;
        return tapPx(r.centerX(), r.centerY());
    }

    private boolean tapPct(float xf, float yf) {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        return tapPx(w * xf, h * yf);
    }

    private boolean tapPx(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 80);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        return dispatchGesture(gesture, null, null);
    }

    private boolean tooSoon() {
        return System.currentTimeMillis() - lastNavAction < NAV_GAP_MS;
    }

    private void markNavAction() { lastNavAction = System.currentTimeMillis(); }

    private boolean containsAny(String s, String... needles) {
        for (String n : needles) if (s.contains(n)) return true;
        return false;
    }

    private boolean hasExact(AccessibilityNodeInfo root, String target) { return findExact(root, target) != null; }
    private boolean hasContains(AccessibilityNodeInfo root, String target) { return findContains(root, target) != null; }
    private boolean clickExact(AccessibilityNodeInfo root, String target) { return clickNode(findExact(root, target)); }
    private boolean clickContains(AccessibilityNodeInfo root, String target) { return clickNode(findContains(root, target)); }

    private boolean clickNode(AccessibilityNodeInfo n) {
        if (n == null) return false;
        AccessibilityNodeInfo c = n;
        int up = 0;
        while (c != null && !c.isClickable() && up < 7) { c = c.getParent(); up++; }
        return c != null && c.isClickable() && c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private AccessibilityNodeInfo findExact(AccessibilityNodeInfo node, String target) {
        if (node == null) return null;
        if (target.equals(norm(node.getText())) || target.equals(norm(node.getContentDescription()))) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findExact(node.getChild(i), target);
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo findContains(AccessibilityNodeInfo node, String target) {
        if (node == null) return null;
        if (norm(node.getText()).contains(target) || norm(node.getContentDescription()).contains(target)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findContains(node.getChild(i), target);
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable() || "android.widget.EditText".contentEquals(node.getClassName())) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findEditable(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private void collectText(AccessibilityNodeInfo node, List<String> out, int depth) {
        if (node == null || depth > 40) return;
        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        if (t != null && t.length() > 0) out.add(t.toString());
        if (d != null && d.length() > 0) out.add(d.toString());
        for (int i = 0; i < node.getChildCount(); i++) collectText(node.getChild(i), out, depth + 1);
    }

    private Analysis fetchAndAnalyze() throws Exception {
        String endpoint = "https://query1.finance.yahoo.com/v8/finance/chart/GC=F?range=5d&interval=5m";
        HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("Market HTTP " + code);

        BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();

        JSONObject root = new JSONObject(sb.toString());
        JSONObject result = root.getJSONObject("chart").getJSONArray("result").getJSONObject(0);
        JSONArray arr = result.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0).getJSONArray("close");
        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) if (!arr.isNull(i)) closes.add(arr.getDouble(i));
        if (closes.size() < 60) throw new Exception("Qızıl qiymət məlumatı azdır");

        double last = closes.get(closes.size() - 1);
        double e9 = ema(closes, 9);
        double e21 = ema(closes, 21);
        double rsi = rsi(closes, 14);
        double move = avgAbsMove(closes, 14);

        String signal;
        String reason;
        if (e9 >= e21) {
            signal = "BUY";
            reason = "DEMO test: GOLD trend yuxarıdır, RSI=" + String.format(Locale.US, "%.1f", rsi);
        } else {
            signal = "SELL";
            reason = "DEMO test: GOLD trend aşağıdır, RSI=" + String.format(Locale.US, "%.1f", rsi);
        }

        double sl = signal.equals("SELL") ? last + move * 2.0 : last - move * 2.0;
        double tp = signal.equals("SELL") ? last - move * 3.0 : last + move * 3.0;
        return new Analysis(signal, reason, last, sl, tp);
    }

    private double ema(List<Double> v, int p) {
        double e = 0;
        for (int i = 0; i < p; i++) e += v.get(i);
        e /= p;
        double k = 2.0 / (p + 1.0);
        for (int i = p; i < v.size(); i++) e = v.get(i) * k + e * (1 - k);
        return e;
    }

    private double rsi(List<Double> v, int p) {
        double gain = 0, loss = 0;
        for (int i = v.size() - p; i < v.size(); i++) {
            double d = v.get(i) - v.get(i - 1);
            if (d > 0) gain += d; else loss -= d;
        }
        if (loss == 0) return 100;
        double rs = (gain / p) / (loss / p);
        return 100 - 100 / (1 + rs);
    }

    private double avgAbsMove(List<Double> v, int p) {
        double s = 0;
        for (int i = v.size() - p; i < v.size(); i++) s += Math.abs(v.get(i) - v.get(i - 1));
        return Math.max(s / p, 0.10);
    }

    private boolean isRunning() {
        if (!prefs().getBoolean(MainActivity.KEY_RUNNING, false)) return false;
        long end = prefs().getLong("session_end", 0L);
        if (end > 0 && System.currentTimeMillis() >= end) {
            prefs().edit().putBoolean(MainActivity.KEY_RUNNING, false)
                    .putString("pending_signal", "")
                    .putString("nav_state", "10 dəqiqəlik GOLD sessiyası bitdi")
                    .apply();
            return false;
        }
        return true;
    }

    private SharedPreferences prefs() { return getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE); }
    private String norm(CharSequence s) { return s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT); }
    private String safe(String s) { return s == null ? "naməlum" : s.substring(0, Math.min(s.length(), 140)); }

    @Override public void onInterrupt() { }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(uiLoop);
        if (scheduler != null) scheduler.shutdownNow();
        super.onDestroy();
    }

    static class Analysis {
        final String signal, reason;
        final double price, sl, tp;
        Analysis(String signal, String reason, double price, double sl, double tp) {
            this.signal = signal; this.reason = reason; this.price = price; this.sl = sl; this.tp = tp;
        }
    }

    static class ClickPair {
        final AccessibilityNodeInfo left, right;
        ClickPair(AccessibilityNodeInfo left, AccessibilityNodeInfo right) { this.left = left; this.right = right; }
    }
}
