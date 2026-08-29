package com.hospital.inpatient.security;

import java.util.List;

public record CurrentUser(long id, String username, List<String> roles, Long staffId, Long departmentId) {
    public CurrentUser(long id, String username, List<String> roles) { this(id, username, roles, null, null); }
}
