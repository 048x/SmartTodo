package com.example.smarttodo;

import org.junit.Test;

import static org.junit.Assert.*;

/** TodoExtractor 纯 JVM 单元测试。只断言"是否抽取 / 标题关键词 / 是否带截止"，不绑具体日期。 */
public class TodoExtractorTest {

    /** 断言应抽取成功，且标题包含某个关键词。 */
    private static void expectHitWith(String keyword, String title, String body) {
        TodoExtractor.Hint h = TodoExtractor.extract(title, body);
        assertNotNull("应抽出待办: " + body, h);
        assertTrue("标题应包含[" + keyword + "] 实际=[" + h.title + "]", h.title.contains(keyword));
    }

    private static void expectNoHit(String title, String body) {
        assertNull("应抑制: " + body, TodoExtractor.extract(title, body));
    }

    // ---------- Bug 修复回归 ----------

    /** 回归：动作词表此前缺"完成"，导致"3天内完成培训"漏判。 */
    @Test
    public void verbCompletionIsRecognized() {
        TodoExtractor.Hint h = TodoExtractor.extract("通知", "请于3天内完成安全教育培训");
        assertNotNull("含'完成'的任务应被抽取", h);
        assertTrue("应解析出截止时间", h.dueAt > 0);
    }

    // ---------- 应抽取 ----------

    @Test
    public void explicitActionWords() {
        expectHitWith("作业", "作业提交提醒", "明天下午3点前把高数作业交到学习通");
        expectHitWith("报名表", "班级群", "@全体成员 周四23:59前提交报名表");
        expectHitWith("班会", "教务通知", "12月5日下午2点开班会，地点教学楼301");
    }

    @Test
    public void implicitVerbs() {
        expectHitWith("报表", "工作群", "小李 记得本周五前把报表发我");
    }

    // ---------- 应抑制 ----------

    @Test
    public void suppressSmallTalk() {
        expectNoHit("群聊", "哈哈哈明天见");
        expectNoHit("群聊", "晚上吃什么呀");
        expectNoHit("群聊", "收到，谢谢老师");
    }

    @Test
    public void suppressSpam() {
        expectNoHit("广告", "限时优惠！红包抽奖秒杀，点击查看");
    }
}
