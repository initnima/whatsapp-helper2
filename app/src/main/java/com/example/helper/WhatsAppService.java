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
            // ذخیره پورت در فایل برای اسکریپت پایتون
            try (FileWriter fw = new FileWriter("/sdcard/whatsapp_helper_port.txt")) {
                fw.write(String.valueOf(port));
            } catch (IOException ignored) {}
        } catch (Exception ignored) {}
    }

    private int findAvailablePort() {
        for (int port : new int[]{8080, 8081, 8082}) {
            try {
                ServerSocket ss = new ServerSocket(port);
                ss.close();
                return port;
            } catch (IOException ignored) {}
        }
        return 8080; // fallback
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (httpServer != null) httpServer.stop();
        instance = null;
    }

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

    public String readLastMessage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        List<AccessibilityNodeInfo> msgs = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/message_text");
        if (msgs.isEmpty()) return null;
        AccessibilityNodeInfo last = msgs.get(msgs.size() - 1);
        CharSequence txt = last.getText();
        return txt != null ? txt.toString() : null;
    }

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

    public void openApp(String pkg) {
        try {
            startActivity(getPackageManager().getLaunchIntentForPackage(pkg));
        } catch (Exception ignored) {}
    }

    public void goBack() {
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    // ---------- Unread chats (JSON) ----------
    public String getUnreadChats() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "[]";
        List<AccessibilityNodeInfo> rows = new ArrayList<>();
        rows.addAll(root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/contact_row_container"));
        if (rows.isEmpty()) rows.addAll(root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversation_row"));
        if (rows.isEmpty()) {
            collectChatRows(root, rows);
        }

        JSONArray json = new JSONArray();
        for (AccessibilityNodeInfo row : rows) {
            int count = 0;
            List<AccessibilityNodeInfo> badges = row.findAccessibilityNodeInfosByViewId("com.whatsapp:id/unread_count");
            if (badges.isEmpty()) badges = row.findAccessibilityNodeInfosByViewId("com.whatsapp:id/unread_indicator");
            if (!badges.isEmpty()) {
                try { count = Integer.parseInt(badges.get(0).getText().toString().trim()); } catch (Exception ignored) {}
            }
            if (count > 0) {
                String name = "";
                try {
                    List<AccessibilityNodeInfo> names = row.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversations_row_contact_name");
                    if (!names.isEmpty()) name = names.get(0).getText().toString();
                } catch (Exception ignored) {}
                if (!name.isEmpty()) {
                    try {
                        JSONObject obj = new JSONObject();
                        obj.put("name", name);
                        obj.put("count", count);
                        json.put(obj);
                    } catch (Exception ignored) {}
                }
            }
        }
        return json.toString();
    }

    private void collectChatRows(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        if (node.isClickable() && node.getChildCount() > 0) {
            List<AccessibilityNodeInfo> names = node.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversations_row_contact_name");
            if (!names.isEmpty()) out.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) collectChatRows(node.getChild(i), out);
    }

    // ---------- Smart UI mapping ----------
    public String getUIMap() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "{}";
        Map<String, String> map = new HashMap<>();
        findSendButton(root, map);
        findInputField(root, map);
        return new JSONObject(map).toString();
    }

    private void findSendButton(AccessibilityNodeInfo node, Map<String, String> map) {
        if (node == null) return;
        if (("android.widget.ImageButton".equals(node.getClassName()) || "android.widget.Button".equals(node.getClassName())) &&
            node.getContentDescription() != null && node.getContentDescription().toString().toLowerCase().contains("send")) {
            String id = node.getViewIdResourceName();
            if (id != null && !id.isEmpty()) map.put("send", id);
        }
        for (int i = 0; i < node.getChildCount(); i++) findSendButton(node.getChild(i), map);
    }

    private void findInputField(AccessibilityNodeInfo node, Map<String, String> map) {
        if (node == null) return;
        if ("android.widget.EditText".equals(node.getClassName())) {
            String id = node.getViewIdResourceName();
            if (id != null && !id.isEmpty()) map.put("input", id);
        }
        for (int i = 0; i < node.getChildCount(); i++) findInputField(node.getChild(i), map);
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
                    if (!s.isEmpty() && !seen.contains(s)) {
                        seen.add(s);
                        contacts.add(s);
                    }
                }
            }
            root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            try { Thread.sleep(800); } catch (Exception ignored) {}
        }
        goBack();
        return contacts;
    }
}