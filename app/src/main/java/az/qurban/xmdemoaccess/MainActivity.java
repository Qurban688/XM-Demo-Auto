package az.qurban.xmdemoaccess;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    public static final String PREFS = "xm_demo_prefs";
    public static final String KEY_RUNNING = "running";
    public static final long SESSION_MS = 30L * 60L * 1000L;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(22), dp(26), dp(22), dp(22));
        root.setBackgroundColor(Color.WHITE);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("XM GOLD DEMO AUTO v4");
        title.setTextSize(24);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER);
        root.addView(title, fullWrap());

        TextView sub = new TextView(this);
        sub.setText("GOLD / XAUUSD • 30 dəqiqə • DEMO ONLY");
        sub.setTextSize(16);
        sub.setTextColor(Color.rgb(200,120,0));
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp1 = fullWrap();
        sp1.setMargins(0, dp(6), 0, dp(18));
        root.addView(sub, sp1);

        Button access = makeButton("ACCESSIBILITY İCAZƏSİ", Color.rgb(75,75,75));
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(access);

        Button start = makeButton("START — GOLD 30 DƏQİQƏ", Color.rgb(20,160,70));
        start.setOnClickListener(v -> startSession());
        root.addView(start);

        Button refresh = makeButton("STATUSU YENİLƏ", Color.rgb(35,105,210));
        refresh.setOnClickListener(v -> refreshStatus());
        root.addView(refresh);

        Button stop = makeButton("STOP", Color.rgb(210,35,35));
        stop.setOnClickListener(v -> stopSession());
        root.addView(stop);

        status = new TextView(this);
        status.setTextSize(14);
        status.setTextColor(Color.BLACK);
        status.setGravity(Gravity.LEFT);
        status.setPadding(dp(10), dp(16), dp(10), dp(16));
        LinearLayout.LayoutParams sp = fullWrap();
        sp.setMargins(0, dp(12), 0, 0);
        root.addView(status, sp);

        setContentView(scroll);
        refreshStatus();
    }

    private void startSession() {
        long now = System.currentTimeMillis();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_RUNNING, true)
                .putLong("session_start", now)
                .putLong("session_end", now + SESSION_MS)
                .putLong("last_trade_time", 0L)
                .putInt("trade_count", 0)
                .putString("signal", "WAIT")
                .putString("reason", "İlk GOLD analizi gözlənilir")
                .putString("nav_state", "XM açılır")
                .putBoolean("demo_detected", false)
                .putBoolean("demo_seen", false)
                .putBoolean("gold_detected", false)
                .putBoolean("buy_found", false)
                .putBoolean("sell_found", false)
                .putString("screen_sample", "")
                .apply();

        Intent launch = getPackageManager().getLaunchIntentForPackage("com.xm.webapp");
        if (launch == null) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, false).apply();
            Toast.makeText(this, "XM proqramı tapılmadı.", Toast.LENGTH_LONG).show();
            refreshStatus();
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
        Toast.makeText(this, "GOLD DEMO bot başladı.", Toast.LENGTH_LONG).show();
    }

    private void stopSession() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_RUNNING, false)
                .putString("reason", "İstifadəçi STOP basdı")
                .putString("nav_state", "STOP")
                .apply();
        Toast.makeText(this, "Bot dayandırıldı.", Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    private void refreshStatus() {
        if (status == null) return;
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean running = p.getBoolean(KEY_RUNNING, false);
        long end = p.getLong("session_end", 0L);
        long remain = Math.max(0, end - System.currentTimeMillis());
        long min = remain / 60000L;
        long sec = (remain % 60000L) / 1000L;

        String txt = "Status: " + (running ? "START" : "STOP") +
                "\nQalan vaxt: " + min + " dəq " + sec + " san" +
                "\n\nNaviqasiya: " + p.getString("nav_state", "-") +
                "\nDEMO bu ekranda: " + (p.getBoolean("demo_detected", false) ? "HƏ" : "YOX") +
                "\nDEMO sessiyada görüldü: " + (p.getBoolean("demo_seen", false) ? "HƏ" : "YOX") +
                "\nGOLD/XAUUSD: " + (p.getBoolean("gold_detected", false) ? "HƏ" : "YOX") +
                "\nBUY düyməsi: " + (p.getBoolean("buy_found", false) ? "HƏ" : "YOX") +
                "\nSELL düyməsi: " + (p.getBoolean("sell_found", false) ? "HƏ" : "YOX") +
                "\n\nSiqnal: " + p.getString("signal", "WAIT") +
                "\nSəbəb: " + p.getString("reason", "-") +
                "\nQızıl qiyməti: " + p.getString("price", "-") +
                "\nSL: " + p.getString("sl", "-") +
                "\nTP: " + p.getString("tp", "-") +
                "\n\nTrade sayı: " + p.getInt("trade_count", 0) + " / 2" +
                "\nSon analiz: " + p.getString("last_analysis", "yoxdur") +
                "\n\nXM-dən oxunan mətn:\n" + p.getString("screen_sample", "-");
        status.setText(txt);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
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
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62));
        p.setMargins(0, dp(6), 0, dp(6));
        b.setLayoutParams(p);
        return b;
    }

    private int dp(int v) {
        return (int)(v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
