package az.qurban.xmdemoaccess;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.SharedPreferences;
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
    private static final long COOLDOWN_MS = 10L * 60L * 1000L;
    private static final int MAX_TRADES = 2;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ScheduledExecutorService scheduler;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);

        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::analyzeMarketSafe, 1, 60, TimeUnit.SECONDS);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        if (!XM_PACKAGE.contentEquals(event.getPackageName())) return;
        if (!isRunning()) return;
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::inspectAndAct, 350);
    }

    private void analyzeMarketSafe() {
        if (!isRunning()) return;
        long now = System.currentTimeMillis();
        long end = prefs().getLong("session_end", 0L);
        if (end > 0 && now >= end) {
            prefs().edit().putBoolean(MainActivity.KEY_RUNNING, false)
                    .putString("reason", "30 dəqiqəlik GOLD sessiyası bitdi")
                    .apply();
            return;
        }
        try {
            Analysis a = fetchAndAnalyze();
            SharedPreferences.Editor e = prefs().edit();
            e.putString("signal", a.signal)
                    .putString("reason", a.reason)
                    .putString("price", String.format(Locale.US, "%.2f", a.price))
                    .putString("sl", String.format(Locale.US, "%.2f", a.sl))
                    .putString("tp", String.format(Locale.US, "%.2f", a.tp))
                    .putString("last_analysis", new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()))
                    .apply();
            handler.post(this::inspectAndAct);
        } catch (Exception ex) {
            prefs().edit().putString("signal", "WAIT")
                    .putString("reason", "Qızıl analiz xətası: " + safe(ex.getMessage()))
                    .apply();
        }
    }

    private void inspectAndAct() {
        if (!isRunning()) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        List<String> texts = new ArrayList<>();
        collectText(root, texts, 0);
        String joined = String.join(" ", texts).toLowerCase(Locale.ROOT);

        boolean demo = joined.contains("demo") || joined.contains("practice") || joined.contains("virtual");
        boolean goldDetected = joined.contains("xauusd") || joined.contains("xau/usd") || joined.contains("gold") || joined.contains("qızıl");
        boolean buyFound = hasExact(root, "buy") || hasExact(root, "al");
        boolean sellFound = hasExact(root, "sell") || hasExact(root, "sat");

        prefs().edit()
                .putBoolean("demo_detected", demo)
                .putBoolean("gold_detected", goldDetected)
                .putBoolean("buy_found", buyFound)
                .putBoolean("sell_found", sellFound)
                .apply();

        if (!demo) return; // HARD SAFETY: no click without current DEMO text on XM screen.

        // Ensure GOLD/XAUUSD is the active instrument before any trade click.
        if (!goldDetected) {
            if (clickExact(root, "xauusd")) return;
            if (clickExact(root, "xau/usd")) return;
            if (clickExact(root, "gold")) return;
            if (clickExact(root, "qızıl")) return;
            if (clickExact(root, "metals")) return;
            return;
        }

        // Reach the trade ticket if needed.
        if (!buyFound && !sellFound) {
            if (clickExact(root, "trade")) return;
            if (clickExact(root, "new order")) return;
            if (clickExact(root, "order")) return;
            return;
        }

        SharedPreferences p = prefs();
        String signal = p.getString("signal", "WAIT");
        int count = p.getInt("trade_count", 0);
        long lastTrade = p.getLong("last_trade_time", 0L);
        long now = System.currentTimeMillis();

        if (count >= MAX_TRADES) return;
        if (now - lastTrade < COOLDOWN_MS) return;
        if (!"BUY".equals(signal) && !"SELL".equals(signal)) return;

        boolean clicked;
        if ("BUY".equals(signal)) {
            clicked = clickExact(root, "buy") || clickExact(root, "al");
        } else {
            clicked = clickExact(root, "sell") || clickExact(root, "sat");
        }

        if (clicked) {
            p.edit().putInt("trade_count", count + 1)
                    .putLong("last_trade_time", now)
                    .putString("reason", p.getString("reason", "") + " • GOLD " + signal + " klikləndi")
                    .apply();
            handler.postDelayed(this::confirmIfVisible, 900);
        }
    }

    private void confirmIfVisible() {
        if (!isRunning()) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        clickExact(root, "confirm");
        clickExact(root, "place order");
        clickExact(root, "submit");
        clickExact(root, "təsdiq et");
        clickExact(root, "sifariş ver");
    }

    private boolean hasExact(AccessibilityNodeInfo root, String target) {
        return findExact(root, target) != null;
    }

    private boolean clickExact(AccessibilityNodeInfo root, String target) {
        AccessibilityNodeInfo n = findExact(root, target);
        if (n == null) return false;
        AccessibilityNodeInfo c = n;
        while (c != null && !c.isClickable()) c = c.getParent();
        if (c != null && c.isClickable()) return c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        return false;
    }

    private AccessibilityNodeInfo findExact(AccessibilityNodeInfo node, String target) {
        if (node == null) return null;
        String t = norm(node.getText());
        String d = norm(node.getContentDescription());
        if (target.equals(t) || target.equals(d)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo found = findExact(child, target);
            if (found != null) return found;
        }
        return null;
    }

    private void collectText(AccessibilityNodeInfo node, List<String> out, int depth) {
        if (node == null || depth > 35) return;
        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        if (t != null && t.length() > 0) out.add(t.toString());
        if (d != null && d.length() > 0) out.add(d.toString());
        for (int i = 0; i < node.getChildCount(); i++) {
            collectText(node.getChild(i), out, depth + 1);
        }
    }

    private Analysis fetchAndAnalyze() throws Exception {
        // GC=F is COMEX Gold futures; used here as a public demo-market proxy for GOLD/XAUUSD direction.
        String endpoint = "https://query1.finance.yahoo.com/v8/finance/chart/GC=F?range=5d&interval=5m";
        HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        if (c.getResponseCode() != 200) throw new Exception("Market HTTP " + c.getResponseCode());

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

        String signal = "WAIT";
        String reason = "GOLD siqnalı zəifdir";
        if (e9 > e21 && rsi >= 53 && rsi <= 67 && last > e9) {
            signal = "BUY";
            reason = "GOLD EMA9>EMA21, RSI yüksəlişi təsdiqləyir";
        } else if (e9 < e21 && rsi >= 33 && rsi <= 47 && last < e9) {
            signal = "SELL";
            reason = "GOLD EMA9<EMA21, RSI enişi təsdiqləyir";
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
                    .putString("reason", "30 dəqiqəlik GOLD sessiyası bitdi")
                    .apply();
            return false;
        }
        return true;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
    }

    private String norm(CharSequence s) {
        return s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT);
    }

    private String safe(String s) {
        return s == null ? "naməlum" : s.substring(0, Math.min(s.length(), 120));
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        if (scheduler != null) scheduler.shutdownNow();
        super.onDestroy();
    }

    static class Analysis {
        final String signal, reason;
        final double price, sl, tp;
        Analysis(String signal, String reason, double price, double sl, double tp) {
            this.signal = signal;
            this.reason = reason;
            this.price = price;
            this.sl = sl;
            this.tp = tp;
        }
    }
}
