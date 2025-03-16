package com.moyz.adi.common.service;

import com.moyz.adi.common.base.ThreadContext;
import com.moyz.adi.common.cosntant.AdiConstant;
import com.moyz.adi.common.dto.SearchEngineResp;
import com.moyz.adi.common.dto.SearchReturn;
import com.moyz.adi.common.dto.SearchReturnWebPage;
import com.moyz.adi.common.entity.AiModel;
import com.moyz.adi.common.entity.AiSearchRecord;
import com.moyz.adi.common.entity.User;
import com.moyz.adi.common.helper.LLMContext;
import com.moyz.adi.common.helper.SSEEmitterHelper;
import com.moyz.adi.common.rag.CompositeRAG;
import com.moyz.adi.common.rag.EmbeddingRAG;
import com.moyz.adi.common.searchengine.SearchEngineServiceContext;
import com.moyz.adi.common.util.UuidUtil;
import com.moyz.adi.common.vo.AssistantChatParams;
import com.moyz.adi.common.vo.SseAskParams;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static com.moyz.adi.common.enums.ErrorEnum.B_NO_ANSWER;

/**
 * RAG search
 */
@Slf4j
@Service
public class SearchService {

    @Lazy
    @Resource
    private SearchService self;

    @Resource
    private EmbeddingRAG searchRagService;

    @Resource
    private CompositeRAG compositeRAG;

    @Resource
    private SSEEmitterHelper sseEmitterHelper;

    @Resource
    private AiSearchRecordService aiSearchRecordService;

    @Resource
    private UserDayCostService userDayCostService;

    @Resource
    private AiModelService aiModelService;

    @Resource
    private AsyncTaskExecutor mainExecutor;

    public SseEmitter search(boolean isBriefSearch, String searchText, String engineName, String modelName) {
        User user = ThreadContext.getCurrentUser();
        SseEmitter sseEmitter = new SseEmitter();
        if (!sseEmitterHelper.checkOrComplete(user, sseEmitter)) {
            return sseEmitter;
        }
        sseEmitterHelper.startSse(user, sseEmitter);
        self.asyncSearch(user, sseEmitter, isBriefSearch, searchText, engineName, modelName);
        return sseEmitter;
    }

    @Async
    public void asyncSearch(User user, SseEmitter sseEmitter, boolean isBriefSearch, String searchText, String searchName, String modelName) {
        SearchReturn searchResult = SearchEngineServiceContext.getService(searchName).search(searchText);
        if (StringUtils.isNotBlank(searchResult.getErrorMessage())) {
            sseEmitterHelper.sendAndComplete(user.getId(), sseEmitter, searchResult.getErrorMessage());
            return;
        }
        if (CollectionUtils.isEmpty(searchResult.getItems())) {
            sseEmitterHelper.sendAndComplete(user.getId(), sseEmitter, B_NO_ANSWER.getInfo());
            return;
        }
        boolean sendFail = false;
        try {
            sseEmitter.send(SseEmitter.event().name(AdiConstant.SSEEventName.AI_SEARCH_SOURCE_LINKS).data(searchResult.getItems()));
        } catch (IOException e) {
            sendFail = true;
            log.error("asyncSearch error", e);
            sseEmitterHelper.sendErrorAndComplete(user.getId(), sseEmitter, e.getMessage());
        }
        if (sendFail) {
            return;
        }
        if (isBriefSearch) {
            briefSearch(user, searchText, modelName, searchResult.getItems(), sseEmitter);
        } else {
            detailSearch(user, searchText, searchName, modelName, searchResult.getItems(), sseEmitter);
        }
    }

    /**
     * 1.Search by search engine
     * 2.Create prompt by search response
     * 3.Send prompt to llm
     *
     * @param user
     * @param searchText
     * @param modelName
     * @param resultItems
     * @param sseEmitter
     */
    public void briefSearch(User user, String searchText, String modelName, List<SearchReturnWebPage> resultItems, SseEmitter sseEmitter) {
        log.info("briefSearch,searchText:{}", searchText);
        StringBuilder builder = new StringBuilder();
        for (SearchReturnWebPage item : resultItems) {
            builder.append(item.getSnippet()).append("\n\n");
        }
        String ragQuestion = builder.toString();
        String prompt = EmbeddingRAG.parsePromptTemplate(searchText, ragQuestion);

        SearchEngineResp resp = new SearchEngineResp().setItems(resultItems);

        SseAskParams sseAskParams = new SseAskParams();
        sseAskParams.setUuid(UuidUtil.createShort());
        sseAskParams.setAssistantChatParams(AssistantChatParams.builder().systemMessage(StringUtils.EMPTY).userMessage(prompt).build());
        sseAskParams.setSseEmitter(sseEmitter);
        sseAskParams.setModelName(modelName);
        sseAskParams.setUser(user);
        sseEmitterHelper.commonProcess(sseAskParams, (response, promptMeta, answerMeta) -> {

            AiModel aiModel = aiModelService.getByName(modelName);

            AiSearchRecord newRecord = new AiSearchRecord();
            newRecord.setUuid(sseAskParams.getUuid());
            newRecord.setQuestion(searchText);
            newRecord.setSearchEngineResp(resp);
            newRecord.setPrompt(prompt);
            newRecord.setPromptTokens(promptMeta.getTokens());
            newRecord.setAnswer(response);
            newRecord.setAnswerTokens(answerMeta.getTokens());
            newRecord.setUserUuid(user.getUuid());
            newRecord.setUserId(user.getId());
            newRecord.setAiModelId(null != aiModel ? aiModel.getId() : 0L);
            aiSearchRecordService.save(newRecord);

            if (null != aiModel) {
                userDayCostService.appendCostToUser(user, promptMeta.getTokens() + answerMeta.getTokens(), aiModel.getIsFree());
            }
        });
    }

