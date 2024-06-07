package com.appsmith.server.services.ce;

import com.appsmith.server.configurations.CommonConfig;
import com.appsmith.server.constants.FieldName;
import com.appsmith.server.domains.UsagePulse;
import com.appsmith.server.dtos.UsagePulseDTO;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.repositories.UsagePulseRepository;
import com.appsmith.server.services.ConfigService;
import com.appsmith.server.services.SessionUserService;
import com.appsmith.server.services.TenantService;
import com.appsmith.server.services.UserService;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import static java.lang.Boolean.FALSE;

@RequiredArgsConstructor
public class UsagePulseServiceCEImpl implements UsagePulseServiceCE {

    private final UsagePulseRepository repository;

    private final SessionUserService sessionUserService;

    private final UserService userService;

    private final TenantService tenantService;

    private final ConfigService configService;

    private final CommonConfig commonConfig;

    /**
     * To create a usage pulse
     *
     * @param usagePulseDTO UsagePulseDTO
     * @return Mono of UsagePulse
     */
    @Override
    public Mono<UsagePulse> createPulse(UsagePulseDTO usagePulseDTO) {
        if (null == usagePulseDTO.getViewMode()) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.VIEW_MODE));
        } else if (FALSE.equals(usagePulseDTO.getViewMode()) && usagePulseDTO.getAnonymousUserId() != null) {
            // We don't expect anonymous user to have access to edit mode
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.ANONYMOUS_USER_ID));
        }

        return Mono.just(new UsagePulse());
    }

    /**
     * To save usagePulse to the database
     *
     * @param usagePulse UsagePulse
     * @return Mono of UsagePulse
     */
    public Mono<UsagePulse> save(UsagePulse usagePulse) {
        return repository.save(usagePulse);
    }
}
