package com.hospital.inpatient.service;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class OrderNumberTest {
    @Test void dateAndPaddedDatabaseSequenceAreReadable() {
        assertThat(OrderNumber.format(Instant.parse("2026-08-28T13:00:00Z"),303))
                .isEqualTo("2026082800000303");
    }
    @Test void businessDateUsesShanghaiMidnight() {
        assertThat(OrderNumber.format(Instant.parse("2026-08-28T15:59:59Z"),1)).startsWith("20260828");
        assertThat(OrderNumber.format(Instant.parse("2026-08-28T16:00:00Z"),2)).startsWith("20260829");
    }
    @Test void identitiesAreNotTruncatedWhenTheyOutgrowEightDigits() {
        assertThat(OrderNumber.format(Instant.parse("2026-08-28T13:00:00Z"),100000001))
                .isEqualTo("20260828100000001");
        assertThat(OrderNumber.format(Instant.parse("2026-08-28T13:00:00Z"),Long.MAX_VALUE))
                .matches("[0-9]{27}");
    }
    @Test void invalidIdentityIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> OrderNumber.format(Instant.EPOCH,0));
    }
}
