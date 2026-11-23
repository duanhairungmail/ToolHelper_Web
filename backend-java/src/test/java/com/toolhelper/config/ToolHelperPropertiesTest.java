package com.toolhelper.config;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToolHelperPropertiesTest {
    @Test void absentDataRootUsesWorkingDirectoryInsteadOfUserProfile() {
        Path expected = Path.of(".runtime", "local-data").toAbsolutePath().normalize();
        assertEquals(expected, properties(null).root());
        assertEquals(expected, properties("").root());
        assertEquals(expected, properties("  ").root());
    }

    @Test void explicitDataRootStillSupportsRelativeAndAbsolutePaths() {
        assertEquals(Path.of(".runtime", "custom-data").toAbsolutePath().normalize(),
                properties(".runtime/custom-data").root());
        Path absolute = Path.of("target", "custom-data").toAbsolutePath().normalize();
        assertEquals(absolute, properties(absolute.toString()).root());
    }

    private static ToolHelperProperties properties(String root) {
        return new ToolHelperProperties(root, List.of("http://127.0.0.1:5173"),
                List.of("5.12.6"), 4_000_000, 8_000_000, 4_000_000,
                1000, 12, 4_000_000, 7, 1000);
    }
}
