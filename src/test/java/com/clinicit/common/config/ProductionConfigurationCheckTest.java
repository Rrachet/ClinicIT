package com.clinicit.common.config;

import com.clinicit.ClinicITApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionConfigurationCheckTest {

    private static MockEnvironment prod() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        return env;
    }

    private static MockEnvironment safeProd() {
        return prod()
                .withProperty("spring.datasource.url", "jdbc:postgresql://db.internal:5432/clinicit")
                .withProperty("spring.datasource.username", "clinicit_app")
                .withProperty("spring.datasource.password", "a-long-random-secret-from-the-vault")
                .withProperty("clinicit.cors.allowed-origins", "https://app.cityclinic.example")
                .withProperty("clinicit.notifications.status-link-base-url", "https://app.cityclinic.example")
                .withProperty("clinicit.notifications.provider", "none")
                .withProperty("clinicit.notifications.enabled", "false")
                .withProperty("spring.jpa.hibernate.ddl-auto", "validate");
    }

    @Test
    void aCompleteSafeConfigurationStarts() {
        assertThat(ProductionConfigurationCheck.problems(safeProd())).isEmpty();
        new ProductionConfigurationCheck().postProcessEnvironment(safeProd(), null);
    }

    @Test
    void everyMissingSettingIsReportedAtOnce() {
        List<String> problems = ProductionConfigurationCheck.problems(prod());

        assertThat(String.join("\n", problems)).contains("DB_URL", "DB_USERNAME", "DB_PASSWORD",
                "CLINICIT_CORS_ALLOWED_ORIGINS", "CLINICIT_PUBLIC_APP_URL", "CLINICIT_NOTIFICATIONS_PROVIDER");
    }

    @Test
    void unresolvedPlaceholdersCountAsMissing() {
        MockEnvironment env = safeProd().withProperty("spring.datasource.url", "${DB_URL}");
        assertThat(ProductionConfigurationCheck.problems(env)).singleElement().asString().contains("DB_URL is not set");
    }

    @Test
    void developmentCredentialsAreRefused() {
        for (String password : List.of("clinicit", "postgres", "Password")) {
            MockEnvironment env = safeProd().withProperty("spring.datasource.password", password);
            assertThat(ProductionConfigurationCheck.problems(env)).as(password)
                    .singleElement().asString().contains("development default");
        }
    }

    @Test
    void corsAndPublicUrlMustBeExplicitHttps() {
        MockEnvironment env = safeProd()
                .withProperty("clinicit.cors.allowed-origins", "https://app.example, http://other.example, *")
                .withProperty("clinicit.notifications.status-link-base-url", "http://app.example");

        assertThat(String.join("\n", ProductionConfigurationCheck.problems(env)))
                .contains("must use https: http://other.example")
                .contains("must not contain wildcards: *")
                .contains("CLINICIT_PUBLIC_APP_URL must use https");
    }

    @Test
    void theDevelopmentNotificationProviderCannotRunInProduction() {
        MockEnvironment env = safeProd()
                .withProperty("clinicit.notifications.enabled", "true")
                .withProperty("clinicit.notifications.provider", "development");
        assertThat(ProductionConfigurationCheck.problems(env)).singleElement().asString()
                .contains("development notification provider");
    }

    @Test
    void demoDataCannotBeEnabledInProduction() {
        MockEnvironment env = safeProd();
        env.setProperty("clinicit.demo.enabled", "true");
        assertThat(ProductionConfigurationCheck.problems(env)).singleElement().asString()
                .contains("CLINICIT_DEMO_ENABLED must not be set in production");
    }

    @Test
    void schemaChangesStayWithFlywayAndMlUrlMustBeHttp() {
        MockEnvironment env = safeProd()
                .withProperty("spring.jpa.hibernate.ddl-auto", "update")
                .withProperty("clinicit.prediction.ml-base-url", "ml-service:8000");
        assertThat(String.join("\n", ProductionConfigurationCheck.problems(env)))
                .contains("ddl-auto").contains("CLINICIT_ML_BASE_URL");
        assertThat(ProductionConfigurationCheck.problems(
                safeProd().withProperty("clinicit.prediction.ml-base-url", "http://ml.internal:8000"))).isEmpty();
    }

    @Test
    void otherProfilesAreNotChecked() {
        new ProductionConfigurationCheck().postProcessEnvironment(new MockEnvironment(), null);
    }

    @Test
    void theRealApplicationRefusesToStartInProdWithoutConfiguration() {
        // Fails before any bean exists: no database, migration or port is touched.
        assertThatThrownBy(() -> new SpringApplicationBuilder(ClinicITApplication.class)
                .profiles("prod")
                .properties("spring.main.web-application-type=none")
                // Command-line arguments outrank environment variables, so the result does not
                // depend on what happens to be set on the machine running the test.
                .run("--spring.datasource.password=", "--clinicit.cors.allowed-origins="))
                .hasMessageContaining("Production configuration is not safe to start")
                .hasMessageContaining("DB_PASSWORD is not set")
                .hasMessageContaining("CLINICIT_CORS_ALLOWED_ORIGINS is not set");
    }
}
