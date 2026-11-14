package com.migrateflow.intake;

import com.migrateflow.common.ApiException;
import org.springframework.http.HttpStatus;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict RFC 4180-style CSV parser (spec 4.1 and ADR-011). Tracks both the
 * 1-based data-row ordinal and the physical line where each record starts
 * (they differ when a quoted field embeds a newline). A malformed row fails
 * the whole upload: duplicate/blank headers, unterminated or misplaced
 * quotes, NUL bytes, and non-UTF-8 content are all rejected.
 */
public final class CsvParser {

    public record CsvRow(int dataRowNumber, int physicalLineStart, Map<String, String> values) {}
    public record ParsedCsv(List<String> headers, List<CsvRow> rows) {}

    private CsvParser() {}

    public static ApiException malformed(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "CSV_MALFORMED", message);
    }

    public static ParsedCsv parse(byte[] bytes, int maxRows, int maxColumns) {
        for (byte b : bytes) {
            if (b == 0) throw malformed("File contains a NUL byte");
        }
        String text;
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            text = decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw malformed("File is not valid UTF-8");
        }
        if (text.startsWith("\uFEFF")) text = text.substring(1); // optional UTF-8 BOM

        List<List<String>> records = new ArrayList<>();
        List<Integer> recordLines = new ArrayList<>();
        int i = 0, line = 1, n = text.length();
        while (i < n) {
            int recordStartLine = line;
            List<String> record = new ArrayList<>();
            boolean endOfRecord = false;
            while (!endOfRecord) {
                if (i >= n) { // trailing comma at EOF implies one last empty field
                    record.add("");
                    endOfRecord = true;
                    break;
                }
                StringBuilder field = new StringBuilder();
                if (text.charAt(i) == '"') {
                    i++;
                    boolean closed = false;
                    while (i < n) {
                        char c = text.charAt(i);
                        if (c == '"') {
                            if (i + 1 < n && text.charAt(i + 1) == '"') { field.append('"'); i += 2; }
                            else { closed = true; i++; break; }
                        } else {
                            if (c == '\n') line++;
                            field.append(c);
                            i++;
                        }
                    }
                    if (!closed) throw malformed("Unterminated quoted field starting at line " + recordStartLine);
                    if (i < n) {
                        char c = text.charAt(i);
                        if (c != ',' && c != '\r' && c != '\n') {
                            throw malformed("Unexpected character after closing quote at line " + line);
                        }
                    }
                } else {
                    while (i < n) {
                        char c = text.charAt(i);
                        if (c == ',' || c == '\r' || c == '\n') break;
                        if (c == '"') throw malformed("Bare quote inside unquoted field at line " + line);
                        field.append(c);
                        i++;
                    }
                }
                record.add(field.toString());
                if (i < n && text.charAt(i) == ',') {
                    i++;
                } else {
                    if (i < n && text.charAt(i) == '\r') i++;
                    if (i < n && text.charAt(i) == '\n') { i++; line++; }
                    endOfRecord = true;
                }
            }
            // Skip a single trailing empty record produced by a final newline.
            boolean blankRecord = record.size() == 1 && record.get(0).isEmpty();
            if (!(blankRecord && i >= n)) {
                records.add(record);
                recordLines.add(recordStartLine);
            }
        }
        if (records.isEmpty()) throw malformed("File is empty");

        List<String> headers = records.get(0);
        if (headers.size() > maxColumns) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CSV_TOO_MANY_COLUMNS",
                    "File has " + headers.size() + " columns; the limit is " + maxColumns);
        }
        for (String h : headers) {
            if (h == null || h.isBlank()) throw malformed("Header names must be nonblank");
        }
        if (headers.stream().distinct().count() != headers.size()) {
            throw malformed("Duplicate header names are not allowed");
        }
        int dataCount = records.size() - 1;
        if (dataCount > maxRows) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "CSV_TOO_MANY_ROWS",
                    "File has " + dataCount + " data rows; the limit is " + maxRows);
        }
        List<CsvRow> rows = new ArrayList<>();
        for (int r = 1; r < records.size(); r++) {
            List<String> rec = records.get(r);
            if (rec.size() != headers.size()) {
                throw malformed("Row at line " + recordLines.get(r) + " has " + rec.size()
                        + " fields; expected " + headers.size());
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (int c = 0; c < headers.size(); c++) values.put(headers.get(c), rec.get(c));
            rows.add(new CsvRow(r, recordLines.get(r), values));
        }
        return new ParsedCsv(List.copyOf(headers), rows);
    }
}
