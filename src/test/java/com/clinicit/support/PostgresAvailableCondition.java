package com.clinicit.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Skips Postgres-backed tests when neither {@code CLINICIT_TEST_DB_URL} nor Docker
 * is available. Registered with {@code @ExtendWith}, which (unlike {@code @EnabledIf})
 * is inherited by subclasses.
 */
class PostgresAvailableCondition implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        return PostgresTestDatabase.isAvailable()
                ? ConditionEvaluationResult.enabled("PostgreSQL available")
                : ConditionEvaluationResult.disabled(
                        "No PostgreSQL: set CLINICIT_TEST_DB_URL or start Docker for Testcontainers");
    }
}
