package uk.gov.digital.ho.hocs.search.application.queue;

import org.apache.camel.LoggingLevel;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.aws.sqs.SqsConstants;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uk.gov.digital.ho.hocs.search.api.CaseDataService;
import uk.gov.digital.ho.hocs.search.api.dto.CreateCaseRequest;
import uk.gov.digital.ho.hocs.search.api.dto.CreateCorrespondentRequest;
import uk.gov.digital.ho.hocs.search.api.dto.CreateTopicRequest;
import uk.gov.digital.ho.hocs.search.api.dto.UpdateCaseRequest;

import static uk.gov.digital.ho.hocs.search.application.RequestData.transferHeadersToMDC;

@Component
public class SearchConsumer extends RouteBuilder {

    private static final String CREATE_CASE_QUEUE = "direct:createCaseQueue";
    private static final String UPDATE_CASE_QUEUE = "direct:updateCaseQueue";
    private static final String DELETE_CASE_QUEUE = "direct:deleteCaseQueue";
    private static final String COMPLETE_CASE_QUEUE = "direct:completeCaseQueue";
    private static final String CREATE_CORRESPONDENT_QUEUE = "direct:createCorrespondentQueue";
    private static final String DELETE_CORRESPONDENT_QUEUE = "direct:deleteCorrespondentQueue";
    private static final String CREATE_TOPIC_QUEUE = "direct:createTopicQueue";
    private static final String DELETE_TOPIC_QUEUE = "direct:deleteTopicQueue";
    private final CaseDataService caseDataService;
    private final String searchQueue;
    private final String dlq;
    private final int maximumRedeliveries;
    private final int redeliveryDelay;
    private final int backOffMultiplier;

    @Autowired
    public SearchConsumer(CaseDataService caseDataService,
                          @Value("${search.queue}") String searchQueue,
                          @Value("${search.queue.dlq}") String dlq,
                          @Value("${search.queue.maximumRedeliveries}") int maximumRedeliveries,
                          @Value("${search.queue.redeliveryDelay}") int redeliveryDelay,
                          @Value("${search.queue.backOffMultiplier}") int backOffMultiplier) {
        this.caseDataService = caseDataService;
        this.searchQueue = searchQueue;
        this.dlq = dlq;
        this.maximumRedeliveries = maximumRedeliveries;
        this.redeliveryDelay = redeliveryDelay;
        this.backOffMultiplier = backOffMultiplier;
    }

