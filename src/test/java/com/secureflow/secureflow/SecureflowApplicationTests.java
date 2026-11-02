package com.secureflow.secureflow;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:secureflow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=validate"
})
class SecureflowApplicationTests {

	@Autowired
	private Flyway flyway;

	@Test
	void contextLoads() {
		assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("3");
	}

}
