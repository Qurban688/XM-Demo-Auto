package az.qurban.xmdemoaccess;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.SharedPreferences;
import android.graphics.Path;
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

    private final Runnable uiLoop = new Runnable() {
        @Override public void run() {
            try {
                if (isRunning()) inspectAndAct();
            } finally {
                handler.postDelayed(this, 650L);
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
        handler.postDelayed(this::inspectAndAct, 120L);
    }

    private void inspectAndAct() {
        if (!isRunning()) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            prefs().edit().putString("nav_state", "XM ekranı gözlənilir").apply();
            return;
        }

        String joined = collectJoinedText(root);
        boolean demoText = containsAny(joined, "demo", "practice", "virtual");
        boolean realText = containsAny(joined, "real account", "live account", "real hesab", "canlı hesab");
        boolean goldText = containsAny(joined, "gold", "xauusd", "xau/usd");
        boolean buyText = containsAny(joined, "buy");
        boolean sellText = containsAny(joined, "sell");

        prefs().edit()
                .putBoolean("demo_detected", demoText)
                .putBoolean("gold_detected", goldText)
                .putBoolean("buy_found", buyText)
                .putBoolean("sell_found", sellText)
                .putString("screen_sample", joined.length() > 900 ? joined.substring(0, 900) : joined)
                .apply();

        if (realText) {
            prefs().edit()
                    .putBoolean(MainActivity.KEY_RUNNING, false)
                    .putString("nav_state", "REAL/LIVE aşkarlandı — dayandırıldı")
                    .putString("reason", "Bu versiya yalnız DEMO üçündür")
                    .apply();
            return;
        }

        if (!prefs().getBoolean("demo_user_confirmed", false)) return;

        long now = System.currentTimeMillis();
        int phase = prefs().getInt("nav_phase", 0);
        long phaseAt = prefs().getLong("nav_phase_at", prefs().getLong("session_start", now));
        long age = now - phaseAt;

        // User screenshots: 720x1600. Percent taps make this scale to the same layout.
        // 0: force Home first so every run starts from a known screen.
        if (phase == 0 && age >= 2200L) {
            tapPct(0.10f, 0.945f); // Home bottom-left
            setPhase(1, "Home seçildi");
            return;
        }

        // 1: Home -> Markets (bottom second icon)
        if (phase == 1 && age >= 1400L) {
            tapPct(0.30f, 0.945f); // Markets
            setPhase(2, "Markets seçildi");
            return;
        }

        // 2: GOLD is the first row in user's current Markets/Popular screen.
        if (phase == 2 && age >= 1600L) {
            tapPct(0.25f, 0.247f); // GOLD first row
            setPhase(3, "GOLD sətrinə basıldı");
            return;
        }

        // 3: GOLD chart opens and bottom Trade tab becomes selected.
        if (phase == 3 && age >= 2200L) {
            prefs().edit()
                    .putBoolean("gold_selected_by_bot", true)
                    .putInt("nav_phase", 4)
                    .putLong("nav_phase_at", now)
                    .putString("nav_state", goldText ? "GOLD trade ekranı hazırdır" : "GOLD trade ekranı qəbul edildi")
                    .apply();
            return;
        }

        if (phase < 4) return;

        // Pending click: many XM layouts execute directly; if a confirmation button appears, press it.
        String pending = prefs().getString("pending_signal", "");
        long pendingSince = prefs().getLong("pending_since", 0L);
        if (!pending.isEmpty()) {
            if (clickAnyText(root, "confirm", "place order", "submit", "open position", "execute", "təsdiq", "sifariş ver")) {
                finalizeTrade(pending, "təsdiq düyməsi basıldı");
                return;
            }
            if (now - pendingSince >= 2600L) {
                finalizeTrade(pending, "BUY/SELL klik cəhdi tamamlandı");
            }
            return;
        }

        int count = prefs().getInt("trade_count", 0);
        if (count >= MAX_TRADES) {
            prefs().edit().putBoolean(MainActivity.KEY_RUNNING, false).putString("nav_state", "5/5 trade cəhdi tamamlandı").apply();
            return;
        }

        long lastTrade = prefs().getLong("last_trade_time", 0L);
        if (lastTrade > 0 && now - lastTrade < COOLDOWN_MS) {
            long left = (COOLDOWN_MS - (now - lastTrade)) / 1000L;
            prefs().edit().putString("nav_state", "Növbəti trade üçün " + left + " san").apply();
            return;
        }

        String signal = prefs().getString("signal", "WAIT");
        if (!"BUY".equals(signal) && !"SELL".equals(signal)) {
            prefs().edit().putString("nav_state", "GOLD hazırdır, analiz gözlənilir").apply();
            return;
        }

        boolean clicked = false;
        if ("BUY".equals(signal)) {
            clicked = clickContains(root, "buy");
            if (!clicked) clicked = tapPct(0.735f, 0.827f); // exact BUY center from screenshot
        } else {
            clicked = clickContains(root, "sell");
            if (!clicked) clicked = tapPct(0.265f, 0.827f); // exact SELL center from screenshot
        }

        if (clicked) {
            prefs().edit()
                    .putString("pending_signal", signal)
                    .putLong("pending_since", now)
                    .putString("nav_state", "GOLD " + signal + " basıldı")
                    .apply();
        } else {
            prefs().edit().putString("nav_state", signal + " klik alınmadı").apply();
        }
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
        if (closes.size() < 30) throw new Exception("Qiymət məlumatı azdır");

        double last = closes.get(closes.size() - 1);
        double e9 = ema(closes, 9);
        double e21 = ema(closes, 21);
        double rsi = rsi(closes, 14);
        double move = avgAbsMove(closes, 14);

        String signal = e9 >= e21 ? "BUY" : "SELL";
        String reason = signal + " • GOLD EMA9/EMA21 trend • RSI=" + String.format(Locale.US, "%.1f", rsi);
        double sl = signal.equals("SELL") ? last + move * 2.0 : last - move * 2.0;
        double tp = signal.equals("SELL") ? last - move * 3.0 : last + move * 3.0;
        return new Analysis(signal, reason, last, sl, tp);
    }

    private void finalizeTrade(String signal, String note) {
        int newCount = Math.min(MAX_TRADES, prefs().getInt("trade_count", 0) + 1);
        prefs().edit()
                .putInt("trade_count", newCount)
                .putLong("last_trade_time", System.currentTimeMillis())
                .putString("pending_signal", "")
                .putLong("pending_since", 0L)
                .putString("nav_state", "GOLD " + signal + " — " + newCount + "/5")
                .putString("reason", prefs().getString("reason", "") + " • " + note)
                .apply();
    }

    private void setPhase(int phase, String state) {
        prefs().edit().putInt("nav_phase", phase).putLong("nav_phase_at", System.currentTimeMillis()).putString("nav_state", state).apply();
    }

    private boolean tapPct(float xPct, float yPct) {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        Path path = new Path();
        path.moveTo(w * xPct, h * yPct);
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(path, 0, 80));
        return dispatchGesture(b.build(), null, null);
    }

    private boolean clickAnyText(AccessibilityNodeInfo root, String... values) {
        for (String v : values) if (clickContains(root, v)) return true;
        return false;
    }

    private boolean clickContains(AccessibilityNodeInfo node, String target) {
        AccessibilityNodeInfo n = findContains(node, target);
        if (n == null) return false;
        AccessibilityNodeInfo c = n;
        for (int i = 0; i < 6 && c != null && !c.isClickable(); i++) c = c.getParent();
        return c != null && c.isClickable() && c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private AccessibilityNodeInfo findContains(AccessibilityNodeInfo node, String target) {
        if (node == null) return null;
        String t = norm(node.getText());
        String d = norm(node.getContentDescription());
        if (t.contains(target) || d.contains(target)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo f = findContains(node.getChild(i), target);
            if (f != null) return f;
        }
        return null;
    }

    private String collectJoinedText(AccessibilityNodeInfo root) {
        List<String> out = new ArrayList<>();
        collectText(root, out, 0);
        return String.join(" | ", out).toLowerCase(Locale.ROOT);
    }

    private void collectText(AccessibilityNodeInfo node, List<String> out, int depth) {
        if (node == null || depth > 35) return;
        if (node.getText() != null && node.getText().length() > 0) out.add(node.getText().toString());
        if (node.getContentDescription() != null && node.getContentDescription().length() > 0) out.add(node.getContentDescription().toString());
        for (int i = 0; i < node.getChildCount(); i++) collectText(node.getChild(i), out, depth + 1);
    }

    private boolean containsAny(String s, String... values) {
        for (String v : values) if (s.contains(v)) return true;
        return false;
    }

    private String norm(CharSequence s) {
        return s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT);
    }

    private double ema(List<Double> v, int p) {
        double e = 0;
        for (int i = 0; i < p; i++) e += v.get(i);
        e /= p;
        double k = 2.0 / (p + 1.0);
        for (int i = p; i < v.size(); i++) e = v.get(i) * k + e * (1.0 - k);
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
            prefs().edit().putBoolean(MainActivity.KEY_RUNNING, false).putString("nav_state", "10 dəqiqə bitdi").apply();
            return false;
        }
        return true;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
    }

    private String safe(String s) {
        return s == null ? "naməlum" : s.substring(0, Math.min(120, s.length()));
    }

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
            this.signal = signal;
            this.reason = reason;
            this.price = price;
            this.sl = sl;
            this.tp = tp;
        }
    }
}
