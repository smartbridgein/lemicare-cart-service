package com.lemicare.shoppingcart.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryOption {
    private String courierId; // New: To hold Shiprocket's courier ID
    private String carrierName;
    private String serviceType;
    private String description;
    private BigDecimal cost;
    private String currency;
    private Integer estimatedDeliveryDays;
    private String etdRaw; // New: To hold the raw ETD string if needed for debugging
    private BigDecimal minWeight; // New: Min weight for this option
    private BigDecimal maxWeight; // New: Max weight for this option
    private boolean withBestOption;
}
