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

    // ---------- Basic helpers ----------
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

    /** Opens a chat by contact name (exact match on the contact name element). */
    public boolean clickChatByName(String name) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        List<AccessibilityNodeInfo> chats = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversations_row_contact_name");
        for (AccessibilityNodeInfo chat : chats) {
            if (chat.getText() != null && chat.getText().toString().equals(name)) {
                chat.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                return true;
            }
        }
        return false;
    }

    // ---------- Robust message reader ----------
    public String readLastMessage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;

        List<AccessibilityNodeInfo> msgs = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/message_text");
        if (!msgs.isEmpty()) {
            CharSequence txt = msgs.get(msgs.size() - 1).getText();
            if (txt != null && txt.length() > 0) return txt.toString().trim();
        }

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
        if (best != null) return best.getText().toString().trim();

        String longest = null;
        for (AccessibilityNodeInfo node : all) {
            CharSequence t = node.getText();
            if (t != null && t.length() > 1) {
                String s = t.toString().trim();
                if (!s.matches("\\d{1,2}:\\d{2}") && !s.equalsIgnoreCase("typing…")) {
                    if (longest == null || s.length() > longest.length()) longest = s;
                }
            }
        }
        return longest;
    }

    public void openApp(String pkg) {
        try { startActivity(getPackageManager().getLaunchIntentForPackage(pkg)); } catch (Exception ignored) {}
    }

    public void goBack() { performGlobalAction(GLOBAL_ACTION_BACK); }

    // ---------- Unread detection ----------
    public String getUnreadChats() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "[]";

        List<AccessibilityNodeInfo> allNodes = new ArrayList<>();
        collectAllNodes(root, allNodes);

        List<AccessibilityNodeInfo> badges = new ArrayList<>();
        for (AccessibilityNodeInfo node : allNodes) {
            CharSequence txt = node.getText();
            if (txt != null && txt.toString().trim().matches("\\d+")) { badges.add(node); continue; }
            CharSequence desc = node.getContentDescription();
            if (desc != null && desc.toString().toLowerCase().contains("unread")) badges.add(node);
        }

        JSONArray result = new JSONArray();
        Set<String> used = new HashSet<>();

        for (AccessibilityNodeInfo badge : badges) {
            int count = 0;
            CharSequence txt = badge.getText();
            if (txt != null && txt.toString().trim().matches("\\d+")) {
                count = Integer.parseInt(txt.toString().trim());
            } else if (badge.getContentDescription() != null) {
                String d = badge.getContentDescription().toString();
                String[] parts = d.split("\\s+");
                for (String part : parts) if (part.matches("\\d+")) { count = Integer.parseInt(part); break; }
            }
            if (count == 0) continue;

            AccessibilityNodeInfo parent = badge.getParent();
            while (parent != null) {
                String name = findContactNameInRow(parent);
                if (name != null && !used.contains(name)) {
                    used.add(name);
                    try { result.put(new JSONObject().put("name", name).put("count", count)); } catch (Exception ignored) {}
                    break;
                }
                parent = parent.getParent();
            }
        }
        return result.toString();
    }

    public boolean openFirstChat() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        List<AccessibilityNodeInfo> names = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversations_row_contact_name");
        if (!names.isEmpty()) { names.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK); return true; }
        return false;
    }

    public String getAllChats() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "[]";
        List<AccessibilityNodeInfo> names = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversations_row_contact_name");
        JSONArray arr = new JSONArray();
        Set<String> used = new HashSet<>();
        for (AccessibilityNodeInfo n : names) {
            CharSequence txt = n.getText();
            if (txt != null) {
                String name = txt.toString().trim();
                if (!name.isEmpty() && !used.contains(name)) { used.add(name); arr.put(name); }
            }
        }
        return arr.toString();
    }

    // ---------- UI state ----------
    public String getCurrentState() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "unknown";
        List<AccessibilityNodeInfo> inputs = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/entry");
        if (!inputs.isEmpty()) return "chat_open";
        List<AccessibilityNodeInfo> contacts = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversations_row_contact_name");
        if (!contacts.isEmpty()) return "chat_list";
        return "other";
    }

    // ---------- Focus input (robust) ----------
    public boolean focusInputField() {
        for (int attempt = 0; attempt < 5; attempt++) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) { sleep(300); continue; }
            List<AccessibilityNodeInfo> inputs = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/entry");
            if (inputs.isEmpty()) inputs = findAllEditTexts(root);
            if (!inputs.isEmpty()) {
                AccessibilityNodeInfo input = inputs.get(0);
                input.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                sleep(200);
                input.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                return true;
            }
            sleep(500);
        }
        return false;
    }

    // ---------- Click by content description ----------
    public boolean clickByContentDesc(String desc) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(desc);
        if (nodes.isEmpty()) nodes = findNodesByContentDescription(root, desc);
        for (AccessibilityNodeInfo node : nodes) {
            if (node.isClickable()) { node.performAction(AccessibilityNodeInfo.ACTION_CLICK); return true; }
        }
        return false;
    }

    private List<AccessibilityNodeInfo> findNodesByContentDescription(AccessibilityNodeInfo root, String desc) {
        List<AccessibilityNodeInfo> result = new ArrayList<>();
        if (root == null) return result;
        if (desc.equals(root.getContentDescription())) result.add(root);
        for (int i = 0; i < root.getChildCount(); i++)
            result.addAll(findNodesByContentDescription(root.getChild(i), desc));
        return result;
    }

    // ---------- Get bounds ----------
    public String getBoundsById(String id) {
        AccessibilityNodeInfo node = findElementById(id);
        if (node != null) {
            android.graphics.Rect rect = new android.graphics.Rect();
            node.getBoundsInScreen(rect);
            return rect.left + "," + rect.top + "," + rect.right + "," + rect.bottom;
        }
        return null;
    }

    // ---------- Dump all visible UI elements ----------
    public String dumpUI() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "[]";
        List<AccessibilityNodeInfo> all = new ArrayList<>();
        collectAllNodes(root, all);
        JSONArray arr = new JSONArray();
        for (AccessibilityNodeInfo node : all) {
            try {
                JSONObject obj = new JSONObject();
                obj.put("resource-id", node.getViewIdResourceName());
                obj.put("class", node.getClassName() != null ? node.getClassName().toString() : "");
                obj.put("text", node.getText() != null ? node.getText().toString() : "");
                obj.put("content-desc", node.getContentDescription() != null ? node.getContentDescription().toString() : "");
                obj.put("clickable", node.isClickable());
                android.graphics.Rect rect = new android.graphics.Rect();
                node.getBoundsInScreen(rect);
                obj.put("bounds", rect.left + "," + rect.top + "," + rect.right + "," + rect.bottom);
                arr.put(obj);
            } catch (Exception ignored) {}
        }
        return arr.toString();
    }

    private void sleep(long ms) { try { Thread.sleep(ms); } catch (Exception ignored) {} }

    private void collectAllNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) collectAllNodes(node.getChild(i), out);
    }

    private String findContactNameInRow(AccessibilityNodeInfo row) {
        List<AccessibilityNodeInfo> children = new ArrayList<>();
        collectAllNodes(row, children);
        String best = null;
        for (AccessibilityNodeInfo child : children) {
            CharSequence txt = child.getText();
            if (txt != null) {
                String s = txt.toString().trim();
                if (s.length() > 1 && !s.matches("\\d+")) {
                    if (best == null || s.length() > best.length()) best = s;
                }
            }
        }
        return best;
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