package com.wsms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class PortalCustomizationCreateDto {

    @NotBlank(message = "Layout name is required")
    @Size(max = 100, message = "Layout name must not exceed 100 characters")
    private String name;

    @NotBlank(message = "Header text is required")
    @Size(max = 255, message = "Header text must not exceed 255 characters")
    private String headerText;

    @NotBlank(message = "Footer text is required")
    @Size(max = 255, message = "Footer text must not exceed 255 characters")
    private String footerText;

    @NotBlank(message = "Body content is required")
    private String bodyContent;

    @NotBlank(message = "Logo URL is required")
    private String logoUrl;

    public PortalCustomizationCreateDto() {}

    public PortalCustomizationCreateDto(String name, String headerText, String footerText, String bodyContent, String logoUrl) {
        this.name = name;
        this.headerText = headerText;
        this.footerText = footerText;
        this.bodyContent = bodyContent;
        this.logoUrl = logoUrl;
    }

    // Getters and Setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getHeaderText() { return headerText; }
    public void setHeaderText(String headerText) { this.headerText = headerText; }

    public String getFooterText() { return footerText; }
    public void setFooterText(String footerText) { this.footerText = footerText; }

    public String getBodyContent() { return bodyContent; }
    public void setBodyContent(String bodyContent) { this.bodyContent = bodyContent; }

    public String getLogoUrl() { return logoUrl; }
    public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }
}
