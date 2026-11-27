package com.toolhelper.sql;

import com.toolhelper.support.ApiException;
import org.junit.jupiter.api.Test;
import static com.toolhelper.sql.SqlModels.FormatRequest;
import static org.junit.jupiter.api.Assertions.*;

class SqlFormatterTest {
    private final SqlFormatter formatter = new SqlFormatter();
    private FormatRequest request(String dialect, String sql) { return new FormatRequest(dialect, "MYSQL".equals(dialect) ? "BACKSLASH" : "STANDARD", sql, "UPPER", 4); }
    @Test void formatsBasicStatementsAndKeepsLiteralWhitespace() {
        var result = formatter.format(request("MYSQL", "select name from users where note='a  b';"));
        assertTrue(result.sql().contains("FROM users"));
        assertTrue(result.sql().contains("'a  b'"));
        assertEquals(1, result.statementCount());
    }
    @Test void formatsMultipleStatementsWithoutChangingLiteralSemicolonsOrComments() {
        String input = "select 'x;y' from t; -- safe\nupdate t set a='it''s' where id=1;";
        var result = formatter.format(request("MYSQL", input));
        assertTrue(result.sql().contains("'x;y'"));
        assertTrue(result.sql().contains("-- safe"));
        assertTrue(result.sql().contains("'it''s'"));
        assertEquals(2, result.statementCount());
    }
    @Test void rejectsUnbalancedSqlAndPreservesDollarQuotedSyntax() {
        assertEquals("SQL_UNSUPPORTED_SYNTAX", assertThrows(ApiException.class,
                () -> formatter.format(request("MYSQL", "select (from t"))).code());
        String dollar = "select $$a  b$$;";
        assertTrue(formatter.format(request("POSTGRESQL", dollar)).sql().contains("$$a  b$$"));
    }
    @Test void refusesWrongDialectAndOptions() {
        assertEquals("SQL_UNSUPPORTED_DIALECT", assertThrows(ApiException.class,
                () -> formatter.format(new FormatRequest("ORACLE", "STANDARD", "select 1", "UPPER", 4))).code());
    }
    @Test void replaysCommentsAtTheirOriginalTokenBoundariesAndIsIdempotent() {
        for (String dialect : new String[]{"MYSQL", "POSTGRESQL"}) {
            String input = "-- before\nselect id /* keep  spaces */ from users -- after table\nwhere name='line1\n\tline2'; -- end";
            var first = formatter.format(request(dialect, input));
            assertTrue(java.util.regex.Pattern.compile("id\\s+/\\* keep  spaces \\*/").matcher(first.sql()).find());
            assertTrue(first.sql().contains("-- after table\n"));
            assertTrue(first.sql().contains("'line1\n\tline2'"));
            assertEquals(first.sql(), formatter.format(request(dialect, first.sql())).sql());
        }
    }
    @Test void literalAndIdentifierCaseIsNotChangedByKeywordCaseAndIndentOptions() {
        String input = "select \"Select\", E'it\\'s  \\n', $tag$a;  b$tag$ from \"Table\";";
        var result = formatter.format(new FormatRequest("POSTGRESQL", "STANDARD", input, "LOWER", 2));
        assertTrue(result.sql().contains("\"Select\""));
        assertTrue(result.sql().contains("$tag$a;  b$tag$"));
        assertTrue(result.sql().contains("E'it\\'s  \\n'"));
        assertTrue(result.sql().startsWith("select"));
    }
    @Test void bothMySqlEscapeModesAreHandledByOriginalLexicalRules() {
        String noEscape = "select 'a\\', 'b';";
        assertTrue(formatter.format(new FormatRequest("MYSQL", "NO_BACKSLASH_ESCAPES", noEscape, "UPPER", 4)).sql().contains("'a\\'"));
        assertThrows(ApiException.class, () -> formatter.format(new FormatRequest("MYSQL", "BACKSLASH", noEscape, "UPPER", 4)));
        assertTrue(formatter.format(request("MYSQL", "select 'it\\'s';")).sql().contains("'it\\'s'"));
    }
    @Test void rejectsHintsTemplatesClientCommandsPlaceholdersAndResourceOverruns() {
        for (String text : new String[]{"select /*+ hint */ 1", "/*! select 1 */", "select ?", "select ${x}", "DELIMITER $$", "select 'unclosed", "select 1 /* unclosed"})
            assertThrows(ApiException.class, () -> formatter.format(request("MYSQL", text)), text);
        assertTrue(formatter.format(request("MYSQL", "select '${literal}'")).sql().contains("'${literal}'"));
        assertThrows(ApiException.class, () -> formatter.format(request("MYSQL", "select 1;".repeat(51))));
        assertThrows(ApiException.class, () -> formatter.format(request("MYSQL", "select " + "(".repeat(65) + "1" + ")".repeat(65))));
        assertThrows(ApiException.class, () -> formatter.format(request("MYSQL", " ".repeat(262145))));
    }
}
