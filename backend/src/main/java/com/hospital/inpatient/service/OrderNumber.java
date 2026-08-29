package com.hospital.inpatient.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Human-readable identifier: Shanghai creation date + global database identity.
 * The sequence never resets daily. Gaps after rolled-back inserts are intentional. */
public final class OrderNumber {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuuMMdd", Locale.ROOT)
            .withZone(ZoneId.of("Asia/Shanghai"));
    private OrderNumber() {}

    public static String format(Instant createdAt, long id) {
        if (id <= 0) throw new IllegalArgumentException("Order identity must be positive");
        // Minimum width only: never truncate a large identity and cause collisions.
        return DATE.format(createdAt) + String.format(Locale.ROOT, "%08d", id);
    }
}
