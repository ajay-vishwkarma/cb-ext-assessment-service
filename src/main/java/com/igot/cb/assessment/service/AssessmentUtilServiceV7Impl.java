package com.igot.cb.assessment.service;


import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.igot.cb.cache.RedisCacheMgr;
import com.igot.cb.cassandra.utils.CassandraOperation;
import com.igot.cb.common.service.OutboundRequestHandlerServiceImpl;
import com.igot.cb.common.util.CbExtAssessmentServerProperties;
import com.igot.cb.common.util.Constants;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AssessmentUtilServiceV7Impl implements AssessmentUtilServiceV7 {

    @Autowired
    CbExtAssessmentServerProperties serverProperties;

    @Autowired
    OutboundRequestHandlerServiceImpl outboundRequestHandlerService;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    CassandraOperation cassandraOperation;

    private Logger logger = LoggerFactory.getLogger(AssessmentUtilServiceV7Impl.class);

    @Autowired
    RedisCacheMgr redisCacheMgr;

    @Override
    public List<Map<String, Object>> readQuestionDetails(List<String> identifiers) {
        try {
            StringBuilder sbUrl = new StringBuilder(serverProperties.getAssessmentHost());
            sbUrl.append(serverProperties.getAssessmentQuestionListPath());
            Map<String, String> headers = new HashMap<>();
            headers.put(Constants.AUTHORIZATION, serverProperties.getSbApiKey());
            Map<String, Object> requestBody = new HashMap<>();
            Map<String, Object> requestData = new HashMap<>();
            Map<String, Object> searchData = new HashMap<>();
            requestData.put(Constants.SEARCH, searchData);
            requestBody.put(Constants.REQUEST, requestData);
            List<Map<String, Object>> questionDataList = new ArrayList<>();
            int chunkSize = 15;
            for (int i = 0; i < identifiers.size(); i += chunkSize) {
                List<String> identifierList;
                if ((i + chunkSize) >= identifiers.size()) {
                    identifierList = identifiers.subList(i, identifiers.size());
                } else {
                    identifierList = identifiers.subList(i, i + chunkSize);
                }
                searchData.put(Constants.IDENTIFIER, identifierList);
                Map<String, Object> data = outboundRequestHandlerService.fetchResultUsingPost(sbUrl.toString(), requestBody, headers);
                if (!ObjectUtils.isEmpty(data)) {
                    questionDataList.add(data);
                }
            }
            return questionDataList;
        } catch (Exception e) {
            logger.info(String.format("Failed to process the readQuestionDetails. %s", e.getMessage()));
        }
        return new ArrayList<>();
    }


    @Override
    public Map<String, Object> getReadHierarchyApiResponse(String assessmentIdentifier, String token) {
        try {
            StringBuilder sbUrl = new StringBuilder(serverProperties.getAssessmentHost());
            sbUrl.append(serverProperties.getAssessmentHierarchyReadPath());
            String serviceURL = sbUrl.toString().replace(Constants.IDENTIFIER_REPLACER, assessmentIdentifier);
            Map<String, String> headers = new HashMap<>();
            headers.put(Constants.X_AUTH_TOKEN, token);
            headers.put(Constants.AUTHORIZATION, serverProperties.getSbApiKey());
            Object o = outboundRequestHandlerService.fetchUsingGetWithHeaders(serviceURL, headers);
            Map<String, Object> data = new ObjectMapper().convertValue(o, Map.class);
            return data;
        } catch (Exception e) {
            logger.error("error in getReadHierarchyApiResponse  " + e.getMessage(), e);
        }
        return new HashMap<>();
    }

    public Map<String, Object> fetchHierarchyFromAssessServc(String qSetId, String token) {
        Map<String, Object> readHierarchyApiResponse = getReadHierarchyApiResponse(qSetId, token);
        if (!readHierarchyApiResponse.isEmpty())
            if (ObjectUtils.isEmpty(readHierarchyApiResponse) || !Constants.OK.equalsIgnoreCase((String) readHierarchyApiResponse.get(Constants.RESPONSE_CODE))) {
                throw new RuntimeException("Internal Server Error");
            }
        return ((Map<String, Object>) ((Map<String, Object>) readHierarchyApiResponse.get(Constants.RESULT)).get(Constants.QUESTION_SET));
    }

    public Map<String, Object> readAssessmentHierarchyFromCache(String assessmentIdentifier, boolean editMode, String token) {

        if (editMode) return fetchHierarchyFromAssessServc(assessmentIdentifier, token);

        String questStr = Constants.EMPTY;
        if (serverProperties.qListFromCacheEnabled()) {
            questStr = redisCacheMgr.getCache(Constants.ASSESSMENT_ID + assessmentIdentifier + Constants.UNDER_SCORE + Constants.QUESTION_SET);
        }
        if (StringUtils.isEmpty(questStr)) {
            Map<String, Object> propertyMap = new HashMap<String, Object>();
            propertyMap.put(Constants.IDENTIFIER, assessmentIdentifier);
            List<Map<String, Object>> hierarchyList = cassandraOperation.getRecordsByPropertiesWithoutFiltering(serverProperties.getAssessmentHierarchyNameSpace(), serverProperties.getAssessmentHierarchyTable(), propertyMap, null);
            if (!CollectionUtils.isEmpty(hierarchyList)) {
                Map<String, Object> assessmentEntry = hierarchyList.get(0);
                String hierarchyStr = (String) assessmentEntry.get(Constants.HIERARCHY);
                if (StringUtils.isNotBlank(hierarchyStr)) {
                    try {
                        Map<String, Object> questionHierarchy = mapper.readValue(hierarchyStr, new TypeReference<Map<String, Object>>() {
                        });
                        redisCacheMgr.putCache(Constants.ASSESSMENT_ID + assessmentIdentifier + Constants.UNDER_SCORE + Constants.QUESTION_SET, questionHierarchy, serverProperties.getRedisQuestionsReadTimeOut().intValue());
                        return questionHierarchy;
                    } catch (Exception e) {
                        logger.error("Failed to read hierarchy data. Exception: ", e);
                    }
                }
            }
        } else {
            try {
                return mapper.readValue(questStr, new TypeReference<Map<String, Object>>() {
                });
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        return MapUtils.EMPTY_SORTED_MAP;
    }

    public List<Map<String, Object>> readUserSubmittedAssessmentRecords(String userId, String assessmentId) {
        Map<String, Object> propertyMap = new HashMap<String, Object>();
        propertyMap.put(Constants.USER_ID, userId);
        propertyMap.put(Constants.ASSESSMENT_ID_KEY, assessmentId);
        return cassandraOperation.getRecordsByPropertiesWithoutFiltering(Constants.SUNBIRD_KEY_SPACE_NAME, serverProperties.getAssessmentUserSubmitDataTable(), propertyMap, null);
    }


    public Map<String, Object> readQListfromCache(List<String> questionIds, String assessmentIdentifier, boolean editMode, String token) throws IOException {
        if (serverProperties.qListFromCacheEnabled() && !editMode)
            return qListFromCache(assessmentIdentifier, editMode, token);
        else return qListFrmAssessService(questionIds);
    }

    private Map<String, Object> qListFrmAssessService(List<String> questionIds) {
        // Read question details for the fetched question IDs
        List<Map<String, Object>> questionResultsList = readQuestionDetails(questionIds);
        if (CollectionUtils.isEmpty(questionResultsList)) return new HashMap<>();
        // Construct the question map
        return questionResultsList.stream().flatMap(questList -> ((List<Map<String, Object>>) ((Map<String, Object>) questList.get(Constants.RESULT)).get(Constants.QUESTIONS)).stream()).collect(Collectors.toMap(question -> (String) question.get(Constants.IDENTIFIER), question -> question));
    }

    public Map<String, Object> qListFromCache(String assessmentIdentifier, boolean editMode, String token) throws IOException {
        String questStr = redisCacheMgr.getCache(Constants.ASSESSMENT_ID + assessmentIdentifier + Constants.UNDER_SCORE + Constants.QUESTIONS);
        if (StringUtils.isEmpty(questStr)) {
            Map<String, Object> questionMap = new HashMap<>();
            // Read assessment hierarchy from DB
            Map<String, Object> assessmentData = readAssessmentHierarchyFromCache(assessmentIdentifier, editMode, token);
            if (CollectionUtils.isEmpty(assessmentData)) return questionMap;
            List<Map<String, Object>> children = (List<Map<String, Object>>) assessmentData.get(Constants.CHILDREN);
            if (CollectionUtils.isEmpty(children)) return questionMap;
            // Fetch recursive question IDs
            List<String> questionIds = fetchRecursiveQuestionIds(children, new ArrayList<>());
            if (CollectionUtils.isEmpty(questionIds)) return questionMap;
            // Construct the question map
            questionMap = qListFrmAssessService(questionIds);
            if (!CollectionUtils.isEmpty(questionMap))
                redisCacheMgr.putCache(Constants.ASSESSMENT_ID + assessmentIdentifier + Constants.UNDER_SCORE + Constants.QUESTIONS, questionMap, serverProperties.getRedisQuestionsReadTimeOut().intValue());
            return questionMap;
        } else {
            return mapper.readValue(questStr, new TypeReference<Map<String, Object>>() {
            });
        }
    }

    private List<String> fetchRecursiveQuestionIds(List<Map<String, Object>> children, List<String> questionIds) {
        if (CollectionUtils.isEmpty(children)) return questionIds;
        for (Map<String, Object> child : children) {
            if (Constants.QUESTION_SET.equalsIgnoreCase((String) child.get(Constants.OBJECT_TYPE))) {
                fetchRecursiveQuestionIds((List<Map<String, Object>>) child.get(Constants.CHILDREN), questionIds);
            } else {
                questionIds.add((String) child.get(Constants.IDENTIFIER));
            }
        }
        return questionIds;
    }


    private static Double calculateScoreForOptionWeightage(Map<String, Object> question, String assessmentType, Map<String, Object> optionWeightages, Double sectionMarks, List<String> marked) {
        if (assessmentType.equalsIgnoreCase(Constants.OPTION_WEIGHTAGE)) {
            Map<String, Object> optionWeightageMap = (Map<String, Object>) optionWeightages.get(question.get(Constants.IDENTIFIER));
            for (Map.Entry<String, Object> optionWeightAgeFromOptions : optionWeightageMap.entrySet()) {
                String submittedQuestionSetIndex = marked.get(0);
                if (submittedQuestionSetIndex.equals(optionWeightAgeFromOptions.getKey())) {
                    sectionMarks = sectionMarks + (Integer) optionWeightAgeFromOptions.getValue();
                }
            }
        }
        return sectionMarks;
    }


    /**
     * Retrieves option weightages for a list of questions corresponding to their options.
     *
     * @param questions   the list of questionIDs/doIds.
     * @param questionMap the map containing questions/Question Level details.
     * @return a map containing Identifier mapped to their option and option weightages.
     * @throws Exception if there is an error processing the questions.
     */
    private Map<String, Object> getOptionWeightages(List<String> questions, Map<String, Object> questionMap) {
        logger.info("Retrieving option weightages for questions based on the options...");
        Map<String, Object> ret = new HashMap<>();
        for (String questionId : questions) {
            Map<String, Object> optionWeightage = new HashMap<>();
            Map<String, Object> question = (Map<String, Object>) questionMap.get(questionId);
            if (question.containsKey(Constants.QUESTION_TYPE)) {
                String questionType = ((String) question.get(Constants.QUESTION_TYPE)).toLowerCase();
                Map<String, Object> editorStateObj = (Map<String, Object>) question.get(Constants.EDITOR_STATE);
                List<Map<String, Object>> options = (List<Map<String, Object>>) editorStateObj.get(Constants.OPTIONS);
                switch (questionType) {
                    case Constants.MCQ_SCA:
                    case Constants.MCQ_MCA:
                    case Constants.MCQ_MCA_W:
                        for (Map<String, Object> option : options) {
                            Map<String, Object> valueObj = (Map<String, Object>) option.get(Constants.VALUE);
                            optionWeightage.put(valueObj.get(Constants.VALUE).toString(), option.get(Constants.ANSWER));
                        }
                        break;
                    default:
                        break;
                }
            }
            ret.put(question.get(Constants.IDENTIFIER).toString(), optionWeightage);
        }
        logger.info("Option weightages retrieved successfully.");
        return ret;
    }


    /**
     * Gets index for each question based on the question type.
     *
     * @param options the list of options.
     */
    private void getMarkedIndexForOptionWeightAge(List<Map<String, Object>> options, List<String> marked) {
        logger.info("Processing marks for option weightage...");
        for (Map<String, Object> option : options) {
            String submittedQuestionSetIndex = (String) option.get(Constants.INDEX);
            marked.add(submittedQuestionSetIndex);
        }
        logger.info("Marks for option weightage processed successfully.");
    }

    /**
     * Retrieves the index of marked options from the provided options list if it is a correct answer.
     *
     * @param options the list of options.
     * @param marked  the list to store marked indices for correct answer.
     */
    private void getMarkedIndexForQuestionWeightAge(List<Map<String, Object>> options, List<String> marked) {
        for (Map<String, Object> option : options) {
            if ((boolean) option.get(Constants.SELECTED_ANSWER)) {
                marked.add((String) option.get(Constants.INDEX));
            }
        }
    }

    /**
     * Handles the correct answer scenario by updating the section marks.
     *
     * @param sectionMarks             the current section marks.
     * @param questionSetSectionScheme the question set section scheme.
     * @param proficiencyMap           the proficiency map containing question levels.
     * @return the updated section marks.
     */
    private Double handleCorrectAnswer(Double sectionMarks, Map<String, Object> questionSetSectionScheme, Map<String, Object> proficiencyMap) {
        logger.info("Handling correct answer scenario...");
        sectionMarks = sectionMarks + (Integer) questionSetSectionScheme.get((String) proficiencyMap.get(Constants.QUESTION_LEVEL));
        logger.info("Correct answer scenario handled successfully.");
        return sectionMarks;
    }

    /**
     * Handles the incorrect answer scenario by updating the section marks .
     * and applying negative marking if applicable.
     *
     * @param negativeMarksValue       the value of negative marks for incorrect answers.
     * @param sectionMarks             the current section marks.
     * @param questionSetSectionScheme the question set section scheme.
     * @param proficiencyMap           the proficiency map containing question levels.
     * @return the updated section marks.
     */
    private Double handleIncorrectAnswer(int negativeMarksValue, Double sectionMarks, Map<String, Object> questionSetSectionScheme, Map<String, Object> proficiencyMap) {
        logger.info("Handling incorrect answer scenario...");
        if (negativeMarksValue > 0) {
            sectionMarks = sectionMarks - (((double) negativeMarksValue / 100) * (int) questionSetSectionScheme.get((String) proficiencyMap.get(Constants.QUESTION_LEVEL)));
        }
        logger.info("Incorrect answer scenario handled successfully.");
        return sectionMarks;
    }

    /**
     * Handles blank answers by counting skipped questions.
     *
     * @param userQuestionList the list of user questions.
     * @param answers          the map containing answers.
     * @param blank            the current count of blank answers.
     * @return the updated count of blank answers.
     */
    private Integer handleBlankAnswers(List<Map<String, Object>> userQuestionList, Map<String, Object> answers, Integer blank) {
        logger.info("Handling blank answers...");
        // Increment the blank counter for skipped question objects
        if (answers.size() > userQuestionList.size()) {
            blank += answers.size() - userQuestionList.size();
        }
        logger.info("Blank answers handled successfully.");
        return blank;
    }

    /**
     * Updates the result map with assessment data.
     *
     * @param userQuestionList the list of user questions.
     * @param correct          the count of correct answers.
     * @param blank            the count of blank answers.
     * @param inCorrect        the count of incorrect answers.
     * @param resultMap        the map to store assessment results.
     * @param sectionMarks     the section marks obtained.
     * @param totalMarks       the total marks for the assessment.
     */
    private void updateResultMap(List<Map<String, Object>> userQuestionList, Integer correct, Integer blank, Integer inCorrect, Map<String, Object> resultMap, Double sectionMarks, Integer totalMarks) {
        logger.info("Updating result map...");
        resultMap.put(Constants.INCORRECT, inCorrect);
        resultMap.put(Constants.BLANK, blank);
        resultMap.put(Constants.CORRECT, correct);
        resultMap.put(Constants.CHILDREN, userQuestionList);
        resultMap.put(Constants.SECTION_MARKS, sectionMarks);
        resultMap.put(Constants.TOTAL_MARKS, totalMarks);
        logger.info("Result map updated successfully.");
    }


    /**
     * Computes the result of a section based on section marks, total marks, and minimum pass value.
     *
     * @param sectionMarks     the marks obtained in the section.
     * @param totalMarks       the total marks available for the section.
     * @param minimumPassValue the minimum percentage required to pass the section.
     * @param resultMap        the map to store the section result.
     */
    private void computeSectionResults(Double sectionMarks, Integer totalMarks, int minimumPassValue, Map<String, Object> resultMap) {
        logger.info("Computing section results...");
        if (sectionMarks > 0 && totalMarks > 0 && ((sectionMarks / totalMarks) * 100 >= minimumPassValue)) {
            resultMap.put(Constants.SECTION_RESULT, Constants.PASS);
        } else {
            resultMap.put(Constants.SECTION_RESULT, Constants.FAIL);
        }
        logger.info("Section results computed successfully.");
    }

    private Map<String, Object> getProficiencyMap(Map<String, Object> questionMap, Map<String, Object> question) {
        return (Map<String, Object>) questionMap.get(question.get(Constants.IDENTIFIER));
    }

    private void sortAnswers(List<String> answer) {
        if (answer.size() > 1) Collections.sort(answer);
    }

    /**
     * Calculates the pass percentage based on the given assessment type and updates the resultMap with the result.
     *
     * @param sectionMarks   Marks obtained in the section
     * @param totalMarks     Total marks possible in the section
     * @param correct        Number of correct answers
     * @param blank          Number of blank answers
     * @param inCorrect      Number of incorrect answers
     * @param assessmentType Type of assessment (either "QUESTION_WEIGHTAGE" or "OPTION_WEIGHTAGE")
     * @param resultMap      Map to store the result of the calculation
     */
    private static void calculatePassPercentage(Double sectionMarks, Integer totalMarks, Integer correct, Integer blank, Integer inCorrect, String assessmentType, Map<String, Object> resultMap) {
        if (assessmentType.equalsIgnoreCase(Constants.QUESTION_WEIGHTAGE)) {
            resultMap.put(Constants.RESULT, sectionMarks / (double) totalMarks * 100);
        } else if (assessmentType.equalsIgnoreCase(Constants.OPTION_WEIGHTAGE)) {
            int total;
            total = correct + blank + inCorrect;
            resultMap.put(Constants.RESULT, total == 0 ? 0 : ((correct * 100d) / total));
            resultMap.put(Constants.TOTAL, total);
        }
    }

    /**
     * Shuffles the list of option maps.
     *
     * @param optionsMapList The list of option maps to be shuffled.
     * @return A new list containing the shuffled option maps.
     */
    public static List<Map<String, Object>> shuffleOptions(List<Map<String, Object>> optionsMapList) {
        // Create a copy of the original list to avoid modifying the input list
        List<Map<String, Object>> shuffledList = new ArrayList<>(optionsMapList);
        // Shuffle the list using Collections.shuffle()
        Collections.shuffle(shuffledList);
        return shuffledList;
    }


    /**
     * @param questionSetDetailsMap a map containing details about the question set.
     * @param originalQuestionList  a list of original question identifiers.
     * @param userQuestionList      a list of maps where each map represents a user's question with its details.
     * @param questionMap           a map containing additional question-related information.
     * @return a map with validation results and resultMap.
     */
    public Map<String, Object> validateQumlAssessmentV7(Map<String, Object> questionSetDetailsMap, List<String> originalQuestionList, List<Map<String, Object>> userQuestionList, Map<String, Object> questionMap) {
        try {
            String assessmentType = getAssessmentType(questionSetDetailsMap);
            int minimumPassPercentage = getMinimumPassPercentage(questionSetDetailsMap);
            int totalMarks = getTotalMarks(questionSetDetailsMap);

            Map<String, Object> resultMap = new HashMap<>();

            Map<String, Object> answers = getQumlAnswersV7(originalQuestionList, questionMap);

            Integer correct = 0;
            Integer blank = 0;
            Integer inCorrect = 0;
            Double sectionMarks = 0.0;
            Map<String, Object> questionSetSectionScheme = new HashMap<>();
            int negativeMarksValue = 0;
            Map<String, Object> optionWeightages = new HashMap<>();
            if (assessmentType.equalsIgnoreCase(Constants.OPTION_WEIGHTAGE)) {
                optionWeightages = getOptionWeightages(originalQuestionList, questionMap);
            } else if (assessmentType.equalsIgnoreCase(Constants.QUESTION_WEIGHTAGE)) {
                questionSetSectionScheme = getQuestionSetSectionScheme(questionSetDetailsMap);
                negativeMarksValue = getNegativeMarksValue(questionSetDetailsMap);
            }

            for (Map<String, Object> question : userQuestionList) {
                Map<String, Object> proficiencyMap = getProficiencyMap(questionMap, question);
                List<String> marked = new ArrayList<>();
                handleqTypeQuestionV7(question, marked, assessmentType);
                if (CollectionUtils.isEmpty(marked)) {
                    blank++;
                    question.put(Constants.RESULT, Constants.BLANK);
                } else {
                    List<String> answer = mapper.convertValue(answers.get(question.get(Constants.IDENTIFIER)), new TypeReference<List<String>>() {
                    });
                    sortAnswers(answer);
                    sortAnswers(marked);
                    if (assessmentType.equalsIgnoreCase(Constants.QUESTION_WEIGHTAGE)) {
                        if (answer.equals(marked)) {
                            question.put(Constants.RESULT, Constants.CORRECT);
                            correct++;
                            sectionMarks = handleCorrectAnswer(sectionMarks, questionSetSectionScheme, proficiencyMap);
                        } else {
                            question.put(Constants.RESULT, Constants.INCORRECT);
                            inCorrect++;
                            sectionMarks = handleIncorrectAnswer(negativeMarksValue, sectionMarks, questionSetSectionScheme, proficiencyMap);
                        }
                    }
                    sectionMarks = calculateScoreForOptionWeightage(question, assessmentType, optionWeightages, sectionMarks, marked);
                }
            }
            blank = handleBlankAnswers(userQuestionList, answers, blank);
            updateResultMap(userQuestionList, correct, blank, inCorrect, resultMap, sectionMarks, totalMarks);
            calculatePassPercentage(sectionMarks, totalMarks, correct, blank, inCorrect, assessmentType, resultMap);
            computeSectionResults(sectionMarks, totalMarks, minimumPassPercentage, resultMap);
            return resultMap;
        } catch (Exception ex) {
            logger.error("Error when verifying assessment. Error : ", ex);
        }
        return new HashMap<>();
    }

    @Override
    public Map<String, Object> filterQuestionMapDetailV7(Map<String, Object> questionMapResponse, String primaryCategory) {
        List<String> questionParams = serverProperties.getAssessmentQuestionParams();
        Map<String, Object> updatedQuestionMap = new HashMap<>();
        for (String questionParam : questionParams) {
            if (questionMapResponse.containsKey(questionParam)) {
                updatedQuestionMap.put(questionParam, questionMapResponse.get(questionParam));
            }
        }
        if (questionMapResponse.containsKey(Constants.EDITOR_STATE) && primaryCategory.equalsIgnoreCase(Constants.PRACTICE_QUESTION_SET)) {
            Map<String, Object> editorState = (Map<String, Object>) questionMapResponse.get(Constants.EDITOR_STATE);
            updatedQuestionMap.put(Constants.EDITOR_STATE, editorState);
        }
        if (questionMapResponse.containsKey(Constants.CHOICES) && updatedQuestionMap.containsKey(Constants.PRIMARY_CATEGORY)) {
            Map<String, Object> choicesObj = (Map<String, Object>) questionMapResponse.get(Constants.CHOICES);
            Map<String, Object> updatedChoicesMap = new HashMap<>();
            if (choicesObj.containsKey(Constants.OPTIONS)) {
                List<Map<String, Object>> optionsMapList = (List<Map<String, Object>>) choicesObj.get(Constants.OPTIONS);
                updatedChoicesMap.put(Constants.OPTIONS, shuffleOptions(optionsMapList));
            }
            updatedQuestionMap.put(Constants.CHOICES, updatedChoicesMap);
        }
        if (questionMapResponse.containsKey(Constants.RHS_CHOICES) && updatedQuestionMap.containsKey(Constants.PRIMARY_CATEGORY) && updatedQuestionMap.get(Constants.PRIMARY_CATEGORY).toString().equalsIgnoreCase(Constants.MTF_QUESTION)) {
            List<Object> rhsChoicesObj = (List<Object>) questionMapResponse.get(Constants.RHS_CHOICES);
            Collections.shuffle(rhsChoicesObj);
            updatedQuestionMap.put(Constants.RHS_CHOICES, rhsChoicesObj);
        }

        return updatedQuestionMap;
    }

    /**
     * Retrieves the negative marks value from the question set details map.
     *
     * @param questionSetDetailsMap Map containing question set details
     * @return Negative marks value as an integer
     */
    private static int getNegativeMarksValue(Map<String, Object> questionSetDetailsMap) {
        int negativeMarksValue;
        String negativeWeightAgeEnabled;
        negativeWeightAgeEnabled = (String) questionSetDetailsMap.get(Constants.NEGATIVE_MARKING_PERCENTAGE);
        negativeMarksValue = Integer.parseInt(negativeWeightAgeEnabled.replace("%", ""));
        return negativeMarksValue;
    }

    /**
     * Extracts the question set section scheme from the question set details map.
     *
     * @param questionSetDetailsMap Map containing question set details
     * @return Question set section scheme as a map of string to object
     */
    private Map<String, Object> getQuestionSetSectionScheme(Map<String, Object> questionSetDetailsMap) {
        return mapper.convertValue(questionSetDetailsMap.get(Constants.QUESTION_SECTION_SCHEME), new TypeReference<Map<String, Object>>() {
        });
    }

    /**
     * Retrieves the minimum pass percentage from the question set details map.
     * If the minimum pass percentage is not present in the map, returns 0 as the default value.
     *
     * @param questionSetDetailsMap Map containing question set details
     * @return Minimum pass percentage as an integer
     */
    private int getMinimumPassPercentage(Map<String, Object> questionSetDetailsMap) {
        if (questionSetDetailsMap.get(Constants.MINIMUM_PASS_PERCENTAGE) != null) {
            return (int) questionSetDetailsMap.get(Constants.MINIMUM_PASS_PERCENTAGE);
        }
        return 0;
    }

    /**
     * Retrieves the assessment type from the question set details map.
     *
     * @param questionSetDetailsMap Map containing question set details
     * @return Assessment type as a string
     */
    private String getAssessmentType(Map<String, Object> questionSetDetailsMap) {
        return (String) questionSetDetailsMap.get(Constants.ASSESSMENT_TYPE);
    }

    /**
     * Retrieves the total marks for the assessment from the question set details map.
     *
     * @param questionSetDetailsMap Map containing question set details
     * @return Total marks as an integer
     */
    private int getTotalMarks(Map<String, Object> questionSetDetailsMap) {
        return (int) questionSetDetailsMap.get(Constants.TOTAL_MARKS);
    }

    private Map<String, Object> getQumlAnswersV7(List<String> questions, Map<String, Object> questionMap) {
        Map<String, Object> ret = new HashMap<>();
        for (String questionId : questions) {
            List<String> correctOption = new ArrayList<>();
            Map<String, Object> question = mapper.convertValue(questionMap.get(questionId), new TypeReference<Map<String, Object>>() {
            });
            if (question.containsKey(Constants.QUESTION_TYPE)) {
                String questionType = ((String) question.get(Constants.QUESTION_TYPE)).toLowerCase();
                Map<String, Object> editorStateObj = mapper.convertValue(question.get(Constants.EDITOR_STATE), new TypeReference<Map<String, Object>>() {
                });
                List<Map<String, Object>> options = mapper.convertValue(editorStateObj.get(Constants.OPTIONS), new TypeReference<List<Map<String, Object>>>() {
                });
                switch (questionType) {
                    case Constants.MTF:
                        for (Map<String, Object> option : options) {
                            Map<String, Object> valueObj = mapper.convertValue(option.get(Constants.VALUE), new TypeReference<Map<String, Object>>() {
                            });
                            correctOption.add(valueObj.get(Constants.VALUE).toString() + "-" + option.get(Constants.ANSWER).toString().toLowerCase());
                        }
                        break;
                    case Constants.FTB:
                        for (Map<String, Object> option : options) {
                            if ((boolean) option.get(Constants.ANSWER)) {
                                int position = Integer.parseInt((String) option.get("position")) - 1;
                                Map<String, Object> valueObj = mapper.convertValue(option.get(Constants.VALUE), new TypeReference<Map<String, Object>>() {
                                });
                                correctOption.add(position + "-" + valueObj.get(Constants.BODY).toString());
                            }
                        }
                        break;
                    case Constants.MCQ_SCA:
                    case Constants.MCQ_MCA:
                    case Constants.MCQ_SCA_TF:
                        for (Map<String, Object> option : options) {
                            if ((boolean) option.get(Constants.ANSWER)) {
                                Map<String, Object> valueObj = mapper.convertValue(option.get(Constants.VALUE), new TypeReference<Map<String, Object>>() {
                                });
                                correctOption.add(valueObj.get(Constants.VALUE).toString());
                            }
                        }
                        break;
                    default:
                        break;
                }
            } else {
                List<Map<String, Object>> optionsList = mapper.convertValue(question.get(Constants.OPTIONS), new TypeReference<List<Map<String, Object>>>() {
                });
                for (Map<String, Object> options : optionsList) {
                    if ((boolean) options.get(Constants.IS_CORRECT))
                        correctOption.add(options.get(Constants.OPTION_ID).toString());
                }
            }
            ret.put(question.get(Constants.IDENTIFIER).toString(), correctOption);
        }

        return ret;
    }


    /**
     * Handles the logic for processing type question v2.
     *
     * @param question       Map containing question details
     * @param marked         List of marked options
     * @param assessmentType Assessment type
     */
    private void handleqTypeQuestionV7(Map<String, Object> question, List<String> marked, String assessmentType) {
        if (question.containsKey(Constants.QUESTION_TYPE)) {
            String questionType = ((String) question.get(Constants.QUESTION_TYPE)).toLowerCase();
            Map<String, Object> editorStateObj = mapper.convertValue(question.get(Constants.EDITOR_STATE), new TypeReference<Map<String, Object>>() {
            });
            List<Map<String, Object>> options = mapper.convertValue(editorStateObj.get(Constants.OPTIONS), new TypeReference<List<Map<String, Object>>>() {
            });
            getMarkedIndexForEachQuestionV7(questionType, options, marked, assessmentType);
        }
    }


    /**
     * Retrieves the marked index for each question based on the question type.
     *
     * @param questionType   Type of the question
     * @param options        List of options for the question
     * @param marked         List to store the marked indexes
     * @param assessmentType Type of assessment
     */
    private void getMarkedIndexForEachQuestionV7(String questionType, List<Map<String, Object>> options, List<String> marked, String assessmentType) {
        logger.info("Getting marks or index for each question...");
        switch (questionType) {
            case Constants.MTF:
                for (Map<String, Object> option : options) {
                    marked.add(option.get(Constants.INDEX).toString() + "-" + option.get(Constants.SELECTED_ANSWER).toString().toLowerCase());
                }
                break;
            case Constants.FTB:
                for (Map<String, Object> option : options) {
                    marked.add(option.get(Constants.INDEX) + "-" + option.get(Constants.SELECTED_ANSWER));
                }
                break;
            case Constants.MCQ_SCA:
            case Constants.MCQ_MCA:
            case Constants.MCQ_SCA_TF:
                if (assessmentType.equalsIgnoreCase(Constants.QUESTION_WEIGHTAGE)) {
                    getMarkedIndexForQuestionWeightAge(options, marked);
                } else if (assessmentType.equalsIgnoreCase(Constants.OPTION_WEIGHTAGE)) {
                    getMarkedIndexForOptionWeightAge(options, marked);
                }
                break;
            case Constants.MCQ_MCA_W:
                if (assessmentType.equalsIgnoreCase(Constants.OPTION_WEIGHTAGE)) {
                    getMarkedIndexForOptionWeightAge(options, marked);
                }
                break;
            default:
                break;
        }
        logger.info("Marks or index retrieved successfully.");
    }
}
