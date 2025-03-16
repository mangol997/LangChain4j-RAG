package com.moyz.adi.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.moyz.adi.common.base.SearchEngineRespTypeHandler;
import com.moyz.adi.common.base.UUIDTypeHandler;
import com.moyz.adi.common.dto.SearchEngineResp;
import com.moyz.adi.common.rag.ApacheAgeGraphStore;
import com.moyz.adi.common.rag.EmbeddingRAG;
import com.moyz.adi.common.rag.GraphRAG;
import com.moyz.adi.common.util.LocalDateTimeUtil;
import com.pgvector.PGvector;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Configuration
public class BeanConfig {

    @Value("${spring.datasource.url}")
    private String dataBaseUrl;

    @Value("${spring.datasource.username}")
    private String dataBaseUserName;

    @Value("${spring.datasource.password}")
    private String dataBasePassword;

    @Bean
    public RestTemplate restTemplate() {
        log.info("Configuration:create restTemplate");
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        // 设置建立连接超时时间  毫秒
        requestFactory.setConnectTimeout(60000);
        // 设置读取数据超时时间  毫秒
        requestFactory.setReadTimeout(60000);
        RestTemplate restTemplate = new RestTemplate();
        // 注册LOG拦截器
//        restTemplate.setInterceptors(Lists.newArrayList(new LogClientHttpRequestInterceptor()));
        restTemplate.setRequestFactory(new BufferingClientHttpRequestFactory(requestFactory));

        return restTemplate;
    }

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        log.info("Configuration:create objectMapper");
        ObjectMapper objectMapper = new Jackson2ObjectMapperBuilder().createXmlMapper(false).build();
        objectMapper.registerModules(LocalDateTimeUtil.getSimpleModule(), new JavaTimeModule(), new Jdk8Module());
        //设置null值不参与序列化(字段不被显示)
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return objectMapper;
    }

    @Bean(name = "mainExecutor")
    @Primary
    public AsyncTaskExecutor mainExecutor() {
        int processorsNum = Runtime.getRuntime().availableProcessors();
        log.info("mainExecutor,processorsNum:{}", processorsNum);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(processorsNum * 2);
        executor.setMaxPoolSize(100);
        return executor;
    }

    @Bean(name = "imagesExecutor")
    public AsyncTaskExecutor imagesExecutor() {
        int processorsNum = Runtime.getRuntime().availableProcessors();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        log.info("imagesExecutor corePoolSize:{},maxPoolSize:{}", processorsNum, processorsNum * 2);
        executor.setCorePoolSize(processorsNum);
        executor.setMaxPoolSize(processorsNum * 2);
        return executor;
    }

    @Bean
    @Primary
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource)
            throws Exception {
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(dataSource);
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 分页插件
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        // 防止全表更新
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());
        bean.setPlugins(interceptor);
        bean.setMapperLocations(
                new PathMatchingResourcePatternResolver().getResources("classpath*:/mapper/*.xml"));
        MybatisConfiguration configuration = bean.getConfiguration();
        if (null == configuration) {
            configuration = new MybatisConfiguration();
            bean.setConfiguration(configuration);
        }
        bean.getConfiguration().getTypeHandlerRegistry().register(PGvector.class, PostgresVectorTypeHandler.class);
        bean.getConfiguration().getTypeHandlerRegistry().register(SearchEngineResp.class, SearchEngineRespTypeHandler.class);
        bean.getConfiguration().getTypeHandlerRegistry().register(UUID.class, UUIDTypeHandler.class);
        return bean.getObject();
    }

    @Bean(name = "kbEmbeddingStore")
    @Primary
    public EmbeddingStore<TextSegment> initKbEmbeddingStore() {
        // 正则表达式匹配
        String regex = "jdbc:postgresql://([^:/]+):(\\d+)/(\\w+).+";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(dataBaseUrl);

        String host = "";
        String port = "";
        String databaseName = "";
        if (matcher.matches()) {
            host = matcher.group(1);
            port = matcher.group(2);
            databaseName = matcher.group(3);

            log.info("Host: " + host);
            log.info("Port: " + port);
            log.info("Database: " + databaseName);
        } else {
            throw new RuntimeException("parse url error");
        }
        return PgVectorEmbeddingStore.builder()
                .host(host)
                .port(Integer.parseInt(port))
                .database(databaseName)
                .user(dataBaseUserName)
                .password(dataBasePassword)
                .dimension(384)
                .createTable(true)
                .dropTableFirst(false)
                .table("adi_knowledge_base_embedding")
                .build();
    }

    @Bean
    @Primary
    public EmbeddingRAG initKnowledgeBaseRAGService(EmbeddingStore<TextSegment> kbEmbeddingStore) {
        EmbeddingRAG ragService = new EmbeddingRAG(kbEmbeddingStore);
        ragService.init();
        return ragService;
    }

    @Bean(name = "searchEmbeddingStore")
    public EmbeddingStore<TextSegment> initSearchEmbeddingStore() {
        // 正则表达式匹配
        String regex = "jdbc:postgresql://([^:/]+):(\\d+)/(\\w+).+";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(dataBaseUrl);

        String host = "";
        String port = "";
        String databaseName = "";
        if (matcher.matches()) {
            host = matcher.group(1);
            port = matcher.group(2);
            databaseName = matcher.group(3);

            log.info("Host: " + host);
            log.info("Port: " + port);
            log.info("Database: " + databaseName);
        } else {
            throw new RuntimeException("parse url error");
        }
        return PgVectorEmbeddingStore.builder()
                .host(host)
                .port(Integer.parseInt(port))
                .database(databaseName)
                .user(dataBaseUserName)
                .password(dataBasePassword)
                .dimension(384)
                .createTable(true)
                .dropTableFirst(false)
                .table("adi_ai_search_embedding")
                .build();
    }

    @Bean(name = "searchRagService")
    public EmbeddingRAG initSearchRAG(EmbeddingStore<TextSegment> searchEmbeddingStore) {
        EmbeddingRAG ragService = new EmbeddingRAG(searchEmbeddingStore);
        ragService.init();
        return ragService;
    }

    @Bean(name = "kbGraphStore")
    @Primary
    public ApacheAgeGraphStore initApacheAgeGraphStore() {
        String regex = "jdbc:postgresql://([^:/]+):(\\d+)/(\\w+).+";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(dataBaseUrl);

        String host = "";
        String port = "";
        String databaseName = "";
        if (matcher.matches()) {
            host = matcher.group(1);
            port = matcher.group(2);
            databaseName = matcher.group(3);

            log.info("Host: " + host);
            log.info("Port: " + port);
            log.info("Database: " + databaseName);
        } else {
            throw new RuntimeException("parse url error");
        }
        return ApacheAgeGraphStore.builder()
                .host(host)
                .port(Integer.parseInt(port))
                .database(databaseName)
                .user(dataBaseUserName)
                .password(dataBasePassword)
                .createGraph(true)
                .dropGraphFirst(false)
                .graphName("adi_knowledge_base_graph")
                .build();
    }

    @Bean(name = "graphRag")
    @Primary
    public GraphRAG initGraphRAG(ApacheAgeGraphStore kbGraphStore) {
        return new GraphRAG(kbGraphStore);
    }

//    @Bean(name = "queryRouterRagService")
//    public RAGService queryRouterRagService() {
//        RAGService ragService = new RAGService("adi_advanced_rag_query_embedding", dataBaseUrl, dataBaseUserName, dataBasePassword);
//        ragService.init();
//        return ragService;
//    }
}
