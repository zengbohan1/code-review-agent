package com.shortdrama.agent.config;

import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * RAG 组件装配（Spring AI 1.1 无 transformers/pgvector 自动配置，手动装配更可控）：
 * <ul>
 *   <li>Embedding：本地 ONNX 模型（all-MiniLM-L6-v2，384 维，jar 内置，零外部依赖）——
 *       DeepSeek 无 embedding API，本地模型是唯一零成本方案</li>
 *   <li>VectorStore：pgvector + HNSW 索引 + 余弦距离</li>
 *   <li>QuestionAnswerAdvisor：检索 Top-K 注入 prompt（Advisor 横切，业务代码零侵入）</li>
 * </ul>
 */
@Configuration
public class RagConfig {

    /**
     * 本地 ONNX embedding 模型（bge-small-zh-v1.5，512 维，中文优化）。
     *
     * <p>模型文件在 models/onnx/ 下（.gitignore 排除，scripts/download-model.ps1 一键下载，
     * 从 hf-mirror 镜像获取）。<b>不能依赖无参构造</b>：默认从 GitHub raw 拉取英文模型，
     * 国内网络会卡死启动且英文模型对中文语义检索失效；显式指定本地中文模型后零网络依赖。
     */
    @Bean
    EmbeddingModel embeddingModel(
            @Value("${faq.model-path:models/onnx/model.onnx}") String modelPath,
            @Value("${faq.tokenizer-path:models/onnx/tokenizer.json}") String tokenizerPath) {
        TransformersEmbeddingModel model = new TransformersEmbeddingModel();
        model.setModelResource(new FileSystemResource(modelPath));
        model.setTokenizerResource(new FileSystemResource(tokenizerPath));
        model.setDisableCaching(true);
        return model;
    }

    /** pgvector 向量库：HNSW 索引（近似最近邻，检索性能 O(log n)）。 */
    @Bean
    PgVectorStore vectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(512)                        // bge-small-zh-v1.5 输出维度
                .distanceType(PgDistanceType.COSINE_DISTANCE)
                .indexType(PgIndexType.HNSW)
                .initializeSchema(true)                 // 自动建 vector_store 表
                .build();
    }

    /** RAG 问答 Advisor：检索 Top-3 相关 FAQ 片段注入系统提示。 */
    @Bean
    QuestionAnswerAdvisor questionAnswerAdvisor(PgVectorStore vectorStore) {
        return QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder().topK(3).build())
                .build();
    }
}
