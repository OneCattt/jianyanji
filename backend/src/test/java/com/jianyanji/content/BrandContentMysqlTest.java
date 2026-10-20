package com.jianyanji.content;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;

/** 主线程统一迁移后验证真实MySQL发布/下架/逻辑删除，无Flyway自动迁移。 */
@EnabledIfEnvironmentVariable(named = "PAYMENTS_TEST_MYSQL_URL", matches = ".+")
class BrandContentMysqlTest {
    @Test
    void draftReviewPublishEditUnpublishAndDeleteKeepPublicBoundary() {
        String url = System.getenv("PAYMENTS_TEST_MYSQL_URL");
        assertTrue(
                url.matches(
                        "jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/java_rewards_test_[A-Za-z0-9_]+(?:\\?.*)?"),
                "仅使用本机隔离测试库");
        DriverManagerDataSource source =
                new DriverManagerDataSource(
                        url,
                        System.getenv().getOrDefault("PAYMENTS_TEST_MYSQL_USER", "root"),
                        System.getenv().getOrDefault("PAYMENTS_TEST_MYSQL_PASSWORD", ""));
        Db db = new Db(new JdbcTemplate(source), new DataSourceTransactionManager(source));
        BrandContentService service =
                new BrandContentService(
                        db,
                        mock(Access.class),
                        new MockEnvironment()
                                .withProperty("CONTENT_VIDEO_HOSTS", "media.example.com"));
        ObjectNode body =
                obj(
                        "type",
                        "video",
                        "title",
                        "隔离测试-" + id(),
                        "summary",
                        "真实存储边界",
                        "body",
                        "测试正文",
                        "cover",
                        "https://media.example.com/a.jpg",
                        "imageUrls",
                        arr(),
                        "videoUrl",
                        "https://media.example.com/clip.mp4",
                        "durationSeconds",
                        30,
                        "productIds",
                        arr(),
                        "reason",
                        "隔离测试");
        long post = l(service.save(null, body, 0), "id");
        try {
            assertThrows(BusinessException.class, () -> service.detail(post));
            assertThrows(
                    BusinessException.class,
                    () -> service.transition(post, "publish", obj("reason", "未审核直接发布"), 0));
            service.transition(post, "review", obj("reason", "品牌审核"), 0);
            assertThrows(BusinessException.class, () -> service.detail(post));
            service.transition(post, "publish", obj("reason", "发布"), 0);
            assertEquals(
                    "https://media.example.com/clip.mp4", s(service.detail(post), "video_url"));
            service.save(post, body, 0);
            assertThrows(BusinessException.class, () -> service.detail(post));
            service.transition(post, "review", obj("reason", "重审"), 0);
            service.transition(post, "publish", obj("reason", "发布"), 0);
            service.transition(post, "unpublish", obj("reason", "下架"), 0);
            assertThrows(BusinessException.class, () -> service.detail(post));
            service.transition(post, "remove", obj("reason", "逻辑移除"), 0);
            assertThrows(BusinessException.class, () -> service.detail(post));
            assertEquals(
                    1,
                    l(db.one("SELECT is_deleted FROM brand_posts WHERE id=?", post), "is_deleted"));
        } finally {
            db.update(
                    "UPDATE brand_posts SET is_deleted=1,update_user_id=0,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                    post);
        }
    }
}