    @Override
    public void configure() {

        errorHandler(deadLetterChannel(dlq)
                .loggingLevel(LoggingLevel.ERROR)
                .log("Failed to add audit after configured back-off. ${body}")
                .useOriginalMessage()
                .retryAttemptedLogLevel(LoggingLevel.WARN)
                .maximumRedeliveries(maximumRedeliveries)
                .redeliveryDelay(redeliveryDelay)
                .backOffMultiplier(backOffMultiplier)
                .asyncDelayedRedelivery()
                .logRetryStackTrace(true));

        from(searchQueue).routeId("searchCommandRoute")
                .setProperty(SqsConstants.RECEIPT_HANDLE, header(SqsConstants.RECEIPT_HANDLE))
                .process(transferHeadersToMDC())
                .log(LoggingLevel.INFO, "Audit message received")
                .unmarshal().json(JsonLibrary.Jackson, CreateAuditDto.class)
                .setProperty("type", simple("${body.type}"))
                .log(LoggingLevel.INFO, "type: ${body.type}")
                .setProperty("caseUUID", simple("${body.caseUUID}"))
                .log(LoggingLevel.INFO, "caseUUID: ${body.caseUUID}")
                .setProperty("payLoad", simple("${body.data}"))
                .log(LoggingLevel.DEBUG, "payLoad: ${body}")
                .process(createPayload())
                .choice()
                .when(simple("${property.type} == '" + EventType.CASE_CREATED + "'"))
                .to(CREATE_CASE_QUEUE)
                .endChoice()
                .when(simple("${property.type} == '" + EventType.CASE_UPDATED + "'"))
                .to(UPDATE_CASE_QUEUE)
                .endChoice()
                .when(simple("${property.type} == '" + EventType.CASE_DELETED + "'"))
                .to(DELETE_CASE_QUEUE)
                .endChoice()
                .when(simple("${property.type} == '" + EventType.CASE_COMPLETED + "'"))
                .to(COMPLETE_CASE_QUEUE)
                .endChoice()
                .when(simple("${property.type} == '" + EventType.CORRESPONDENT_CREATED + "'"))
                .to(CREATE_CORRESPONDENT_QUEUE)
                .endChoice()
                .when(simple("${property.type} == '" + EventType.CORRESPONDENT_DELETED + "'"))
                .to(DELETE_CORRESPONDENT_QUEUE)
                .endChoice()
                .when(simple("${property.type} == '" + EventType.CASE_TOPIC_CREATED + "'"))
                .to(CREATE_TOPIC_QUEUE)
                .endChoice()
                .when(simple("${property.type} == '" + EventType.CASE_TOPIC_DELETED + "'"))
                .to(DELETE_TOPIC_QUEUE)
                .endChoice()
                .otherwise()
                .log(LoggingLevel.DEBUG, "Ignoring Message ${property.type}")
                .setHeader(SqsConstants.RECEIPT_HANDLE, exchangeProperty(SqsConstants.RECEIPT_HANDLE))
                .endChoice()
                .end()
                .log("Command processed");

        from(CREATE_CASE_QUEUE)
                .log(LoggingLevel.DEBUG, CREATE_CASE_QUEUE)
                .unmarshal().json(JsonLibrary.Jackson, CreateCaseRequest.class)
                .bean(caseDataService, "createCase(${property.caseUUID}, ${body})")
                .setHeader(SqsConstants.RECEIPT_HANDLE, exchangeProperty(SqsConstants.RECEIPT_HANDLE));

        from(UPDATE_CASE_QUEUE)
                .log(LoggingLevel.DEBUG, UPDATE_CASE_QUEUE)
                .unmarshal().json(JsonLibrary.Jackson, UpdateCaseRequest.class)
                .bean(caseDataService, "updateCase(${property.caseUUID}, ${body})")
                .setHeader(SqsConstants.RECEIPT_HANDLE, exchangeProperty(SqsConstants.RECEIPT_HANDLE));

        from(DELETE_CASE_QUEUE)
                .log(LoggingLevel.DEBUG, DELETE_CASE_QUEUE)
                .bean(caseDataService, "deleteCase(${property.caseUUID})")
                .setHeader(SqsConstants.RECEIPT_HANDLE, exchangeProperty(SqsConstants.RECEIPT_HANDLE));

        from(COMPLETE_CASE_QUEUE)
                .log(LoggingLevel.DEBUG, COMPLETE_CASE_QUEUE)
                .bean(caseDataService, "completeCase(${property.caseUUID})")
                .setHeader(SqsConstants.RECEIPT_HANDLE, exchangeProperty(SqsConstants.RECEIPT_HANDLE));

        from(CREATE_CORRESPONDENT_QUEUE)
                .log(LoggingLevel.DEBUG, CREATE_CORRESPONDENT_QUEUE)
                .unmarshal().json(JsonLibrary.Jackson, CreateCorrespondentRequest.class)
                .bean(caseDataService, "createCorrespondent(${property.caseUUID}, ${body})")
                .setHeader(SqsConstants.RECEIPT_HANDLE, exchangeProperty(SqsConstants.RECEIPT_HANDLE));

        from(DELETE_CORRESPONDENT_QUEUE)
                .log(LoggingLevel.DEBUG, DELETE_CORRESPONDENT_QUEUE)
                .bean(caseDataService, "deleteCorrespondent(${property.caseUUID}, ${body})")
                .setHeader(SqsConstants.RECEIPT_HANDLE, exchangeProperty(SqsConstants.RECEIPT_HANDLE));

        from(CREATE_TOPIC_QUEUE)
                .log(LoggingLevel.DEBUG, CREATE_TOPIC_QUEUE)
                .unmarshal().json(JsonLibrary.Jackson, CreateTopicRequest.class)
                .bean(caseDataService, "createTopic(${property.caseUUID}, ${body})")
                .setHeader(SqsConstants.RECEIPT_HANDLE, exchangeProperty(SqsConstants.RECEIPT_HANDLE));

        from(DELETE_TOPIC_QUEUE)
                .log(LoggingLevel.DEBUG, DELETE_TOPIC_QUEUE)
                .bean(caseDataService, "deleteTopic(${property.caseUUID}, ${body})")
                .setHeader(SqsConstants.RECEIPT_HANDLE, exchangeProperty(SqsConstants.RECEIPT_HANDLE));
    }

    private Processor createPayload() {
        return exchange -> exchange.getOut().setBody(exchange.getProperty("payLoad"));
    }
}