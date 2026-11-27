package cn.edu.scut.chatbot.context.link;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface LinkCardMapper {

    LinkCardDO selectById(@Param("id") long id);

    LinkCardDO selectByUrlHash(@Param("urlHash") String urlHash);

    int upsert(LinkCardDO row);

    int deleteById(@Param("id") long id);

    List<LinkCardDO> selectPaged(@Param("domain") String domain,
                                 @Param("from") LocalDateTime from,
                                 @Param("to") LocalDateTime to,
                                 @Param("offset") int offset,
                                 @Param("limit") int limit);

    long countPaged(@Param("domain") String domain,
                    @Param("from") LocalDateTime from,
                    @Param("to") LocalDateTime to);
}
