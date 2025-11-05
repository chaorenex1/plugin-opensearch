package run.halo.opensearch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.stream.Streams;
import org.opensearch.OpenSearchException;
import org.opensearch.action.bulk.BulkRequest;
import org.opensearch.action.bulk.BulkResponse;
import org.opensearch.action.delete.DeleteRequest;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.client.indices.CreateIndexRequest;
import org.opensearch.client.indices.GetIndexRequest;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.index.query.BoolQueryBuilder;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.index.reindex.DeleteByQueryRequest;
import org.opensearch.search.SearchHit;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.search.fetch.subphase.highlight.HighlightBuilder;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.infra.utils.JsonUtils;
import run.halo.app.search.HaloDocument;
import run.halo.app.search.SearchEngine;
import run.halo.app.search.SearchOption;
import run.halo.meilisearch.HtmlUtils;

@Slf4j
@Component
public class OpensearchSearchEngine implements SearchEngine, DisposableBean,
    InitializingBean, ApplicationListener<run.halo.opensearch.ConfigUpdatedEvent> {

    private static final String[] HIGHLIGHT_ATTRIBUTES =
        {"title", "description", "content", "categories", "tags"};

    private final ExtensionClient client;

    private RestHighLevelClient openSearchClient;
    private String indexName;
    private volatile boolean available = false;

    public OpensearchSearchEngine(ExtensionClient client) {
        this.client = client;
    }

    private void refresh(String host, int port, String username, String password, String indexName) {
        if (this.available) {
            try {
                this.destroy();
            } catch (Exception e) {
                log.warn("Failed to destroy OpensearchSearchEngine during refreshing config", e);
            }
        }

        try {
            this.openSearchClient = OpensearchClient.getInstance(host, port, username, password,false);
            this.indexName = indexName;
            GetIndexRequest getIndexRequest = new GetIndexRequest(indexName);
            boolean exists=
                this.openSearchClient.indices().exists(getIndexRequest, RequestOptions.DEFAULT);
            if (!exists) {
                CreateIndexRequest createIndexRequest = new CreateIndexRequest(indexName);
                // Specify in the settings how many shards you want in the index
                createIndexRequest.settings(Settings.builder()
                    .put("index.number_of_shards", 1)
                    .put("index.number_of_replicas", 1)
                );

                // Define mapping for HaloDocument fields
                Map<String, Object> properties = new HashMap<>();
                properties.put("id", Map.of("type", "keyword"));
                properties.put("metadataName", Map.of("type", "keyword"));
                properties.put("title", Map.of(
                    "type", "text",
                    "analyzer", "hanlp_standard",
                    "fields", Map.of("keyword", Map.of("type", "keyword"))
                ));
                properties.put("description", Map.of(
                    "type", "text",
                    "analyzer", "hanlp_standard"
                ));
                properties.put("content", Map.of(
                    "type", "text",
                    "analyzer", "hanlp_standard"
                ));
                properties.put("categories", Map.of("type", "keyword"));
                properties.put("tags", Map.of("type", "keyword"));
                properties.put("published", Map.of("type", "boolean"));
                properties.put("recycled", Map.of("type", "boolean"));
                properties.put("exposed", Map.of("type", "boolean"));
                properties.put("ownerName", Map.of("type", "keyword"));
                properties.put("type", Map.of("type", "keyword"));
                properties.put("creationTimestamp", Map.of("type", "date"));
                properties.put("updateTimestamp", Map.of("type", "date"));
                properties.put("permalink", Map.of("type", "keyword"));
                properties.put("annotations", Map.of("type", "object", "enabled", false));

                Map<String, Object> mapping = new HashMap<>();
                mapping.put("properties", properties);

                createIndexRequest.mapping(mapping);
                this.openSearchClient.indices().create(createIndexRequest, RequestOptions.DEFAULT);
            }
            this.available = true;
            log.info("Opensearch client initialized successfully, index: {}", indexName);
        } catch (OpenSearchException e) {
            log.error("Failed to initialize Opensearch client", e);
            this.available = false;
        } catch (Exception e) {
            log.error("Unexpected error during Opensearch initialization", e);
            this.available = false;
        }
    }

    @Override
    public boolean available() {
        return available;
    }

    private HaloDocument cleanDocument(HaloDocument document) {
        if (document == null) {
            return document;
        }

        try {
            var originalJson = JsonUtils.mapper().writeValueAsString(document);
            var cleanedDocument = JsonUtils.mapper().readValue(originalJson, HaloDocument.class);
            
            cleanedDocument.setDescription(HtmlUtils.stripHtmlAndTrim(document.getDescription()));
            cleanedDocument.setContent(HtmlUtils.stripHtmlAndTrim(document.getContent()));
            
            return cleanedDocument;
        } catch (Exception e) {
            log.warn("Failed to clean document, using original", e);
            return document;
        }
    }

    @Override
    public void addOrUpdate(Iterable<HaloDocument> docs) {
        if (!available) {
            log.warn("Opensearch is not available, skipping addOrUpdate");
            return;
        }

        List<HaloDocument> documents = Streams.of(docs)
            .map(this::cleanDocument)
            .toList();

        try {
            BulkRequest bulkRequest = new BulkRequest();

            for (HaloDocument document : documents) {
                String docJson = JsonUtils.mapper().writeValueAsString(document);
                IndexRequest indexRequest = new IndexRequest(indexName)
                    .id(document.getMetadataName())
                    .source(docJson, XContentType.JSON);
                bulkRequest.add(indexRequest);
            }

            if (bulkRequest.numberOfActions() > 0) {
                BulkResponse bulkResponse = openSearchClient.bulk(bulkRequest, RequestOptions.DEFAULT);
                if (bulkResponse.hasFailures()) {
                    log.error("Bulk indexing has failures: {}", bulkResponse.buildFailureMessage());
                } else {
                    log.info("Successfully indexed {} documents", bulkResponse.getItems().length);
                }
            }
        } catch (IOException e) {
            log.error("Failed to add/update documents", e);
        }
    }

    @Override
    public void deleteDocument(Iterable<String> docIds) {
        if (!available) {
            log.warn("Opensearch is not available, skipping deleteDocument");
            return;
        }

        var metadataNames = Streams.of(docIds).map(id -> {
            String[] split = id.split("-", 2);
            return split.length > 1 ? split[1] : id;
        }).toList();

        try {
            BulkRequest bulkRequest = new BulkRequest();

            for (String metadataName : metadataNames) {
                DeleteRequest deleteRequest = new DeleteRequest(indexName, metadataName);
                bulkRequest.add(deleteRequest);
            }

            if (bulkRequest.numberOfActions() > 0) {
                BulkResponse bulkResponse = openSearchClient.bulk(bulkRequest, RequestOptions.DEFAULT);
                if (bulkResponse.hasFailures()) {
                    log.error("Bulk deletion has failures: {}", bulkResponse.buildFailureMessage());
                } else {
                    log.info("Successfully deleted {} documents", bulkResponse.getItems().length);
                }
            }
        } catch (OpenSearchException | IOException e) {
            log.error("Failed to delete documents", e);
        }
    }

    @Override
    public void deleteAll() {
        if (!available) {
            log.warn("Opensearch is not available, skipping deleteAll");
            return;
        }

        try {
            DeleteByQueryRequest deleteByQueryRequest = new DeleteByQueryRequest(indexName);
            deleteByQueryRequest.setQuery(QueryBuilders.matchAllQuery());

            openSearchClient.deleteByQuery(deleteByQueryRequest, RequestOptions.DEFAULT);
            log.info("Successfully deleted all documents from index: {}", indexName);
        } catch (OpenSearchException | IOException e) {
            log.error("Failed to delete all documents", e);
        }
    }

    @Override
    public run.halo.app.search.SearchResult search(SearchOption searchOption) {
        if (!available) {
            return new run.halo.app.search.SearchResult();
        }

        try {
            // Build bool query with filters
            BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();

            // Add search query on multiple fields
            if (searchOption.getKeyword() != null && !searchOption.getKeyword().isEmpty()) {
                // Boost title field for better relevance
                boolQuery.must(QueryBuilders.multiMatchQuery(searchOption.getKeyword())
                    .field("title", 3.0f)
                    .field("description", 2.0f)
                    .field("content", 1.0f));
            } else {
                boolQuery.must(QueryBuilders.matchAllQuery());
            }

            // Add filters
            boolQuery.filter(QueryBuilders.termQuery("recycled", false));
            boolQuery.filter(QueryBuilders.termQuery("exposed", true));
            boolQuery.filter(QueryBuilders.termQuery("published", true));

            // Build search source
            SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
            searchSourceBuilder.query(boolQuery);
            searchSourceBuilder.from(0);
            searchSourceBuilder.size(searchOption.getLimit());

            // Add highlighting
            HighlightBuilder highlightBuilder = new HighlightBuilder();
            for (String field : HIGHLIGHT_ATTRIBUTES) {
                highlightBuilder.field(field)
                    .preTags(searchOption.getHighlightPreTag())
                    .postTags(searchOption.getHighlightPostTag())
                    .fragmentSize(200)
                    .numOfFragments(1);
            }
            searchSourceBuilder.highlighter(highlightBuilder);

            // Execute search
            SearchRequest searchRequest = new SearchRequest(indexName);
            searchRequest.source(searchSourceBuilder);

            SearchResponse searchResponse = openSearchClient.search(searchRequest, RequestOptions.DEFAULT);

            // Convert results
            var result = new run.halo.app.search.SearchResult();
            result.setLimit(searchOption.getLimit());
            long totalHits = 0;
            if (searchResponse.getHits().getTotalHits() != null) {
                try {
                    // TotalHits.value is a public field in Lucene/OpenSearch
                    org.apache.lucene.search.TotalHits totalHitsObj = searchResponse.getHits().getTotalHits();
                    var field = totalHitsObj.getClass().getField("value");
                    totalHits = field.getLong(totalHitsObj);
                } catch (Exception e) {
                    log.warn("Failed to get total hits count", e);
                }
            }
            result.setTotal(totalHits);
            result.setKeyword(searchOption.getKeyword());
            result.setProcessingTimeMillis(searchResponse.getTook().getMillis());
            result.setHits(convertHits(searchResponse.getHits().getHits()));

            return result;
        } catch (OpenSearchException | IOException e) {
            log.error("Failed to search", e);
            return new run.halo.app.search.SearchResult();
        }
    }

    private List<HaloDocument> convertHits(SearchHit[] hits) {
        List<HaloDocument> documents = new ArrayList<>();

        for (SearchHit hit : hits) {
            try {
                String sourceJson = hit.getSourceAsString();
                HaloDocument document = JsonUtils.mapper().readValue(sourceJson, HaloDocument.class);

                // Apply highlights if available
                if (hit.getHighlightFields() != null && !hit.getHighlightFields().isEmpty()) {
                    var highlightFields = hit.getHighlightFields();

                    if (highlightFields.containsKey("title")) {
                        StringBuilder sb = new StringBuilder();
                        for (var fragment : highlightFields.get("title").fragments()) {
                            sb.append(fragment.string()).append(" ");
                        }
                        document.setTitle(sb.toString().trim());
                    }

                    if (highlightFields.containsKey("description")) {
                        StringBuilder sb = new StringBuilder();
                        for (var fragment : highlightFields.get("description").fragments()) {
                            sb.append(fragment.string()).append(" ");
                        }
                        document.setDescription(sb.toString().trim());
                    }

                    if (highlightFields.containsKey("content")) {
                        StringBuilder sb = new StringBuilder();
                        for (var fragment : highlightFields.get("content").fragments()) {
                            sb.append(fragment.string()).append(" ");
                        }
                        document.setContent(sb.toString().trim());
                    }
                }

                documents.add(document);
            } catch (Exception e) {
                log.warn("Failed to convert search hit to HaloDocument", e);
            }
        }

        return documents;
    }

    @Override
    public void destroy() throws Exception {
        this.available = false;
    }

    @Override
    public void onApplicationEvent(ConfigUpdatedEvent event) {
        var properties = event.getOpensearchProperties();

        var host = properties.getHost();
        var port = properties.getPort();
        var indexName = properties.getIndexName();
        var username = properties.getUsername();
        var password = properties.getPassword();

        if (host == null || host.isEmpty()) {
            log.warn("Opensearch host is not configured");
            return;
        }

        refresh(host, port, username, password, indexName);
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        var configMapOpt = client.fetch(ConfigMap.class, "Opensearch-engine-config");
        if (configMapOpt.isEmpty()) {
            log.warn("Opensearch configuration not found");
            return;
        }

        var configMap = configMapOpt.get();
        var data = configMap.getData();
        if (data == null || !data.containsKey("basic")) {
            log.warn("Opensearch configuration data is missing");
            return;
        }

        try {
            var properties = JsonUtils.mapper().readValue(data.get("basic"), OpensearchProperties.class);
            var host = properties.getHost();
            var port = properties.getPort();
            var indexName = properties.getIndexName();
            var username = properties.getUsername();
            var password = properties.getPassword();

            if (host != null && !host.isEmpty()) {
                refresh(host, port, username, password, indexName);
            }
        } catch (Exception e) {
            log.error("Failed to parse Opensearch configuration", e);
        }
    }
}