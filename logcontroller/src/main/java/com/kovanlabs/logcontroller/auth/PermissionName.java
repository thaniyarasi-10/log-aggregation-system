package com.kovanlabs.logcontroller.auth;

public final class PermissionName {

    public static final String LOGS_READ = "logs:read";
    public static final String LOGS_WRITE = "logs:write";
    public static final String METRICS_READ = "metrics:read";
    public static final String ALERTS_READ = "alerts:read";
    public static final String SERVICES_READ = "services:read";
    public static final String USERS_MANAGE = "users:manage";
    public static final String SERVICES_MANAGE = "services:manage";

    private PermissionName() {
    }
}
