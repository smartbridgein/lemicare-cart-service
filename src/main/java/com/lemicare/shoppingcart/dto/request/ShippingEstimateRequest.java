package com.lemicare.shoppingcart.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShippingEstimateRequest {
    @NotBlank(message = "Destination Pincode is required")
    private int destinationPincode;
    // Add userId/guestId here for context if needed, but often derived from headers/cookies
    // private String userId;
    // private String guestId;
}
