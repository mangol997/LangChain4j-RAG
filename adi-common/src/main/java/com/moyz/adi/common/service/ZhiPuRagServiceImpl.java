package com.moyz.adi.common.service;


import dev.langchain4j.community.model.zhipu.ZhipuAiChatModel;
import dev.langchain4j.community.model.zhipu.ZhipuAiEmbeddingModel;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentByLineSplitter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.elasticsearch.ElasticsearchEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

import static com.moyz.adi.common.util.Utils.glob;
import static com.moyz.adi.common.util.Utils.toPath;
import static dev.langchain4j.data.document.loader.FileSystemDocumentLoader.loadDocuments;
import org.testcontainers.elasticsearch.ElasticsearchContainer;

/**
 * @description:  智谱-ES搭建
 * @author mangolzhang
 * @date 2025/3/16 22:41
 */
@Service
@Slf4j
public class ZhiPuRagServiceImpl implements ZhiPuRagService {

    private final String ZHIPU_KEY = "e56961ba05f84b659df95d85620eee96.4mDPmFX5FloIddyp";
    @Override
    public void zhiPuChatModel() {
        ChatLanguageModel qwenModel = ZhipuAiChatModel.builder()
                .apiKey(ZHIPU_KEY)
                .callTimeout(Duration.ofSeconds(60))
                .connectTimeout(Duration.ofSeconds(60))
                .writeTimeout(Duration.ofSeconds(60))
                .readTimeout(Duration.ofSeconds(60))
                .build();
    }

    /**
     * @description: 获取分割文件
     * @author mangolzhang
     * @date: 2025/3/17 22:15
     */
    @Override
    public void spiltContent() {
        List<Document> documents = loadDocuments(toPath("documents/"), glob("*.txt"));
        DocumentByLineSplitter splitter = new DocumentByLineSplitter(200, 2000);
        List<TextSegment> textSegments = splitter.splitAll(documents);
        //向量化
        ZhipuAiEmbeddingModel embeddingModel = ZhipuAiEmbeddingModel.builder()
                .apiKey(ZHIPU_KEY)
                .logRequests(true)
                .logResponses(true)
                .maxRetries(1)
                .callTimeout(Duration.ofSeconds(60))
                .connectTimeout(Duration.ofSeconds(60))
                .writeTimeout(Duration.ofSeconds(60))
                .readTimeout(Duration.ofSeconds(60))
                .build();
        //向量化
        Response<List<Embedding>> listResponse = embeddingModel.embedAll(textSegments);
        ElasticsearchContainer elastic =
                new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:8.17.0")
                        .withPassword("elastic");
        elastic.start();

        final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials("elastic", "elastic"));

        RestClient client = RestClient.builder(HttpHost.create("http://192.168.9.181:9200"))
                .setHttpClientConfigCallback(httpClientBuilder -> {
                    httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                    httpClientBuilder.setSSLContext(elastic.createSslContextFromCa());
                    return httpClientBuilder;
                })
                .build();
        log.info("vector data successful");
        //向量化存储
        EmbeddingStore<TextSegment> embeddingStore = ElasticsearchEmbeddingStore.builder()
                .indexName("vector store")
                .restClient(client)
                .build();
        embeddingStore.addAll(listResponse.content());
        log.info("向量数据插入成功");

        //query data
        String question = "I want to pilot a car";
        Embedding questionAsVector = embeddingModel.embed(question).content();

        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(questionAsVector)
                        .build());
    }

    @Override
    public void queryContent() {

    }
}
