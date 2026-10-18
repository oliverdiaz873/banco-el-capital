package com.bancoelcapital.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

/**
 * Profile-independence of the management exposure contract (ADR-12 Acceptance Supplement, D9). It
 * inherits every HTTP check from {@link ActuatorExposureTest} and reruns them with the {@code test}
 * profile active, which declares no management configuration: health stays the only reachable
 * endpoint and the correlation behaviour is unchanged, so no profile widens the exposure.
 */
@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:actuator-exposure-test-profile;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
class ActuatorExposureUnderTestProfileTest extends ActuatorExposureTest {

  @Autowired Environment environment;

  @Test
  void runsWithTheTestProfileActiveAndItsOwnConfiguration() {
    assertThat(environment.getActiveProfiles()).contains("test");
    assertThat(environment.getProperty("spring.datasource.url"))
        .isEqualTo("jdbc:h2:mem:actuator-exposure-test-profile;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
  }
}
