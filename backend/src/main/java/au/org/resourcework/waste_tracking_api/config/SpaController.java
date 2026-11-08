package au.org.resourcework.waste_tracking_api.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Only known client-side routes fall back to the SPA; API/missing asset paths never do. */
@Controller
public class SpaController {
    @GetMapping({"/admin/settings/container-types", "/admin/settings/material-categories",
            "/admin/settings/users", "/admin/settings/organisation", "/admin/settings/reporting",
            "/admin/settings/whitegoods"})
    public String settings() { return "forward:/index.html"; }
}
