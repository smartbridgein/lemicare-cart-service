package com.lemicare.shoppingcart.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShippingEstimate {
    private String cartId;
    private Integer destinationPincode;
    private BigDecimal estimatedTotalShippingCost;
    private List<DeliveryOption> deliveryOptions; // List of available delivery services/rates
}
