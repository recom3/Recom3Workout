package com.recom3.workout;

import java.io.IOException;
import java.util.*;

/** Single-line RFC 4180 fields; exercise metadata excludes line breaks. */
final class CsvFields {
    static String line(String... fields) {
        StringBuilder text = new StringBuilder();
        for (String field : fields) {
            if (text.length() > 0) text.append(',');
            text.append('"').append(field.replace("\"", "\"\"")).append('"');
        }
        return text.append('\n').toString();
    }
    static String[] read(String line) throws IOException {
        List<String> fields = new ArrayList<String>(); int at = 0;
        while (at < line.length()) {
            if (line.charAt(at++) != '"') throw new IOException("Invalid CSV field");
            StringBuilder field = new StringBuilder(); boolean closed = false;
            while (at < line.length()) {
                char c = line.charAt(at++);
                if (c == '"') {
                    if (at < line.length() && line.charAt(at) == '"') { at++; field.append('"'); }
                    else { closed = true; break; }
                } else field.append(c);
            }
            if (!closed || at < line.length() && line.charAt(at++) != ',') throw new IOException("Invalid CSV delimiter");
            fields.add(field.toString());
        }
        return fields.toArray(new String[fields.size()]);
    }
    // Prefix only potentially active spreadsheet formulas; recovery removes exactly this added prefix.
    static String display(String text) {
        return text.length() > 0 && "=+-@'".indexOf(text.charAt(0)) >= 0 ? "'" + text : text;
    }
    static String undisplay(String text) { return text.startsWith("'") ? text.substring(1) : text; }
}
