package au.org.resourcework.waste_tracking_api.electricaltesting.dto;
import java.util.List;
public record ElectricalTestingSummary(String month, List<CategoryTotals> categories, long divertedToOls, long divertedToColl) {
    public record CategoryTotals(Long categoryId, String categoryName, long untestedBrokenFailed, long testedAndPriced, long stockCull) {}
}
