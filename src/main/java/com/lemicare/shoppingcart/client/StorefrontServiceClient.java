package com.lemicare.shoppingcart.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "storefront-service", url = "${services.cms.url}")
public interface StorefrontServiceClient {

    // DTO for product details from Storefront Service
    record ProductDetailsResponse(String productId, String name, String imageUrl, double price) {}

    @GetMapping("/api/internal/storefront/{orgId}/product/{productId}/details")
    ProductDetailsResponse getProductDetails(@PathVariable("orgId") String orgId, @PathVariable("productId") String productId);
}
