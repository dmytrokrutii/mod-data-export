package org.folio.dataexp.service.export.strategies;

import static java.util.stream.Collectors.toMap;
import static net.minidev.json.parser.JSONParser.DEFAULT_PERMISSIVE_MODE;
import static org.folio.dataexp.service.export.Constants.OUTPUT_BUFFER_SIZE;
import static org.folio.dataexp.util.ErrorCode.ERROR_CONVERTING_JSON_TO_MARC;
import static org.folio.dataexp.util.ErrorCode.ERROR_FIELDS_MAPPING_SRS;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.log4j.Log4j2;
import net.minidev.json.JSONObject;
import net.minidev.json.parser.JSONParser;
import net.minidev.json.parser.ParseException;
import org.apache.commons.lang3.StringUtils;
import org.folio.dataexp.domain.dto.ExportRequest;
import org.folio.dataexp.domain.dto.MappingProfile;
import org.folio.dataexp.domain.entity.ExportIdEntity;
import org.folio.dataexp.domain.entity.JobExecutionExportFilesEntity;
import org.folio.dataexp.domain.entity.JobExecutionExportFilesStatus;
import org.folio.dataexp.domain.entity.MarcRecordEntity;
import org.folio.dataexp.exception.TransformationRuleException;
import org.folio.dataexp.repository.ExportIdEntityRepository;
import org.folio.dataexp.repository.InstanceEntityRepository;
import org.folio.dataexp.repository.JobProfileEntityRepository;
import org.folio.dataexp.repository.MappingProfileEntityRepository;
import org.folio.dataexp.service.JobExecutionService;
import org.folio.dataexp.service.export.LocalStorageWriter;
import org.folio.dataexp.repository.MarcAuthorityRecordAllRepository;
import org.folio.dataexp.service.logs.ErrorLogService;
import org.folio.dataexp.util.ErrorCode;
import org.folio.dataexp.util.S3FilePathUtils;
import org.folio.spring.FolioExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Log4j2
public abstract class AbstractExportStrategy implements ExportStrategy {

  protected int exportIdsBatch;
  protected String exportTmpStorage;

  private InstanceEntityRepository instanceEntityRepository;
  private ExportIdEntityRepository exportIdEntityRepository;
  private MappingProfileEntityRepository mappingProfileEntityRepository;
  private JobProfileEntityRepository jobProfileEntityRepository;
  private JobExecutionService jobExecutionService;
  private JsonToMarcConverter jsonToMarcConverter;

  protected ErrorLogService errorLogService;
  protected MarcAuthorityRecordAllRepository marcAuthorityRecordAllRepository;
  protected FolioExecutionContext folioExecutionContext;


  @PersistenceContext
  protected EntityManager entityManager;

  @Value("#{ T(Integer).parseInt('${application.export-ids-batch}')}")
  protected void setExportIdsBatch(int exportIdsBatch) {
    this.exportIdsBatch = exportIdsBatch;
  }

  @Value("${application.export-tmp-storage}")
  protected void setExportTmpStorage(String exportTmpStorage) {
    this.exportTmpStorage = exportTmpStorage;
  }

  public static Optional<JSONObject> getAsJsonObject(String jsonAsString) {
    try {
      var jsonParser = new JSONParser(DEFAULT_PERMISSIVE_MODE);
      return Optional.of((JSONObject) jsonParser.parse(jsonAsString));
    } catch (ParseException e) {
      log.error("getAsJsonObject:: Error converting string to json {}", e.getMessage());
    }
    return Optional.empty();
  }

  @Override
  public ExportStrategyStatistic saveMarcToLocalStorage(JobExecutionExportFilesEntity exportFilesEntity, ExportRequest exportRequest, ExportedMarcListener exportedMarcListener) {
    var exportStatistic = new ExportStrategyStatistic(exportedMarcListener);
    var mappingProfile = getMappingProfile(exportFilesEntity.getJobExecutionId());
    var localStorageWriter = createLocalStorageWrite(exportFilesEntity);
    processSlices(exportFilesEntity, exportStatistic, mappingProfile, exportRequest, localStorageWriter);
    try {
      localStorageWriter.close();
    } catch (Exception e) {
      log.error("saveMarcToRemoteStorage:: Error while saving file {} to local storage for job execution {}", exportFilesEntity.getFileLocation(), exportFilesEntity.getJobExecutionId());
      exportStatistic.setDuplicatedSrs(0);
      exportStatistic.removeExported();
      long countFailed = exportIdEntityRepository.countExportIds(exportFilesEntity.getJobExecutionId(),
        exportFilesEntity.getFromId(), exportFilesEntity.getToId());
      exportStatistic.setFailed((int) countFailed);
    }
    return exportStatistic;
  }

