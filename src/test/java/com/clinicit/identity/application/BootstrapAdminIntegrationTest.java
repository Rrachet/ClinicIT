package com.clinicit.identity.application;

import com.clinicit.identity.domain.Role;
import com.clinicit.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BootstrapAdminIntegrationTest extends PostgresIntegrationTest {

    @Autowired org.springframework.transaction.support.TransactionTemplate tx;

    private void run(BootstrapAdmin.Properties properties) {
        BootstrapAdmin bootstrap = new BootstrapAdmin(properties, clinics, users, passwordEncoder, tx);
        tx.executeWithoutResult(status -> bootstrap.run(new DefaultApplicationArguments()));
    }

    @Test
    void createsFirstClinicAndAdminOnEmptyInstallation() {
        run(new BootstrapAdmin.Properties("First Clinic", "Asia/Kolkata", "Owner@First.test", PASSWORD, "Owner"));

        var admin = users.findByEmail("owner@first.test").orElseThrow();
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(clinics.findById(admin.getClinicId()).orElseThrow().getName()).isEqualTo("First Clinic");
        assertThat(authService.login("owner@first.test", PASSWORD, "127.0.0.1").accessToken()).isNotBlank();
    }

    @Test
    void doesNothingOnceUsersExistOrWhenNotConfigured() {
        run(new BootstrapAdmin.Properties(null, null, null, null, null));
        assertThat(users.count()).isZero();

        staff(clinic("Existing"), Role.ADMIN, null, "existing@x.test");
        run(new BootstrapAdmin.Properties("Another", null, "second@x.test", PASSWORD, null));
        assertThat(users.findByEmail("second@x.test")).isEmpty();
    }

    @Test
    void refusesWeakBootstrapPassword() {
        assertThatThrownBy(() -> run(new BootstrapAdmin.Properties("Clinic", null, "a@x.test", "short", null)))
                .isInstanceOf(IllegalStateException.class);
    }
}
