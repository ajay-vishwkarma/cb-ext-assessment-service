package com.igot.cb.assessment.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.igot.cb.assessment.repo.AssessmentRepository;
import com.igot.cb.cache.RedisCacheMgr;
import com.igot.cb.cassandra.utils.CassandraOperation;
import com.igot.cb.common.model.SBApiResponse;
import com.igot.cb.common.service.ContentService;
import com.igot.cb.common.service.OutboundRequestHandlerServiceImpl;
import com.igot.cb.common.util.AccessTokenValidator;
import com.igot.cb.common.util.CbExtAssessmentServerProperties;
import com.igot.cb.common.util.Constants;
import com.igot.cb.common.util.ProjectUtil;
import com.igot.cb.core.producer.Producer;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

import static com.igot.cb.common.util.ProjectUtil.updateErrorDetails;
import static java.util.stream.Collectors.toList;

@Service
public class AssessmentServiceV7Impl implements AssessmentServiceV7 {
    private final Logger logger = LoggerFactory.getLogger(AssessmentServiceV7Impl.class);
    @Autowired
    CbExtAssessmentServerProperties serverProperties;

    @Autowired
    Producer kafkaProducer;

    @Autowired
    OutboundRequestHandlerServiceImpl outboundRequestHandlerService;

    @Autowired
    AssessmentUtilServiceV7 assessUtilServ;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    AssessmentRepository assessmentRepository;

    @Autowired
    AccessTokenValidator accessTokenValidator;

    @Autowired
    ContentService contentService;

    @Autowired
    CassandraOperation cassandraOperation;

