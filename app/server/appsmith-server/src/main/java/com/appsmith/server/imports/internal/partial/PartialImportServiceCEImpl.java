package com.appsmith.server.imports.internal.partial;

import com.appsmith.external.constants.AnalyticsEvents;
import com.appsmith.external.helpers.Stopwatch;
import com.appsmith.external.models.CreatorContextType;
import com.appsmith.external.models.Datasource;
import com.appsmith.server.acl.AclPermission;
import com.appsmith.server.actioncollections.base.ActionCollectionService;
import com.appsmith.server.applications.base.ApplicationService;
import com.appsmith.server.constants.FieldName;
import com.appsmith.server.domains.ActionCollection;
import com.appsmith.server.domains.Application;
import com.appsmith.server.domains.CustomJSLib;
import com.appsmith.server.domains.Layout;
import com.appsmith.server.domains.NewAction;
import com.appsmith.server.domains.NewPage;
import com.appsmith.server.domains.Plugin;
import com.appsmith.server.domains.User;
import com.appsmith.server.domains.Workspace;
import com.appsmith.server.dtos.ApplicationJson;
import com.appsmith.server.dtos.BuildingBlockDTO;
import com.appsmith.server.dtos.BuildingBlockImportDTO;
import com.appsmith.server.dtos.BuildingBlockResponseDTO;
import com.appsmith.server.dtos.ImportingMetaDTO;
import com.appsmith.server.dtos.MappedImportableResourcesDTO;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.helpers.ImportArtifactPermissionProvider;
import com.appsmith.server.imports.importable.ImportableService;
import com.appsmith.server.imports.internal.ImportService;
import com.appsmith.server.newactions.base.NewActionService;
import com.appsmith.server.newpages.base.NewPageService;
import com.appsmith.server.refactors.applications.RefactoringService;
import com.appsmith.server.repositories.PermissionGroupRepository;
import com.appsmith.server.services.AnalyticsService;
import com.appsmith.server.services.ApplicationPageService;
import com.appsmith.server.services.ApplicationTemplateService;
import com.appsmith.server.services.SessionUserService;
import com.appsmith.server.services.WorkspaceService;
import com.appsmith.server.solutions.ActionPermission;
import com.appsmith.server.solutions.ApplicationPermission;
import com.appsmith.server.solutions.DatasourcePermission;
import com.appsmith.server.solutions.PagePermission;
import com.appsmith.server.solutions.WorkspacePermission;
import com.appsmith.server.widgets.refactors.WidgetRefactorUtil;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.multipart.Part;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Slf4j
public class PartialImportServiceCEImpl implements PartialImportServiceCE {

    private final ImportService importService;
    private final WorkspaceService workspaceService;
    private final ApplicationService applicationService;
    private final AnalyticsService analyticsService;
    private final DatasourcePermission datasourcePermission;
    private final WorkspacePermission workspacePermission;
    private final ApplicationPermission applicationPermission;
    private final PagePermission pagePermission;
    private final ActionPermission actionPermission;
    private final SessionUserService sessionUserService;
    private final TransactionalOperator transactionalOperator;
    private final PermissionGroupRepository permissionGroupRepository;
    private final ImportableService<Plugin> pluginImportableService;
    private final ImportableService<NewPage> newPageImportableService;
    private final ImportableService<CustomJSLib> customJSLibImportableService;
    private final ImportableService<Datasource> datasourceImportableService;
    private final ImportableService<NewAction> newActionImportableService;
    private final ImportableService<ActionCollection> actionCollectionImportableService;
    private final NewPageService newPageService;
    private final RefactoringService refactoringService;
    private final ApplicationTemplateService applicationTemplateService;
    private final WidgetRefactorUtil widgetRefactorUtil;
    private final ApplicationPageService applicationPageService;
    private final NewActionService newActionService;
    private final ActionCollectionService actionCollectionService;

    @Override
    public Mono<Application> importResourceInPage(
            String workspaceId, String applicationId, String pageId, String branchName, Part file) {
        return importService
                .extractArtifactExchangeJson(file)
                .flatMap(artifactExchangeJson -> {
                    if (artifactExchangeJson instanceof ApplicationJson
                            && isImportableResource((ApplicationJson) artifactExchangeJson)) {
                        return importResourceInPage(
                                workspaceId, applicationId, pageId, branchName, (ApplicationJson) artifactExchangeJson);
                    } else {
                        return Mono.error(
                                new AppsmithException(
                                        AppsmithError.GENERIC_JSON_IMPORT_ERROR,
                                        "The file is not compatible with the current partial import operation. Please check the file and try again."));
                    }
                })
                .map(BuildingBlockImportDTO::getApplication);
    }

