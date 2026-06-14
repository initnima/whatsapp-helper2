package com.example.helper;

import fi.iki.elonen.NanoHTTPD;
import java.util.Map;

public class SimpleHttpServer extends NanoHTTPD {
    public SimpleHttpServer(int port) {
        super(port);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Map<String, String> params = session.getParms();
        WhatsAppService svc = WhatsAppService.getInstance();
        if (svc == null) return newFixedLengthResponse("Service not running");

        try {
            if ("/click".equals(uri)) {
                String id = params.get("id");
                if (id != null && svc.clickById(id)) return newFixedLengthResponse("OK");
                return newFixedLengthResponse("FAIL");
            } else if ("/type".equals(uri)) {
                String text = params.get("text");
                if (text != null && svc.typeText(text)) return newFixedLengthResponse("OK");
                return newFixedLengthResponse("FAIL");
            } else if ("/read".equals(uri)) {
                String msg = svc.readLastMessage();
                return newFixedLengthResponse(msg != null ? msg : "");
            } else if ("/open".equals(uri)) {
                String pkg = params.get("pkg");
                svc.openApp(pkg != null ? pkg : "com.whatsapp");
                return newFixedLengthResponse("OK");
            } else if ("/back".equals(uri)) {
                svc.goBack();
                return newFixedLengthResponse("OK");
            } else {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Unknown command");
            }
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.getMessage());
        }
    }
}
