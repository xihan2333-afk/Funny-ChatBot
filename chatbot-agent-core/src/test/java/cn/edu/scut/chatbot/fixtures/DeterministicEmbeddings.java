package cn.edu.scut.chatbot.fixtures;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 确定性伪向量夹具（T005：embedding 剧本——真模型不进确定性测试，宪法第七条）：
 * <ul>
 *   <li>相同文本永远映射相同向量（SHA-256 逐段派生），去重合并/重建断言有稳定锚；</li>
 *   <li>近邻关系可精确构造——{@link #unitVectorAtSimilarity} 以正交基合成出与基准向量
 *       余弦相似度恰为给定值的向量（0.84/0.85/0.86 边界用例的分寸来源）；</li>
 *   <li>文本 → 向量覆盖表：个别文本手工指定向量，其余走哈希派生。</li>
 * </ul>
 * 产出的向量喂给两处：embedding 假上游的响应体（openai-compat embeddings 形态），
 * 以及测试内直算的期望值（重排公式断言）。
 */
public final class DeterministicEmbeddings {

    private DeterministicEmbeddings() {
    }

    /**
     * 文本 → 确定性向量：SHA-256(text#i) 的前 4 字节按 big-endian 转 int，
     * 归一到 (-1, 1)。同一文本同一维度永远同值。
     */
    public static float[] vectorOf(String text, int dim) {
        float[] vector = new float[dim];
        for (int i = 0; i < dim; i++) {
            vector[i] = component(text, i);
        }
        return vector;
    }

    private static float component(String text, int index) {
        byte[] digest = sha256(text + "#" + index);
        int asInt = ((digest[0] & 0xFF) << 24) | ((digest[1] & 0xFF) << 16)
                | ((digest[2] & 0xFF) << 8) | (digest[3] & 0xFF);
        // int 全量程映射到 (-1, 1)：除以 2^31 后钳到边界内
        return Math.max(-0.9999999f, Math.min(0.9999999f, asInt / 2147483648f));
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 构造与 base 余弦相似度恰为 cosine 的单位向量（cosine ∈ (-1, 1)）：
     * result = cosine * base_unit + sqrt(1 - cosine²) * e，e 是与 base 正交的单位向量
     * （随机方向去掉 base 分量后归一）。种子固定可复现。
     */
    public static float[] unitVectorAtSimilarity(float[] base, double cosine, long seed) {
        float[] unit = normalize(base);
        double orthogonalScale = Math.sqrt(Math.max(0, 1 - cosine * cosine));
        Random random = new Random(seed);
        float[] orthogonal = null;
        for (int attempt = 0; attempt < 64 && orthogonal == null; attempt++) {
            float[] candidate = new float[unit.length];
            double dot = 0;
            for (int i = 0; i < candidate.length; i++) {
                candidate[i] = (float) random.nextGaussian();
                dot += candidate[i] * unit[i];
            }
            // 去掉 base 方向分量（Gram-Schmidt），剩正交分量
            double residualNorm = 0;
            for (int i = 0; i < candidate.length; i++) {
                candidate[i] -= (float) (dot * unit[i]);
                residualNorm += candidate[i] * candidate[i];
            }
            if (residualNorm > 1e-12) {
                double norm = Math.sqrt(residualNorm);
                for (int i = 0; i < candidate.length; i++) {
                    candidate[i] /= norm;
                }
                orthogonal = candidate;
            }
        }
        if (orthogonal == null) {
            throw new IllegalStateException("正交向量合成失败（维度过低或种子退化）");
        }
        float[] result = new float[unit.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = (float) (cosine * unit[i] + orthogonalScale * orthogonal[i]);
        }
        return result;
    }

    /**
     * 两向量余弦相似度（断言与构造共用一口径）
     */
    public static double cosine(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private static float[] normalize(float[] vector) {
        double norm = 0;
        for (float component : vector) {
            norm += component * component;
        }
        norm = Math.sqrt(norm);
        if (norm < 1e-12) {
            throw new IllegalArgumentException("零向量无法归一");
        }
        float[] unit = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            unit[i] = (float) (vector[i] / norm);
        }
        return unit;
    }

    /**
     * 为一次 embeddings 请求（openai-compat 形态：{"model":..., "input":[文本...]}）
     * 生成 openai 响应形态的 Respond 步骤：{"data":[{"index":i,"embedding":[...]}]}。
     * overrides 优先（个别文本手工指定向量），其余走 {@link #vectorOf} 哈希派生。
     */
    public static ScriptedHttpUpstream.Respond embeddingsResponseFor(
            ScriptedHttpUpstream.RecordedRequest request, int dim, Map<String, float[]> overrides) {
        List<float[]> vectors = new ArrayList<>();
        try {
            JsonNode body = JsonMappers.shared().readTree(request.body());
            for (JsonNode text : body.path("input")) {
                String textValue = text.asText();
                float[] override = overrides == null ? null : overrides.get(textValue);
                vectors.add(override != null ? override : vectorOf(textValue, dim));
            }
        } catch (Exception e) {
            return new ScriptedHttpUpstream.Respond(500, "application/json",
                    "{\"error\":{\"message\":\"embedding 剧本请求体解析失败：" + e.getMessage() + "\"}}");
        }
        StringBuilder json = new StringBuilder("{\"data\":[");
        for (int i = 0; i < vectors.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"index\":").append(i).append(",\"embedding\":[");
            float[] vector = vectors.get(i);
            for (int j = 0; j < vector.length; j++) {
                if (j > 0) {
                    json.append(',');
                }
                json.append(vector[j]);
            }
            json.append("]}");
        }
        json.append("]}");
        return new ScriptedHttpUpstream.Respond(200, "application/json", json.toString());
    }
}
