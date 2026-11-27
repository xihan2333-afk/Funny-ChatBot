package cn.edu.scut.chatbot.context.media;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface EmojiMappingMapper {

    EmojiMappingDO selectById(@Param("id") long id);

    EmojiMappingDO selectByGroupFingerprint(@Param("platform") String platform,
                                            @Param("botId") String botId,
                                            @Param("groupId") String groupId,
                                            @Param("fingerprint") String fingerprint);

    int upsert(EmojiMappingDO row);

    int updateDescription(@Param("id") long id,
                          @Param("description") String description,
                          @Param("source") String source);

    List<EmojiMappingDO> selectPaged(@Param("platform") String platform,
                                     @Param("botId") String botId,
                                     @Param("groupId") String groupId,
                                     @Param("keyword") String keyword,
                                     @Param("offset") int offset,
                                     @Param("limit") int limit);

    long countPaged(@Param("platform") String platform,
                    @Param("botId") String botId,
                    @Param("groupId") String groupId,
                    @Param("keyword") String keyword);
}
