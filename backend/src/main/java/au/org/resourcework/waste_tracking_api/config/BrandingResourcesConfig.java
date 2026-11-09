package au.org.resourcework.waste_tracking_api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Share the original branding files without duplicating them in frontend/public. */
@Configuration
public class BrandingResourcesConfig implements WebMvcConfigurer {
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/branding/**").addResourceLocations("classpath:/img/");
    }
}
