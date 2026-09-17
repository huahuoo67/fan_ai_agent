package com.fan.fanaiagent.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType.COSINE_DISTANCE;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.HNSW;

// 为方便开发调试和部署，临时注释，如果需要使用 PgVector 存储知识库，取消注释即可
@Slf4j
@Configuration
public class PgVectorVectorStoreConfig {

    /**
     * DashScope Embedding 接口单次请求最多接受 20 条文本，
     * 超出会返回 HTTP 400: batch size is invalid, it should not be larger than 20.
     */
    private static final int EMBEDDING_BATCH_SIZE = 20;

    /** 是否清理「库里存在、但知识库中已删除」的旧 chunk */
    private static final boolean REMOVE_STALE_DOCUMENTS = true;

    @Bean
    public VectorStore pgVectorVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel dashscopeEmbeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, dashscopeEmbeddingModel)
                // .dimensions(1536)                 // 不显式指定，由 Spring AI 按模型真实维度自动推断
                .distanceType(COSINE_DISTANCE)       // Optional: defaults to COSINE_DISTANCE
                .indexType(HNSW)                     // Optional: defaults to HNSW
                .initializeSchema(true)              // Optional: defaults to false
                .schemaName("public")                // Optional: defaults to "public"
                .vectorTableName("vector_store")     // Optional: defaults to "vector_store"
                .maxDocumentBatchSize(10000)         // Optional: defaults to 10000
                .build();
    }

    /**
     * 把 Markdown 知识库同步到 PgVector —— 按内容指纹做增量，不改动就不重新向量化。
     *
     * 为什么必须自己做增量：Spring AI 的 Document 默认用 {@code RandomIdGenerator}
     * （直接 UUID.randomUUID()，完全不看内容），所以每次 loadMarkdowns() 得到的 id 都是全新的。
     * 那样 PgVectorStore.add() 里的 ON CONFLICT (id) 永远不生效，退化成纯 INSERT：
     * 每次启动都会重新调用一遍 embedding，并把整份知识库重复写一份，表会无限膨胀。
     *
     * 做法：用「文件名 + chunk 正文」算一个确定性 UUID 作为 id，
     * 启动时先查库里已有 id，只对新增/变更的 chunk 调 embedding，
     * 再清理知识库中已删除的旧 chunk。
     *
     * 注意：这段逻辑不能写在 pgVectorVectorStore 的 @Bean 方法体内。
     * @Bean 方法体先于 Spring 的 afterPropertiesSet() 回调执行，而建表（initializeSchema）
     * 是在 afterPropertiesSet() 里做的。若在方法体内直接 add()，表还没建出来，
     * 会报 relation "public.vector_store" does not exist。
     */
    @Bean
    public ApplicationRunner pgVectorDataLoader(@Qualifier("pgVectorVectorStore") VectorStore pgVectorVectorStore,
                                                LoveAppDocumentLoader loveAppDocumentLoader,
                                                JdbcTemplate jdbcTemplate) {
        return args -> {
            // 1. 加载文档，并给每个 chunk 赋一个「内容不变则 id 不变」的稳定 id
            List<Document> documents = loveAppDocumentLoader.loadMarkdowns().stream()
                    .map(PgVectorVectorStoreConfig::withStableId)
                    .toList();

            // 2. 查出库里已有的 id
            Set<String> existingIds = new HashSet<>(jdbcTemplate.queryForList(
                    "SELECT id::text FROM public.vector_store", String.class));

            // 3. 只向量化新增 / 内容有变化的 chunk
            List<Document> changedDocuments = documents.stream()
                    .filter(doc -> !existingIds.contains(doc.getId()))
                    .toList();

            if (changedDocuments.isEmpty()) {
                log.info("PgVector 知识库无变化，跳过向量化（当前共 {} 条）", documents.size());
            } else {
                int totalBatch = (changedDocuments.size() + EMBEDDING_BATCH_SIZE - 1) / EMBEDDING_BATCH_SIZE;
                for (int i = 0; i < changedDocuments.size(); i += EMBEDDING_BATCH_SIZE) {
                    List<Document> batch = changedDocuments.subList(i,
                            Math.min(i + EMBEDDING_BATCH_SIZE, changedDocuments.size()));
                    pgVectorVectorStore.add(batch);
                    log.info("PgVector 写入进度：第 {}/{} 批，本批 {} 条",
                            i / EMBEDDING_BATCH_SIZE + 1, totalBatch, batch.size());
                }
                log.info("PgVector 增量同步完成：新增/更新 {} 条，未变化跳过 {} 条",
                        changedDocuments.size(), documents.size() - changedDocuments.size());
            }

            // 4. 清理知识库中已删除的 chunk（只处理带 filename 的记录，避免误删手工写入的数据）
            if (REMOVE_STALE_DOCUMENTS) {
                removeStaleDocuments(jdbcTemplate, documents);
            }
        };
    }

    /**
     * 给 Document 赋一个确定性 id：同一文件、同一段正文 → 永远得到同一个 UUID。
     * 这样内容没变时 id 不变，add() 的 upsert 才能真正生效，也就不会重复向量化。
     */
    private static Document withStableId(Document raw) {
        String filename = String.valueOf(raw.getMetadata().getOrDefault("filename", ""));
        String fingerprint = filename + "|" + raw.getText();
        String stableId = UUID.nameUUIDFromBytes(fingerprint.getBytes(StandardCharsets.UTF_8)).toString();
        return new Document(stableId, raw.getText(), raw.getMetadata());
    }

    /** 删除「库里已有、但本次加载的文档集合中不存在」的记录。 */
    private static void removeStaleDocuments(JdbcTemplate jdbcTemplate, List<Document> documents) {
        Set<String> currentIds = documents.stream().map(Document::getId).collect(Collectors.toSet());
        if (currentIds.isEmpty()) {
            log.warn("本次加载到的文档为空，跳过旧数据清理，避免误删整张表");
            return;
        }
        String placeholders = currentIds.stream().map(id -> "?").collect(Collectors.joining(", "));
        int removed = jdbcTemplate.update(
                "DELETE FROM public.vector_store "
                        + "WHERE metadata->>'filename' IS NOT NULL AND id::text NOT IN (" + placeholders + ")",
                currentIds.toArray());
        if (removed > 0) {
            log.info("PgVector 已清理知识库中不存在的旧 chunk：{} 条", removed);
        }
    }
}
