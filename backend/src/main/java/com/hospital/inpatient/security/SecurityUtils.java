package com.hospital.inpatient.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtils {
    private static boolean unrestricted() {
        return currentUser().roles().stream().anyMatch(java.util.Set.of("ADMIN","ADMISSION","PHARMACY","CASHIER")::contains);
    }
    public static Long doctorScope() {
        var user = currentUser();
        return !unrestricted() && user.roles().contains("DOCTOR") ? (user.staffId()==null ? -1L : user.staffId()) : null;
    }
    public static Long departmentScope() {
        var user = currentUser();
        return !unrestricted() && doctorScope()==null && user.roles().contains("NURSE") ? (user.departmentId()==null ? -1L : user.departmentId()) : null;
    }
    public static Long statisticsDepartmentScope() {
        var user = currentUser();
        return !unrestricted() && user.roles().stream().anyMatch(java.util.Set.of("DOCTOR","NURSE")::contains)
                ? (user.departmentId()==null ? -1L : user.departmentId()) : null;
    }
    private SecurityUtils() {
    }

    public static boolean pharmacyScope() {
        return currentUser().roles().stream().anyMatch(java.util.Set.of("ADMIN","PHARMACY")::contains);
    }

    public static CurrentUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser user)) {
            return new CurrentUser(0, "system", java.util.List.of());
        }
        return user;
    }
}
