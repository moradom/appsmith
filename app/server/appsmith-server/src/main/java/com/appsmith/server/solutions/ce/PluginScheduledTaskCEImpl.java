package com.appsmith.server.solutions.ce;

import com.appsmith.server.helpers.PluginScheduledTaskUtils;
import com.appsmith.server.services.ConfigService;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * This class represents a scheduled task that pings cloud services for any updates in available plugins.
 */
@Slf4j
@RequiredArgsConstructor
public class PluginScheduledTaskCEImpl implements PluginScheduledTaskCE {

    private final PluginScheduledTaskUtils pluginScheduledTaskUtils;
    private final ConfigService configService;

    @Override
    public void updateRemotePlugins() {}

    @AllArgsConstructor
    @Getter
    @EqualsAndHashCode
    public static class PluginIdentifier {
        String pluginName;
        String version;
    }
}
