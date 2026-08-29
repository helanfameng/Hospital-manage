package com.hospital.inpatient;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SqlMirrorConsistencyTest {
    @Test
    void standaloneScriptsMatchFlywayMigrations() throws Exception {
        assertSame("schema.sql", "V1__schema.sql");
        assertSame("indexes.sql", "V2__indexes.sql");
        assertSame("views.sql", "V3__views.sql");
        assertSame("routines.sql", "V4__routines.sql");
        assertSame("seed.sql", "V5__seed.sql");
    }

    private void assertSame(String standalone, String migration) throws Exception {
        String expected = Files.readString(Path.of("..", "database", standalone)).replace("\r\n", "\n");
        String actual = Files.readString(Path.of("src", "main", "resources", "db", "migration", migration)).replace("\r\n", "\n");
        assertThat(actual).isEqualTo(expected);
    }
}
