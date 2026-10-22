package au.org.resourcework.waste_tracking_api.electricaltesting;
public enum TallyType {
    UNTESTED_BROKEN_FAILED, TESTED_AND_PRICED, STOCK_CULL, DIVERTED_TO_OLS, DIVERTED_TO_COLL;
    public boolean requiresCategory() { return this != DIVERTED_TO_OLS && this != DIVERTED_TO_COLL; }
}
