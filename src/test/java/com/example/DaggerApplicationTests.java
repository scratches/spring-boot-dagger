package com.example;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import static org.assertj.core.api.Assertions.assertThat;

@RunWith(SpringRunner.class)
public class DaggerApplicationTests {

	private static int port;

	@BeforeClass
	public static void init() {
		Main main = DaggerMain.builder().function(new DaggerApplication())
				.args("--server.port=0").build();
		main.server().run();
		port = main.port();
	}

	@Test
	public void contextLoads() {
		assertThat(new TestRestTemplate()
				.postForEntity("http://localhost:" + port + "/", "foo", String.class)
				.getBody()).isEqualTo("FOO");
	}

}
