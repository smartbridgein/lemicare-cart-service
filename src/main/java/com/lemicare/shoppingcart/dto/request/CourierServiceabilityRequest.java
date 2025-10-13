package com.lemicare.shoppingcart.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CourierServiceabilityRequest {

    private String organizationId;

    private Integer pickup_postcode; // Matches Shiprocket's snake_case
    private Integer delivery_postcode; // Matches Shiprocket's snake_case
    private BigDecimal weight;
    private Integer cod; // 0 for prepaid, 1 for COD
    private String order_id; // Matches Shiprocket's snake_case
    private BigDecimal length;
    private BigDecimal width;
    private BigDecimal height;
    private BigDecimal declared_value; // Matches Shiprocket's snake_case
    private Integer is_international; // Matches Shiprocket's snake_case (0 or 1)
    private Integer items_count; // Matches Shiprocket's snake_case
    private String mode;
    private String currency;
    private Integer seller_id; // Matches Shiprocket's snake_case
    // private BigDecimal TotalVolumeCubicCm;
}