  @Override
  public void setStatusBaseExportStatistic(JobExecutionExportFilesEntity exportFilesEntity, ExportStrategyStatistic exportStatistic) {
    if (exportStatistic.getFailed() == 0 && exportStatistic.getExported() > 0) {
      exportFilesEntity.setStatus(JobExecutionExportFilesStatus.COMPLETED);
    }
    if (exportStatistic.getFailed() > 0 && exportStatistic.getExported() > 0) {
      exportFilesEntity.setStatus(JobExecutionExportFilesStatus.COMPLETED_WITH_ERRORS);
    }
    if (exportStatistic.getFailed() >= 0 && exportStatistic.getExported() == 0) {
      exportFilesEntity.setStatus(JobExecutionExportFilesStatus.FAILED);
    }
  }

  abstract List<MarcRecordEntity> getMarcRecords(Set<UUID> externalIds, MappingProfile mappingProfile, ExportRequest exportRequest,
                                                 UUID jobExecutionId);

  abstract GeneratedMarcResult getGeneratedMarc(Set<UUID> ids, MappingProfile mappingProfile, ExportRequest exportRequest,
                                                UUID jobExecutionId, ExportStrategyStatistic exportStatistic);

  abstract Optional<ExportIdentifiersForDuplicateErrors> getIdentifiers(UUID id);

  abstract Map<UUID, MarcFields> getAdditionalMarcFieldsByExternalId(List<MarcRecordEntity> marcRecords, MappingProfile mappingProfile, UUID jobExecutionId) throws TransformationRuleException;

  protected LocalStorageWriter createLocalStorageWrite(JobExecutionExportFilesEntity exportFilesEntity) {
    return new LocalStorageWriter(S3FilePathUtils.getLocalStorageWriterPath(exportTmpStorage, exportFilesEntity.getFileLocation()), OUTPUT_BUFFER_SIZE);
  }

  protected void createAndSaveMarc(Set<UUID> externalIds, ExportStrategyStatistic exportStatistic, MappingProfile mappingProfile,
      UUID jobExecutionId, ExportRequest exportRequest, LocalStorageWriter localStorageWriter) {
    var externalIdsWithMarcRecord = new HashSet<UUID>();
    var marcRecords = getMarcRecords(externalIds, mappingProfile, exportRequest, jobExecutionId);
    createAndSaveMarcFromJsonRecord(externalIds, exportStatistic, mappingProfile, jobExecutionId, externalIdsWithMarcRecord, marcRecords, localStorageWriter);
    var result = getGeneratedMarc(externalIds, mappingProfile, exportRequest, jobExecutionId, exportStatistic);
    createAndSaveGeneratedMarc(result, exportStatistic, localStorageWriter);
  }

