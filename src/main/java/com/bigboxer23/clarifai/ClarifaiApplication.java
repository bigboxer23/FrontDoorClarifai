package com.bigboxer23.clarifai;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** */
@SpringBootApplication
@EnableScheduling
@OpenAPIDefinition(
		info =
				@Info(
						title = "Clarifai front door camera",
						version = "1",
						description = "Provides ability to have clarifai service analyzecontent from"
								+ " motion service on raspberry pi and send notifications via"
								+ " email/webhook and store analyzed content inAWS S3 bucket",
						contact =
								@Contact(
										name = "bigboxer23@gmail.com",
										url = "https://github.com/bigboxer23/FrontDoorClarifai")))
public class ClarifaiApplication {
	public static void main(String[] args) {
		SpringApplication.run(ClarifaiApplication.class, args);
	}

	public ClarifaiApplication() {}
}