    @Override
    public SBApiResponse submitAssessmentAsyncV7(Map<String, Object> submitRequest, String userAuthToken, boolean editMode) {
        logger.info("AssessmentServiceV7Impl::submitAssessmentAsyncV7.. started");
        SBApiResponse outgoingResponse = ProjectUtil.createDefaultResponse(Constants.API_SUBMIT_ASSESSMENT);
        String progressUpdateAPIRespone = "";
        long assessmentCompletionTime = Calendar.getInstance().getTime().getTime();
        try {
            // Step-1 fetch userid
            String userId = accessTokenValidator.fetchUserIdFromAccessToken(userAuthToken);
            if (ObjectUtils.isEmpty(userId)) {
                updateErrorDetails(outgoingResponse, Constants.USER_ID_DOESNT_EXIST, HttpStatus.BAD_REQUEST);
                return outgoingResponse;
            }
            String assessmentIdFromRequest = (String) submitRequest.get(Constants.IDENTIFIER);
            String errMsg;
            List<Map<String, Object>> sectionListFromSubmitRequest = new ArrayList<>();
            List<Map<String, Object>> hierarchySectionList = new ArrayList<>();
            Map<String, Object> assessmentHierarchy = new HashMap<>();
            Map<String, Object> existingAssessmentData = new HashMap<>();
            //Confirm whether the submitted request sections and questions match.
            errMsg = validateSubmitAssessmentRequest(submitRequest, userId, hierarchySectionList,
                    sectionListFromSubmitRequest, assessmentHierarchy, existingAssessmentData, userAuthToken, editMode);
            if (StringUtils.isNotBlank(errMsg)) {
                updateErrorDetails(outgoingResponse, errMsg, HttpStatus.BAD_REQUEST);
                return outgoingResponse;
            }
            int maxAssessmentRetakeAttempts = (Integer) assessmentHierarchy.get(Constants.MAX_ASSESSMENT_RETAKE_ATTEMPTS);
            int retakeAttemptsConsumed = calculateAssessmentRetakeCount(userId, assessmentIdFromRequest);
            String assessmentPrimaryCategory = (String) assessmentHierarchy.get(Constants.PRIMARY_CATEGORY);
            String assessmentType = ((String) assessmentHierarchy.get(Constants.ASSESSMENT_TYPE)).toLowerCase();
            String scoreCutOffType;
            if (assessmentType.equalsIgnoreCase(Constants.QUESTION_WEIGHTAGE)) {
                scoreCutOffType = Constants.SECTION_LEVEL_SCORE_CUTOFF;
            } else {
                scoreCutOffType = Constants.ASSESSMENT_LEVEL_SCORE_CUTOFF;
            }
            List<Map<String, Object>> sectionLevelsResults = new ArrayList<>();
            for (Map<String, Object> hierarchySection : hierarchySectionList) {
                String hierarchySectionId = (String) hierarchySection.get(Constants.IDENTIFIER);
                String userSectionId = "";
                Map<String, Object> userSectionData = new HashMap<>();
                for (Map<String, Object> sectionFromSubmitRequest : sectionListFromSubmitRequest) {
                    userSectionId = (String) sectionFromSubmitRequest.get(Constants.IDENTIFIER);
                    if (userSectionId.equalsIgnoreCase(hierarchySectionId)) {
                        userSectionData = sectionFromSubmitRequest;
                        break;
                    }
                }

                hierarchySection.put(Constants.SCORE_CUTOFF_TYPE, scoreCutOffType);
                List<Map<String, Object>> questionsListFromSubmitRequest = new ArrayList<>();
                if (userSectionData.containsKey(Constants.CHILDREN)
                        && !ObjectUtils.isEmpty(userSectionData.get(Constants.CHILDREN))) {
                    questionsListFromSubmitRequest = (List<Map<String, Object>>) userSectionData
                            .get(Constants.CHILDREN);
                }
                List<String> desiredKeys = List.of(Constants.IDENTIFIER);
                List<Object> questionsList = questionsListFromSubmitRequest.stream()
                        .flatMap(x -> desiredKeys.stream().filter(x::containsKey).map(x::get)).collect(toList());
                List<String> questionsListFromAssessmentHierarchy = questionsList.stream()
                        .map(object -> Objects.toString(object, null)).collect(toList());
                Map<String, Object> result = new HashMap<>();
                Map<String, Object> questionSetDetailsMap = getParamDetailsForQTypes(hierarchySection, assessmentHierarchy, hierarchySectionId);
                switch (scoreCutOffType) {
                    case Constants.ASSESSMENT_LEVEL_SCORE_CUTOFF: {
                        result.putAll(createResponseMapWithProperStructure(hierarchySection,
                                assessUtilServ.validateQumlAssessmentV7(questionSetDetailsMap, questionsListFromAssessmentHierarchy,
                                        questionsListFromSubmitRequest, assessUtilServ.readQListfromCache(questionsListFromAssessmentHierarchy, assessmentIdFromRequest, editMode, userAuthToken))));
                        Map<String, Object> finalRes = calculateAssessmentFinalResults(result);
                        outgoingResponse.getResult().putAll(finalRes);
                        outgoingResponse.getResult().put(Constants.PRIMARY_CATEGORY, assessmentPrimaryCategory);
                        progressUpdateAPIRespone = contentService.updateContentProgress(userAuthToken, submitRequest, userId, outgoingResponse);
                        if (!Constants.PRACTICE_QUESTION_SET.equalsIgnoreCase(assessmentPrimaryCategory) && !editMode && Constants.SUCCESS.equalsIgnoreCase(progressUpdateAPIRespone)) {
                            String questionSetFromAssessmentString = (String) existingAssessmentData
                                    .get(Constants.ASSESSMENT_READ_RESPONSE_KEY);
                            Map<String, Object> questionSetFromAssessment = null;
                            if (StringUtils.isNotBlank(questionSetFromAssessmentString)) {
                                questionSetFromAssessment = mapper.readValue(questionSetFromAssessmentString,
                                        new TypeReference<Map<String, Object>>() {
                                        });
                            }
                            writeDataToDatabaseAndTriggerKafkaEvent(submitRequest, userId, questionSetFromAssessment, finalRes,
                                    (String) assessmentHierarchy.get(Constants.PRIMARY_CATEGORY));
                        }
                        return outgoingResponse;
                    }
                    case Constants.SECTION_LEVEL_SCORE_CUTOFF: {
                        result.putAll(createResponseMapWithProperStructure(hierarchySection,
                                assessUtilServ.validateQumlAssessmentV7(questionSetDetailsMap, questionsListFromAssessmentHierarchy,
                                        questionsListFromSubmitRequest, assessUtilServ.readQListfromCache(questionsListFromAssessmentHierarchy, assessmentIdFromRequest, editMode, userAuthToken))));
                        sectionLevelsResults.add(result);
                    }
                    break;
                    default:
                        break;
                }
            }
            if (Constants.SECTION_LEVEL_SCORE_CUTOFF.equalsIgnoreCase(scoreCutOffType)) {
                long assessmentStartTime = 0;
                if (existingAssessmentData.get(Constants.START_TIME) != null) {
                    Date assessmentStart = (Date) existingAssessmentData.get(Constants.START_TIME);
                    assessmentStartTime = assessmentStart.getTime();
                }
                Map<String, Object> result = calculateSectionFinalResults(sectionLevelsResults, assessmentStartTime, assessmentCompletionTime, maxAssessmentRetakeAttempts, retakeAttemptsConsumed);
                outgoingResponse.getResult().putAll(result);
                outgoingResponse.getParams().setStatus(Constants.SUCCESS);
                outgoingResponse.setResponseCode(HttpStatus.OK);
                outgoingResponse.getResult().put(Constants.PRIMARY_CATEGORY, assessmentPrimaryCategory);
                progressUpdateAPIRespone = contentService.updateContentProgress(userAuthToken, submitRequest, userId, outgoingResponse);
                if (!Constants.PRACTICE_QUESTION_SET.equalsIgnoreCase(assessmentPrimaryCategory) && !editMode && Constants.SUCCESS.equalsIgnoreCase(progressUpdateAPIRespone)) {
                    String questionSetFromAssessmentString = (String) existingAssessmentData
                            .get(Constants.ASSESSMENT_READ_RESPONSE_KEY);
                    Map<String, Object> questionSetFromAssessment = null;
                    if (StringUtils.isNotBlank(questionSetFromAssessmentString)) {
                        questionSetFromAssessment = mapper.readValue(questionSetFromAssessmentString,
                                new TypeReference<Map<String, Object>>() {
                                });
                    }
                    writeDataToDatabaseAndTriggerKafkaEvent(submitRequest, userId, questionSetFromAssessment, result,
                            (String) assessmentHierarchy.get(Constants.PRIMARY_CATEGORY));
                }
                return outgoingResponse;
            }

        } catch (Exception e) {
            String errMsg = String.format("Failed to process assessment submit request. Exception: ", e.getMessage());
            logger.error(errMsg, e);
            updateErrorDetails(outgoingResponse, errMsg, HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return outgoingResponse;
    }

    @Override
    public SBApiResponse retakeAssessmentV7(String assessmentIdentifier, String token, Boolean editMode) {
        logger.info("AssessmentServiceV7Impl::retakeAssessmentV7... Started");
        SBApiResponse response = ProjectUtil.createDefaultResponse(Constants.API_RETAKE_ASSESSMENT_GET);
        String errMsg = "";
        int retakeAttemptsAllowed = 0;
        int retakeAttemptsConsumed = 0;
        try {
            String userId = accessTokenValidator.fetchUserIdFromAccessToken(token);
            if (StringUtils.isBlank(userId)) {
                updateErrorDetails(response, Constants.USER_ID_DOESNT_EXIST, HttpStatus.INTERNAL_SERVER_ERROR);
                return response;
            }

            Map<String, Object> assessmentAllDetail = assessUtilServ
                    .readAssessmentHierarchyFromCache(assessmentIdentifier, editMode, token);
            if (MapUtils.isEmpty(assessmentAllDetail)) {
                updateErrorDetails(response, Constants.ASSESSMENT_HIERARCHY_READ_FAILED,
                        HttpStatus.INTERNAL_SERVER_ERROR);
                return response;
            }
            if (assessmentAllDetail.get(Constants.MAX_ASSESSMENT_RETAKE_ATTEMPTS) != null) {
                retakeAttemptsAllowed = (int) assessmentAllDetail.get(Constants.MAX_ASSESSMENT_RETAKE_ATTEMPTS);
            }

            // if (serverProperties.isAssessmentRetakeCountVerificationEnabled()) {
            retakeAttemptsConsumed = calculateAssessmentRetakeCount(userId, assessmentIdentifier);
            retakeAttemptsConsumed = retakeAttemptsConsumed - 1;
            //}
        } catch (Exception e) {
            errMsg = String.format("Error while calculating retake assessment. Exception: %s", e.getMessage());
            logger.error(errMsg, e);
        }
        if (StringUtils.isNotBlank(errMsg)) {
            updateErrorDetails(response, errMsg, HttpStatus.INTERNAL_SERVER_ERROR);
        } else {
            response.getResult().put(Constants.TOTAL_RETAKE_ATTEMPTS_ALLOWED, retakeAttemptsAllowed);
            response.getResult().put(Constants.RETAKE_ATTEMPTS_CONSUMED, retakeAttemptsConsumed);
        }
        logger.info("AssessmentServiceV7Impl::retakeAssessmentV7... Completed");
        return response;
    }

    @Override
    public SBApiResponse readAssessmentV7(String assessmentIdentifier, String parentContextId, String token, boolean editMode) {
        logger.info("AssessmentServiceV7Impl::readAssessmentV7... Started");
        SBApiResponse response = ProjectUtil.createDefaultResponse(Constants.API_READ_ASSESSMENT);
        String errMsg = "";
        try {
            String userId = accessTokenValidator.fetchUserIdFromAccessToken(token);
            if (StringUtils.isBlank(userId)) {
                updateErrorDetails(response, Constants.USER_ID_DOESNT_EXIST, HttpStatus.INTERNAL_SERVER_ERROR);
                return response;
            }
            logger.info(String.format("ReadAssessment... UserId: %s, AssessmentIdentifier: %s", userId, assessmentIdentifier));

            Map<String, Object> assessmentAllDetail = null;

            // Step-1 : Read assessment using assessment Id from the Assessment Service
            if (editMode) {
                assessmentAllDetail = assessUtilServ.fetchHierarchyFromAssessServc(assessmentIdentifier, token);
            } else {
                assessmentAllDetail = assessUtilServ
                        .readAssessmentHierarchyFromCache(assessmentIdentifier, editMode, token);
            }

            if (MapUtils.isEmpty(assessmentAllDetail)) {
                updateErrorDetails(response, Constants.ASSESSMENT_HIERARCHY_READ_FAILED,
                        HttpStatus.INTERNAL_SERVER_ERROR);
                return response;
            }

            //Step-2 : If Practice Assessment return without saving
            if (Constants.PRACTICE_QUESTION_SET
                    .equalsIgnoreCase((String) assessmentAllDetail.get(Constants.PRIMARY_CATEGORY)) || editMode) {
                response.getResult().put(Constants.QUESTION_SET, readAssessmentLevelData(assessmentAllDetail));
                return response;
            }

            // Step-3 : If read user submitted assessment
            List<Map<String, Object>> existingDataList = assessUtilServ.readUserSubmittedAssessmentRecords(
                    userId, assessmentIdentifier);
            Instant assessmentStartTime = Instant.now();

            if (existingDataList.isEmpty()) {
                logger.info("Assessment read first time for user.");
                if (null == assessmentAllDetail.get(Constants.EXPECTED_DURATION)) {
                    errMsg = Constants.ASSESSMENT_INVALID;
                } else {
                    errMsg = validateContextLocking(assessmentAllDetail, parentContextId, response, userId);
                    if (StringUtils.isNotBlank(errMsg)) {
                        return response;
                    }
                    int expectedDuration = (Integer) assessmentAllDetail.get(Constants.EXPECTED_DURATION);
                    Instant assessmentEndTime = calculateAssessmentSubmitTime(expectedDuration,
                            assessmentStartTime, 0);
                    Map<String, Object> assessmentData = readAssessmentLevelData(assessmentAllDetail);
                    assessmentData.put(Constants.START_TIME, assessmentStartTime);
                    assessmentData.put(Constants.END_TIME, assessmentEndTime);
                    response.getResult().put(Constants.QUESTION_SET, assessmentData);
                    Boolean isAssessmentUpdatedToDB = assessmentRepository.addUserAssesmentDataToDB(userId,
                            assessmentIdentifier, assessmentStartTime, assessmentEndTime,
                            (Map<String, Object>) (response.getResult().get(Constants.QUESTION_SET)),
                            Constants.NOT_SUBMITTED);
                    if (Boolean.FALSE.equals(isAssessmentUpdatedToDB)) {
                        errMsg = Constants.ASSESSMENT_DATA_START_TIME_NOT_UPDATED;
                    }
                }
            } else {
                logger.info("Assessment read... user has details... ");
                Object endTimeObj = existingDataList.get(0).get(Constants.END_TIME);
                Date existingAssessmentEndTime = endTimeObj instanceof Instant
                        ? Date.from((Instant) endTimeObj)
                        : (Date) endTimeObj;
                if (assessmentStartTime.isBefore(existingAssessmentEndTime.toInstant())
                        && Constants.NOT_SUBMITTED.equalsIgnoreCase((String) existingDataList.get(0).get(Constants.STATUS))) {

                    String questionSetFromAssessmentString = (String) existingDataList.get(0)
                            .get(Constants.ASSESSMENT_READ_RESPONSE_KEY);

                    Map<String, Object> questionSetFromAssessment = new Gson().fromJson(
                            questionSetFromAssessmentString, new TypeToken<HashMap<String, Object>>() {
                            }.getType());

                    questionSetFromAssessment.put(Constants.START_TIME, assessmentStartTime.toEpochMilli());
                    questionSetFromAssessment.put(Constants.END_TIME, existingAssessmentEndTime);

                    response.getResult().put(Constants.QUESTION_SET, questionSetFromAssessment);
                } else if ((assessmentStartTime.isBefore(existingAssessmentEndTime.toInstant())
                        && Constants.SUBMITTED.equalsIgnoreCase((String) existingDataList.get(0).get(Constants.STATUS)))
                        || assessmentStartTime.isAfter(existingAssessmentEndTime.toInstant())) {

                    logger.info("Incase the assessment is submitted before the end time, or the endtime has exceeded, read assessment freshly");

                    if (assessmentAllDetail.get(Constants.MAX_ASSESSMENT_RETAKE_ATTEMPTS) != null) {
                        int retakeAttemptsAllowed = (int) assessmentAllDetail.get(Constants.MAX_ASSESSMENT_RETAKE_ATTEMPTS) + 1;
                        int retakeAttemptsConsumed = calculateAssessmentRetakeCount(userId, assessmentIdentifier);

                        if (retakeAttemptsConsumed >= retakeAttemptsAllowed) {
                            errMsg = Constants.ASSESSMENT_RETRY_ATTEMPTS_CROSSED;
                            updateErrorDetails(response, errMsg, HttpStatus.INTERNAL_SERVER_ERROR);
                            return response;
                        }
                    }

                    Map<String, Object> assessmentData = readAssessmentLevelData(assessmentAllDetail);
                    int expectedDuration = (Integer) assessmentAllDetail.get(Constants.EXPECTED_DURATION);

                    Instant assessmentEndTime = calculateAssessmentSubmitTime(expectedDuration, assessmentStartTime, 0);

                    assessmentData.put(Constants.START_TIME, assessmentStartTime.toEpochMilli());
                    assessmentData.put(Constants.END_TIME, assessmentEndTime.toEpochMilli());

                    response.getResult().put(Constants.QUESTION_SET, assessmentData);

                    boolean isAssessmentUpdatedToDB = assessmentRepository.addUserAssesmentDataToDB(
                            userId,
                            assessmentIdentifier,
                            assessmentStartTime,
                            assessmentEndTime,
                            assessmentData,
                            Constants.NOT_SUBMITTED
                    );

                    if (!isAssessmentUpdatedToDB) {
                        errMsg = Constants.ASSESSMENT_DATA_START_TIME_NOT_UPDATED;
                    }
                }
            }
            } catch (Exception e) {
            errMsg = String.format("Error while reading assessment. Exception: %s", e.getMessage());
            logger.error(errMsg, e);
        }
        if (StringUtils.isNotBlank(errMsg)) {
            updateErrorDetails(response, errMsg, HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return response;
    }

    private String validateContextLocking(Map<String, Object> assessmentAllDetail, String parentContextId,
                                          SBApiResponse response, String userId) {
        String errMsg = "";
        String contextCategory = (String) assessmentAllDetail.get(Constants.CONTEXT_CATEGORY_TAG);
        if (Constants.FINAL_PROGRAM_ASSESSMENT.equalsIgnoreCase(contextCategory) &&
                StringUtils.isNotBlank(parentContextId)) {
            Map<String, Object> contentDetails = contentService.readContentFromCache(parentContextId, null);
            if (contentDetails != null) {
                String contextLockingType = (String) contentDetails.get(Constants.CONTEXT_LOCKING_TYPE);
                if (Constants.COURSE_ASSESSMENT_ONLY.equalsIgnoreCase(contextLockingType)) {
                    Set<String> courseIds = contentService.readChildCoursesFromCache(parentContextId);
                    if (!isAllCourseCompleted(userId, courseIds)) {
                        errMsg = "User has not completed one or more courses in this program";
                        updateErrorDetails(response, errMsg, HttpStatus.BAD_REQUEST);
                        return errMsg;
                    }
                } else {
                    errMsg = "API doesn’t support this feature";
                    updateErrorDetails(response, errMsg, HttpStatus.INTERNAL_SERVER_ERROR);
                    return errMsg;
                }
            }
        }
        return errMsg;
    }

    private boolean isAllCourseCompleted(String userId, Set<String> courseIds) {
        if (courseIds == null || courseIds.isEmpty()) {
            return false;
        }
        Map<String, Object> propertyMap = new HashMap<>();
        propertyMap.put(Constants.ACTIVE, Boolean.TRUE);
        propertyMap.put(Constants.USER_ID_CONSTANT, userId);
        for (String courseId : courseIds) {
            propertyMap.put(Constants.COURSE_ID, courseId);
            List<Map<String, Object>> enrolments = cassandraOperation.getRecordsByProperties(
                    Constants.KEYSPACE_SUNBIRD_COURSES, Constants.TABLE_USER_ENROLMENT, propertyMap,
                    Arrays.asList(Constants.USER_ID_CONSTANT, Constants.COURSE_ID, Constants.BATCH_ID, Constants.COMPLETION_PERCENTAGE, Constants.PROGRESS, Constants.STATUS
                    ));

            if (enrolments.isEmpty() || enrolments.get(0) == null || !Constants.STATUS_COMPLETED.equals(String.valueOf(enrolments.get(0).get(Constants.STATUS)))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public SBApiResponse readAssessmentResultV7(Map<String, Object> request, String userAuthToken) {
        logger.info("AssessmentServiceV7Impl::readAssessmentResultV7... Started");
        SBApiResponse response = ProjectUtil.createDefaultResponse(Constants.API_READ_ASSESSMENT_RESULT);
        try {
            String userId = accessTokenValidator.fetchUserIdFromAccessToken(userAuthToken);
            if (StringUtils.isBlank(userId)) {
                updateErrorDetails(response, Constants.USER_ID_DOESNT_EXIST, HttpStatus.INTERNAL_SERVER_ERROR);
                return response;
            }

            String errMsg = validateAssessmentReadResult(request);
            if (StringUtils.isNotBlank(errMsg)) {
                updateErrorDetails(response, errMsg, HttpStatus.BAD_REQUEST);
                return response;
            }

            Map<String, Object> requestBody = (Map<String, Object>) request.get(Constants.REQUEST);
            String assessmentIdentifier = (String) requestBody.get(Constants.ASSESSMENT_ID_KEY);

            List<Map<String, Object>> existingDataList = assessUtilServ.readUserSubmittedAssessmentRecords(
                    userId, assessmentIdentifier);

            if (existingDataList.isEmpty()) {
                updateErrorDetails(response, Constants.USER_ASSESSMENT_DATA_NOT_PRESENT, HttpStatus.BAD_REQUEST);
                return response;
            }

            String statusOfLatestObject = (String) existingDataList.get(0).get(Constants.STATUS);
            if (!Constants.SUBMITTED.equalsIgnoreCase(statusOfLatestObject)) {
                response.getResult().put(Constants.STATUS_IS_IN_PROGRESS, true);
                return response;
            }

            String latestResponse = (String) existingDataList.get(0).get(Constants.SUBMIT_ASSESSMENT_RESPONSE_KEY);
            if (StringUtils.isNotBlank(latestResponse)) {
                response.putAll(mapper.readValue(latestResponse, new TypeReference<Map<String, Object>>() {
                }));
            }
        } catch (Exception e) {
            String errMsg = String.format("Failed to process Assessment read response. Excption: %s", e.getMessage());
            updateErrorDetails(response, errMsg, HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return response;
    }

    @Override
    public SBApiResponse readQuestionListV7(Map<String, Object> requestBody, String authUserToken, boolean editMode) {
        logger.info("AssessmentServiceV7Impl::readQuestionListV7... Started");
        SBApiResponse response = ProjectUtil.createDefaultResponse(Constants.API_QUESTIONS_LIST);
        String errMsg;
        Map<String, String> result = new HashMap<>();
        try {
            List<String> identifierList = new ArrayList<>();
            List<Object> questionList = new ArrayList<>();
            result = validateQuestionListAPI(requestBody, authUserToken, identifierList, editMode);
            errMsg = result.get(Constants.ERROR_MESSAGE);
            if (StringUtils.isNotBlank(errMsg)) {
                updateErrorDetails(response, errMsg, HttpStatus.BAD_REQUEST);
                return response;
            }

            String assessmentIdFromRequest = (String) requestBody.get(Constants.ASSESSMENT_ID_KEY);
            Map<String, Object> questionsMap = assessUtilServ.readQListfromCache(identifierList, assessmentIdFromRequest, editMode, authUserToken);
            for (String questionId : identifierList) {
                questionList.add(assessUtilServ.filterQuestionMapDetailV7((Map<String, Object>) questionsMap.get(questionId),
                        result.get(Constants.PRIMARY_CATEGORY)));
            }
            if (errMsg.isEmpty() && identifierList.size() == questionList.size()) {
                response.getResult().put(Constants.QUESTIONS, questionList);
            }
        } catch (Exception e) {
            errMsg = String.format("Failed to fetch the question list. Exception: %s", e.getMessage());
            logger.error(errMsg, e);
        }
        if (StringUtils.isNotBlank(errMsg)) {
            updateErrorDetails(response, errMsg, HttpStatus.BAD_REQUEST);
        }
        return response;
    }

    private String validateAssessmentReadResult(Map<String, Object> request) {
        String errMsg = "";
        if (MapUtils.isEmpty(request) || !request.containsKey(Constants.REQUEST)) {
            return Constants.INVALID_REQUEST;
        }

        Map<String, Object> requestBody = (Map<String, Object>) request.get(Constants.REQUEST);
        if (MapUtils.isEmpty(requestBody)) {
            return Constants.INVALID_REQUEST;
        }
        List<String> missingAttribs = new ArrayList<String>();
        if (!requestBody.containsKey(Constants.ASSESSMENT_ID_KEY)
                || StringUtils.isBlank((String) requestBody.get(Constants.ASSESSMENT_ID_KEY))) {
            missingAttribs.add(Constants.ASSESSMENT_ID_KEY);
        }

        if (!requestBody.containsKey(Constants.BATCH_ID)
                || StringUtils.isBlank((String) requestBody.get(Constants.BATCH_ID))) {
            missingAttribs.add(Constants.BATCH_ID);
        }

        if (!requestBody.containsKey(Constants.COURSE_ID)
                || StringUtils.isBlank((String) requestBody.get(Constants.COURSE_ID))) {
            missingAttribs.add(Constants.COURSE_ID);
        }

        if (!missingAttribs.isEmpty()) {
            errMsg = "One or more mandatory fields are missing in Request. Mandatory fields are : "
                    + missingAttribs.toString();
        }

        return errMsg;
    }


    private String validateSubmitAssessmentRequest(Map<String, Object> submitRequest, String userId,
                                                   List<Map<String, Object>> hierarchySectionList, List<Map<String, Object>> sectionListFromSubmitRequest,
                                                   Map<String, Object> assessmentHierarchy, Map<String, Object> existingAssessmentData, String token, boolean editMode) throws Exception {
        submitRequest.put(Constants.USER_ID, userId);
        if (StringUtils.isEmpty((String) submitRequest.get(Constants.IDENTIFIER))) {
            return Constants.INVALID_ASSESSMENT_ID;
        }
        String assessmentIdFromRequest = (String) submitRequest.get(Constants.IDENTIFIER);
        assessmentHierarchy.putAll(assessUtilServ.readAssessmentHierarchyFromCache(assessmentIdFromRequest, editMode, token));
        if (MapUtils.isEmpty(assessmentHierarchy)) {
            return Constants.READ_ASSESSMENT_FAILED;
        }

        hierarchySectionList.addAll((List<Map<String, Object>>) assessmentHierarchy.get(Constants.CHILDREN));
        sectionListFromSubmitRequest.addAll((List<Map<String, Object>>) submitRequest.get(Constants.CHILDREN));
        if (((String) (assessmentHierarchy.get(Constants.PRIMARY_CATEGORY)))
                .equalsIgnoreCase(Constants.PRACTICE_QUESTION_SET) || editMode)
            return "";

        List<Map<String, Object>> existingDataList = assessUtilServ.readUserSubmittedAssessmentRecords(
                userId, (String) submitRequest.get(Constants.IDENTIFIER));
        if (existingDataList.isEmpty()) {
            return Constants.USER_ASSESSMENT_DATA_NOT_PRESENT;
        } else {
            existingAssessmentData.putAll(existingDataList.get(0));
        }

        Object startTimeObj = existingAssessmentData.get(Constants.START_TIME);

        Date assessmentStartTime = Optional.ofNullable(startTimeObj)
                .map(obj -> {
                    if (obj instanceof Instant) return Date.from((Instant) obj);
                    if (obj instanceof Date) return (Date) obj;
                    if (obj instanceof String) return Date.from(Instant.parse((String) obj));
                    return null;
                })
                .orElse(null);

        if (assessmentStartTime == null) {
            return Constants.READ_ASSESSMENT_START_TIME_FAILED;
        }
        int expectedDuration = (Integer) assessmentHierarchy.get(Constants.EXPECTED_DURATION);
        Instant later = calculateAssessmentSubmitTime(expectedDuration,
                assessmentStartTime.toInstant(),
                Integer.parseInt(serverProperties.getUserAssessmentSubmissionDuration()));
        Instant submissionTime = Instant.now();
        int time = submissionTime.compareTo(later);
        if (time <= 0) {
            List<String> desiredKeys = List.of(Constants.IDENTIFIER);
            List<Object> hierarchySectionIds = hierarchySectionList.stream()
                    .flatMap(x -> desiredKeys.stream().filter(x::containsKey).map(x::get)).collect(toList());
            List<Object> submitSectionIds = sectionListFromSubmitRequest.stream()
                    .flatMap(x -> desiredKeys.stream().filter(x::containsKey).map(x::get)).collect(toList());
            if (!new HashSet<>(hierarchySectionIds).containsAll(submitSectionIds)) {
                return Constants.WRONG_SECTION_DETAILS;
            } else {
                String areQuestionIdsSame = validateIfQuestionIdsAreSame(submitRequest,
                        sectionListFromSubmitRequest, desiredKeys, userId, existingAssessmentData);
                if (!areQuestionIdsSame.isEmpty())
                    return areQuestionIdsSame;
            }
        } else {
            return Constants.ASSESSMENT_SUBMIT_EXPIRED;
        }

        return "";
    }

    private Instant calculateAssessmentSubmitTime(int expectedDurationInSeconds, Instant assessmentStartTime,
                                                  int bufferTimeInSeconds) {
        int totalDurationInSeconds = expectedDurationInSeconds;

        if (bufferTimeInSeconds > 0) {
            totalDurationInSeconds += Integer.parseInt(serverProperties.getUserAssessmentSubmissionDuration());
        }

        return assessmentStartTime.plusSeconds(totalDurationInSeconds);
    }

    private String validateIfQuestionIdsAreSame(Map<String, Object> submitRequest,
                                                List<Map<String, Object>> sectionListFromSubmitRequest, List<String> desiredKeys, String userId,
                                                Map<String, Object> existingAssessmentData) throws Exception {
        String questionSetFromAssessmentString = (String) existingAssessmentData
                .get(Constants.ASSESSMENT_READ_RESPONSE_KEY);
        if (StringUtils.isNotBlank(questionSetFromAssessmentString)) {
            Map<String, Object> questionSetFromAssessment = mapper.readValue(questionSetFromAssessmentString,
                    new TypeReference<Map<String, Object>>() {
                    });
            if (questionSetFromAssessment != null && questionSetFromAssessment.get(Constants.CHILDREN) != null) {
                List<Map<String, Object>> sections = (List<Map<String, Object>>) questionSetFromAssessment
                        .get(Constants.CHILDREN);
                List<String> desiredKey = List.of(Constants.CHILD_NODES);
                List<Object> questionList = sections.stream()
                        .flatMap(x -> desiredKey.stream().filter(x::containsKey).map(x::get)).collect(toList());
                List<Object> questionIdsFromAssessmentHierarchy = new ArrayList<>();
                List<Map<String, Object>> questionsListFromSubmitRequest = new ArrayList<>();
                for (Object question : questionList) {
                    questionIdsFromAssessmentHierarchy.addAll((List<String>) question);
                }
                for (Map<String, Object> userSectionData : sectionListFromSubmitRequest) {
                    if (userSectionData.containsKey(Constants.CHILDREN)
                            && !ObjectUtils.isEmpty(userSectionData.get(Constants.CHILDREN))) {
                        questionsListFromSubmitRequest
                                .addAll((List<Map<String, Object>>) userSectionData.get(Constants.CHILDREN));
                    }
                }
                List<Object> userQuestionIdsFromSubmitRequest = questionsListFromSubmitRequest.stream()
                        .flatMap(x -> desiredKeys.stream().filter(x::containsKey).map(x::get))
                        .collect(toList());
                if (!new HashSet<>(questionIdsFromAssessmentHierarchy).containsAll(userQuestionIdsFromSubmitRequest)) {
                    return Constants.ASSESSMENT_SUBMIT_INVALID_QUESTION;
                }
            }
        } else {
            return Constants.ASSESSMENT_SUBMIT_QUESTION_READ_FAILED;
        }
        return "";
    }

    private Map<String, Object> calculateSectionFinalResults(List<Map<String, Object>> sectionLevelResults, long assessmentStartTime, long assessmentCompletionTime, int maxAssessmentRetakeAttempts, int retakeAttemptsConsumed) {
        Map<String, Object> res = new HashMap<>();
        Double result;
        Integer correct = 0;
        Integer blank = 0;
        Integer inCorrect = 0;
        Integer total = 0;
        Double totalSectionMarks = 0.0;
        Integer totalMarks = 0;
        int pass = 0;
        Double totalResult = 0.0;
        try {
            for (Map<String, Object> sectionChildren : sectionLevelResults) {
                res.put(Constants.CHILDREN, sectionLevelResults);
                result = (Double) sectionChildren.get(Constants.RESULT);
                totalResult += result;
                blank += (Integer) sectionChildren.get(Constants.BLANK);
                correct += (Integer) sectionChildren.get(Constants.CORRECT);
                inCorrect += (Integer) sectionChildren.get(Constants.INCORRECT);
                Integer minimumPassPercentage = (Integer) sectionChildren.get(Constants.PASS_PERCENTAGE);
                if (result >= minimumPassPercentage) {
                    pass++;
                }
                if (sectionChildren.get(Constants.SECTION_MARKS) != null) {
                    totalSectionMarks += (Double) sectionChildren.get(Constants.SECTION_MARKS);
                }
                if (sectionChildren.get(Constants.TOTAL_MARKS) != null) {
                    totalMarks += (Integer) sectionChildren.get(Constants.TOTAL_MARKS);
                }
            }
            if (correct > 0 && inCorrect > 0) {
                res.put(Constants.OVERALL_RESULT, ((double) correct / (double) (correct + inCorrect)) * 100);
            } else {
                res.put(Constants.OVERALL_RESULT, 0);
            }
            res.put(Constants.BLANK, blank);
            res.put(Constants.CORRECT, correct);
            res.put(Constants.INCORRECT, inCorrect);
            res.put(Constants.PASS, (pass == sectionLevelResults.size()));
            res.put(Constants.TIME_TAKEN_FOR_ASSESSMENT, assessmentCompletionTime - assessmentStartTime);
            res.put(Constants.MAX_ASSESSMENT_RETAKE_ATTEMPTS, maxAssessmentRetakeAttempts);
            res.put(Constants.RETAKE_ATTEMPT_CONSUMED, retakeAttemptsConsumed);
            double totalPercentage = (totalSectionMarks / (double) totalMarks) * 100;
            res.put(Constants.TOTAL_PERCENTAGE, totalPercentage);
            res.put(Constants.TOTAL_SECTION_MARKS, totalSectionMarks);
            res.put(Constants.TOTAL_MARKS, totalMarks);
        } catch (Exception e) {
            logger.error("Failed to calculate assessment score. Exception: ", e);
        }
        return res;
    }

    private int calculateAssessmentRetakeCount(String userId, String assessmentId) {
        List<Map<String, Object>> userAssessmentDataList = assessUtilServ.readUserSubmittedAssessmentRecords(userId,
                assessmentId);
        return (int) userAssessmentDataList.stream()
                .filter(userData -> userData.containsKey(Constants.SUBMIT_ASSESSMENT_RESPONSE_KEY)
                        && null != userData.get(Constants.SUBMIT_ASSESSMENT_RESPONSE_KEY))
                .count();
    }

    private Map<String, Object> getParamDetailsForQTypes(Map<String, Object> hierarchySection, Map<String, Object> assessmentHierarchy, String hierarchySectionId) throws IOException {
        logger.info("Starting getParamDetailsForQTypes with assessmentHierarchy: {}", assessmentHierarchy);
        Map<String, Object> questionSetDetailsMap = new HashMap<>();
        String assessmentType = (String) assessmentHierarchy.get(Constants.ASSESSMENT_TYPE);
        questionSetDetailsMap.put(Constants.ASSESSMENT_TYPE, assessmentType);
        questionSetDetailsMap.put(Constants.MINIMUM_PASS_PERCENTAGE, assessmentHierarchy.get(Constants.MINIMUM_PASS_PERCENTAGE));
        questionSetDetailsMap.put(Constants.TOTAL_MARKS, hierarchySection.get(Constants.TOTAL_MARKS));
        if (assessmentType.equalsIgnoreCase(Constants.QUESTION_WEIGHTAGE)) {
            Map<String, Map<String, Object>> questionSectionSchema = (Map<String, Map<String, Object>>) hierarchySection.get(Constants.SECTION_LEVEL_DEFINITION);
            questionSetDetailsMap.put(Constants.QUESTION_SECTION_SCHEME, generateMarkMap(questionSectionSchema));
            questionSetDetailsMap.put(Constants.NEGATIVE_MARKING_PERCENTAGE, assessmentHierarchy.get(Constants.NEGATIVE_MARKING_PERCENTAGE));
            questionSetDetailsMap.put("hierarchySectionId", hierarchySectionId);
        }
        logger.info("Completed getParamDetailsForQTypes with result: {}", questionSetDetailsMap);
        return questionSetDetailsMap;
    }

    public Map<String, Integer> generateMarkMap(Map<String, Map<String, Object>> qSectionSchemeMap) {
        Map<String, Integer> markMap = new HashMap<>();
        logger.info("Starting to generate mark map from qSectionSchemeMap");
        qSectionSchemeMap.keySet().forEach(sectionKey -> {
            Map<String, Object> proficiencyMap = qSectionSchemeMap.get(sectionKey);
            proficiencyMap.forEach((key, value) -> {
                if (key.equalsIgnoreCase("marksForQuestion")) {
                    markMap.put(sectionKey, (Integer) value);
                }
            });
        });
        logger.info("Completed generating mark map");
        return markMap;
    }

    public Map<String, Object> createResponseMapWithProperStructure(Map<String, Object> hierarchySection,
                                                                    Map<String, Object> resultMap) {
        Map<String, Object> sectionLevelResult = new HashMap<>();
        sectionLevelResult.put(Constants.IDENTIFIER, hierarchySection.get(Constants.IDENTIFIER));
        sectionLevelResult.put(Constants.OBJECT_TYPE, hierarchySection.get(Constants.OBJECT_TYPE));
        sectionLevelResult.put(Constants.PRIMARY_CATEGORY, hierarchySection.get(Constants.PRIMARY_CATEGORY));
        sectionLevelResult.put(Constants.PASS_PERCENTAGE, hierarchySection.get(Constants.MINIMUM_PASS_PERCENTAGE));
        sectionLevelResult.put(Constants.NAME, hierarchySection.get(Constants.NAME));
        Double result;
        if (!ObjectUtils.isEmpty(resultMap)) {
            result = (Double) resultMap.get(Constants.RESULT);
            sectionLevelResult.put(Constants.RESULT, result);
            sectionLevelResult.put(Constants.BLANK, resultMap.get(Constants.BLANK));
            sectionLevelResult.put(Constants.CORRECT, resultMap.get(Constants.CORRECT));
            sectionLevelResult.put(Constants.INCORRECT, resultMap.get(Constants.INCORRECT));
            sectionLevelResult.put(Constants.CHILDREN, resultMap.get(Constants.CHILDREN));
            sectionLevelResult.put(Constants.SECTION_RESULT, resultMap.get(Constants.SECTION_RESULT));
            sectionLevelResult.put(Constants.TOTAL_MARKS, resultMap.get(Constants.TOTAL_MARKS));
            sectionLevelResult.put(Constants.SECTION_MARKS, resultMap.get(Constants.SECTION_MARKS));


        } else {
            result = 0.0;
            sectionLevelResult.put(Constants.RESULT, result);
            List<String> childNodes = (List<String>) hierarchySection.get(Constants.CHILDREN);
            sectionLevelResult.put(Constants.TOTAL, childNodes.size());
            sectionLevelResult.put(Constants.BLANK, childNodes.size());
            sectionLevelResult.put(Constants.CORRECT, 0);
            sectionLevelResult.put(Constants.INCORRECT, 0);
        }
        sectionLevelResult.put(Constants.PASS,
                result >= ((Integer) hierarchySection.get(Constants.MINIMUM_PASS_PERCENTAGE)));
        sectionLevelResult.put(Constants.OVERALL_RESULT, result);
        return sectionLevelResult;
    }

    private Map<String, Object> calculateAssessmentFinalResults(Map<String, Object> assessmentLevelResult) {
        Map<String, Object> res = new HashMap<>();
        try {
            res.put(Constants.CHILDREN, Collections.singletonList(assessmentLevelResult));
            Double result = (Double) assessmentLevelResult.get(Constants.RESULT);
            res.put(Constants.OVERALL_RESULT, result);
            res.put(Constants.TOTAL, assessmentLevelResult.get(Constants.TOTAL));
            res.put(Constants.BLANK, assessmentLevelResult.get(Constants.BLANK));
            res.put(Constants.CORRECT, assessmentLevelResult.get(Constants.CORRECT));
            res.put(Constants.PASS_PERCENTAGE, assessmentLevelResult.get(Constants.PASS_PERCENTAGE));
            res.put(Constants.INCORRECT, assessmentLevelResult.get(Constants.INCORRECT));
            res.put(Constants.NAME, assessmentLevelResult.get(Constants.NAME));
            Integer minimumPassPercentage = (Integer) assessmentLevelResult.get(Constants.PASS_PERCENTAGE);
            res.put(Constants.PASS, result >= minimumPassPercentage);
        } catch (Exception e) {
            logger.error("Failed to calculate Assessment final results. Exception: ", e);
        }
        return res;
    }

    private void writeDataToDatabaseAndTriggerKafkaEvent(Map<String, Object> submitRequest, String userId,
                                                         Map<String, Object> questionSetFromAssessment, Map<String, Object> result, String primaryCategory) {
        try {
            if (questionSetFromAssessment.get(Constants.START_TIME) != null) {
                Long existingAssessmentStartTime = (Long) questionSetFromAssessment.get(Constants.START_TIME);
                Timestamp startTime = new Timestamp(existingAssessmentStartTime);
                Boolean isAssessmentUpdatedToDB = assessmentRepository.updateUserAssesmentDataToDB(userId,
                        (String) submitRequest.get(Constants.IDENTIFIER), submitRequest, result, Constants.SUBMITTED,
                        startTime, null);
                if (Boolean.TRUE.equals(isAssessmentUpdatedToDB)) {
                    Map<String, Object> kafkaResult = new HashMap<>();
                    kafkaResult.put(Constants.CONTENT_ID_KEY, submitRequest.get(Constants.IDENTIFIER));
                    kafkaResult.put(Constants.COURSE_ID,
                            submitRequest.get(Constants.COURSE_ID) != null ? submitRequest.get(Constants.COURSE_ID)
                                    : "");
                    kafkaResult.put(Constants.BATCH_ID,
                            submitRequest.get(Constants.BATCH_ID) != null ? submitRequest.get(Constants.BATCH_ID) : "");
                    kafkaResult.put(Constants.USER_ID, submitRequest.get(Constants.USER_ID));
                    kafkaResult.put(Constants.ASSESSMENT_ID_KEY, submitRequest.get(Constants.IDENTIFIER));
                    kafkaResult.put(Constants.PRIMARY_CATEGORY, primaryCategory);
                    kafkaResult.put(Constants.TOTAL_SCORE, result.get(Constants.OVERALL_RESULT));
                    if ((primaryCategory.equalsIgnoreCase("Competency Assessment")
                            && submitRequest.containsKey("competencies_v3")
                            && submitRequest.get("competencies_v3") != null)) {
                        ObjectMapper objectMapper = new ObjectMapper(); //Updated for Json Import
                        List<Map<String, Object>> competencyList = objectMapper.readValue(
                                (String) submitRequest.get("competencies_v3"),
                                new TypeReference<List<Map<String, Object>>>() {
                                }
                        );

                        if (!competencyList.isEmpty()) {
                            kafkaResult.put(Constants.COMPETENCY, competencyList.get(0));  // First competency map
                        } else {
                            kafkaResult.put(Constants.COMPETENCY, "");
                        }
                    }

                    kafkaProducer.push(serverProperties.getAssessmentSubmitTopic(), kafkaResult);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to write data for assessment submit response. Exception: ", e);
        }
    }

    private Map<String, Object> readAssessmentLevelData(Map<String, Object> assessmentAllDetail) {
        List<String> assessmentParams = serverProperties.getAssessmentLevelParams();
        Map<String, Object> assessmentFilteredDetail = new HashMap<>();
        for (String assessmentParam : assessmentParams) {
            if ((assessmentAllDetail.containsKey(assessmentParam))) {
                assessmentFilteredDetail.put(assessmentParam, assessmentAllDetail.get(assessmentParam));
            }
        }
        readSectionLevelParams(assessmentAllDetail, assessmentFilteredDetail);
        return assessmentFilteredDetail;
    }

    private void readSectionLevelParams(Map<String, Object> assessmentAllDetail,
                                        Map<String, Object> assessmentFilteredDetail) {
        List<Map<String, Object>> sectionResponse = new ArrayList<>();
        List<String> sectionIdList = new ArrayList<>();
        List<String> sectionParams = serverProperties.getAssessmentSectionParams();
        List<Map<String, Object>> sections = (List<Map<String, Object>>) assessmentAllDetail.get(Constants.CHILDREN);
        String assessmentType = (String) assessmentAllDetail.get(Constants.ASSESSMENT_TYPE);
        for (Map<String, Object> section : sections) {
            sectionIdList.add((String) section.get(Constants.IDENTIFIER));
            Map<String, Object> newSection = new HashMap<>();
            for (String sectionParam : sectionParams) {
                if (section.containsKey(sectionParam)) {
                    newSection.put(sectionParam, section.get(sectionParam));
                }
            }
            List<Map<String, Object>> questions = (List<Map<String, Object>>) section.get(Constants.CHILDREN);
            List<String> childNodeList;
            if (assessmentType.equalsIgnoreCase(Constants.QUESTION_WEIGHTAGE)) {
                List<Map<String, Object>> selectedQuestionsList = processRandomizationForQuestions((Map<String, Map<String, Object>>) section.get(Constants.SECTION_LEVEL_DEFINITION), questions);
                childNodeList = selectedQuestionsList.stream()
                        .map(question -> (String) question.get(Constants.IDENTIFIER))
                        .collect(toList());
            } else {
                int maxQuestions = (int) section.getOrDefault(Constants.MAX_QUESTIONS, questions.size());
                List<Map<String, Object>> shuffledQuestionsList = shuffleQuestions(questions);
                childNodeList = shuffledQuestionsList.stream()
                        .map(question -> (String) question.get(Constants.IDENTIFIER))
                        .limit(maxQuestions)
                        .collect(toList());
            }
            Collections.shuffle(childNodeList);
            newSection.put(Constants.CHILD_NODES, childNodeList);
            sectionResponse.add(newSection);
        }
        assessmentFilteredDetail.put(Constants.CHILDREN, sectionResponse);
        assessmentFilteredDetail.put(Constants.CHILD_NODES, sectionIdList);
    }

    private List<Map<String, Object>> processRandomizationForQuestions(Map<String, Map<String, Object>> sectionLevelDefinitionMap, List<Map<String, Object>> questions) {
        List<Map<String, Object>> shuffledQuestionsList = shuffleQuestions(questions);
        List<Map<String, Object>> selectedQuestionsList = new ArrayList<>();
        Map<String, Integer> noOfQuestionsMap = new HashMap<>();
        Map<String, Integer> dupNoOfQuestionsMap = new HashMap<>();     // Duplicate map for tracking selected questions
        boolean result = sectionLevelDefinitionMap.values().stream()
                .anyMatch(proficiencyMap -> {
                    Object maxNoOfQuestionsValue = proficiencyMap.get(Constants.NO_OF_QUESTIONS);
                    if (maxNoOfQuestionsValue instanceof Integer) {
                        return (Integer) maxNoOfQuestionsValue > 0;
                    }
                    return false;
                });

        if (!result) {
            return questions;
        } else {
            // Populate noOfQuestionsMap and noOfMaxQuestionsMap from sectionLevelDefinitionMap
            sectionLevelDefinitionMap.forEach((sectionLevelDefinitionKey, proficiencyMap) -> proficiencyMap.forEach((key, value) -> {
                if (key.equalsIgnoreCase(Constants.NO_OF_QUESTIONS)) {
                    noOfQuestionsMap.put(sectionLevelDefinitionKey, (Integer) value);
                    dupNoOfQuestionsMap.put(sectionLevelDefinitionKey, 0);
                }
            }));

            // Process each question for randomization and limit checking
            for (Map<String, Object> question : shuffledQuestionsList) {
                String questionLevel = (String) question.get(Constants.QUESTION_LEVEL);
                // Check if adding one more question of this level is within limits
                if (dupNoOfQuestionsMap.getOrDefault(questionLevel, 0) < noOfQuestionsMap.getOrDefault(questionLevel, 0)) {
                    // Add the question to selected list
                    selectedQuestionsList.add(question);
                    // Update dupNoOfQuestionsMap to track the count of selected questions for this level
                    dupNoOfQuestionsMap.put(questionLevel, dupNoOfQuestionsMap.getOrDefault(questionLevel, 0) + 1);
                }
            }
            return selectedQuestionsList;
        }
    }

    /**
     * Shuffles the list of questions maps.
     *
     * @param questions The list of questions maps to be shuffled.
     * @return A new list containing the shuffled questions maps.
     */
    public static List<Map<String, Object>> shuffleQuestions(List<Map<String, Object>> questions) {
        // Create a copy of the original list to avoid modifying the input list
        List<Map<String, Object>> shuffledQnsList = new ArrayList<>(questions);
        // Shuffle the list using Collections.shuffle()
        Collections.shuffle(shuffledQnsList);
        return shuffledQnsList;
    }

    private Map<String, String> validateQuestionListAPI(Map<String, Object> requestBody, String authUserToken,
                                                        List<String> identifierList, boolean editMode) throws IOException {
        Map<String, String> result = new HashMap<>();
        String userId = accessTokenValidator.fetchUserIdFromAccessToken(authUserToken);
        if (StringUtils.isBlank(userId)) {
            result.put(Constants.ERROR_MESSAGE, Constants.USER_ID_DOESNT_EXIST);
            return result;
        }
        String assessmentIdFromRequest = (String) requestBody.get(Constants.ASSESSMENT_ID_KEY);
        if (StringUtils.isBlank(assessmentIdFromRequest)) {
            result.put(Constants.ERROR_MESSAGE, Constants.ASSESSMENT_ID_KEY_IS_NOT_PRESENT_IS_EMPTY);
            return result;
        }
        identifierList.addAll(getQuestionIdList(requestBody));
        if (identifierList.isEmpty()) {
            result.put(Constants.ERROR_MESSAGE, Constants.IDENTIFIER_LIST_IS_EMPTY);
            return result;
        }

        Map<String, Object> assessmentAllDetail = assessUtilServ
                .readAssessmentHierarchyFromCache(assessmentIdFromRequest, editMode, authUserToken);

        if (MapUtils.isEmpty(assessmentAllDetail)) {
            result.put(Constants.ERROR_MESSAGE, Constants.ASSESSMENT_HIERARCHY_READ_FAILED);
            return result;
        }
        String primaryCategory = (String) assessmentAllDetail.get(Constants.PRIMARY_CATEGORY);
        if (Constants.PRACTICE_QUESTION_SET
                .equalsIgnoreCase(primaryCategory) || editMode) {
            result.put(Constants.PRIMARY_CATEGORY, primaryCategory);
            result.put(Constants.ERROR_MESSAGE, StringUtils.EMPTY);
            return result;
        }

        Map<String, Object> userAssessmentAllDetail = new HashMap<String, Object>();

        List<Map<String, Object>> existingDataList = assessUtilServ.readUserSubmittedAssessmentRecords(
                userId, assessmentIdFromRequest);
        String questionSetFromAssessmentString = (!existingDataList.isEmpty())
                ? (String) existingDataList.get(0).get(Constants.ASSESSMENT_READ_RESPONSE_KEY)
                : "";
        if (StringUtils.isNotBlank(questionSetFromAssessmentString)) {
            userAssessmentAllDetail.putAll(mapper.readValue(questionSetFromAssessmentString,
                    new TypeReference<Map<String, Object>>() {
                    }));
        } else {
            result.put(Constants.ERROR_MESSAGE, Constants.USER_ASSESSMENT_DATA_NOT_PRESENT);
            return result;
        }

        if (!MapUtils.isEmpty(userAssessmentAllDetail)) {
            result.put(Constants.PRIMARY_CATEGORY, (String) userAssessmentAllDetail.get(Constants.PRIMARY_CATEGORY));
            List<String> questionsFromAssessment = new ArrayList<>();
            List<Map<String, Object>> sections = (List<Map<String, Object>>) userAssessmentAllDetail
                    .get(Constants.CHILDREN);
            for (Map<String, Object> section : sections) {
                // Out of the list of questions received in the payload, checking if the request
                // has only those ids which are a part of the user's latest assessment
                // Fetching all the remaining questions details from the Redis
                questionsFromAssessment.addAll((List<String>) section.get(Constants.CHILD_NODES));
            }
            if (validateQuestionListRequest(identifierList, questionsFromAssessment)) {
                result.put(Constants.ERROR_MESSAGE, StringUtils.EMPTY);
            } else {
                result.put(Constants.ERROR_MESSAGE, Constants.THE_QUESTIONS_IDS_PROVIDED_DONT_MATCH);
            }
            return result;
        } else {
            result.put(Constants.ERROR_MESSAGE, Constants.ASSESSMENT_ID_INVALID);
            return result;
        }
    }

    private List<String> getQuestionIdList(Map<String, Object> questionListRequest) {
        try {
            if (questionListRequest.containsKey(Constants.REQUEST)) {
                Map<String, Object> request = (Map<String, Object>) questionListRequest.get(Constants.REQUEST);
                if ((!ObjectUtils.isEmpty(request)) && request.containsKey(Constants.SEARCH)) {
                    Map<String, Object> searchObj = (Map<String, Object>) request.get(Constants.SEARCH);
                    if (!ObjectUtils.isEmpty(searchObj) && searchObj.containsKey(Constants.IDENTIFIER)
                            && !CollectionUtils.isEmpty((List<String>) searchObj.get(Constants.IDENTIFIER))) {
                        return (List<String>) searchObj.get(Constants.IDENTIFIER);
                    }
                }
            }
        } catch (Exception e) {
            logger.error(String.format("Failed to process the questionList request body. %s", e.getMessage()));
        }
        return Collections.emptyList();
    }

    private Boolean validateQuestionListRequest(List<String> identifierList, List<String> questionsFromAssessment) {
        return (new HashSet<>(questionsFromAssessment).containsAll(identifierList)) ? Boolean.TRUE : Boolean.FALSE;
    }

}
