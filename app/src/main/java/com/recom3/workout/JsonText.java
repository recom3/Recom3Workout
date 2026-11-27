package com.recom3.workout;

final class JsonText {
    static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 32) out.append(String.format(java.util.Locale.US, "\\u%04x", (int)c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
}
