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
    private int recordChars;

    CoverCsvReader(BufferedReader source) {
        this.source = source;
    }

    private String process(String line) throws IOException {
        if (header) {
            header = false;
            if (line.chars().filter(c -> c == ',').count() >= CatalogImportLimits.COLUMNS)
                throw new com.rlibanez.eplsync.exception.CatalogValidationException("El CSV supera el máximo de 64 columnas");
            var fields = new CSVParserBuilder().build().parseLine(line);
            if (fields.length > 0 && "Portada".equalsIgnoreCase(fields[fields.length - 1].strip())) {
                coverColumn = fields.length - 1;
            }
            return line;
        }
        for (int i = 0; i < line.length(); i++) {
            if ((i & 1023) == 0) CatalogOperationBudget.check();
            char c = line.charAt(i);
            if (c == '"') {
                if (!quoted && column == coverColumn
                        && (line.startsWith("\"http://", i) || line.startsWith("\"https://", i))
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
                if (column >= CatalogImportLimits.COLUMNS) throw new com.rlibanez.eplsync.exception.CatalogValidationException("El CSV supera el máximo de 64 columnas");
            }
        }
        // Keep state across physical lines: quoted synopses can contain newlines.
        if (!quoted) column = 0;
        return line;
    }

    @Override
    public int read(char[] target, int offset, int length) throws IOException {
        CatalogOperationBudget.check();
        java.util.Objects.checkFromIndexSize(offset, length, target.length);
        if (length == 0) return 0;
        if (position == buffer.length()) {
            String line = readBoundedLine();
            if (line == null) return -1;
            recordChars += line.length() + 1;
            if (recordChars > CatalogImportLimits.RECORD_CHARS)
                throw new com.rlibanez.eplsync.exception.CatalogValidationException("Una fila del CSV supera el máximo de 1048576 caracteres");
            buffer = process(line) + "\n";
            if (!quoted) recordChars = 0;
            position = 0;
        }
        int count = Math.min(length, buffer.length() - position);
        buffer.getChars(position, position + count, target, offset);
        position += count;
        return count;
    }

    private String readBoundedLine() throws IOException {
        var line = new StringBuilder();
        for (int c; (c = source.read()) != -1;) {
            if ((line.length() & 2047) == 0) CatalogOperationBudget.check();
            if (Thread.currentThread().isInterrupted())
                throw new com.rlibanez.eplsync.exception.CatalogValidationException("Procesamiento del CSV interrumpido");
            if (c == '\n') return line.toString();
            if (c == '\r') {
                source.mark(1);
                if (source.read() != '\n') source.reset();
                return line.toString();
            }
            if (line.length() >= CatalogImportLimits.RECORD_CHARS)
                throw new com.rlibanez.eplsync.exception.CatalogValidationException("Una fila del CSV supera el máximo de 1048576 caracteres");
            if (c == 0) throw new com.rlibanez.eplsync.exception.CatalogValidationException("El CSV contiene caracteres nulos");
            line.append((char) c);
        }
        return line.isEmpty() ? null : line.toString();
    }

    @Override
    public void close() throws IOException {
        source.close();
    }
}
