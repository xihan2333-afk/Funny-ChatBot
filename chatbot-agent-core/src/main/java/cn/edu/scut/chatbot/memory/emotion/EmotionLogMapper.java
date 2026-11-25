package cn.edu.scut.chatbot.memory.emotion;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * emotion_log 表 Mapper：追加写、当日累计查询（钳制判据）、90 天裁剪。
 * append-only——不 update 不 delete（裁剪除外）。
 */
@Mapper
public interface EmotionLogMapper {

    int insertLog(EmotionLogDO log);

    /**
     * 当日 |delta| 累计（防操纵钳制的日上限判据）
     */
    long sumAbsDeltaSince(@Param("platform") String platform,
                          @Param("botId") String botId,
                          @Param("groupId") String groupId,
                          @Param("since") LocalDateTime since);

    /**
     * 裁剪保留期外的行（批量限次——避免长事务）
     */
    int deleteBefore(@Param("cutoff") LocalDateTime cutoff,
                     @Param("limit") int limit);
}