  protected void createAndSaveMarcFromJsonRecord(Set<UUID> externalIds, ExportStrategyStatistic exportStatistic, MappingProfile mappingProfile,
                                                 UUID jobExecutionId, Set<UUID> externalIdsWithMarcRecord, List<MarcRecordEntity> marcRecords, LocalStorageWriter localStorageWriter) {
    marcRecords = new ArrayList<>(marcRecords);
    log.info("marcRecords size: {}", marcRecords.size());
    Map<UUID, MarcFields> additionalFieldsPerId;
    try {
      additionalFieldsPerId = getAdditionalMarcFieldsByExternalId(marcRecords, mappingProfile, jobExecutionId);
    } catch (TransformationRuleException e) {
      log.error(e);
      errorLogService.saveGeneralError(e.getMessage(), jobExecutionId);
      return;
    }
    var duplicatedUuidWithIdentifiers = new LinkedHashMap<UUID, Optional<ExportIdentifiersForDuplicateErrors>>();
    for (var marcRecordEntity : marcRecords) {
      var marc = StringUtils.EMPTY;
      try {
        var marcHoldingsItemsFields = additionalFieldsPerId.getOrDefault(marcRecordEntity.getExternalId(), new MarcFields());
        if (!marcHoldingsItemsFields.getErrorMessages().isEmpty()) {
          errorLogService
            .saveGeneralErrorWithMessageValues(ERROR_FIELDS_MAPPING_SRS.getCode(), marcHoldingsItemsFields.getErrorMessages(), jobExecutionId);
        }
        marc = jsonToMarcConverter.convertJsonRecordToMarcRecord(marcRecordEntity.getContent(), marcHoldingsItemsFields.getHoldingItemsFields(), mappingProfile);
      } catch (Exception e) {
        exportStatistic.incrementFailed();
        saveConvertJsonRecordToMarcRecordError(marcRecordEntity, jobExecutionId, e);
        continue;
      }
      localStorageWriter.write(marc);
      if (externalIdsWithMarcRecord.contains(marcRecordEntity.getExternalId())) {
        exportStatistic.incrementDuplicatedSrs();
        var exportIdentifiers = getIdentifiers(marcRecordEntity.getExternalId());
        duplicatedUuidWithIdentifiers.put(marcRecordEntity.getExternalId(), exportIdentifiers);
      } else {
        externalIdsWithMarcRecord.add(marcRecordEntity.getExternalId());
      }
      exportStatistic.incrementExported();
    }
    saveDuplicateErrors(duplicatedUuidWithIdentifiers, marcRecords, jobExecutionId);
    marcRecords.clear();
    externalIds.removeAll(externalIdsWithMarcRecord);
  }

  protected void createAndSaveGeneratedMarc(GeneratedMarcResult result, ExportStrategyStatistic exportStatistic, LocalStorageWriter localStorageWriter) {
    log.info("Generated marc size: {}", result.getMarcRecords().size());
    result.getMarcRecords().forEach(marc -> {
          if (StringUtils.isNotEmpty(marc)) {
            localStorageWriter.write(marc);
          }
          exportStatistic.incrementExported();
        });
    exportStatistic.setFailed(exportStatistic.getFailed() + result.getFailedIds().size());
    exportStatistic.addNotExistIdsAll(result.getNotExistIds());
  }

  protected boolean isDeletedJobProfile(UUID jobProfileId) {
    return StringUtils.equals(jobProfileId.toString(), "2c9be114-6d35-4408-adac-9ead35f51a27");
  }

  private void saveDuplicateErrors(LinkedHashMap<UUID, Optional<ExportIdentifiersForDuplicateErrors>> duplicatedUuidWithIdentifiers,
      List<MarcRecordEntity> marcRecords, UUID jobExecutionId) {
    var externalIdsAsKeys = duplicatedUuidWithIdentifiers.keySet();
    var srsIdByExternalId = getSrsIdByExternalIdMap(marcRecords);
    for (var externalId : externalIdsAsKeys) {
      var exportIdentifiersOpt = duplicatedUuidWithIdentifiers.get(externalId);
      if (exportIdentifiersOpt.isPresent()) {
        var exportIdentifiers = exportIdentifiersOpt.get();
        var errorMessage = getDuplicatedSRSErrorMessage(externalId, marcRecords, exportIdentifiers);
        log.warn(errorMessage);
        if (instanceEntityRepository.findByIdIn(Set.of(externalId)).isEmpty()) {
          errorLogService.saveGeneralErrorWithMessageValues(ErrorCode.ERROR_NON_EXISTING_INSTANCE.getCode(),
            List.of(String.format(ErrorCode.ERROR_NON_EXISTING_INSTANCE.getDescription(), srsIdByExternalId.get(externalId))), jobExecutionId);
        }
        errorLogService.saveGeneralErrorWithMessageValues(ErrorCode.ERROR_DUPLICATE_SRS_RECORD.getCode(), List.of(errorMessage), jobExecutionId);
      }
    }
  }

  public void saveConvertJsonRecordToMarcRecordError(MarcRecordEntity marcRecordEntity, UUID jobExecutionId, Exception e) {
    var errorMessage = String.format(ERROR_CONVERTING_JSON_TO_MARC.getDescription(), marcRecordEntity.getExternalId().toString());
    log.error("{} : {}", errorMessage, e.getMessage());
    errorLogService.saveGeneralError(errorMessage, jobExecutionId);
  }

