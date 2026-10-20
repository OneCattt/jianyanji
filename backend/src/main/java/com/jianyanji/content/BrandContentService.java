package com.jianyanji.content;

import static com.jianyanji.common.BusinessException.*;
import static com.jianyanji.common.J.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.*;
import java.net.URI;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/** 品牌管理内容的草稿、审核和发布闭环；公众仅能读取已发布且未删除的内容。 */
@Service
public class BrandContentService {
    /** 内容持久化与业务事务。 */
    private final Db db;
    /** 权限入口提供的审计上下文。 */
    private final Access access;
    /** 允许播放的媒体主机名，来自CONTENT_VIDEO_HOSTS配置；空配置关闭视频发布。 */
    private final Set<String> videoHosts;

    /** 后台按页查询全部有效记录，筛选和计数在数据库执行。 */
    public ObjectNode adminPage(java.util.Map<String, String> query) {
        return new PageQuery(query, "FROM brand_posts WHERE is_deleted=0")
                .eq("status", "status").keyword("title", "summary")
                .select(db, "*", "id DESC", row -> { decode(row); return row; });
    }

    public BrandContentService(Db db, Access access, Environment environment) {
        this.db = db;
        this.access = access;
        this.videoHosts = new HashSet<>();
        for (String host : environment.getProperty("CONTENT_VIDEO_HOSTS", "").split(","))
            if (!host.trim().isEmpty()) videoHosts.add(host.trim().toLowerCase(Locale.ROOT));
    }

    /** 编号游标分页，避免无限制扫描和公开草稿。 */
    public ObjectNode published(Long before, int limit) {
        Input.require(limit >= 1 && limit <= 30, "每页数量须为1至30");
        if (before != null) Input.id(before);
        List<ObjectNode> rows =
                db.list(
                        "SELECT id,type,title,summary,cover,published_at FROM brand_posts WHERE status='published' AND is_deleted=0 AND id<? ORDER BY id DESC LIMIT ?",
                        before == null ? Long.MAX_VALUE : before,
                        limit + 1);
        boolean more = rows.size() > limit;
        rows = rows.subList(0, Math.min(rows.size(), limit));
        return obj(
                "items",
                rows,
                "nextBefore",
                more ? l(rows.get(rows.size() - 1), "id") : null);
    }

    /** 后台最多读取最近200篇内容，包含草稿及审核状态。 */
    public List<ObjectNode> adminList() {
        List<ObjectNode> rows =
                db.list("SELECT * FROM brand_posts WHERE is_deleted=0 ORDER BY id DESC LIMIT 200");
        for (ObjectNode row : rows) decode(row);
        return rows;
    }

    public ObjectNode detail(long id) {
        ObjectNode row =
                db.one(
                        "SELECT id,type,title,summary,body,cover,image_urls,video_url,duration_seconds,product_ids,published_at FROM brand_posts WHERE id=? AND status='published' AND is_deleted=0",
                        id);
        if (row == null) throw notFound("内容未发布或已下架");
        decode(row);
        row.set("products", json(products(row.path("productIds"), true)));
        return row;
    }

    /** 所有内容保存均回到草稿，防止已审核内容被直接改写后继续公开。 */
    public ObjectNode save(Long id, JsonNode body, long actor) {
        Input.strict(
                body,
                "type",
                "title",
                "summary",
                "body",
                "cover",
                "imageUrls",
                "videoUrl",
                "durationSeconds",
                "productIds",
                "reason");
        String type = Input.text(body, "type", 1, 20),
                title = Input.text(body, "title", 1, 100),
                summary = Input.text(body, "summary", 1, 300),
                text = Input.text(body, "body", 1, 20000),
                reason = Input.reason(body);
        Input.require("article".equals(type) || "video".equals(type), "内容类型须为图文或短视频");
        String cover = https(Input.text(body, "cover", 1, 1000));
        ArrayNode images = Input.array(body, "imageUrls", 0, 9),
                productIds = Input.array(body, "productIds", 0, 10);
        for (JsonNode image : images) {
            Input.require(image.isTextual() && image.asText().length() <= 1000, "图片地址不合法");
            https(image.asText());
        }
        Set<Long> seen = new HashSet<>();
        for (JsonNode value : productIds)
            Input.require(
                    value.isIntegralNumber()
                            && value.canConvertToLong()
                            && value.longValue() > 0
                            && value.longValue() <= 9007199254740991L
                            && seen.add(value.longValue()),
                    "关联商品编号不能重复或无效");
        String video = Input.text(body, "videoUrl", 0, 1000);
        long duration = Input.integer(body, "durationSeconds", 0, 180);
        if ("video".equals(type)) {
            validateVideo(video);
            Input.require(duration > 0, "短视频时长须为1至180秒");
        } else Input.require(video.isEmpty() && duration == 0, "图文不能包含视频字段");
        return db.tx(
                () -> {
                    Input.require(
                            products(productIds, false).size() == productIds.size(), "关联商品不存在或已删除");
                    long saved;
                    if (id == null)
                        saved =
                                db.insert(
                                        "INSERT INTO brand_posts(type,title,summary,body,cover,image_urls,video_url,duration_seconds,product_ids,create_user_id,update_user_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                                        type,
                                        title,
                                        summary,
                                        text,
                                        cover,
                                        stringify(images),
                                        video,
                                        duration,
                                        stringify(productIds),
                                        actor,
                                        actor);
                    else {
                        if (db.update(
                                        "UPDATE brand_posts SET type=?,title=?,summary=?,body=?,cover=?,image_urls=?,video_url=?,duration_seconds=?,product_ids=?,status='draft',reviewed_by=NULL,reviewed_at=NULL,published_at=NULL,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                        type,
                                        title,
                                        summary,
                                        text,
                                        cover,
                                        stringify(images),
                                        video,
                                        duration,
                                        stringify(productIds),
                                        actor,
                                        id)
                                == 0) throw notFound("内容不存在");
                        saved = id;
                    }
                    access.audit("brand-content.save", "brand-post", saved, reason);
                    return obj("id", saved, "status", "draft");
                });
    }

