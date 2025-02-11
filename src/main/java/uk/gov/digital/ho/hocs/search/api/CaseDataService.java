package uk.gov.digital.ho.hocs.search.api;

import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.index.query.QueryBuilders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import uk.gov.digital.ho.hocs.search.api.dto.*;
import uk.gov.digital.ho.hocs.search.client.elasticsearchclient.ElasticSearchClient;
import uk.gov.digital.ho.hocs.search.domain.model.CaseData;
import uk.gov.digital.ho.hocs.search.domain.model.Topic;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.value;
import static uk.gov.digital.ho.hocs.search.application.LogEvent.*;

@Service
@Slf4j
public class CaseDataService {

    private final ElasticSearchClient elasticSearchClient;

    private final int resultsLimit;

    @Autowired
    public CaseDataService(ElasticSearchClient elasticSearchClient, @Value("${elastic.results.limit}") int resultsLimit) {
        this.elasticSearchClient = elasticSearchClient;
        this.resultsLimit = resultsLimit;
    }

    public void createCase(UUID caseUUID, CreateCaseRequest createCaseRequest) {
        log.debug("Creating case {}", caseUUID);
        CaseData caseData = getCaseData(caseUUID);
        caseData.create(createCaseRequest);
        elasticSearchClient.save(caseData);
        log.info("Created case {}", caseUUID, value(EVENT, SEARCH_CASE_CREATED));

    }

    public void updateCase(UUID caseUUID, UpdateCaseRequest updateCaseRequest) {
        log.debug("Updating case {}", caseUUID);
        CaseData caseData = getCaseData(caseUUID);
        caseData.update(updateCaseRequest);
        elasticSearchClient.update(caseData);
        log.info("Updated case {}", caseUUID, value(EVENT, SEARCH_CASE_UPDATED));
    }

    public void deleteCase(UUID caseUUID) {
        log.debug("Deleting case {}", caseUUID);
        CaseData caseData = getCaseData(caseUUID);
        caseData.delete();
        elasticSearchClient.update(caseData);
        log.info("Deleted case {}", caseUUID, value(EVENT, SEARCH_CASE_DELETED));
    }

    public void completeCase(UUID caseUUID) {
        log.debug("Complete case {}", caseUUID);
        CaseData caseData = getCaseData(caseUUID);
        caseData.complete();
        elasticSearchClient.update(caseData);
        log.info("Compeleted case {}", caseUUID, value(EVENT, SEARCH_CASE_DELETED));
    }

    public void createCorrespondent(UUID caseUUID, CreateCorrespondentRequest createCorrespondentRequest) {
        log.debug("Adding correspondent {} to case {}", createCorrespondentRequest.getUuid(), caseUUID);
        CaseData caseData = getCaseData(caseUUID);
        caseData.addCorrespondent(createCorrespondentRequest);
        elasticSearchClient.update(caseData);
        log.info("Added correspondent {} to case {}", createCorrespondentRequest.getUuid(), caseUUID, value(EVENT, SEARCH_CORRESPONDENT_ADDED));
    }

    public void deleteCorrespondent(UUID caseUUID, String correspondentUUID) {
        log.debug("Deleting correspondent {} from case {}", correspondentUUID, caseUUID);
        CaseData caseData = getCaseData(caseUUID);
        caseData.removeCorrespondent(UUID.fromString(correspondentUUID));
        elasticSearchClient.update(caseData);
        log.info("Deleted correspondent {} from case {}", correspondentUUID, caseUUID, value(EVENT, SEARCH_CORRESPONDENT_DELETED));
    }

    public void createTopic(UUID caseUUID, CreateTopicRequest createTopicRequest) {
        log.debug("Adding topic {} to case {}", createTopicRequest.getUuid(), caseUUID);
        CaseData caseData = getCaseData(caseUUID);
        caseData.addTopic(Topic.from(createTopicRequest));
        elasticSearchClient.update(caseData);
        log.info("Added topic {} to case {}", createTopicRequest.getUuid(), caseUUID, value(EVENT, SEARCH_TOPIC_ADDED));
    }

    public void deleteTopic(UUID caseUUID, String topicUUID) {
        log.debug("Deleting topic {} from case {}", topicUUID, caseUUID);
        CaseData caseData = getCaseData(caseUUID);
        caseData.removeTopic(UUID.fromString(topicUUID));
        elasticSearchClient.update(caseData);
        log.info("Deleted topic {} from case {}", topicUUID, caseUUID, value(EVENT, SEARCH_TOPIC_DELETED));
    }

    Set<UUID> search(SearchRequest request) {
        log.info("Searching for case {}", request.toString(), value(EVENT, SEARCH_REQUEST));
        HocsQueryBuilder hocsQueryBuilder = new HocsQueryBuilder(QueryBuilders.boolQuery());
        hocsQueryBuilder.caseTypes(request.getCaseTypes());
        hocsQueryBuilder.dateRange(request.getDateReceived());
        hocsQueryBuilder.correspondent(request.getCorrespondentName());
        hocsQueryBuilder.topic(request.getTopic());
        hocsQueryBuilder.dataFields(request.getData());
        hocsQueryBuilder.activeOnlyFlag(request.getActiveOnly());

        Set<UUID> caseUUIDs;
        if (hocsQueryBuilder.hasClauses()) {
            caseUUIDs = elasticSearchClient.search(hocsQueryBuilder.build(), resultsLimit);
        } else {
            caseUUIDs = new HashSet<>(0);
        }

        log.info("Results {}", caseUUIDs.size(), value(EVENT, SEARCH_RESPONSE));
        return caseUUIDs;
    }

    private CaseData getCaseData(UUID caseUUID) {
        log.debug("Fetching Case {}", caseUUID);
        return elasticSearchClient.findById(caseUUID);
    }

}