package com.lemicare.shoppingcart.client;

import com.lemicare.shoppingcart.dto.request.CourierServiceabilityRequest;
import com.lemicare.shoppingcart.dto.response.DeliveryOption;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@FeignClient(name = "delivery-service", url = "${services.delivery.url}")
public interface DeliveryServiceClient {
    @GetMapping("/api/internal/serviceability")
    List<DeliveryOption> getAvailableCourierService(@RequestBody CourierServiceabilityRequest request);
}
