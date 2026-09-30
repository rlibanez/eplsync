package com.rlibanez.eplsync.importer;

import com.opencsv.CSVParserBuilder;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

/** Streaming repair for the known extra closing quote in the final Portada column. */
final class CoverCsvReader extends Reader {
    private final BufferedReader source;
    private String buffer = "";
    private int position;
    private boolean header = true;
    private int coverColumn = -1;
    private int column;
    private boolean quoted;

    CoverCsvReader(BufferedReader source) {
        this.source = source;
    }

    private String process(String line) throws IOException {
        if (header) {
            header = false;
            var fields = new CSVParserBuilder().build().parseLine(line);
            if (fields.length > 0 && "Portada".equalsIgnoreCase(fields[fields.length - 1].strip())) {
                coverColumn = fields.length - 1;
            }
            return line;
        }
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (!quoted && column == coverColumn
                        && line.substring(i).matches("\"https?://[^\"\\r\\n]+\"\"")) {
                    line = line.substring(0, line.length() - 1);
                }
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                column++;
            }
        }
        // Keep state across physical lines: quoted synopses can contain newlines.
        if (!quoted) column = 0;
        return line;
    }

    @Override
    public int read(char[] target, int offset, int length) throws IOException {
        java.util.Objects.checkFromIndexSize(offset, length, target.length);
        if (length == 0) return 0;
        if (position == buffer.length()) {
            String line = source.readLine();
            if (line == null) return -1;
            buffer = process(line) + "\n";
            position = 0;
        }
        int count = Math.min(length, buffer.length() - position);
        buffer.getChars(position, position + count, target, offset);
        position += count;
        return count;
    }

    @Override
    public void close() throws IOException {
        source.close();
    }
}
