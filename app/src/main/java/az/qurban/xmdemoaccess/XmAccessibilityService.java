package az.qurban.xmdemoaccess;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.SharedPreferences;
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
        scheduler.scheduleAtFixedRate(this::analyzeMarketSafe, 1, 20, TimeUnit.SECONDS);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        if (!XM_PACKAGE.contentEquals(event.getPackageName())) return;
        if (!isRunning()) return;
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::inspectAndAct, 450);
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
            handler.post(this::inspectAndAct);
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
        if (root == null) {
            prefs().edit().putString("nav_state", "XM ekran ağacı alınmadı").apply();
            return;
        }

        List<String> texts = new ArrayList<>();
        collectText(root, texts, 0);
        String joined = String.join(" | ", texts).toLowerCase(Locale.ROOT);

        boolean demoCurrent = containsAny(joined, "demo", "practice", "virtual");
        boolean demoSeen = prefs().getBoolean("demo_seen", false) || demoCurrent;
        boolean realDetected = containsAny(joined, "real account", "live account", "real hesab", "canlı hesab");
        boolean goldDetected = containsAny(joined, "xauusd", "xau/usd", "gold", "qızıl");
        boolean buyFound = hasContains(root, "buy") || hasExact(root, "al");
        boolean sellFound = hasContains(root, "sell") || hasExact(root, "sat");

        String sample = joined.length() > 900 ? joined.substring(0, 900) : joined;
        prefs().edit()
                .putBoolean("demo_detected", demoCurrent)
                .putBoolean("demo_seen", demoSeen)
                .putBoolean("gold_detected", goldDetected)
                .putBoolean("buy_found", buyFound)
                .putBoolean("sell_found", sellFound)
                .putString("screen_sample", sample)
                .apply();

        if (realDetected) {
            prefs().edit()
                    .putBoolean(MainActivity.KEY_RUNNING, false)
                    .putString("nav_state", "REAL/LIVE hesab aşkarlandı — bot dayandırıldı")
                    .putString("reason", "Bu versiya yalnız DEMO üçündür")
                    .apply();
            return;
        }

        if (!goldDetected) {
            if (clickContains(root, "xauusd") || clickContains(root, "xau/usd") || clickContains(root, "gold")) {
                prefs().edit().putString("nav_state", "GOLD nəticəsinə basıldı").apply();
                return;
            }

            AccessibilityNodeInfo edit = findEditable(root);
            if (edit != null) {
                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "GOLD");
                if (edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    prefs().edit().putString("nav_state", "Axtarışa GOLD yazıldı").apply();
                    handler.postDelayed(this::inspectAndAct, 900);
                    return;
                }
            }

            if (clickContains(root, "search") || clickContains(root, "axtar") || clickContains(root, "find")) {
                prefs().edit().putString("nav_state", "Search açıldı").apply();
                return;
            }
            if (clickContains(root, "markets") || clickContains(root, "market") || clickContains(root, "quotes") ||
                    clickContains(root, "instruments") || clickContains(root, "symbols") || clickContains(root, "alətlər")) {
                prefs().edit().putString("nav_state", "Markets/Quotes açıldı").apply();
                return;
            }
            if (clickContains(root, "trade") || clickContains(root, "ticarət")) {
                prefs().edit().putString("nav_state", "Trade bölməsinə keçildi").apply();
                return;
            }

            prefs().edit().putString("nav_state", "GOLD yolu tapılmadı — statusu yoxla").apply();
            return;
        }

        if (!buyFound && !sellFound) {
            if (clickContains(root, "trade") || clickContains(root, "new order") || clickContains(root, "order") ||
                    clickContains(root, "ticarət") || clickContains(root, "əmr")) {
                prefs().edit().putString("nav_state", "GOLD trade ticket açılır").apply();
                return;
            }
            prefs().edit().putString("nav_state", "GOLD tapıldı, BUY/SELL gözlənilir").apply();
            return;
        }

        boolean userConfirmedDemo = prefs().getBoolean("demo_user_confirmed", false);
        if (!userConfirmedDemo) {
            prefs().edit().putString("nav_state", "DEMO təsdiqi yoxdur — trade bloklandı").apply();
            return;
        }

        SharedPreferences p = prefs();
        int count = p.getInt("trade_count", 0);
        long lastTrade = p.getLong("last_trade_time", 0L);
        long now = System.currentTimeMillis();

        if (count >= MAX_TRADES) {
            p.edit().putString("nav_state", "5/5 trade tamamlandı").apply();
            return;
        }
        if (now - lastTrade < COOLDOWN_MS) {
            long left = (COOLDOWN_MS - (now - lastTrade)) / 1000L;
            p.edit().putString("nav_state", "Növbəti trade üçün " + left + " san gözlənilir").apply();
            return;
        }

        String signal = p.getString("signal", "WAIT");
        if (!"BUY".equals(signal) && !"SELL".equals(signal)) {
            p.edit().putString("nav_state", "GOLD hazırdır, analiz WAIT verir").apply();
            return;
        }

        boolean clicked = "BUY".equals(signal)
                ? (clickContains(root, "buy") || clickExact(root, "al"))
                : (clickContains(root, "sell") || clickExact(root, "sat"));

        if (clicked) {
            int newCount = count + 1;
            p.edit()
                    .putInt("trade_count", newCount)
                    .putLong("last_trade_time", now)
                    .putString("nav_state", "GOLD " + signal + " klikləndi — " + newCount + "/5")
                    .putString("reason", p.getString("reason", "") + " • DEMO GOLD " + signal + " klikləndi")
                    .apply();
            handler.postDelayed(this::confirmIfVisible, 900);
        } else {
            p.edit().putString("nav_state", signal + " düyməsi tapıldı, amma klik alınmadı").apply();
        }
    }

    private void confirmIfVisible() {
        if (!isRunning() || !prefs().getBoolean("demo_user_confirmed", false)) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        clickContains(root, "place order");
        clickContains(root, "confirm");
        clickContains(root, "submit");
        clickContains(root, "təsdiq");
        clickContains(root, "sifariş ver");
    }

    private boolean containsAny(String s, String... needles) {
        for (String n : needles) if (s.contains(n)) return true;
        return false;
    }

    private boolean hasExact(AccessibilityNodeInfo root, String target) {
        return findExact(root, target) != null;
    }

    private boolean hasContains(AccessibilityNodeInfo root, String target) {
        return findContains(root, target) != null;
    }

    private boolean clickExact(AccessibilityNodeInfo root, String target) {
        return clickNode(findExact(root, target));
    }

    private boolean clickContains(AccessibilityNodeInfo root, String target) {
        return clickNode(findContains(root, target));
    }

    private boolean clickNode(AccessibilityNodeInfo n) {
        if (n == null) return false;
        AccessibilityNodeInfo c = n;
        int up = 0;
        while (c != null && !c.isClickable() && up < 6) {
            c = c.getParent();
            up++;
        }
        return c != null && c.isClickable() && c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private AccessibilityNodeInfo findExact(AccessibilityNodeInfo node, String target) {
        if (node == null) return null;
        String t = norm(node.getText());
        String d = norm(node.getContentDescription());
        if (target.equals(t) || target.equals(d)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findExact(node.getChild(i), target);
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo findContains(AccessibilityNodeInfo node, String target) {
        if (node == null) return null;
        String t = norm(node.getText());
        String d = norm(node.getContentDescription());
        if (t.contains(target) || d.contains(target)) return node;
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
        if (node == null || depth > 35) return;
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
            reason = "DEMO test: GOLD qısa trend yuxarıdır, RSI=" + String.format(Locale.US, "%.1f", rsi);
        } else {
            signal = "SELL";
            reason = "DEMO test: GOLD qısa trend aşağıdır, RSI=" + String.format(Locale.US, "%.1f", rsi);
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
            prefs().edit()
                    .putBoolean(MainActivity.KEY_RUNNING, false)
                    .putString("reason", "10 dəqiqəlik GOLD sessiyası bitdi")
                    .putString("nav_state", "Sessiya bitdi")
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
        if (s == null) return "naməlum";
        return s.substring(0, Math.min(s.length(), 120));
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
