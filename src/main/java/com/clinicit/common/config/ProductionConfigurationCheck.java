package com.clinicit.common.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Refuses to start the {@code prod} profile with unsafe or incomplete configuration, and
 * lists every problem at once. It runs before any bean is created, so nothing (not even the
 * database migration) happens with a bad configuration.
 *
 * <p>Checked: database credentials are set and are not a known development default; CORS
 * origins and the public app URL are set and use HTTPS; the development notification
 * provider (which records instead of sending) is not active; schema changes stay with
 * Flyway ({@code ddl-auto} validate or none); the ML service URL, if any, is an http(s) URL.
 */
public class ProductionConfigurationCheck implements EnvironmentPostProcessor {

    static final Set<String> DEVELOPMENT_PASSWORDS = Set.of("clinicit", "postgres", "password", "secret", "changeme", "admin");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) return;
        List<String> problems = problems(environment);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Production configuration is not safe to start (profile 'prod'):\n - "
                    + String.join("\n - ", problems) + "\nSee .env.example and docs/OPERATIONS.md.");
        }
    }

    static List<String> problems(ConfigurableEnvironment env) {
        List<String> problems = new ArrayList<>();

        String url = value(env, "spring.datasource.url");
        String user = value(env, "spring.datasource.username");
        String password = value(env, "spring.datasource.password");
        if (url == null) problems.add("DB_URL is not set");
        if (user == null) problems.add("DB_USERNAME is not set");
        if (password == null) {
            problems.add("DB_PASSWORD is not set");
        } else if (DEVELOPMENT_PASSWORDS.contains(password.toLowerCase(Locale.ROOT))) {
            problems.add("DB_PASSWORD is a development default; use a real secret");
        }

        String origins = value(env, "clinicit.cors.allowed-origins");
        if (origins == null) {
            problems.add("CLINICIT_CORS_ALLOWED_ORIGINS is not set (the frontend's exact https origin)");
        } else {
            for (String origin : origins.split(",")) {
                String o = origin.trim();
                if (o.isEmpty()) continue;
                if (o.contains("*")) problems.add("CLINICIT_CORS_ALLOWED_ORIGINS must not contain wildcards: " + o);
                else if (!o.startsWith("https://")) problems.add("CLINICIT_CORS_ALLOWED_ORIGINS must use https: " + o);
            }
        }

        if ("true".equalsIgnoreCase(value(env, "clinicit.demo.enabled"))) {
            problems.add("CLINICIT_DEMO_ENABLED must not be set in production: demo data is for evaluation only");
        }

        String publicUrl = value(env, "clinicit.notifications.status-link-base-url");
        if (publicUrl == null) {
            problems.add("CLINICIT_PUBLIC_APP_URL is not set (used in patients' status links)");
        } else if (!publicUrl.startsWith("https://")) {
            problems.add("CLINICIT_PUBLIC_APP_URL must use https: " + publicUrl);
        }

        boolean notificationsEnabled = !"false".equalsIgnoreCase(value(env, "clinicit.notifications.enabled"));
        String provider = value(env, "clinicit.notifications.provider");
        if (provider == null) {
            problems.add("CLINICIT_NOTIFICATIONS_PROVIDER is not set (a real provider, or 'none' with "
                    + "CLINICIT_NOTIFICATIONS_ENABLED=false)");
        } else if (notificationsEnabled && "development".equalsIgnoreCase(provider)) {
            problems.add("the development notification provider records messages instead of sending them; configure "
                    + "a real provider, or set CLINICIT_NOTIFICATIONS_ENABLED=false");
        }

        String ddl = value(env, "spring.jpa.hibernate.ddl-auto");
        if (ddl != null && !Set.of("validate", "none").contains(ddl)) {
            problems.add("spring.jpa.hibernate.ddl-auto must be 'validate' or 'none' (schema changes go through Flyway)");
        }

        String ml = value(env, "clinicit.prediction.ml-base-url");
        if (ml != null && !isHttpUrl(ml)) {
            problems.add("CLINICIT_ML_BASE_URL must be an http(s) URL, or empty to disable the ML service: " + ml);
        }
        return problems;
    }

    /** The resolved, non-blank value, or null if unset, blank or an unresolvable ${…} placeholder. */
    private static String value(ConfigurableEnvironment env, String key) {
        try {
            String value = env.getProperty(key);
            return value == null || value.isBlank() ? null : value.trim();
        } catch (IllegalArgumentException unresolvedPlaceholder) {
            return null;
        }
    }

    private static boolean isHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
