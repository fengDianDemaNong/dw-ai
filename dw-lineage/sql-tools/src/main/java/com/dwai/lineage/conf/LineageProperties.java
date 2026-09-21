package com.dwai.lineage.conf;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lineage")
public class LineageProperties {
    /** standalone | standard | multi */
    private String runMode = "standalone";
    private String orgBaseUrl = "";
    private String publicBaseUrl = "";
    private String serviceBaseUrl = "";
    private String moduleToken = "";

    public String getRunMode() {
        return runMode;
    }

    public void setRunMode(String runMode) {
        this.runMode = runMode;
    }

    public String runMode() {
        if (runMode == null || runMode.isBlank()) return "standalone";
        String m = runMode.trim().toLowerCase();
        if ("standalone".equals(m) || "standard".equals(m) || "multi".equals(m)) return m;
        return "standalone";
    }

    public boolean isStandalone() {
        return "standalone".equals(runMode());
    }

    public boolean isStandard() {
        return "standard".equals(runMode());
    }

    public boolean isMulti() {
        return "multi".equals(runMode());
    }

    public String getOrgBaseUrl() {
        return orgBaseUrl;
    }

    public void setOrgBaseUrl(String orgBaseUrl) {
        this.orgBaseUrl = orgBaseUrl;
    }

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setPublicBaseUrl(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }

    public String getServiceBaseUrl() {
        return serviceBaseUrl;
    }

    public void setServiceBaseUrl(String serviceBaseUrl) {
        this.serviceBaseUrl = serviceBaseUrl;
    }

    public String serviceBaseUrl() {
        if (serviceBaseUrl != null && !serviceBaseUrl.isBlank()) return serviceBaseUrl.trim();
        return publicBaseUrl == null ? "" : publicBaseUrl.trim();
    }

    public String getModuleToken() {
        return moduleToken;
    }

    public void setModuleToken(String moduleToken) {
        this.moduleToken = moduleToken;
    }
}
