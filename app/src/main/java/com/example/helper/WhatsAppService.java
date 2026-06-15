package com.example.helper;

import android.accessibilityservice.AccessibilityService;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.ServerSocket;
import java.util.*;

public class WhatsAppService extends AccessibilityService {
    private static WhatsAppService instance;
    private SimpleHttpServer httpServer;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        int port = findAvailablePort();
        httpServer = new SimpleHttpServer(port);
        try {
            httpServer.start();
            try (FileWriter fw = new FileWriter("/sdcard/whatsapp_helper_port.txt")) {
                fw.write(String.valueOf(port));
            } catch (IOException ignored) {}
        } catch (Exception ignored) {}
    }

    private int findAvailablePort() {
        for (int port : new int[]{8080, 8081, 8082}) {
            try { ServerSocket ss = new ServerSocket(port); ss.close(); return port; } catch (IOException ignored) {}
        }
        return 8080;
    }

    @Override public void onDestroy() { super.onDestroy(); if (httpServer != null) httpServer.stop(); instance = null; }
    public static WhatsAppService getInstance() { return instance; }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}

    // ---------- Basic actions ----------
    public AccessibilityNodeInfo findElementById(String id) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
        return nodes.isEmpty() ? null : nodes.get(0);
    }

    public boolean clickById(String id) {
        AccessibilityNodeInfo node = findElementById(id);
        if (node != null) { node.performAction(AccessibilityNodeInfo.ACTION_CLICK); return true; }
        return false;
    }

    public boolean typeText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        List<AccessibilityNodeInfo> inputs = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/entry");
        if (inputs.isEmpty()) inputs = findAllEditTexts(root);
        if (inputs.isEmpty()) return false;
        AccessibilityNodeInfo input = inputs.get(0);
        input.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        return true;
    }

    private List<AccessibilityNodeInfo> findAllEditTexts(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        collectByClass(root, "android.widget.EditText", out);
        return out;
    }

    private void collectByClass(AccessibilityNodeInfo node, String className, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        if (className.equals(node.getClassName())) out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) collectByClass(node.getChild(i), className, out);
    }

    /** Opens a chat by contact name – only matches on the actual contact name element. */
    public boolean clickChatByName(String name) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        List<AccessibilityNodeInfo> names = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversations_row_contact_name");
        for (AccessibilityNodeInfo node : names) {
            if (name.equals(node.getText() != null ? node.getText().toString() : "")) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                return true;
            }
        }
        return false;
    }

    // ---------- Accurate unread detection (only returns actual contact names) ----------
    public String getUnreadChats() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "[]";

        // Find all chat rows (any clickable container that has a contact name)
        List<AccessibilityNodeInfo> rows = new ArrayList<>();
        findChatRows(root, rows);

        JSONArray result = new JSONArray();
        Set<String> usedNames = new HashSet<>();

        for (AccessibilityNodeInfo row : rows) {
            // Get the contact name (exact element)
            String name = getContactNameFromRow(row);
            if (name == null || name.isEmpty()) continue;

            // Get unread count (badge)
            int count = getUnreadCountFromRow(row);
            if (count > 0 && !usedNames.contains(name)) {
                usedNames.add(name);
                try {
                    JSONObject obj = new JSONObject();
                    obj.put("name", name);
                    obj.put("count", count);
                    result.put(obj);
                } catch (Exception ignored) {}
            }
        }
        return result.toString();
    }

    private void findChatRows(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        // A chat row is clickable and contains a contact name element
        if (node.isClickable() && hasContactNameElement(node)) {
            out.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            findChatRows(node.getChild(i), out);
        }
    }

    private boolean hasContactNameElement(AccessibilityNodeInfo node) {
        List<AccessibilityNodeInfo> names = node.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversations_row_contact_name");
        return !names.isEmpty();
    }

    private String getContactNameFromRow(AccessibilityNodeInfo row) {
        List<AccessibilityNodeInfo> names = row.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversations_row_contact_name");
        if (!names.isEmpty()) {
            CharSequence txt = names.get(0).getText();
            return txt != null ? txt.toString().trim() : null;
        }
        return null;
    }

    private int getUnreadCountFromRow(AccessibilityNodeInfo row) {
        // Try known badge IDs
        List<AccessibilityNodeInfo> badges = row.findAccessibilityNodeInfosByViewId("com.whatsapp:id/unread_count");
        if (badges.isEmpty()) badges = row.findAccessibilityNodeInfosByViewId("com.whatsapp:id/unread_indicator");
        if (!badges.isEmpty()) {
            CharSequence t = badges.get(0).getText();
            if (t != null) {
                try { return Integer.parseInt(t.toString().trim()); } catch (NumberFormatException ignored) {}
            }
        }
        // Also check contentDescription for "unread"
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        collectAllNodes(row, all);
        for (AccessibilityNodeInfo n : all) {
            CharSequence desc = n.getContentDescription();
            if (desc != null && desc.toString().toLowerCase().contains("unread")) {
                String d = desc.toString();
                String[] parts = d.split("\\s+");
                for (String part : parts) {
                    if (part.matches("\\d+")) {
                        return Integer.parseInt(part);
                    }
                }
            }
        }
        return 0;
    }

    // ---------- Open first chat ----------
    public boolean openFirstChat() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        List<AccessibilityNodeInfo> names = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversations_row_contact_name");
        if (!names.isEmpty()) {
            names.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
            return true;
        }
        return false;
    }

    // ---------- Improved message reader ----------
    public String readLastMessage() {
    AccessibilityNodeInfo root = getRootInActiveWindow();
    if (root == null) return null;

    // 1) Try the known message_text ID
    List<AccessibilityNodeInfo> msgs = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/message_text");
    if (!msgs.isEmpty()) {
        // Return the last one (newest)
        AccessibilityNodeInfo last = msgs.get(msgs.size() - 1);
        CharSequence txt = last.getText();
        if (txt != null && txt.length() > 0) return txt.toString().trim();
    }

    // 2) Scan every node for resource‑id containing "message", pick newest by Y position
    List<AccessibilityNodeInfo> all = new ArrayList<>();
    collectAllNodes(root, all);
    AccessibilityNodeInfo best = null;
    int maxY = -1;
    for (AccessibilityNodeInfo node : all) {
        String rid = node.getViewIdResourceName();
        if (rid != null && rid.contains("message")) {
            CharSequence t = node.getText();
            if (t != null && t.length() > 0) {
                android.graphics.Rect rect = new android.graphics.Rect();
                node.getBoundsInScreen(rect);
                if (rect.bottom > maxY) {
                    maxY = rect.bottom;
                    best = node;
                }
            }
        }
    }
    if (best != null) {
        CharSequence t = best.getText();
        return t != null ? t.toString().trim() : null;
    }

    // 3) Last resort: return the longest text on screen that looks like a message
    String longest = null;
    for (AccessibilityNodeInfo node : all) {
        CharSequence t = node.getText();
        if (t != null && t.length() > 1) {
            String s = t.toString().trim();
            // Exclude timestamps and typing indicators
            if (!s.matches("\\d{1,2}:\\d{2}") && !s.equalsIgnoreCase("typing…")) {
                if (longest == null || s.length() > longest.length()) {
                    longest = s;
                }
            }
        }
    }
    return longest;
}

    public void openApp(String pkg) {
        try { startActivity(getPackageManager().getLaunchIntentForPackage(pkg)); } catch (Exception ignored) {}
    }

    public void goBack() { performGlobalAction(GLOBAL_ACTION_BACK); }

    // ---------- Helper methods ----------
    private void collectAllNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) collectAllNodes(node.getChild(i), out);
    }

    // ---------- Contact extraction ----------
    public List<String> extractContacts() {
        List<String> contacts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        openApp("com.whatsapp");
        try { Thread.sleep(2500); } catch (Exception ignored) {}
        AccessibilityNodeInfo fab = findElementById("com.whatsapp:id/fab");
        if (fab != null) { fab.performAction(AccessibilityNodeInfo.ACTION_CLICK); try { Thread.sleep(2000); } catch (Exception ignored) {} }
        for (int i = 0; i < 40; i++) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) break;
            List<AccessibilityNodeInfo> names = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/contact_name");
            if (names.isEmpty()) break;
            for (AccessibilityNodeInfo n : names) {
                CharSequence txt = n.getText();
                if (txt != null) {
                    String s = txt.toString().trim();
                    if (!s.isEmpty() && !seen.contains(s)) { seen.add(s); contacts.add(s); }
                }
            }
            root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            try { Thread.sleep(800); } catch (Exception ignored) {}
        }
        goBack();
        return contacts;
    }
}