package com.example.helper;

import android.accessibilityservice.AccessibilityService;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;

public class WhatsAppService extends AccessibilityService {
    private static WhatsAppService instance;
    private SimpleHttpServer httpServer;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        httpServer = new SimpleHttpServer(8080);
        try {
            httpServer.start();
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (httpServer != null) httpServer.stop();
        instance = null;
    }

    public static WhatsAppService getInstance() { return instance; }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override
    public void onInterrupt() {}

    public AccessibilityNodeInfo findElementById(String id) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
        return nodes.isEmpty() ? null : nodes.get(0);
    }

    public boolean clickById(String id) {
        AccessibilityNodeInfo node = findElementById(id);
        if (node != null) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            return true;
        }
        return false;
    }

    public boolean typeText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        List<AccessibilityNodeInfo> inputs = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/entry");
        if (inputs.isEmpty()) return false;
        AccessibilityNodeInfo input = inputs.get(0);
        input.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        return true;
    }

    public String readLastMessage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        List<AccessibilityNodeInfo> msgs = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/message_text");
        if (msgs.isEmpty()) return null;
        AccessibilityNodeInfo last = msgs.get(msgs.size() - 1);
        CharSequence txt = last.getText();
        return txt != null ? txt.toString() : null;
    }

    public void openApp(String pkg) {
        try {
            startActivity(getPackageManager().getLaunchIntentForPackage(pkg));
        } catch (Exception ignored) {}
    }

    public void goBack() {
        performGlobalAction(GLOBAL_ACTION_BACK);
    }
}
