package com.migrateflow.intake;

import com.migrateflow.common.ApiException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class CsvParserTest {

    private CsvParser.ParsedCsv parse(String text) {
        return CsvParser.parse(text.getBytes(StandardCharsets.UTF_8), 10_000, 50);
    }

    @Test
    void parsesBasicRowsAndTracksLines() {
        var parsed = parse("Cust_No,Customer_Name\nC0001,Sakura Retail\nC0002,Haruto Supplies\n");
        assertEquals(2, parsed.rows().size());
        assertEquals(1, parsed.rows().get(0).dataRowNumber());
        assertEquals(2, parsed.rows().get(0).physicalLineStart());
        assertEquals("Sakura Retail", parsed.rows().get(0).values().get("Customer_Name"));
    }

    @Test
    void acceptsBomAndQuotedNewlines() {
        var parsed = parse("\uFEFFA,B\n\"x\ny\",2\nlast,3\n");
        assertEquals(2, parsed.rows().size());
        assertEquals("x\ny", parsed.rows().get(0).values().get("A"));
        // Second record physically starts after the embedded newline.
        assertEquals(4, parsed.rows().get(1).physicalLineStart());
        assertEquals(2, parsed.rows().get(1).dataRowNumber());
    }

    @Test
    void rejectsDuplicateHeaders() {
        ApiException ex = assertThrows(ApiException.class, () -> parse("A,A\n1,2\n"));
        assertEquals("CSV_MALFORMED", ex.code());
    }

    @Test
    void rejectsBlankHeader() {
        assertThrows(ApiException.class, () -> parse("A,\n1,2\n"));
    }

    @Test
    void rejectsUnterminatedQuote() {
        assertThrows(ApiException.class, () -> parse("A,B\n\"oops,2\n"));
    }

    @Test
    void rejectsBareQuoteInUnquotedField() {
        assertThrows(ApiException.class, () -> parse("A,B\nhe\"llo,2\n"));
    }

    @Test
    void rejectsWrongFieldCount() {
        assertThrows(ApiException.class, () -> parse("A,B\n1,2,3\n"));
    }

    @Test
    void rejectsTooManyRows() {
        StringBuilder sb = new StringBuilder("A\n");
        for (int i = 0; i < 5; i++) sb.append(i).append('\n');
        ApiException ex = assertThrows(ApiException.class,
                () -> CsvParser.parse(sb.toString().getBytes(StandardCharsets.UTF_8), 3, 50));
        assertEquals("CSV_TOO_MANY_ROWS", ex.code());
    }

    @Test
    void rejectsNulBytesAndBadUtf8() {
        assertThrows(ApiException.class, () -> CsvParser.parse(new byte[]{65, 0, 66}, 10, 50));
        assertThrows(ApiException.class, () -> CsvParser.parse(new byte[]{(byte) 0xC3, 0x28}, 10, 50));
    }
}