    private boolean isImportableResource(ApplicationJson artifactExchangeJson) {
        return artifactExchangeJson.getExportedApplication() == null
                && artifactExchangeJson.getPageList() == null
                && artifactExchangeJson.getModifiedResources() == null;
    }

    private Mono<BuildingBlockImportDTO> importResourceInPage(
            String workspaceId,
            String applicationId,
            String pageId,
            String branchName,
            ApplicationJson applicationJson) {
        MappedImportableResourcesDTO mappedImportableResourcesDTO = new MappedImportableResourcesDTO();

        Mono<String> branchedPageIdMono =
                newPageService.findBranchedPageId(branchName, pageId, AclPermission.MANAGE_PAGES);

        Mono<User> currUserMono = sessionUserService.getCurrentUser();

        // Extract file and get App Json
        Mono<Application> partiallyImportedAppMono = getImportApplicationPermissions()
                .flatMap(permissionProvider -> {
                    // Set Application in App JSON, remove the pages other than the one to be imported in
                    // Set the current page in the JSON to be imported
                    // Debug and get the value from getImportApplicationMono method if any difference
                    // Modify the Application set in JSON to be imported

                    Mono<Workspace> workspaceMono = workspaceService
                            .findById(workspaceId, permissionProvider.getRequiredPermissionOnTargetWorkspace())
                            .switchIfEmpty(Mono.defer(() -> {
                                log.error(
                                        "No workspace found with id: {} and permission: {}",
                                        workspaceId,
                                        permissionProvider.getRequiredPermissionOnTargetWorkspace());
                                return Mono.error(new AppsmithException(
                                        AppsmithError.ACL_NO_RESOURCE_FOUND, FieldName.WORKSPACE, workspaceId));
                            }))
                            .cache();

                    ImportingMetaDTO importingMetaDTO = new ImportingMetaDTO(
                            workspaceId,
                            FieldName.APPLICATION,
                            applicationId,
                            branchName,
                            false,
                            true,
                            permissionProvider,
                            null);

                    // Get the Application from DB
                    Mono<Application> importedApplicationMono = applicationService
                            .findByBranchNameAndDefaultApplicationId(
                                    branchName,
                                    applicationId,
                                    permissionProvider.getRequiredPermissionOnTargetApplication())
                            .cache();

                    return newPageService
                            .findByBranchNameAndDefaultPageId(branchName, pageId, AclPermission.MANAGE_PAGES)
                            .flatMap(page -> {
                                Layout layout =
                                        page.getUnpublishedPage().getLayouts().get(0);
                                return refactoringService.getAllExistingEntitiesMono(
                                        page.getId(), CreatorContextType.PAGE, layout.getId(), true);
                            })
                            .flatMap(nameSet -> {
                                // Fetch name of the existing resources in the page to avoid name clashing
                                Map<String, String> nameMap =
                                        nameSet.stream().collect(Collectors.toMap(name -> name, name -> name));
                                mappedImportableResourcesDTO.setRefactoringNameReference(nameMap);
                                return importedApplicationMono;
                            })
                            .flatMap(application -> {
                                applicationJson.setExportedApplication(application);
                                return Mono.just(applicationJson);
                            })
                            // Import Custom Js Lib and Datasource
                            .then(getApplicationImportableEntities(
                                    importingMetaDTO,
                                    mappedImportableResourcesDTO,
                                    workspaceMono,
                                    importedApplicationMono,
                                    applicationJson))
                            .thenReturn("done")
                            // Update the pageName map for actions and action collection
                            .then(paneNameMapForActionAndActionCollectionInAppJson(
                                    branchedPageIdMono, applicationJson, mappedImportableResourcesDTO))
                            .thenReturn("done")
                            // Import Actions and action collection
                            .then(getActionAndActionCollectionImport(
                                    importingMetaDTO,
                                    mappedImportableResourcesDTO,
                                    workspaceMono,
                                    importedApplicationMono,
                                    applicationJson))
                            .thenReturn("done")
                            .flatMap(result -> {
                                Application application = applicationJson.getExportedApplication();
                                // Keep existing JS Libs and add the imported ones
                                application
                                        .getUnpublishedCustomJSLibs()
                                        .addAll(new HashSet<>(mappedImportableResourcesDTO.getInstalledJsLibsList()));
                                if (mappedImportableResourcesDTO.getActionResultDTO() == null) {
                                    return applicationService.update(application.getId(), application);
                                }
                                return newActionImportableService
                                        .updateImportedEntities(
                                                application, importingMetaDTO, mappedImportableResourcesDTO)
                                        .then(newPageImportableService.updateImportedEntities(
                                                application, importingMetaDTO, mappedImportableResourcesDTO))
                                        .thenReturn(application);
                            });
                })
                .flatMap(application -> {
                    Map<String, Object> fieldNameValueMap = Map.of(
                            FieldName.UNPUBLISHED_JS_LIBS_IDENTIFIER_IN_APPLICATION_CLASS,
                            application.getUnpublishedCustomJSLibs());
                    return applicationService
                            .update(applicationId, fieldNameValueMap, branchName)
                            .then(Mono.just(application));
                })
                // Update the refactored names of the actions and action collections in the DSL bindings
                .flatMap(application -> {
                    // Partial export can have no pages
                    if (applicationJson.getPageList() == null) {
                        return Mono.just(application);
                    }
                    Stopwatch processStopwatch1 = new Stopwatch("Refactoring the widget in DSL ");
                    // The building block is stored as a page in an application
                    final JsonNode dsl = widgetRefactorUtil.convertDslStringToJsonNode(applicationJson
                            .getPageList()
                            .get(0)
                            .getUnpublishedPage()
                            .getLayouts()
                            .get(0)
                            .getDsl());
                    return Flux.fromIterable(mappedImportableResourcesDTO
                                    .getRefactoringNameReference()
                                    .keySet())
                            .filter(name -> !name.equals(mappedImportableResourcesDTO
                                    .getRefactoringNameReference()
                                    .get(name)))
                            .flatMap(name -> {
                                String refactoredName = mappedImportableResourcesDTO
                                        .getRefactoringNameReference()
                                        .get(name);
                                return widgetRefactorUtil.refactorNameInDsl(
                                        dsl,
                                        name,
                                        refactoredName,
                                        applicationPageService.getEvaluationVersion(),
                                        Pattern.compile(name));
                            })
                            .collectList()
                            .flatMap(refactoredDsl -> {
                                processStopwatch1.stopAndLogTimeInMillis();
                                applicationJson.setWidgets(dsl.toString());
                                return Mono.just(application);
                            });
                })
                .as(transactionalOperator::transactional);

        // Send Analytics event
        return partiallyImportedAppMono.zipWith(currUserMono).flatMap(tuple -> {
            Application application = tuple.getT1();
            User user = tuple.getT2();
            final Map<String, Object> eventData = Map.of(FieldName.APPLICATION, application);

            final Map<String, Object> data = Map.of(
                    FieldName.APPLICATION_ID, application.getId(),
                    FieldName.WORKSPACE_ID, application.getWorkspaceId(),
                    FieldName.EVENT_DATA, eventData);
            BuildingBlockImportDTO buildingBlockImportDTO = new BuildingBlockImportDTO();
            buildingBlockImportDTO.setApplication(application);
            buildingBlockImportDTO.setWidgetDsl(applicationJson.getWidgets());
            buildingBlockImportDTO.setRefactoredEntityNameMap(
                    mappedImportableResourcesDTO.getRefactoringNameReference());

            return analyticsService
                    .sendEvent(AnalyticsEvents.PARTIAL_IMPORT.getEventName(), user.getUsername(), data)
                    .thenReturn(buildingBlockImportDTO);
        });
    }

