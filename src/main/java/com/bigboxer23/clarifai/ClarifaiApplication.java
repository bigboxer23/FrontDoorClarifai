package com.bigboxer23.clarifai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 *
 */
@SpringBootApplication
@EnableScheduling
public class ClarifaiApplication
{
	public static void main(String[] args)
	{
		SpringApplication.run(ClarifaiApplication.class, args);
	}

	public ClarifaiApplication()
	{

	}
}
