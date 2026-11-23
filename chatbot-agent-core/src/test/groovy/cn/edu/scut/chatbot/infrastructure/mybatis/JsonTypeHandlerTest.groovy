// 守护纪律：JSON 列类型安全（research R3）——坏数据在反序列化边界报错并带列名，排查半径小
package cn.edu.scut.chatbot.infrastructure.mybatis

import cn.edu.scut.chatbot.bootstrap.GroupKey
import org.apache.ibatis.executor.result.ResultMapException
import org.apache.ibatis.type.JdbcType
import spock.lang.Specification

import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException

class JsonTypeHandlerTest extends Specification {

    /** 显式子类形态：每个 JSON 列声明一个，构造器传目标 record 的 Class（载荷用主代码 GroupKey record） */
    static class GroupKeyHandler extends JsonTypeHandler<GroupKey> {
        GroupKeyHandler() {
            super(GroupKey)
        }
    }

    def handler = new GroupKeyHandler()

    def "往返序列化：写入的 JSON 能还原为等值 record"() {
        given:
        def original = new GroupKey("qq", "10000", "80001")
        def ps = Mock(PreparedStatement)
        def captured = []

        when:
        handler.setParameter(ps, 1, original, JdbcType.VARCHAR)

        then: "写入的是合法 JSON 字符串（record 字段名平铺）"
        1 * ps.setString(1, _) >> { int index, String json -> captured << json }
        captured[0].contains("80001")

        when: "把写出的 JSON 读回"
        def rs = Mock(ResultSet)
        rs.getString("route_key") >> captured[0]
        def restored = handler.getResult(rs, "route_key")

        then:
        restored == original
    }

    def "空值与空串读为 null 不报错"() {
        given:
        def rs = Mock(ResultSet)

        when:
        rs.getString("route_key") >> null

        then:
        handler.getResult(rs, "route_key") == null

        when:
        def rsBlank = Mock(ResultSet)
        rsBlank.getString("route_key") >> "  "

        then:
        handler.getResult(rsBlank, "route_key") == null
    }

    def "坏 JSON 报错携带列名与目标类型（报错位置可定位）"() {
        given:
        def rs = Mock(ResultSet)
        rs.getString("route_key") >> "{这不是合法JSON"

        when:
        handler.getResult(rs, "route_key")

        then: "BaseTypeHandler 包装为 ResultMapException，cause 链里的 SQLException 带列名与目标类型"
        def wrapped = thrown(ResultMapException)
        def sqlException = wrapped.cause as SQLException
        sqlException.message.contains("route_key")
        sqlException.message.contains("GroupKey")
    }
}
