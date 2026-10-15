package com.smartqueue.service;

import com.smartqueue.http.ApiException;

import java.util.regex.Pattern;

/** Server-side input validation (the browser checks are only a convenience). */
public final class Validate {

    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$");
    private static final Pattern PHONE = Pattern.compile("^[0-9+\\-() ]{7,20}$");
    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final Pattern PREFIX = Pattern.compile("^[A-Z]{1,3}$");
    private static final Pattern ICON = Pattern.compile("^[a-z0-9\\-]{2,30}$");

    private Validate() {
    }

    private static boolean hasControlChars(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isISOControl(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    public static String text(String raw, String label, int min, int max) {
        String s = raw == null ? "" : raw.trim();
        if (s.length() < min) {
            throw ApiException.badRequest(label + (min <= 1 ? " is required." : " must be at least " + min + " characters."));
        }
        if (s.length() > max) {
            throw ApiException.badRequest(label + " must be at most " + max + " characters.");
        }
        if (hasControlChars(s)) {
            throw ApiException.badRequest(label + " contains invalid characters.");
        }
        return s;
    }

    /** Optional free text: returns null when blank. */
    public static String optional(String raw, String label, int max) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        return text(raw, label, 1, max);
    }

    public static String email(String raw) {
        String s = text(raw, "Email", 5, 150).toLowerCase();
        if (!EMAIL.matcher(s).matches()) {
            throw ApiException.badRequest("Please enter a valid email address.");
        }
        return s;
    }

    public static String phone(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String s = raw.trim();
        if (!PHONE.matcher(s).matches()) {
            throw ApiException.badRequest("Phone number may contain digits, spaces, + - ( ) and be 7-20 characters long.");
        }
        return s;
    }

    public static String password(String raw) {
        if (raw == null || raw.length() < 8) {
            throw ApiException.badRequest("Password must be at least 8 characters long.");
        }
        if (raw.length() > 100) {
            throw ApiException.badRequest("Password is too long (max 100 characters).");
        }
        boolean letter = false;
        boolean digit = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isLetter(c)) {
                letter = true;
            }
            if (Character.isDigit(c)) {
                digit = true;
            }
        }
        if (!letter || !digit) {
            throw ApiException.badRequest("Password must contain at least one letter and one number.");
        }
        return raw;
    }

    public static String color(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "#4f46e5";
        }
        String s = raw.trim();
        if (!COLOR.matcher(s).matches()) {
            throw ApiException.badRequest("Colour must look like #4f46e5.");
        }
        return s.toLowerCase();
    }

    public static String prefix(String raw) {
        String s = raw == null ? "" : raw.trim().toUpperCase();
        if (!PREFIX.matcher(s).matches()) {
            throw ApiException.badRequest("Token prefix must be 1-3 letters (A-Z).");
        }
        return s;
    }

    public static String icon(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "ticket";
        }
        String s = raw.trim().toLowerCase();
        if (!ICON.matcher(s).matches()) {
            throw ApiException.badRequest("Invalid icon name.");
        }
        return s;
    }

    public static int rangeInt(Integer v, String label, int min, int max) {
        if (v == null) {
            throw ApiException.badRequest(label + " is required.");
        }
        if (v < min || v > max) {
            throw ApiException.badRequest(label + " must be between " + min + " and " + max + ".");
        }
        return v;
    }
}
