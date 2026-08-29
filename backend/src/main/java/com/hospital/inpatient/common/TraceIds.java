package com.hospital.inpatient.common;

import org.slf4j.MDC;

import java.util.UUID;

public final class TraceIds {
    private TraceIds() {
    }

    public static String current() {
        String value = MDC.get("traceId");
        return value == null ? UUID.randomUUID().toString().replace("-", "") : value;
    }
}