    private Mono<ImportArtifactPermissionProvider> getImportApplicationPermissions() {
        return permissionGroupRepository.getCurrentUserPermissionGroups().flatMap(userPermissionGroups -> {
            ImportArtifactPermissionProvider permissionProvider = ImportArtifactPermissionProvider.builder(
                            applicationPermission,
                            pagePermission,
                            actionPermission,
                            datasourcePermission,
                            workspacePermission)
                    .requiredPermissionOnTargetWorkspace(workspacePermission.getReadPermission())
                    .requiredPermissionOnTargetArtifact(applicationPermission.getEditPermission())
                    .permissionRequiredToCreateDatasource(true)
                    .permissionRequiredToEditDatasource(true)
                    .currentUserPermissionGroups(userPermissionGroups)
                    .build();
            return Mono.just(permissionProvider);
        });
    }

    private Mono<Void> getApplicationImportableEntities(
            ImportingMetaDTO importingMetaDTO,
            MappedImportableResourcesDTO mappedImportableResourcesDTO,
            Mono<Workspace> workspaceMono,
            Mono<Application> importedApplicationMono,
            ApplicationJson applicationJson) {
        Mono<Void> pluginMono = pluginImportableService.importEntities(
                importingMetaDTO,
                mappedImportableResourcesDTO,
                workspaceMono,
                importedApplicationMono,
                applicationJson,
                true);

        Mono<Void> datasourceMono = datasourceImportableService.importEntities(
                importingMetaDTO,
                mappedImportableResourcesDTO,
                workspaceMono,
                importedApplicationMono,
                applicationJson,
                true);

        Mono<Void> customJsLibMono = customJSLibImportableService.importEntities(
                importingMetaDTO, mappedImportableResourcesDTO, null, null, applicationJson);

        return pluginMono.then(datasourceMono).then(customJsLibMono).then();
    }

