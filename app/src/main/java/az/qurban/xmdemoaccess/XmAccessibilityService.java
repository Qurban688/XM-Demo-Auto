package az.qurban.xmdemoaccess;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class XmAccessibilityService extends AccessibilityService {
    private static final String XM_PACKAGE = "com.xm.webapp";
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        if (!XM_PACKAGE.contentEquals(event.getPackageName())) return;
        if (!isRunning()) return;
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::inspectXmScreen, 350);
    }

    private void inspectXmScreen() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !isRunning()) return;
        List<String> texts = new ArrayList<>();
        collectText(root, texts, 0);
        String joined = String.join(" ", texts).toLowerCase(Locale.ROOT);
        boolean demoDetected = joined.contains("demo") || joined.contains("practice") || joined.contains("virtual");
        SharedPreferences.Editor e = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit();
        e.putBoolean("demo_detected", demoDetected);
        e.putString("last_screen_text", joined.length() > 1200 ? joined.substring(0, 1200) : joined);
        e.apply();
        // Safety: this build does not auto-click BUY/SELL yet.
    }

    private void collectText(AccessibilityNodeInfo node, List<String> out, int depth) {
        if (node == null || depth > 30) return;
        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        if (t != null && t.length() > 0) out.add(t.toString());
        if (d != null && d.length() > 0) out.add(d.toString());
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectText(child, out, depth + 1);
                child.recycle();
            }
        }
    }

    private boolean isRunning() {
        return getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).getBoolean(MainActivity.KEY_RUNNING, false);
    }

    @Override
    public void onInterrupt() { }
}
