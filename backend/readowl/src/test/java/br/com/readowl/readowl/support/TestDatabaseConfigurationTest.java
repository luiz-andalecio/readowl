package br.com.readowl.readowl.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDatabaseConfigurationTest {

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"readowl", "readowl_dev", "postgres", "readowl_test_copy"})
    void refusesAnyDatabaseThatIsNotExplicitlyForTests(String databaseName) {
        assertThatThrownBy(() -> TestDatabaseConfiguration.requireTestDatabase(databaseName))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptsDatabaseNamedForTests() {
        assertThatCode(() -> TestDatabaseConfiguration.requireTestDatabase("readowl_test"))
                .doesNotThrowAnyException();
    }
}