    /**
     * 1.Search by search engine
     * 2.Save the response to pgvector
     * 3.Retrieve document and create prompt
     * 4.Send prompt to llm
     *
     * @param user
     * @param searchText
     * @param engineName
     * @param modelName
     * @param resultItems
     * @param sseEmitter
     */
    public void detailSearch(User user, String searchText, String engineName, String modelName, List<SearchReturnWebPage> resultItems, SseEmitter sseEmitter) {
        log.info("detailSearch,searchText:{}", searchText);
        AiModel aiModel = LLMContext.getAiModel(modelName);
        //Save to DB
        SearchEngineResp resp = new SearchEngineResp().setItems(resultItems);
        AiSearchRecord newRecord = new AiSearchRecord();
        String searchUuid = UuidUtil.createShort();
        newRecord.setUuid(searchUuid);
        newRecord.setQuestion(searchText);
        newRecord.setSearchEngineResp(resp);
        newRecord.setUserId(user.getId());
        newRecord.setUserUuid(user.getUuid());
        newRecord.setAiModelId(aiModel.getId());
        aiSearchRecordService.save(newRecord);

        CountDownLatch countDownLatch = new CountDownLatch(resultItems.size());
        for (int i = 0; i < resultItems.size(); i++) {
            int finalI = i;
            mainExecutor.execute(() -> {
                try {
                    SearchReturnWebPage item = resultItems.get(finalI);
                    String content;
                    if (finalI < 2) {
                        content = getContentFromRemote(item);

                        //Fill content with html body text
                        item.setContent(content);
                    } else {
                        content = item.getSnippet();
                    }

                    //embedding
                    if (StringUtils.isNotBlank(content)) {
                        Metadata metadata = new Metadata();
                        metadata.put(AdiConstant.MetadataKey.ENGINE_NAME, engineName);
                        metadata.put(AdiConstant.MetadataKey.SEARCH_UUID, searchUuid);
                        Document document = new Document(content, metadata);
                        searchRagService.ingest(document, 0, null);
                    }
                } catch (Exception e) {
                    log.error("Detail search error,uuid:{}", searchUuid, e);
                } finally {
                    countDownLatch.countDown();
                }
            });
        }
        try {
            countDownLatch.await();
        } catch (InterruptedException e) {
            log.error("CountDownLatch await error,uuid:{}", searchUuid, e);
            Thread.currentThread().interrupt();
        }

        log.info("Create prompt");
        int maxInputTokens = aiModel.getMaxInputTokens();
        int maxResults = EmbeddingRAG.getRetrieveMaxResults(searchText, maxInputTokens);
        ContentRetriever contentRetriever = searchRagService.createRetriever(Map.of(AdiConstant.MetadataKey.SEARCH_UUID, searchUuid), maxResults, 0, false);

        SseAskParams sseAskParams = new SseAskParams();
        sseAskParams.setUuid(searchUuid);
        sseAskParams.setUser(user);
        sseAskParams.setAssistantChatParams(
                AssistantChatParams.builder()
                        .messageId(user.getUuid() + "-search")
                        .systemMessage(StringUtils.EMPTY)
                        .userMessage(searchText)
                        .build()
        );
        sseAskParams.setSseEmitter(sseEmitter);
        sseAskParams.setModelName(modelName);
        compositeRAG.ragChat(List.of(contentRetriever), sseAskParams, (response, promptMeta, answerMeta) -> {

            AiSearchRecord existRecord = aiSearchRecordService.lambdaQuery().eq(AiSearchRecord::getUuid, searchUuid).one();

            AiSearchRecord updateRecord = new AiSearchRecord();
            updateRecord.setId(existRecord.getId());
            //Update search engine response content.(with html body text)
            updateRecord.setSearchEngineResp(new SearchEngineResp().setItems(resultItems));
            //TODO 增强后的prompt
            updateRecord.setPrompt("");
            updateRecord.setPromptTokens(promptMeta.getTokens());
            updateRecord.setAnswer(response);
            updateRecord.setAnswerTokens(answerMeta.getTokens());
            aiSearchRecordService.updateById(updateRecord);

            userDayCostService.appendCostToUser(user, promptMeta.getTokens() + answerMeta.getTokens(), aiModel.getIsFree());
        });
    }

    private String getContentFromRemote(SearchReturnWebPage item) {
        String result = "";
        try {
            String url = item.getLink();
            if (StringUtils.isBlank(url) || !url.startsWith("http")) {
                return result;
            }
            org.jsoup.nodes.Document doc = Jsoup.connect(url).ignoreContentType(true).get();
            if (!doc.getElementsByTag("main").isEmpty()) {
                result = doc.getElementsByTag("main").get(0).html();
            } else {
                result = doc.body().html();
            }
            if (StringUtils.isBlank(result)) {
                log.error("Empty content from {}, use snippet instead", item.getLink());
                return item.getSnippet();
            }
        } catch (Exception e) {
            log.error("Failed to load document from {}, use snippet instead", item.getLink(), e);
        }
        Cleaner cleaner = new Cleaner(Safelist.none());
        return cleaner.clean(Jsoup.parse(result)).text();
    }
}
