package com.hospital.inpatient.service;

import java.util.UUID;

/** Application-generated immutable charge identifiers. */
public final class ChargeNumber {
    private ChargeNumber() {}

    public static String next(boolean reversal) {
        return (reversal ? "CR" : "C") + UUID.randomUUID().toString().replace("-", "").substring(0, 30);
    }
}
