package com.appsmith.server.solutions.ce;

import com.appsmith.server.configurations.CommonConfig;
import com.appsmith.server.configurations.DeploymentProperties;
import com.appsmith.server.configurations.ProjectProperties;
import com.appsmith.server.configurations.SegmentConfig;
import com.appsmith.server.helpers.NetworkUtils;
import com.appsmith.server.repositories.ApplicationRepository;
import com.appsmith.server.repositories.DatasourceRepository;
import com.appsmith.server.repositories.NewActionRepository;
import com.appsmith.server.repositories.NewPageRepository;
import com.appsmith.server.repositories.UserRepository;
import com.appsmith.server.repositories.WorkspaceRepository;
import com.appsmith.server.services.ConfigService;
import com.appsmith.server.services.PermissionGroupService;
import com.appsmith.util.WebClientUtils;
import io.micrometer.observation.annotation.Observed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * This class represents a scheduled task that pings a data point indicating that this server installation is live.
 * This ping is only invoked if the Appsmith server is NOT running in Appsmith Cloud & the user has given Appsmith
 * permissions to collect anonymized data
 */
@Slf4j
@RequiredArgsConstructor
@ConditionalOnExpression("!${is.cloud-hosting:false}")
public class PingScheduledTaskCEImpl implements PingScheduledTaskCE {

    private final ConfigService configService;
    private final SegmentConfig segmentConfig;
    private final CommonConfig commonConfig;

    private final WorkspaceRepository workspaceRepository;
    private final ApplicationRepository applicationRepository;
    private final NewPageRepository newPageRepository;
    private final NewActionRepository newActionRepository;
    private final DatasourceRepository datasourceRepository;
    private final UserRepository userRepository;
    private final ProjectProperties projectProperties;
    private final DeploymentProperties deploymentProperties;
    private final NetworkUtils networkUtils;
    private final PermissionGroupService permissionGroupService;

    @Override
    public void pingSchedule() {}

    enum UserTrackingType {
        DAU,
        WAU,
        MAU
    }

    /**
     * Given a unique ID (called a `userId` here), this method hits the Segment API to save a data point on this server
     * instance being live.
     *
     * @param instanceId A unique identifier for this server instance, usually generated at the server's first start.
     * @param ipAddress  The external IP address of this instance's machine.
     * @return A publisher that yields the string response of recording the data point.
     */
    private Mono<String> doPing(String instanceId, String ipAddress) {
        // Note: Hard-coding Segment auth header and the event name intentionally. These are not intended to be
        // environment specific values, instead, they are common values for all self-hosted environments. As such, they
        // are not intended to be configurable.
        final String ceKey = segmentConfig.getCeKey();
        if (StringUtils.isEmpty(ceKey)) {
            log.error("The segment ce key is null");
            return Mono.empty();
        }

        return WebClientUtils.create("https://api.segment.io")
                .post()
                .uri("/v1/track")
                .headers(headers -> headers.setBasicAuth(ceKey, ""))
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(Map.of(
                        "userId",
                        instanceId,
                        "context",
                        Map.of("ip", ipAddress),
                        "properties",
                        Map.of("instanceId", instanceId),
                        "event",
                        "Instance Active")))
                .retrieve()
                .bodyToMono(String.class);
    }

    // Number of milliseconds between the start of each scheduled calls to this method.
    @Scheduled(initialDelay = 2 * 60 * 1000 /* two minutes */, fixedRate = 24 * 60 * 60 * 1000 /* a day */)
    @Observed(name = "pingStats")
    public void pingStats() {}

    private Mono<Map<String, Long>> getUserTrackingDetails() {

        Mono<Long> dauCountMono = userRepository
                .countByDeletedAtIsNullAndLastActiveAtGreaterThanAndIsSystemGeneratedIsNot(
                        Instant.now().minus(1, ChronoUnit.DAYS), true)
                .defaultIfEmpty(0L);
        Mono<Long> wauCountMono = userRepository
                .countByDeletedAtIsNullAndLastActiveAtGreaterThanAndIsSystemGeneratedIsNot(
                        Instant.now().minus(7, ChronoUnit.DAYS), true)
                .defaultIfEmpty(0L);
        Mono<Long> mauCountMono = userRepository
                .countByDeletedAtIsNullAndLastActiveAtGreaterThanAndIsSystemGeneratedIsNot(
                        Instant.now().minus(30, ChronoUnit.DAYS), true)
                .defaultIfEmpty(0L);

        return Mono.zip(dauCountMono, wauCountMono, mauCountMono)
                .map(tuple -> Map.of(
                        UserTrackingType.DAU.name(), tuple.getT1(),
                        UserTrackingType.WAU.name(), tuple.getT2(),
                        UserTrackingType.MAU.name(), tuple.getT3()));
    }
}
