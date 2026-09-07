package az.qurban.xmdemoaccess;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    public static final String PREFS = "xm_demo_prefs";
    public static final String KEY_RUNNING = "running";

    private TextView status;
    private volatile boolean running = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(22), dp(30), dp(22), dp(24));
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("XM DEMO SIGNAL");
        title.setTextSize(27);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER);
        root.addView(title, fullWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("EUR/USD • 5m • DEMO");
        subtitle.setTextSize(16);
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subp = fullWrap();
        subp.setMargins(0, dp(6), 0, dp(20));
        root.addView(subtitle, subp);

        Button analyze = makeButton("START ANALYSIS", Color.rgb(20,160,70));
        analyze.setOnClickListener(v -> startAnalysis());
        root.addView(analyze);

        Button openXm = makeButton("XM AÇ", Color.rgb(35,105,210));
        openXm.setOnClickListener(v -> openXm());
        root.addView(openXm);

        Button access = makeButton("ACCESSIBILITY İCAZƏSİ", Color.rgb(80,80,80));
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(access);

        Button stop = makeButton("STOP", Color.rgb(210,35,35));
        stop.setOnClickListener(v -> {
            running = false;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, false).apply();
            status.setText("Status: STOP");
        });
        root.addView(stop);

        status = new TextView(this);
        status.setTextSize(17);
        status.setTextColor(Color.BLACK);
        status.setPadding(dp(14), dp(18), dp(14), dp(18));
        status.setGravity(Gravity.LEFT);
        status.setText("Hazır. START ANALYSIS bas.");
        LinearLayout.LayoutParams sp = fullWrap();
        sp.setMargins(0, dp(14), 0, 0);
        root.addView(status, sp);

        setContentView(root);
    }

    private void startAnalysis() {
        running = true;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, true).apply();
        status.setText("Məlumat alınır və analiz edilir...");

        new Thread(() -> {
            while (running) {
                try {
                    Analysis a = fetchAndAnalyze();
                    runOnUiThread(() -> status.setText(a.toText()));
                } catch (Exception e) {
                    String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    runOnUiThread(() -> status.setText("Analiz xətası: " + msg + "\n\nInternet bağlantısını yoxla."));
                }

                for (int i = 0; i < 60 && running; i++) {
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
                }
            }
        }, "market-analysis").start();
    }

    private Analysis fetchAndAnalyze() throws Exception {
        String endpoint = "https://query1.finance.yahoo.com/v8/finance/chart/EURUSD=X?range=5d&interval=5m";
        HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0");

        int code = c.getResponseCode();
        if (code != 200) throw new Exception("Market server HTTP " + code);

        BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();

        JSONObject root = new JSONObject(sb.toString());
        JSONObject result = root.getJSONObject("chart").getJSONArray("result").getJSONObject(0);
        JSONArray closesJson = result.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0).getJSONArray("close");

        List<Double> closes = new ArrayList<>();
        for (int i = 0; i < closesJson.length(); i++) {
            if (!closesJson.isNull(i)) {
                double v = closesJson.getDouble(i);
                if (!Double.isNaN(v) && v > 0) closes.add(v);
            }
        }
        if (closes.size() < 60) throw new Exception("Kifayət qədər qiymət məlumatı yoxdur");

        double last = closes.get(closes.size() - 1);
        double e20 = ema(closes, 20);
        double e50 = ema(closes, 50);
        double rsi = rsi(closes, 14);
        double atrLike = avgAbsMove(closes, 14);

        String signal = "WAIT";
        String reason = "Siqnallar tam uyğun deyil";

        if (e20 > e50 && rsi >= 52 && rsi <= 68 && last > e20) {
            signal = "BUY";
            reason = "EMA20 > EMA50 və RSI yüksəlişi təsdiqləyir";
        } else if (e20 < e50 && rsi >= 32 && rsi <= 48 && last < e20) {
            signal = "SELL";
            reason = "EMA20 < EMA50 və RSI enişi təsdiqləyir";
        }

        double sl;
        double tp;
        if (signal.equals("BUY")) {
            sl = last - atrLike * 2.0;
            tp = last + atrLike * 3.0;
        } else if (signal.equals("SELL")) {
            sl = last + atrLike * 2.0;
            tp = last - atrLike * 3.0;
        } else {
            sl = last - atrLike * 2.0;
            tp = last + atrLike * 2.0;
        }

        return new Analysis(signal, reason, last, e20, e50, rsi, sl, tp);
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
        return Math.max(s / p, 0.00015);
    }

    private void openXm() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.xm.webapp");
        if (launch == null) {
            Toast.makeText(this, "XM proqramı tapılmadı.", Toast.LENGTH_LONG).show();
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
    }

    private LinearLayout.LayoutParams fullWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private Button makeButton(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(18);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(color);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
        p.setMargins(0, dp(6), 0, dp(6));
        b.setLayoutParams(p);
        return b;
    }

    private int dp(int v) {
        return (int)(v * getResources().getDisplayMetrics().density + 0.5f);
    }

    static class Analysis {
        final String signal, reason;
        final double price, ema20, ema50, rsi, sl, tp;

        Analysis(String signal, String reason, double price, double ema20, double ema50, double rsi, double sl, double tp) {
            this.signal = signal;
            this.reason = reason;
            this.price = price;
            this.ema20 = ema20;
            this.ema50 = ema50;
            this.rsi = rsi;
            this.sl = sl;
            this.tp = tp;
        }

        String toText() {
            return String.format(Locale.US,
                    "SİQNAL: %s\n\nQiymət: %.5f\nEMA20: %.5f\nEMA50: %.5f\nRSI14: %.1f\n\nSL: %.5f\nTP: %.5f\n\nSəbəb: %s\n\nAvtomatik order göndərilmir — XM-də əmri sən təsdiqlə.",
                    signal, price, ema20, ema50, rsi, sl, tp, reason);
        }
    }
}
