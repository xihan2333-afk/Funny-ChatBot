package cn.edu.scut.chatbot.infrastructure.qdrant;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import cn.edu.scut.chatbot.exception.RetryableException;
import cn.edu.scut.chatbot.infrastructure.http.HttpClientHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Qdrant REST 薄客户端实现（六端点，contracts/memory-retrieval.md §3）：
 * <ul>
 *   <li>复用 HttpClientHolder 共享 JDK HttpClient——依赖矩阵零新增（不引官方 Java 客户端）；</li>
 *   <li>读超时请求级 3 秒固定（检索是主链路同步调用，独立于 llm.translator.read_timeout_seconds）；</li>
 *   <li>api key 头读部署层配置（可空——本地无鉴权部署兼容）；</li>
 *   <li>错误统一归一点分码：网络/超时/上游错误 → memory.qdrant.unavailable，
 *       响应不可解析 → memory.qdrant.bad_response，均抛可重试子类；</li>
 *   <li>写点与删除带 wait=true（读写一致优先——测试断言与「MySQL 先落 Qdrant 后写」
 *       的次序验证都依赖写完成即可见）。</li>
 * </ul>
 */
@Component
public class QdrantRestClient implements QdrantClient {

    private static final Logger log = LoggerFactory.getLogger(QdrantRestClient.class);

    /**
     * 读超时（秒）——检索是主链路同步调用，必须短超时
     */
    private static final int READ_TIMEOUT_SECONDS = 3;

    private final HttpClientHolder holder;

    private final String baseUrl;

    private final String apiKey;

