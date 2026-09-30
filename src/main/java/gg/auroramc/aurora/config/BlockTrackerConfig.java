package gg.auroramc.aurora.config;

import lombok.Getter;
import lombok.Setter;

@Getter
public class BlockTrackerConfig {
    private Boolean enabled = true;
    @Setter
    private Boolean cleanerEnabled = true;
    private String storageType = "file";
}
