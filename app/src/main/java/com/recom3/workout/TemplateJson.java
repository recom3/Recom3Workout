package com.recom3.workout;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/** Strict bounded subset required by Snow3 protocol 1; duplicate decoded keys fail closed. */
final class TemplateJson {
    private final String text;
    private int position;
    private TemplateJson(String text) { this.text = text; }

    static String utf8(byte[] bytes) throws IOException {
        try {
            return Charset.forName("UTF-8").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) { throw invalid(); }
    }
    static Map<String, Object> parse(byte[] bytes, int maximum) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > maximum) throw invalid();
        TemplateJson parser = new TemplateJson(utf8(bytes));
        Map<String, Object> root = object(parser.value(0));
        parser.space();
        if (parser.position != parser.text.length()) throw invalid();
        return root;
    }
    private void space() {
        while (position < text.length() && " \t\r\n".indexOf(text.charAt(position)) >= 0) position++;
    }
    private boolean take(char token) {
        space();
        if (position < text.length() && text.charAt(position) == token) { position++; return true; }
        return false;
    }
    private void require(char token) throws IOException { if (!take(token)) throw invalid(); }
    private Object value(int depth) throws IOException {
        if (depth > 7) throw invalid();
        space();
        if (position >= text.length()) throw invalid();
        char next = text.charAt(position);
        if (next == '{') {
            position++;
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            if (take('}')) return map;
            do {
                space(); String key = string();
                if (map.size() >= 8 || map.containsKey(key)) throw invalid();
                require(':'); map.put(key, value(depth + 1));
                if (take('}')) return map;
                require(',');
            } while (true);
        }
        if (next == '[') {
            position++;
            List<Object> list = new ArrayList<Object>();
            if (take(']')) return list;
            do {
                if (list.size() >= 20) throw invalid();
                list.add(value(depth + 1));
                if (take(']')) return list;
                require(',');
            } while (true);
        }
        if (next == '"') return string();
        int start = position;
        while (position < text.length() && text.charAt(position) >= '0' && text.charAt(position) <= '9') position++;
        String number = text.substring(start, position);
        if (!number.matches("[1-9][0-9]{0,15}")) throw invalid();
        try { return Long.valueOf(number); }
        catch (NumberFormatException failure) { throw invalid(); }
    }
    private String string() throws IOException {
        if (position >= text.length() || text.charAt(position++) != '"') throw invalid();
        StringBuilder result = new StringBuilder();
        boolean ended = false;
        while (position < text.length()) {
            char c = text.charAt(position++);
            if (c == '"') { ended = true; break; }
            if (c < 32 || result.length() >= 1024) throw invalid();
            if (c == '\\') {
                if (position >= text.length()) throw invalid();
                c = text.charAt(position++);
                switch (c) {
                    case '"': case '\\': case '/': break;
                    case 'b': c = '\b'; break;
                    case 'f': c = '\f'; break;
                    case 'n': c = '\n'; break;
                    case 'r': c = '\r'; break;
                    case 't': c = '\t'; break;
                    case 'u':
                        if (position + 4 > text.length()) throw invalid();
                        int value = 0;
                        for (int i = 0; i < 4; i++) {
                            char hex = text.charAt(position++);
                            int digit = hex >= '0' && hex <= '9' ? hex - '0'
                                    : hex >= 'a' && hex <= 'f' ? hex - 'a' + 10
                                    : hex >= 'A' && hex <= 'F' ? hex - 'A' + 10 : -1;
                            if (digit < 0) throw invalid();
                            value = value * 16 + digit;
                        }
                        c = (char) value; break;
                    default: throw invalid();
                }
            }
            result.append(c);
        }
        if (!ended) throw invalid();
        for (int i = 0; i < result.length(); i++) {
            char c = result.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= result.length() || !Character.isLowSurrogate(result.charAt(i))) throw invalid();
            } else if (Character.isLowSurrogate(c)) throw invalid();
        }
        return result.toString();
    }
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) throws IOException {
        if (!(value instanceof Map)) throw invalid(); return (Map<String, Object>) value;
    }
    static List<?> array(Object value) throws IOException {
        if (!(value instanceof List)) throw invalid(); return (List<?>) value;
    }
    static long number(Object value, long maximum) throws IOException {
        if (!(value instanceof Long) || (Long) value < 1 || (Long) value > maximum) throw invalid();
        return (Long) value;
    }
    static void fields(Map<String, Object> map, String... names) throws IOException {
        if (!map.keySet().equals(new HashSet<String>(Arrays.asList(names)))) throw invalid();
    }
    static IOException invalid() { return new IOException("invalid_template"); }
}