    /** 审核和发布为独立操作；下架后需要重新审核。删除仅设置逻辑标记。 */
    public ObjectNode transition(long id, String action, JsonNode body, long actor) {
        Input.strict(body, "reason");
        String reason = Input.reason(body);
        return db.tx(
                () -> {
                    ObjectNode row =
                            db.one("SELECT * FROM brand_posts WHERE id=? AND is_deleted=0", id);
                    if (row == null) throw notFound("内容不存在");
                    String state;
                    switch (action) {
                        case "review":
                            if (!"draft".equals(s(row, "status"))) throw conflict("只有草稿可以审核");
                            state = "reviewed";
                            db.update(
                                    "UPDATE brand_posts SET status='reviewed',reviewed_by=?,reviewed_at=CURRENT_TIMESTAMP(3),update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                    actor,
                                    actor,
                                    id);
                            break;
                        case "publish":
                            if (!"reviewed".equals(s(row, "status"))) throw conflict("请先审核当前版本内容");
                            if ("video".equals(s(row, "type"))) validateVideo(s(row, "video_url"));
                            JsonNode ids = parse(s(row, "product_ids"));
                            Input.require(
                                    products(ids, true).size() == ids.size(), "关联商品已下架，请修改后重新审核");
                            state = "published";
                            db.update(
                                    "UPDATE brand_posts SET status='published',published_at=CURRENT_TIMESTAMP(3),update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                    actor,
                                    id);
                            break;
                        case "unpublish":
                            state = "draft";
                            db.update(
                                    "UPDATE brand_posts SET status='draft',reviewed_by=NULL,reviewed_at=NULL,published_at=NULL,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                    actor,
                                    id);
                            break;
                        case "remove":
                            state = "removed";
                            db.update(
                                    "UPDATE brand_posts SET is_deleted=1,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                                    actor,
                                    id);
                            break;
                        default:
                            throw invalid("不支持的内容操作");
                    }
                    access.audit("brand-content." + action, "brand-post", id, reason);
                    return obj("id", id, "status", state);
                });
    }

    private List<ObjectNode> products(JsonNode ids, boolean published) {
        if (ids.size() == 0) return Collections.emptyList();
        List<Object> args = new ArrayList<>();
        ids.forEach(v -> args.add(v.longValue()));
        return db.list(
                "SELECT p.id,p.name,p.image FROM products p JOIN categories c ON c.id=p.category_id AND c.is_deleted=0 WHERE p.id IN ("
                        + String.join(",", Collections.nCopies(ids.size(), "?"))
                        + ") AND p.is_deleted=0"
                        + (published ? " AND p.active=1 AND c.active=1" : ""),
                args.toArray());
    }

    private void decode(ObjectNode row) {
        row.set("imageUrls", parse(s(row, "image_urls")));
        row.remove("image_urls");
        row.set("productIds", parse(s(row, "product_ids")));
        row.remove("product_ids");
    }

    /** 视频只接受配置媒体域名的稳定HTTPS MP4地址，不复用仅支持图片的上传签名。 */
    void validateVideo(String value) {
        URI uri = URI.create(https(value));
        Input.require(
                videoHosts.contains(uri.getHost().toLowerCase(Locale.ROOT)),
                "视频域名未配置，请先配置CONTENT_VIDEO_HOSTS并在微信后台登记媒体域名");
        Input.require(
                uri.getPath().toLowerCase(Locale.ROOT).endsWith(".mp4")
                        && uri.getRawQuery() == null,
                "短视频须为稳定的MP4地址");
    }

    private String https(String value) {
        try {
            URI uri = URI.create(value);
            Input.require(
                    "https".equals(uri.getScheme())
                            && uri.getHost() != null
                            && uri.getUserInfo() == null
                            && uri.getRawFragment() == null
                            && (uri.getPort() == -1 || uri.getPort() == 443),
                    "素材须为有效HTTPS地址");
            return value;
        } catch (IllegalArgumentException e) {
            throw invalid("素材地址格式不合法");
        }
    }
}
