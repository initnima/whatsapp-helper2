package com.example.helper;

import fi.iki.elonen.NanoHTTPD;
import org.json.JSONArray;
import java.util.Map;

public class SimpleHttpServer extends NanoHTTPD {
    public SimpleHttpServer(int port) { super(port); }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Map<String, String> params = session.getParms();
        WhatsAppService svc = WhatsAppService.getInstance();
        if (svc == null) return newFixedLengthResponse("Service not running");

        try {
            if ("/ping".equals(uri)) return newFixedLengthResponse("OK");
            else if ("/click".equals(uri)) {
                String id = params.get("id");
                return newFixedLengthResponse(id != null && svc.clickById(id) ? "OK" : "FAIL");
            } else if ("/type".equals(uri)) {
                String text = params.get("text");
                return newFixedLengthResponse(text != null && svc.typeText(text) ? "OK" : "FAIL");
            } else if ("/read".equals(uri)) {
                String msg = svc.readLastMessage();
                return newFixedLengthResponse(msg != null ? msg : "");
            } else if ("/open".equals(uri)) {
                String pkg = params.get("pkg");
                svc.openApp(pkg != null ? pkg : "com.whatsapp");
                return newFixedLengthResponse("OK");
            } else if ("/back".equals(uri)) {
                svc.goBack(); return newFixedLengthResponse("OK");
            } else if ("/clickchat".equals(uri)) {
                String name = params.get("name");
                return newFixedLengthResponse(name != null && svc.clickChatByName(name) ? "OK" : "FAIL");
            } else if ("/chats".equals(uri)) {
                return newFixedLengthResponse(svc.getUnreadChats());
            } else if ("/openfirstchat".equals(uri)) {
                return newFixedLengthResponse(svc.openFirstChat() ? "OK" : "FAIL");
            } else if ("/listchats".equals(uri)) {
                return newFixedLengthResponse(svc.getAllChats());
            } else if ("/contacts".equals(uri)) {
                return newFixedLengthResponse(new JSONArray(svc.extractContacts()).toString());
            } else if ("/clickdesc".equals(uri)) {
                String desc = params.get("desc");
                return newFixedLengthResponse(desc != null && svc.clickByContentDesc(desc) ? "OK" : "FAIL");
            } else if ("/bounds".equals(uri)) {
                String id = params.get("id");
                String bounds = svc.getBoundsById(id);
                return newFixedLengthResponse(bounds != null ? bounds : "null");
            } else if ("/focusinput".equals(uri)) {
                return newFixedLengthResponse(svc.focusInputField() ? "OK" : "FAIL");
            } else if ("/state".equals(uri)) {
                return newFixedLengthResponse(svc.getCurrentState());
            } else if ("/dump".equals(uri)) {
                return newFixedLengthResponse(svc.dumpUI());
            } else {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Unknown command");
            }
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.getMessage());
        }
    }
}