    public QdrantRestClient(HttpClientHolder holder,
                            @Value("${qdrant.base-url}") String baseUrl,
                            @Value("${qdrant.api-key:}") String apiKey) {
        this.holder = holder;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    @Override
    public void createCollection(String name, int dim, Collection<QdrantPayloadIndex> payloadIndexes) {
        ObjectNode body = JsonMappers.shared().createObjectNode();
        ObjectNode vectors = body.putObject("vectors");
        vectors.put("size", dim);
        vectors.put("distance", "Cosine");
        exchange("PUT", "/collections/" + name, body.toString());
        if (payloadIndexes != null) {
            for (QdrantPayloadIndex index : payloadIndexes) {
                ObjectNode indexBody = JsonMappers.shared().createObjectNode();
                indexBody.put("field_name", index.field());
                indexBody.put("field_schema", index.schema() == QdrantPayloadIndex.Schema.INTEGER
                        ? "integer" : "keyword");
                exchange("PUT", "/collections/" + name + "/index", indexBody.toString());
            }
        }
    }

    @Override
    public QdrantCollectionInfo describeCollection(String name) {
        HttpResponse<String> response = exchange("GET", "/collections/" + name, null);
        if (response.statusCode() == 404) {
            return null;   // 集合不存在是合法状态（启动自检据此走创建分支），非系统故障
        }
        JsonNode root = parseBody(response);
        JsonNode result = root.path("result");
        int dim = result.path("config").path("params").path("vectors").path("size").asInt(-1);
        long pointsCount = result.path("points_count").asLong(0);
        String status = result.path("status").asText("unknown");
        if (dim < 0) {
            throw new RetryableException(QdrantErrorCodes.QDRANT_BAD_RESPONSE,
                    "describeCollection 响应缺向量维度 collection=" + name);
        }
        return new QdrantCollectionInfo(dim, pointsCount, status);
    }

    @Override
    public void upsert(String collection, List<QdrantPoint> points) {
        if (points == null || points.isEmpty()) {
            return;
        }
        ObjectNode body = JsonMappers.shared().createObjectNode();
        ArrayNode pointsNode = body.putArray("points");
        for (QdrantPoint point : points) {
            ObjectNode pointNode = pointsNode.addObject();
            pointNode.put("id", point.id());
            ArrayNode vectorNode = pointNode.putArray("vector");
            for (float component : point.vector()) {
                vectorNode.add(component);
            }
            pointNode.set("payload", JsonMappers.shared().valueToTree(
                    point.payload() == null ? java.util.Map.of() : point.payload()));
        }
        exchange("PUT", "/collections/" + collection + "/points?wait=true", body.toString());
    }

    @Override
    public List<QdrantHit> search(String collection, float[] vector, QdrantFilter filter, int limit) {
        ObjectNode body = JsonMappers.shared().createObjectNode();
        ArrayNode vectorNode = body.putObject("query").putArray("nearest");
        for (float component : vector) {
            vectorNode.add(component);
        }
        body.put("limit", limit);
        body.put("with_payload", true);
        if (filter != null && (!filter.keywords().isEmpty() || !filter.ranges().isEmpty())) {
            body.set("filter", filterJson(filter));
        }
        HttpResponse<String> response =
                exchange("POST", "/collections/" + collection + "/points/query", body.toString());
        JsonNode result = parseBody(response).path("result").path("points");
        List<QdrantHit> hits = new ArrayList<>();
        for (JsonNode point : result) {
            hits.add(new QdrantHit(point.path("id").asLong(),
                    point.path("score").asDouble(0),
                    readPayload(point.path("payload"))));
        }
        return hits;
    }

    @Override
    public void delete(String collection, List<Long> pointIds) {
        if (pointIds == null || pointIds.isEmpty()) {
            return;
        }
        ObjectNode body = JsonMappers.shared().createObjectNode();
        ArrayNode ids = body.putArray("points");
        pointIds.forEach(ids::add);
        exchange("POST", "/collections/" + collection + "/points/delete?wait=true", body.toString());
    }

    @Override
    public void switchAlias(String alias, String targetCollection) {
        ObjectNode body = JsonMappers.shared().createObjectNode();
        ArrayNode actions = body.putArray("actions");
        // 先删后建：别名已存在指向旧集合时，delete+create 原子重指（零停机切换）
        actions.addObject().putObject("delete_alias").put("alias_name", alias);
        actions.addObject().putObject("create_alias")
                .put("alias_name", alias)
                .put("collection_name", targetCollection);
        exchange("POST", "/collections/aliases", body.toString());
    }

    // ===== 共用底座 =====

    private ObjectNode filterJson(QdrantFilter filter) {
        ObjectNode filterNode = JsonMappers.shared().createObjectNode();
        ArrayNode must = filterNode.putArray("must");
        for (QdrantFilter.KeywordCondition condition : filter.keywords()) {
            must.addObject().put("key", condition.field())
                    .putObject("match").put("value", condition.value());
        }
        for (QdrantFilter.RangeCondition condition : filter.ranges()) {
            ObjectNode range = must.addObject().put("key", condition.field()).putObject("range");
            if (condition.from() != null) {
                range.put("gte", condition.from());
            }
            if (condition.to() != null) {
                range.put("lte", condition.to());
            }
        }
        return filterNode;
    }

    private java.util.Map<String, Object> readPayload(JsonNode payloadNode) {
        if (payloadNode == null || !payloadNode.isObject()) {
            return java.util.Map.of();
        }
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payloadNode.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value.isNumber()) {
                payload.put(entry.getKey(), value.asLong());
            } else {
                payload.put(entry.getKey(), value.asText());
            }
        });
        return payload;
    }

    private HttpResponse<String> exchange(String method, String path, String bodyJson) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS))
                .header("Content-Type", "application/json");
        if (!apiKey.isEmpty()) {
            builder.header("api-key", apiKey);
        }
        switch (method) {
            case "PUT" -> builder.PUT(HttpRequest.BodyPublishers.ofString(bodyJson == null ? "" : bodyJson));
            case "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(bodyJson == null ? "" : bodyJson));
            case "GET" -> builder.GET();
            default -> throw new IllegalArgumentException("不支持的 HTTP 方法：" + method);
        }
        HttpRequest request = builder.build();
        HttpResponse<String> response;
        try {
            response = holder.client().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            log.error("Qdrant 请求失败（网络/超时） errorCode={} path={} traceId=n/a",
                    QdrantErrorCodes.QDRANT_UNAVAILABLE, path, e);
            throw new RetryableException(QdrantErrorCodes.QDRANT_UNAVAILABLE,
                    "Qdrant 请求失败（" + path + "）：" + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RetryableException(QdrantErrorCodes.QDRANT_UNAVAILABLE,
                    "Qdrant 请求被中断（" + path + "）", e);
        }
        if (response.statusCode() / 100 != 2 && response.statusCode() != 404) {
            log.error("Qdrant 上游错误 errorCode={} status={} path={} traceId=n/a body={}",
                    QdrantErrorCodes.QDRANT_UNAVAILABLE, response.statusCode(), path,
                    abbreviate(response.body()));
            throw new RetryableException(QdrantErrorCodes.QDRANT_UNAVAILABLE,
                    "Qdrant 上游错误（status=" + response.statusCode() + "，path=" + path + "）");
        }
        return response;
    }

    private JsonNode parseBody(HttpResponse<String> response) {
        try {
            return JsonMappers.shared().readTree(response.body() == null ? "" : response.body());
        } catch (Exception e) {
            throw new RetryableException(QdrantErrorCodes.QDRANT_BAD_RESPONSE,
                    "Qdrant 响应不是合法 JSON（status=" + response.statusCode() + "）：" + e.getMessage(), e);
        }
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }

    private static String stripTrailingSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
