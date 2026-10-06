package com.proyecto.tramites;

import org.springframework.boot.SpringApplication;

public class TestNovaGestApplication {

	public static void main(String[] args) {
		SpringApplication.from(NovaGestApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