  private String getDuplicatedSRSErrorMessage(UUID externalId, List<MarcRecordEntity> marcRecords, ExportIdentifiersForDuplicateErrors exportIdentifiers) {
    var marcRecordIds = marcRecords.stream().filter(m -> m.getExternalId().equals(externalId))
        .map(e -> e.getId().toString()).collect(Collectors.joining(", "));
    return String.format(ErrorCode.ERROR_DUPLICATE_SRS_RECORD.getDescription(), exportIdentifiers.getIdentifierHridMessage(), marcRecordIds);
  }

  private Map<UUID, UUID> getSrsIdByDeletedExternalIdMap(List<MarcRecordEntity> marcRecords) {
    return marcRecords.stream()
      .filter(MarcRecordEntity::isDeleted)
      .collect(toMap(MarcRecordEntity::getExternalId, MarcRecordEntity::getId, (srsId1, srsId2) -> srsId1));
  }

  private Map<UUID, UUID> getSrsIdByExternalIdMap(List<MarcRecordEntity> marcRecords) {
    return marcRecords.stream()
      .collect(toMap(MarcRecordEntity::getExternalId, MarcRecordEntity::getId, (srsId1, srsId2) -> srsId1));
  }

  private MappingProfile getMappingProfile(UUID jobExecutionId) {
    var jobExecution = jobExecutionService.getById(jobExecutionId);
    var jobProfile = jobProfileEntityRepository.getReferenceById(jobExecution.getJobProfileId());
    return mappingProfileEntityRepository.getReferenceById(jobProfile.getMappingProfileId()).getMappingProfile();
  }

  protected void processSlices(JobExecutionExportFilesEntity exportFilesEntity,
      ExportStrategyStatistic exportStatistic, MappingProfile mappingProfile, ExportRequest exportRequest, LocalStorageWriter localStorageWriter) {
    var slice = exportIdEntityRepository.getExportIds(exportFilesEntity.getJobExecutionId(),
        exportFilesEntity.getFromId(), exportFilesEntity.getToId(), PageRequest.of(0, exportIdsBatch));
    log.info("Slice size: {}", slice.getSize());
    var exportIds = slice.getContent().stream().map(ExportIdEntity::getInstanceId).collect(Collectors.toSet());
    createAndSaveMarc(exportIds, exportStatistic, mappingProfile, exportFilesEntity.getJobExecutionId(), exportRequest, localStorageWriter);
    while (slice.hasNext()) {
      slice = exportIdEntityRepository.getExportIds(exportFilesEntity.getJobExecutionId(),
          exportFilesEntity.getFromId(), exportFilesEntity.getToId(), slice.nextPageable());
      exportIds = slice.getContent().stream().map(ExportIdEntity::getInstanceId).collect(Collectors.toSet());
      createAndSaveMarc(exportIds, exportStatistic, mappingProfile, exportFilesEntity.getJobExecutionId(),
          exportRequest, localStorageWriter);
    }
  }

  @Autowired
  protected void setInstanceEntityRepository(InstanceEntityRepository instanceEntityRepository) {
    this.instanceEntityRepository = instanceEntityRepository;
  }

  @Autowired
  private void setExportIdEntityRepository(ExportIdEntityRepository exportIdEntityRepository) {
    this.exportIdEntityRepository = exportIdEntityRepository;
  }

  @Autowired
  private void setJsonToMarcConverter(JsonToMarcConverter jsonToMarcConverter) {
    this.jsonToMarcConverter = jsonToMarcConverter;
  }

  @Autowired
  private void setMappingProfileEntityRepository(MappingProfileEntityRepository mappingProfileEntityRepository) {
    this.mappingProfileEntityRepository = mappingProfileEntityRepository;
  }

  @Autowired
  private void setJobProfileEntityRepository(JobProfileEntityRepository jobProfileEntityRepository) {
    this.jobProfileEntityRepository = jobProfileEntityRepository;
  }

  @Autowired
  private void setJobExecutionService(JobExecutionService jobExecutionService) {
    this.jobExecutionService = jobExecutionService;
  }

  @Autowired
  protected void setErrorLogService(ErrorLogService errorLogService) {
    this.errorLogService = errorLogService;
  }

  @Autowired
  private void setMarcAuthorityRecordAllRepository(MarcAuthorityRecordAllRepository marcAuthorityRecordAllRepository) {
    this.marcAuthorityRecordAllRepository = marcAuthorityRecordAllRepository;
  }

  @Autowired
  private void setFolioExecutionContext(FolioExecutionContext folioExecutionContext) {
    this.folioExecutionContext = folioExecutionContext;
  }
}
