package com.lemicare.shoppingcart.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryQuoteRequest {

    @NotBlank(message = "Organization ID is required")
    private String organizationId;

    @NotBlank(message = "Postal code is required")
    // Example regex for basic validation, adjust as needed for specific countries
    @Pattern(regexp = "^[a-zA-Z0-9 -]+$", message = "Invalid pin code format")
    private String sourcePincode;

    @NotBlank(message = "Postal code is required")
    // Example regex for basic validation, adjust as needed for specific countries
    @Pattern(regexp = "^[a-zA-Z0-9 -]+$", message = "Invalid postal code format")
    private String destinationPincode;

   /* @NotNull(message = "Origin address is required")
    @Valid // This ensures validation rules within DeliveryAddressDTO are applied
    private DeliveryAddressDTO originAddress;*/

   /* @NotNull(message = "Destination address is required")
    @Valid // This ensures validation rules within DeliveryAddressDTO are applied
    private DeliveryAddressDTO destinationAddress;*/

    @NotNull(message = "Items list cannot be null")
    @Size(min = 1, message = "At least one item must be provided for a delivery quote")
    @Valid // This ensures validation rules within DeliveryItemDTO are applied
    private List<DeliveryItemDTO> items;

    // Optional: Total weight/volume if pre-calculated, or can be derived from items
    private BigDecimal totalWeightKg;
    private BigDecimal totalVolumeCubicCm;

    // Optional: Reference ID, e.g., cartId or orderId if this is for an existing entity
    private String referenceId;

    // Optional: Service type if specific delivery options are requested (e.g., "standard", "express")
    private String serviceType;

    private int numberOfPackages;

    // Optional: Desired delivery date/time range
    // private String desiredDeliveryDate;
    // private String desiredDeliveryTimeSlot;
}
