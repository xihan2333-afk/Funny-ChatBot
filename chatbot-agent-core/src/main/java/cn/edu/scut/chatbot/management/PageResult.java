package cn.edu.scut.chatbot.management;

import java.util.List;

/**
 * 管理平台统一分页响应模型（006 新增全部列表接口使用 1-based 分页）。
 *
 * @param items    当前页数据列表
 * @param total    满足条件的总记录数
 * @param page     当前页码（1-based，最小为 1）
 * @param pageSize 每页条数（1..200）
 */
public record PageResult<T>(List<T> items, long total, int page, int pageSize) {

    public PageResult {
        items = items == null ? List.of() : List.copyOf(items);
        if (page < 1) {
            page = 1;
        }
        if (pageSize < 1) {
            pageSize = 20;
        }
    }

    public static <T> PageResult<T> of(List<T> items, long total, int page, int pageSize) {
        return new PageResult<>(items, total, page, pageSize);
    }

    public static int normalizePage(Integer page) {
        return page == null || page < 1 ? 1 : page;
    }

    public static int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return 20;
        }
        return Math.min(pageSize, 200);
    }

    public static int offset(int page, int pageSize) {
        return (Math.max(page, 1) - 1) * Math.max(pageSize, 1);
    }
}
