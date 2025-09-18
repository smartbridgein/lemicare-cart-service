package com.lemicare.shoppingcart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients
public class LemicareShoppingcartApplication {

	public static void main(String[] args) {
		SpringApplication.run(LemicareShoppingcartApplication.class, args);
	}

}
