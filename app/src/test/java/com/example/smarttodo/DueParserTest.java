package com.example.smarttodo;

import org.junit.Test;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

import static org.junit.Assert.*;

/**
 * DueParser 纯 JVM 单元测试（不依赖 Android，可在任意 JUnit 环境运行）。
 * 所有"相对时间"断言都基于固定的 NOW = 2026-11-22 10:00 周日，保证可重复。
 */
public class DueParserTest {

    private static final TimeZone TZ = TimeZone.getTimeZone("Asia/Shanghai");
    private static final long NOW = fixedNow(2026, Calendar.NOVEMBER, 22, 10, 0);

    private static long fixedNow(int year, int month0, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
        c.clear();
        c.set(year, month0, day, hour, minute, 0);
        return c.getTimeInMillis();
    }

    /** 把时间戳格式化成 "yyyy-MM-dd HH:mm"，便于断言和失败排查。 */
    private static String fmt(long millis) {
        Calendar c = Calendar.getInstance(TZ, Locale.CHINA);
        c.setTimeInMillis(millis);
        return String.format(Locale.CHINA, "%04d-%02d-%02d %02d:%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1,
                c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY),
                c.get(Calendar.MINUTE));
    }

    private static void assertDue(String expected, String phrase) {
        long actual = DueParser.parse(phrase, NOW);
        assertEquals(phrase + " → " + fmt(actual), expected, fmt(actual));
    }

    // ---------- Bug 修复回归 ----------

    /** 回归：X月X日 越界必须 clamp 到当月最后一天，不能进位到下个月。 */
    @Test
    public void invalidDayIsClampedToMonthEnd() {
        // 2026 非闰年，2 月只有 28 天；"2月30日" 应落在 02-28 而不是 03-02
        assertDue("2026-02-28 23:59", "2月30日");
        assertDue("2026-04-30 23:59", "4月31日");
    }

    /** 回归：先置日后置月，避免"31号改设成2月"时 Calendar 自动进位。 */
    @Test
    public void setMonthFrom31StaysInTargetMonth() {
        long now = fixedNow(2026, Calendar.MAY, 31, 10, 0); // 5/31
        long t = DueParser.parse("2月10日", now);
        assertEquals("2月10日 → " + fmt(t), "2026-02-10 23:59", fmt(t));
    }

    /** 回归：今天(周日)说"本周五"应指下一个周五 11-27，而不是刚过去的上周五 11-20。 */
    @Test
    public void thisWeekdayRollsForwardWhenPast() {
        assertDue("2026-11-27 23:59", "本周五");
        assertDue("2026-11-27 23:59", "周五");
        assertDue("2026-11-27 23:59", "星期五");
    }

    // ---------- 已有能力守护 ----------

    @Test
    public void absoluteDatesAndTimes() {
        assertDue("2026-11-23 15:00", "明天下午3点");
        assertDue("2026-11-24 09:30", "后天上午9点30分");
        assertDue("2026-12-05 14:00", "12月5日下午2点");
        assertDue("2026-11-26 23:59", "11/26");
    }

    @Test
    public void relativeDays() {
        assertDue("2026-11-25 23:59", "3天内");
        assertDue("2026-11-25 23:59", "三天内");
        assertDue("2026-11-29 23:59", "一周内");
        assertDue("2026-12-06 23:59", "两周内");
    }

    @Test
    public void weekAndMonthBoundaries() {
        assertDue("2026-11-30 23:59", "月底");
        assertDue("2026-11-27 23:59", "下周五"); // 下周五 = 11-27（周日 11-22 的下周是 11-23~11-29 那周）
        assertDue("2026-11-22 23:59", "今天");
    }

    @Test
    public void crossYearTerms() {
        // 今天 2026-11-22，"开学前" 指向 2027-09-01
        assertDue("2027-09-01 23:59", "开学前");
    }

    @Test
    public void emptyInputReturnsZero() {
        assertEquals(0L, DueParser.parse(null, NOW));
        assertEquals(0L, DueParser.parse("   ", NOW));
        assertEquals(0L, DueParser.parse("没有时间的纯文本", NOW));
    }

    @Test
    public void findDuePhraseExtractsSubstring() {
        assertEquals("明天下午3点", DueParser.findDuePhrase("明天下午3点前交作业"));
        assertEquals("", DueParser.findDuePhrase("随便聊聊"));
    }
}
