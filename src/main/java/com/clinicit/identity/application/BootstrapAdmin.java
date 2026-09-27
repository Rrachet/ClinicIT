package com.clinicit.identity.application;

import com.clinicit.clinic.domain.Clinic;
import com.clinicit.clinic.domain.ClinicRepository;
import com.clinicit.identity.domain.Role;
import com.clinicit.identity.domain.UserAccount;
import com.clinicit.identity.domain.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Creates the first clinic and its admin on an empty installation, so there is someone
 * who can log in and create everyone else. Configured through environment variables
 * (never committed): CLINICIT_BOOTSTRAP_ADMIN_EMAIL, CLINICIT_BOOTSTRAP_ADMIN_PASSWORD,
 * CLINICIT_BOOTSTRAP_CLINIC_NAME, optionally CLINICIT_BOOTSTRAP_ADMIN_NAME and
 * CLINICIT_BOOTSTRAP_CLINIC_TIMEZONE. Does nothing once any user exists.
 */
@Component
public class BootstrapAdmin implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

    @ConfigurationProperties("clinicit.bootstrap")
    public record Properties(
            String clinicName,
            String clinicTimezone,
            String adminEmail,
            String adminPassword,
            String adminName
    ) {}

    private final Properties properties;
    private final ClinicRepository clinics;
    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactions;

    public BootstrapAdmin(
            Properties properties,
            ClinicRepository clinics,
            UserAccountRepository users,
            PasswordEncoder passwordEncoder,
            TransactionTemplate transactions
    ) {
        this.properties = properties;
        this.clinics = clinics;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.transactions = transactions;
    }

    /**
     * Not transactional itself: without bootstrap settings (the normal case) startup does not
     * touch the database here at all.
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(properties.adminEmail())) {
            return;
        }
        transactions.executeWithoutResult(status -> bootstrap());
    }

    private void bootstrap() {
        if (users.count() > 0) {
            log.info("Bootstrap admin configured but users already exist; skipping");
            return;
        }
        if (!StringUtils.hasText(properties.adminPassword()) || properties.adminPassword().length() < 12
                || !StringUtils.hasText(properties.clinicName())) {
            throw new IllegalStateException(
                    "Bootstrap needs clinicit.bootstrap.clinic-name and an admin-password of at least 12 characters");
        }

        Clinic clinic = new Clinic();
        clinic.setName(properties.clinicName());
        if (StringUtils.hasText(properties.clinicTimezone())) {
            clinic.setTimezone(java.time.ZoneId.of(properties.clinicTimezone()).getId());
        }
        clinic = clinics.save(clinic);

        users.save(new UserAccount(
                clinic.getId(),
                properties.adminEmail(),
                passwordEncoder.encode(properties.adminPassword()),
                StringUtils.hasText(properties.adminName()) ? properties.adminName() : "Clinic Admin",
                Role.ADMIN,
                null
        ));
        log.info("Bootstrapped clinic {} with its first admin account", clinic.getId());
    }
}