    private Mono<Void> getActionAndActionCollectionImport(
            ImportingMetaDTO importingMetaDTO,
            MappedImportableResourcesDTO mappedImportableResourcesDTO,
            Mono<Workspace> workspaceMono,
            Mono<Application> importedApplicationMono,
            ApplicationJson applicationJson) {
        Mono<Void> actionMono = newActionImportableService.importEntities(
                importingMetaDTO,
                mappedImportableResourcesDTO,
                workspaceMono,
                importedApplicationMono,
                applicationJson);

        Mono<Void> actionCollectionMono = actionCollectionImportableService.importEntities(
                importingMetaDTO,
                mappedImportableResourcesDTO,
                workspaceMono,
                importedApplicationMono,
                applicationJson);

        return actionMono.then(actionCollectionMono).then();
    }

    private Mono<String> paneNameMapForActionAndActionCollectionInAppJson(
            Mono<String> branchedPageIdMono,
            ApplicationJson applicationJson,
            MappedImportableResourcesDTO mappedImportableResourcesDTO) {
        return branchedPageIdMono.flatMap(
                pageId -> newPageService.findById(pageId, Optional.empty()).flatMap(newPage -> {
                    String pageName = newPage.getUnpublishedPage().getName();
                    // update page name reference with newPage
                    Map<String, NewPage> pageNameMap = new HashMap<>();
                    pageNameMap.put(pageName, newPage);
                    mappedImportableResourcesDTO.setContextMap(pageNameMap);

                    if (applicationJson.getActionList() == null) {
                        return Mono.just(pageName);
                    }

                    applicationJson.getActionList().forEach(action -> {
                        action.getPublishedAction().setPageId(pageName);
                        action.getUnpublishedAction().setPageId(pageName);
                        if (action.getPublishedAction().getCollectionId() != null) {
                            String collectionName = action.getPublishedAction()
                                    .getCollectionId()
                                    .split("_")[1];
                            action.getPublishedAction().setCollectionId(pageName + "_" + collectionName);
                            action.getUnpublishedAction().setCollectionId(pageName + "_" + collectionName);
                        }

                        String actionName = action.getId().split("_")[1];
                        action.setId(pageName + "_" + actionName);
                        action.setGitSyncId(null);
                    });

                    if (applicationJson.getActionCollectionList() == null) {
                        return Mono.just(pageName);
                    }
                    applicationJson.getActionCollectionList().forEach(actionCollection -> {
                        actionCollection.getUnpublishedCollection().setPageId(pageName);
                        if (actionCollection.getPublishedCollection() != null) {
                            actionCollection.getPublishedCollection().setPageId(pageName);
                        }
                        String collectionName = actionCollection.getId().split("_")[1];
                        actionCollection.setId(pageName + "_" + collectionName);
                        actionCollection.setGitSyncId(null);
                    });
                    return Mono.just(pageName);
                }));
    }

