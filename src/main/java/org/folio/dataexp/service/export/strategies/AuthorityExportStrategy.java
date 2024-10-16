package org.folio.dataexp.service.export.strategies;

import static java.lang.String.format;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.maxBy;
import static java.util.stream.Collectors.toList;
import static org.folio.dataexp.util.ErrorCode.ERROR_MESSAGE_PROFILE_USED_ONLY_FOR_NON_DELETED;
import static org.folio.dataexp.util.ErrorCode.ERROR_MESSAGE_USED_ONLY_FOR_SET_TO_DELETION;
import static org.folio.dataexp.util.ErrorCode.ERROR_MESSAGE_UUID_IS_SET_TO_DELETION;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.folio.dataexp.domain.dto.ExportRequest;
import org.folio.dataexp.domain.dto.MappingProfile;
import org.folio.dataexp.domain.entity.MarcRecordEntity;
import org.folio.dataexp.repository.ErrorLogEntityCqlRepository;
import org.folio.dataexp.repository.MarcAuthorityRecordRepository;
import org.folio.dataexp.service.ConsortiaService;
import org.folio.spring.FolioExecutionContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Log4j2
@Component
@RequiredArgsConstructor
public class AuthorityExportStrategy extends AbstractExportStrategy {

  private final ConsortiaService consortiaService;
  private final ErrorLogEntityCqlRepository errorLogEntityCqlRepository;

  protected final MarcAuthorityRecordRepository marcAuthorityRecordRepository;
  protected final FolioExecutionContext context;

  @Override
  List<MarcRecordEntity> getMarcRecords(Set<UUID> externalIds, MappingProfile mappingProfile, ExportRequest exportRequest,
                                        UUID jobExecutionId) {
    if (Boolean.TRUE.equals(mappingProfile.getDefault())) {
      List<MarcRecordEntity> marcAuthorities = getMarcAuthorities(externalIds);
      log.info("Total marc authorities: {}", marcAuthorities.size());
      if (isDeletedJobProfile(exportRequest.getJobProfileId())) {
        log.info("Deleted job profile for authority is being used.");
      }
      Set<String> alreadySavedErrors = new HashSet<>();
      handleDeleted(marcAuthorities, jobExecutionId, exportRequest, alreadySavedErrors);
      marcAuthorities = handleDuplicatedDeletedAndUseLastGeneration(marcAuthorities);
      log.info("Marc authorities after removing: {}", marcAuthorities.size());
      entityManager.clear();
      var foundIds = marcAuthorities.stream().map(rec -> rec.getExternalId()).collect(Collectors.toSet());
      externalIds.removeAll(foundIds);
      log.info("Number of authority records found from local tenant: {}, not found: {}", foundIds.size(), externalIds.size());
      if (!externalIds.isEmpty()) {
        var centralTenantId = consortiaService.getCentralTenantId(folioExecutionContext.getTenantId());
        if (StringUtils.isNotEmpty(centralTenantId)) {
          var authoritiesFromCentralTenant = marcAuthorityRecordRepository.findNonDeletedByExternalIdIn(centralTenantId, externalIds);
          log.info("Number of authority records found from central tenant: {}", authoritiesFromCentralTenant.size());
          handleDeleted(authoritiesFromCentralTenant, jobExecutionId, exportRequest, alreadySavedErrors);
          entityManager.clear();
          marcAuthorities.addAll(authoritiesFromCentralTenant);
          log.info("Total number of authority records found: {}", marcAuthorities.size());
        } else {
          log.error("Central tenant id not found: {}, authorities that cannot be found: {}",
              centralTenantId, externalIds);
        }
      }
      log.debug("Final authority records: {}", marcAuthorities);
      return marcAuthorities;
    }
    return new ArrayList<>();
  }

  protected List<MarcRecordEntity> getMarcAuthorities(Set<UUID> externalIds) {
    return marcAuthorityRecordRepository.findNonDeletedByExternalIdIn(context.getTenantId(), externalIds);
  }

  private void handleDeleted(List<MarcRecordEntity> marcAuthorities, UUID jobExecutionId, ExportRequest exportRequest,
                             Set<String> alreadySavedErrors) {
    var iterator = marcAuthorities.iterator();
    var errorsForDeletedProfile = !errorLogEntityCqlRepository.getByJobExecutionIdAndErrorCodes(jobExecutionId, ERROR_MESSAGE_USED_ONLY_FOR_SET_TO_DELETION.getCode()).isEmpty();
    var errorsForNonDeletedProfile = !errorLogEntityCqlRepository.getByJobExecutionIdAndErrorCodes(jobExecutionId, ERROR_MESSAGE_PROFILE_USED_ONLY_FOR_NON_DELETED.getCode()).isEmpty();
    while (iterator.hasNext()) {
      var rec = iterator.next();
      if (rec.getState().equals("DELETED")) {
        String msg;
        if (!isDeletedJobProfile(exportRequest.getJobProfileId())) {
          msg = format(ERROR_MESSAGE_UUID_IS_SET_TO_DELETION.getDescription(), rec.getExternalId());
          if (!alreadySavedErrors.contains(msg)) {
            errorLogService.saveGeneralErrorWithMessageValues(ERROR_MESSAGE_UUID_IS_SET_TO_DELETION.getCode(),
              List.of(msg), jobExecutionId);
            alreadySavedErrors.add(msg);
          }
          log.error(msg);
          msg = ERROR_MESSAGE_PROFILE_USED_ONLY_FOR_NON_DELETED.getDescription();
          if (!errorsForNonDeletedProfile) {
            errorLogService.saveGeneralErrorWithMessageValues(ERROR_MESSAGE_PROFILE_USED_ONLY_FOR_NON_DELETED.getCode(),
              List.of(msg), jobExecutionId);
            errorsForNonDeletedProfile = true;
          }
          log.error(msg);
          iterator.remove();
        }
      } else if (rec.getState().equals("ACTUAL") && isDeletedJobProfile(exportRequest.getJobProfileId())) {
        var msg = ERROR_MESSAGE_USED_ONLY_FOR_SET_TO_DELETION.getDescription();
        if (!errorsForDeletedProfile) {
          errorLogService.saveGeneralErrorWithMessageValues(ERROR_MESSAGE_USED_ONLY_FOR_SET_TO_DELETION.getCode(),
            List.of(msg), jobExecutionId);
          errorsForDeletedProfile = true;
        }
        log.error(msg);
        iterator.remove();
      }
    }
  }

  private List<MarcRecordEntity> handleDuplicatedDeletedAndUseLastGeneration(List<MarcRecordEntity> marcAuthorities) {
    return marcAuthorities.stream().collect(
      groupingBy(MarcRecordEntity::getExternalId,
        maxBy(comparing(MarcRecordEntity::getGeneration)))).values().stream()
      .flatMap(Optional::stream).collect(toList());
  }

  @Override
  GeneratedMarcResult getGeneratedMarc(Set<UUID> ids, MappingProfile mappingProfile, ExportRequest exportRequest,
      UUID jobExecutionId, ExportStrategyStatistic exportStatistic) {
    var result = new GeneratedMarcResult(jobExecutionId);
    ids.forEach(id -> {
      result.addIdToFailed(id);
      result.addIdToNotExist(id);
    });
    return result;
  }

  @Override
  Optional<ExportIdentifiersForDuplicateErrors> getIdentifiers(UUID id) {
    return Optional.empty();
  }

  @Override
  public Map<UUID,MarcFields> getAdditionalMarcFieldsByExternalId(List<MarcRecordEntity> marcRecords, MappingProfile mappingProfile, UUID jobExecutionId) {
    return new HashMap<>();
  }
}
