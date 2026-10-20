package com.jianyanji.content;

import static com.jianyanji.common.J.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jianyanji.common.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** 内容公开范围、审核生命周期和视频域名边界测试。 */
class BrandContentServiceTest {
    /** 持久化测试替身。 */
    private Db db;
    /** 被测内容服务。 */
    private BrandContentService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        db =
                mock(
                        Db.class,
                        i ->
                                "update".equals(i.getMethod().getName())
                                        ? 1
                                        : RETURNS_DEFAULTS.answer(i));
        service =
                new BrandContentService(
                        db,
                        mock(Access.class),
                        new MockEnvironment()
                                .withProperty("CONTENT_VIDEO_HOSTS", "media.example.com"));
        doAnswer(i -> ((Supplier<?>) i.getArgument(0)).get()).when(db).tx(any(Supplier.class));
    }

    @Test
    void publicListsNeverExposeUnpublishedOrDeletedRowsAndAreBounded() {
        service.published(100L, 12);
        verify(db)
                .list(
                        "SELECT id,type,title,summary,cover,published_at FROM brand_posts WHERE status='published' AND is_deleted=0 AND id<? ORDER BY id DESC LIMIT ?",
                        100L,
                        13);
        assertThrows(BusinessException.class, () -> service.published(null, 10000));
        assertThrows(BusinessException.class, () -> service.detail(55));
        verify(db).one(contains("status='published' AND is_deleted=0"), eq(55L));
    }

    @Test
    void exactlyTwentyPostsEndPaginationAndExtraRowProvidesCursor() {
        java.util.List<ObjectNode> rows = new java.util.ArrayList<>();
        for (int i=30;i>10;i--) rows.add(obj("id",i));
        when(db.list(anyString(), eq(Long.MAX_VALUE), eq(21))).thenReturn(rows);
        assertTrue(service.published(null,20).path("nextBefore").isNull());
        rows.add(obj("id",10));
        ObjectNode result=service.published(null,20);
        assertEquals(20,result.path("items").size());
        assertEquals(11,l(result,"nextBefore"));
    }

    @Test
    void cannotPublishDraftWithoutReviewAndCannotPublishRemovedPost() {
        when(db.one("SELECT * FROM brand_posts WHERE id=? AND is_deleted=0", 5L))
                .thenReturn(obj("id", 5, "status", "draft"));
        assertThrows(
                BusinessException.class,
                () -> service.transition(5, "publish", obj("reason", "发布"), 7));
        when(db.one("SELECT * FROM brand_posts WHERE id=? AND is_deleted=0", 5L)).thenReturn(null);
        assertThrows(
                BusinessException.class,
                () -> service.transition(5, "review", obj("reason", "审核"), 7));
        assertFalse(
                mockingDetails(db).getInvocations().stream()
                        .anyMatch(i -> i.getMethod().getName().equals("update")));
    }

    @Test
    void approvedPostCanPublishAndDeletionIsLogical() {
        when(db.one("SELECT * FROM brand_posts WHERE id=? AND is_deleted=0", 5L))
                .thenReturn(
                        obj("id", 5, "status", "reviewed", "type", "article", "product_ids", "[]"));
        assertEquals(
                "published",
                s(service.transition(5, "publish", obj("reason", "已审核"), 7), "status"));
        service.transition(5, "remove", obj("reason", "移除"), 7);
        verify(db)
                .update(
                        "UPDATE brand_posts SET is_deleted=1,update_user_id=?,update_time=CURRENT_TIMESTAMP(3) WHERE id=? AND is_deleted=0",
                        7L,
                        5L);
    }

    @Test
    void changedPublishedContentMustBecomeDraftAgain() {
        service.save(5L, body(), 7);
        assertTrue(
                mockingDetails(db).getInvocations().stream()
                        .anyMatch(
                                i ->
                                        i.getMethod().getName().equals("update")
                                                && ((String) i.getArgument(0))
                                                        .contains(
                                                                "status='draft',reviewed_by=NULL,reviewed_at=NULL,published_at=NULL")));
    }

    @Test
    void videoRejectsUnknownHostsNonHttpsCredentialsAndNonMp4() {
        service.validateVideo("https://media.example.com/brand.mp4");
        for (String url :
                new String[] {
                    "http://media.example.com/a.mp4",
                    "https://evil.example.com/a.mp4",
                    "https://media.example.com.evil.com/a.mp4",
                    "https://u:p@media.example.com/a.mp4",
                    "https://media.example.com/a.html",
                    "https://media.example.com/a.mp4?token=expires",
                    "not a url"
                }) assertThrows(BusinessException.class, () -> service.validateVideo(url));
        BrandContentService unconfigured =
                new BrandContentService(db, mock(Access.class), new MockEnvironment());
        assertThrows(
                BusinessException.class,
                () -> unconfigured.validateVideo("https://media.example.com/a.mp4"));
    }

    @Test
    void clientCannotSetPublishedStateOrReviewer() {
        ObjectNode command = body();
        command.put("status", "published");
        assertThrows(BusinessException.class, () -> service.save(null, command, 7));
    }

    private ObjectNode body() {
        return obj(
                "type",
                "article",
                "title",
                "真实品牌内容",
                "summary",
                "护理说明",
                "body",
                "纯文字内容",
                "cover",
                "https://media.example.com/cover.jpg",
                "imageUrls",
                arr(),
                "videoUrl",
                "",
                "durationSeconds",
                0,
                "productIds",
                arr(),
                "reason",
                "录入审核素材");
    }
}
