package com.lemicare.shoppingcart.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
public class DeliveryItemDTO {

    @NotBlank(message = "Product ID is required for delivery item")
    private String productId;

    // SKU is often more specific for shipping/inventory
    private String sku;

    @Min(value = 1, message = "Quantity must be at least 1")
    private int quantity;

    @NotNull(message = "Item weight per unit (Kg) is required")
    @Min(value = 0, message = "Weight per unit cannot be negative")
    private BigDecimal weightPerUnitKg;

    // Optional: Dimensions per unit, if needed for volumetric weight calculations
    @Min(value = 0, message = "Length per unit cannot be negative")
    private BigDecimal lengthCm; // Centimeters
    @Min(value = 0, message = "Width per unit cannot be negative")
    private BigDecimal widthCm;  // Centimeters
    @Min(value = 0, message = "Height per unit cannot be negative")
    private BigDecimal heightCm; // Centimeters

    // Optional: Value of the item for insurance/customs
    private BigDecimal itemValue;
    private String currency;
}
