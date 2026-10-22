package au.org.resourcework.waste_tracking_api.whitegoods.dto;
public record WhitegoodsMonthlySummary(String month, Long totalCount, String kgPerItem, String estimatedWeight) {}
