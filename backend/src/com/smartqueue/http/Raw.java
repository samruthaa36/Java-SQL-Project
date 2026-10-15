package com.smartqueue.http;

import java.nio.charset.StandardCharsets;

/** A non-JSON response body (for example a CSV download). */
public final class Raw {

    public final String contentType;
    public final byte[] data;
    public final String filename;

    public Raw(String contentType, byte[] data, String filename) {
        this.contentType = contentType;
        this.data = data;
        this.filename = filename;
    }

    public static Raw csv(String text, String filename) {
        // UTF-8 BOM so Excel opens non-ASCII names correctly
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[body.length + 3];
        out[0] = (byte) 0xEF;
        out[1] = (byte) 0xBB;
        out[2] = (byte) 0xBF;
        System.arraycopy(body, 0, out, 3, body.length);
        return new Raw("text/csv; charset=utf-8", out, filename);
    }
}
