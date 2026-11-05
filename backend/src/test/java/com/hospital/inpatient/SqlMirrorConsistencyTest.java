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
        assertSame("integrity-hardening.sql", "V6__integrity_hardening.sql");
        assertSame("nursing-observation.sql", "V7__nursing_observation.sql");
        assertSame("inpatient-result.sql", "V8__inpatient_result.sql");
        assertSame("inpatient-clinical-record.sql", "V9__inpatient_clinical_record.sql");
        assertSame("medication-schedule.sql", "V10__medication_schedule.sql");
        assertSame("medication-route-normalization.sql", "V11__medication_route_normalization.sql");
        assertSame("remove-prn-frequency.sql", "V12__remove_prn_frequency.sql");
        assertSame("charge-category-snapshot.sql", "V13__charge_category_snapshot.sql");
        assertSame("charge-catalog-snapshot.sql", "V14__charge_catalog_snapshot.sql");
    }

    private void assertSame(String standalone, String migration) throws Exception {
        String expected = Files.readString(Path.of("..", "database", standalone)).replace("\r\n", "\n");
        String actual = Files.readString(Path.of("src", "main", "resources", "db", "migration", migration)).replace("\r\n", "\n");
        assertThat(actual).isEqualTo(expected);
    }
}