    @Override
    public Mono<BuildingBlockResponseDTO> importBuildingBlock(BuildingBlockDTO buildingBlockDTO, String branchName) {
        Mono<ApplicationJson> applicationJsonMono =
                applicationTemplateService.getApplicationJsonFromTemplate(buildingBlockDTO.getTemplateId());

        Stopwatch processStopwatch = new Stopwatch("Download Content from Cloud service");
        return applicationJsonMono.flatMap(applicationJson -> {
            processStopwatch.stopAndLogTimeInMillis();
            Stopwatch processStopwatch1 = new Stopwatch("Importing resource in db ");
            return this.importResourceInPage(
                            buildingBlockDTO.getWorkspaceId(),
                            buildingBlockDTO.getApplicationId(),
                            buildingBlockDTO.getPageId(),
                            branchName,
                            applicationJson)
                    .flatMap(buildingBlockImportDTO -> {
                        processStopwatch1.stopAndLogTimeInMillis();
                        // Fetch layout and get new onPageLoadActions
                        // This data is not present in a client, since these are created
                        // after importing the block
                        BuildingBlockResponseDTO buildingBlockResponseDTO = new BuildingBlockResponseDTO();
                        buildingBlockResponseDTO.setWidgetDsl(buildingBlockImportDTO.getWidgetDsl());
                        buildingBlockResponseDTO.setOnPageLoadActions(new ArrayList<>());

                        Set<String> newOnPageLoadActionNames = new HashSet<>();
                        applicationJson
                                .getPageList()
                                .get(0)
                                .getPublishedPage()
                                .getLayouts()
                                .get(0)
                                .getLayoutOnLoadActions()
                                .forEach(dslExecutableDTOS -> {
                                    dslExecutableDTOS.forEach(dslExecutableDTO -> {
                                        if (dslExecutableDTO.getName() != null) {
                                            // Use the refactored names to get the correct ids
                                            if (buildingBlockImportDTO
                                                            .getRefactoredEntityNameMap()
                                                            .get(dslExecutableDTO.getName())
                                                    != null) {
                                                dslExecutableDTO.setName(buildingBlockImportDTO
                                                        .getRefactoredEntityNameMap()
                                                        .get(dslExecutableDTO.getName()));
                                            }
                                            newOnPageLoadActionNames.add(
                                                    dslExecutableDTO.getName().contains(".")
                                                            ? dslExecutableDTO.getName()
                                                                    .split("\\.")[0]
                                                            : dslExecutableDTO.getName());
                                            buildingBlockResponseDTO
                                                    .getOnPageLoadActions()
                                                    .add(dslExecutableDTO);
                                        }
                                    });
                                });

                        // Fetch all actions and action collections and update the onPageLoadActions with correct ids
                        return actionCollectionService
                                .findByPageId(buildingBlockDTO.getPageId())
                                .collectList()
                                .zipWith(newActionService
                                        .findByPageId(buildingBlockDTO.getPageId())
                                        .collectList())
                                .flatMap(tuple -> {
                                    List<ActionCollection> actionCollections = tuple.getT1();
                                    List<NewAction> newActions = tuple.getT2();

                                    actionCollections.forEach(actionCollection -> {
                                        if (newOnPageLoadActionNames.contains(actionCollection
                                                .getUnpublishedCollection()
                                                .getName())) {
                                            buildingBlockResponseDTO
                                                    .getOnPageLoadActions()
                                                    .forEach(dslExecutableDTO -> {
                                                        if (dslExecutableDTO.getCollectionId() != null) {
                                                            String name = dslExecutableDTO.getCollectionId()
                                                                    .split("_")[1];
                                                            if (name.equals(actionCollection
                                                                    .getUnpublishedCollection()
                                                                    .getName())) {
                                                                dslExecutableDTO.setId(actionCollection.getId());
                                                                dslExecutableDTO.setCollectionId(
                                                                        actionCollection.getId());
                                                            }
                                                        }
                                                    });
                                        }
                                    });

                                    newActions.forEach(newAction -> {
                                        if (newOnPageLoadActionNames.contains(
                                                newAction.getUnpublishedAction().getName())) {
                                            buildingBlockResponseDTO
                                                    .getOnPageLoadActions()
                                                    .forEach(dslExecutableDTO -> {
                                                        if (dslExecutableDTO
                                                                .getName()
                                                                .equals(newAction
                                                                        .getUnpublishedAction()
                                                                        .getName())) {
                                                            dslExecutableDTO.setId(newAction.getId());
                                                        }
                                                    });
                                        }
                                    });

                                    return Mono.just(buildingBlockResponseDTO);
                                });
                    });
        });
    }
}
