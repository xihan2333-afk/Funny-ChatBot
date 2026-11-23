package cn.edu.scut.chatbot.infrastructure.mybatis;

import cn.edu.scut.chatbot.bootstrap.JsonMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * JSON 列泛型 TypeHandler（research R3 决策）：泛型基类 {@code <T extends Record>}，
 * 每个 JSON 列声明一个显式子类（构造器传目标 record 的 Class）——列映射自带类型安全，
 * 拒绝 Map<String,Object> 万能载荷（类型错误推迟到运行时深处）。
 *
 * <p>ObjectMapper 经 {@link JsonMappers} 静态持有者取全局单例：MyBatis 实例化 TypeHandler
 * 不经 Spring 容器拿不到 bean，静态注入是既定取舍（R3）。
 *
 * <p>使用方式：XML 列映射显式指定 typeHandler 与 javaType，如：
 * {@code #{batchDigest, typeHandler=cn.edu.scut.chatbot.engine.intake.BatchDigestHandler, javaType=...}}
 */
public abstract class JsonTypeHandler<T> extends BaseTypeHandler<T> {

    private final Class<T> targetType;

    private final ObjectMapper mapper = JsonMappers.shared();

    protected JsonTypeHandler(Class<T> targetType) {
        this.targetType = targetType;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, T parameter, JdbcType jdbcType)
            throws SQLException {
        try {
            ps.setString(i, mapper.writeValueAsString(parameter));
        } catch (Exception e) {
            throw new SQLException("JSON 列序列化失败（目标类型 " + targetType.getSimpleName()
                    + "）：" + e.getMessage(), e);
        }
    }

    @Override
    public T getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName), columnName);
    }

    @Override
    public T getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex), "column#" + columnIndex);
    }

    @Override
    public T getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex), "column#" + columnIndex);
    }

    /**
     * 坏 JSON 报错带列名与目标类型（报错位置可定位——排查半径小是本方案的立身理由）
     */
    private T parse(String json, String columnName) throws SQLException {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, targetType);
        } catch (Exception e) {
            throw new SQLException("JSON 列反序列化失败（列 " + columnName + " → "
                    + targetType.getSimpleName() + "）：" + e.getMessage(), e);
        }
    }
}
