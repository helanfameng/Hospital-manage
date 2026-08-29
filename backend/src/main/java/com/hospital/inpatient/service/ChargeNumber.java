package com.hospital.inpatient.service;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Types;

/** Shared database sequence for modern charges and modern/legacy charge reversals. */
public final class ChargeNumber {
    private ChargeNumber() {}

    public static String next(JdbcTemplate jdbc, boolean reversal) {
        return jdbc.execute((ConnectionCallback<String>) connection -> {
            try (var statement = connection.prepareCall("{CALL sp_next_charge_number(?,?)}")) {
                statement.setBoolean(1, reversal);
                statement.registerOutParameter(2, Types.VARCHAR);
                statement.execute();
                return statement.getString(2);
            }
        });
    }
}
