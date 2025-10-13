package com.lemicare.shoppingcart.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
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
public class CourierServiceabilityResponse {
    private ShiprocketStatus status;
    private ServiceabilityData data;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ShiprocketStatus {
        private Integer code;
        private String description;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ServiceabilityData {
        @JsonProperty("available_courier_companies")
        private List<AvailableCourierCompany> availableCourierCompanies;
        @JsonProperty("pickup_postcode")
        private Integer pickupPostcode;
        @JsonProperty("delivery_postcode")
        private Integer deliveryPostcode;
        // Add other fields from the data object if necessary
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class AvailableCourierCompany {
        @JsonProperty("courier_id")
        private Integer courierId;
        @JsonProperty("courier_name")
        private String courierName;
        @JsonProperty("estimate_date")
        private String estimateDate; // YYYY-MM-DD
        private BigDecimal rate;
        @JsonProperty("cod_charges")
        private BigDecimal codCharges;
        @JsonProperty("total_charges")
        private BigDecimal totalCharges;
        private String logo;
        private BigDecimal rating;
        @JsonProperty("rto_charges")
        private BigDecimal rtoCharges;
        @JsonProperty("rto_charges_percentage")
        private BigDecimal rtoChargesPercentage;
        @JsonProperty("min_weight")
        private BigDecimal minWeight;
        @JsonProperty("max_weight")
        private BigDecimal maxWeight;
        @JsonProperty("base_weight")
        private BigDecimal baseWeight;
        @JsonProperty("additional_weight_slab")
        private BigDecimal additionalWeightSlab;
        @JsonProperty("charges_per_additional_weight")
        private BigDecimal chargesPerAdditionalWeight;
        @JsonProperty("pickup_availability")
        private Boolean pickupAvailability;
        @JsonProperty("cod_availability")
        private Boolean codAvailability;
        @JsonProperty("delivery_performance")
        private DeliveryPerformance deliveryPerformance;
        @JsonProperty("expected_pickup_date")
        private String expectedPickupDate;
        @JsonProperty("volumetric_weight")
        private BigDecimal volumetricWeight;
        private String description;
        @JsonProperty("delivery_type")
        private String deliveryType;
        @JsonProperty("is_recommendation")
        private Boolean isRecommendation;
        @JsonProperty("warehouse_coverage_status")
        private Integer warehouseCoverageStatus;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class DeliveryPerformance {
        @JsonProperty("rto_percentage")
        private String rtoPercentage;
        @JsonProperty("ontime_delivery_percentage")
        private String ontimeDeliveryPercentage;
    }
}

