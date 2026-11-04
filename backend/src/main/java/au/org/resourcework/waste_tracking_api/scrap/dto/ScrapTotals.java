package au.org.resourcework.waste_tracking_api.scrap.dto;
public record ScrapTotals(String reportingMonth,
    String ironAndSteelWeight,
    String aluminiumWeight,
    String otherNonFerrousWeight,
    String mixedMetalsWeight,
    String ewasteWeight, String totalWeight) {}
