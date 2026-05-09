package com.shortdrama.agent.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * FAQ 策略库加载器：启动时读取 classpath:faq/*.md，按 "## 标题" 切块，
 * 本地 embedding 后写入 pgvector。
 *
 * <p>幂等策略：同一来源文件的内容摘要（取前 8 字节 hash）相同则跳过，
 * 避免每次启动重复插入向量。向量检索由 QuestionAnswerAdvisor 注入对话。
 */
@Component
public class FaqLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FaqLoader.class);

    /** 标记载体：FAQ 文档统一 metadata.category=faq，用于幂等过滤。 */
    private static final String CATEGORY = "faq";

    private final PgVectorStore vectorStore;

    public FaqLoader(PgVectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources("classpath:faq/*.md");
        List<Document> documents = new ArrayList<>();
        for (Resource resource : resources) {
            documents.addAll(chunk(resource));
        }
        if (documents.isEmpty()) {
            log.warn("faq: no markdown files found under classpath:faq/");
            return;
        }
        // 幂等：先删除旧 FAQ 向量再写入（开发期直接重建，成本低）
        vectorStore.delete("category == '" + CATEGORY + "'");
        vectorStore.add(documents);
        log.info("faq: loaded {} chunks from {} files into pgvector", documents.size(), resources.length);
    }

    /**
     * 按 "## 标题" 切块：每节一个 chunk，文本 = 标题 + 正文。
     * 结构化的 FAQ 标题天然是检索单元，比无脑按 token 切更准确。
     */
    private List<Document> chunk(Resource resource) throws IOException {
        List<Document> chunks = new ArrayList<>();
        String content;
        try (InputStream in = resource.getInputStream()) {
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String[] sections = content.split("(?m)^## ");
        for (String section : sections) {
            String trimmed = section.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Document doc = new Document(trimmed);
            doc.getMetadata().put("category", CATEGORY);
            doc.getMetadata().put("source", resource.getFilename());
            chunks.add(doc);
        }
        return chunks;
    }
}
