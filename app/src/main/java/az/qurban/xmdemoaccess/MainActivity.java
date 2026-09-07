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
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    public static final String PREFS = "xm_demo_prefs";
    public static final String KEY_RUNNING = "running";
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(40), dp(24), dp(24));
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("XM DEMO AUTO");
        title.setTextSize(28);
        title.setTextColor(Color.BLACK);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView warning = new TextView(this);
        warning.setText("YALNIZ DEMO HESAB ÜÇÜN");
        warning.setTextSize(16);
        warning.setTextColor(Color.rgb(210,130,0));
        warning.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wp.setMargins(0, dp(8), 0, dp(24));
        root.addView(warning, wp);

        Button access = makeButton("1. ACCESSIBILITY İCAZƏSİ", Color.rgb(70,70,70));
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(access);

        Button start = makeButton("START", Color.rgb(20,160,70));
        start.setOnClickListener(v -> startAutomation());
        root.addView(start);

        Button stop = makeButton("STOP", Color.rgb(210,35,35));
        stop.setOnClickListener(v -> stopAutomation());
        root.addView(stop);

        status = new TextView(this);
        status.setTextSize(16);
        status.setTextColor(Color.DKGRAY);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.setMargins(0, dp(20), 0, 0);
        root.addView(status, sp);
        setContentView(root);
        refreshStatus();
    }

    private Button makeButton(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(20);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(color);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(70));
        p.setMargins(0, dp(8), 0, dp(8));
        b.setLayoutParams(p);
        return b;
    }

    private void startAutomation() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_RUNNING, true).apply();
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.xm.webapp");
        if (launch == null) {
            prefs.edit().putBoolean(KEY_RUNNING, false).apply();
            Toast.makeText(this, "XM proqramı tapılmadı.", Toast.LENGTH_LONG).show();
            refreshStatus();
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
        Toast.makeText(this, "XM açıldı. DEMO hesabı yoxlanacaq.", Toast.LENGTH_LONG).show();
        refreshStatus();
    }

    private void stopAutomation() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, false).apply();
        Toast.makeText(this, "Avtomatlaşdırma dayandırıldı.", Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    private void refreshStatus() {
        boolean running = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_RUNNING, false);
        status.setText(running ? "Status: START aktivdir" : "Status: STOP");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (status != null) refreshStatus();
    }

    private int dp(int v) {
        return (int)(v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
