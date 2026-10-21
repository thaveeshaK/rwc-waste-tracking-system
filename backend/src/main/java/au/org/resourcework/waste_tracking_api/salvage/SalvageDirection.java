package au.org.resourcework.waste_tracking_api.salvage;

public enum SalvageDirection {
    UP, DOWN;

    public String materialContext() { return "SALVAGE_" + name(); }
}
