package com.example.smarttodo;

import java.util.Calendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把"明天下午3点""本周五""3月26日（周三）23:59""月底""下个月15号"
 * "3天内""开学前"这类说法换算成时间戳，用来排序、标记和本地闹钟。
 *
 * 注意：parse(String, long) 的签名保持不变，新增能力全部在内部实现，
 * 另外新增 findDuePhrase(String) 供手动添加时从整句里摘出时间短语。
 */
public class DueParser {

    /** 日期类说法（相对时间在前，避免被"周X"抢先匹配）。 */
    private static final String DATE_TERMS =
            "下个?月\\d{1,2}[日号]|本月底|下个?月底|月底|下个?月|" +
                    "\\d{1,2}天(?:之)?内|一周内|两周内|一周之内|两周之内|" +
                    "开学前?|期末前|放假前|本周末|下周末|周末|" +
                    "今天|明天|后天|大后天|本周[一二三四五六日天]?|下周[一二三四五六日天]?|" +
                    "周[一二三四五六日天]|星期[一二三四五六日天]|" +
                    "\\d{1,2}月\\d{1,2}[日号]?|\\d{1,2}[-/]\\d{1,2}";

    private static final String TIME_TERMS =
            "(?:(?:凌晨|清晨|早上|上午|下午|中午|晚上|今晚|明早)?\\s*\\d{1,2}[:：]\\d{2})|" +
                    "(?:(?:凌晨|清晨|早上|上午|下午|中午|晚上|今晚|明早)?\\s*\\d{1,2}点(?:半|\\d{1,2}分)?)";

    private static final Pattern DATE = Pattern.compile("(" + DATE_TERMS + ")");

    /** 手动添加用：一整段时间短语（可带"前/之前"后缀）。 */
    private static final Pattern PHRASE = Pattern.compile(
            "((?:" + DATE_TERMS + ")(?:\\s*(?:" + TIME_TERMS + "))?" +
                    "|(?:" + TIME_TERMS + "))(?:\\s*(?:之前|前))?");

    private static final Pattern TIME_COLON = Pattern.compile(
            "(凌晨|清晨|早上|上午|下午|中午|晚上|今晚|明早)?\\s*(\\d{1,2})[:：](\\d{2})");

    private static final Pattern TIME_POINT = Pattern.compile(
            "(凌晨|清晨|早上|上午|下午|中午|晚上|今晚|明早)?\\s*(\\d{1,2})点(半|(\\d{1,2})分)?");

    /** 月底 / 下个月X号 / X天内 / 开学前 / 周末 等相对说法。 */
    private static final Pattern RELATIVE = Pattern.compile(
            "(下个?月(\\d{1,2})[日号]|本月底|下个?月底|月底|下个?月|" +
                    "(\\d{1,2})天(?:之)?内|一天内|两天内|三天内|" +
                    "一周内|两周内|一周之内|两周之内|" +
                    "开学前?|期末前|放假前|本周末|下周末|周末)");

    public static long parse(String text, long now) {
        if (text == null || text.trim().isEmpty()) return 0;

        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);

        int hour = 23, minute = 59;
        boolean hasDate = false;
        boolean hasTime = false;
        String pointPrefix = "";

        Matcher rm = RELATIVE.matcher(text);
        if (rm.find()) {
            hasDate = true;
            applyRelative(c, rm, now);
        }

        if (!hasDate) {
            Matcher dm = DATE.matcher(text);
            if (dm.find()) {
                hasDate = true;
                applyDate(c, dm.group());
            }
        }

        Matcher tm = TIME_COLON.matcher(text);
        if (tm.find()) {
            hasTime = true;
            pointPrefix = tm.group(1) == null ? "" : tm.group(1);
            hour = parseInt(tm.group(2), 0);
            minute = parseInt(tm.group(3), 0);
        } else {
            Matcher pm = TIME_POINT.matcher(text);
            if (pm.find()) {
                hasTime = true;
                pointPrefix = pm.group(1) == null ? "" : pm.group(1);
                hour = parseInt(pm.group(2), 0);
                minute = "半".equals(pm.group(3)) ? 30 : parseInt(pm.group(4), 0);
            }
        }

        if (!hasDate && !hasTime) return 0;

        if (hasTime) {
            if ("下午".equals(pointPrefix) || "晚上".equals(pointPrefix) || "今晚".equals(pointPrefix)) {
                if (hour < 12) hour += 12;
            } else if ("中午".equals(pointPrefix)) {
                if (hour < 11) hour = 12;
            }
            if ("明早".equals(pointPrefix) || "今晚".equals(pointPrefix)) {
                if (!hasDate) c.add(Calendar.DAY_OF_MONTH, "明早".equals(pointPrefix) ? 1 : 0);
            }
        }
        if (hour > 23) hour = 23;
        if (minute > 59) minute = 59;

        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /**
     * 手动添加时用：从"明天下午3点前交作业"里摘出"明天下午3点前"。
     * 没找到返回空串。
     */
    public static String findDuePhrase(String text) {
        if (text == null || text.isEmpty()) return "";
        Matcher m = PHRASE.matcher(text);
        if (!m.find()) return "";
        return m.group(1) == null ? "" : m.group(1).trim();
    }

    /** 月底 / 下个月 / X天内 / 开学前 / 周末。 */
    private static void applyRelative(Calendar c, Matcher m, long now) {
        String token = m.group(1);
        String dayStr = m.group(2);
        String daysStr = m.group(3);

        if (token.startsWith("下个") || token.startsWith("下月")) {
            if (dayStr != null && !dayStr.isEmpty()) {
                int day = parseInt(dayStr, 1);
                c.add(Calendar.MONTH, 1);
                setDayClamped(c, day);
                // 例如 12 月说"下个月5号"，加一个月后已是明年，无需再修正
                if (c.getTimeInMillis() < now) c.add(Calendar.YEAR, 1);
            } else {
                c.add(Calendar.MONTH, 1);
                c.set(Calendar.DAY_OF_MONTH, c.getActualMaximum(Calendar.DAY_OF_MONTH));
            }
            return;
        }
        if ("月底".equals(token) || "本月底".equals(token)) {
            c.set(Calendar.DAY_OF_MONTH, c.getActualMaximum(Calendar.DAY_OF_MONTH));
            return;
        }
        if ("下周末".equals(token)) {
            moveToWeekend(c, true);
            return;
        }
        if ("周末".equals(token) || "本周末".equals(token)) {
            moveToWeekend(c, false);
            return;
        }
        if ("开学".equals(token) || "开学前".equals(token)) {
            Calendar t = (Calendar) c.clone();
            t.set(Calendar.MONTH, Calendar.SEPTEMBER);
            t.set(Calendar.DAY_OF_MONTH, 1);
            if (t.getTimeInMillis() < now) t.add(Calendar.YEAR, 1);
            setDate(c, t);
            return;
        }
        if ("期末前".equals(token)) {
            Calendar t = (Calendar) c.clone();
            int month = c.get(Calendar.MONTH); // 0-based
            if (month <= Calendar.JUNE) {
                t.set(Calendar.MONTH, Calendar.JULY);
                t.set(Calendar.DAY_OF_MONTH, 1);
            } else {
                t.add(Calendar.YEAR, 1);
                t.set(Calendar.MONTH, Calendar.JANUARY);
                t.set(Calendar.DAY_OF_MONTH, 15);
            }
            setDate(c, t);
            return;
        }
        if ("放假前".equals(token)) {
            Calendar t = (Calendar) c.clone();
            int month = c.get(Calendar.MONTH);
            if (month >= Calendar.SEPTEMBER) {
                t.add(Calendar.YEAR, 1);
                t.set(Calendar.MONTH, Calendar.JANUARY);
                t.set(Calendar.DAY_OF_MONTH, 15);
            } else if (month <= Calendar.JUNE) {
                t.set(Calendar.MONTH, Calendar.JULY);
                t.set(Calendar.DAY_OF_MONTH, 15);
            } else {
                t.set(Calendar.MONTH, Calendar.SEPTEMBER);
                t.set(Calendar.DAY_OF_MONTH, 1);
            }
            setDate(c, t);
            return;
        }
        if ("一天内".equals(token)) { c.add(Calendar.DAY_OF_MONTH, 1); return; }
        if ("两天内".equals(token)) { c.add(Calendar.DAY_OF_MONTH, 2); return; }
        if ("三天内".equals(token)) { c.add(Calendar.DAY_OF_MONTH, 3); return; }
        if ("一周内".equals(token) || "一周之内".equals(token)) {
            c.add(Calendar.DAY_OF_MONTH, 7);
            return;
        }
        if ("两周内".equals(token) || "两周之内".equals(token)) {
            c.add(Calendar.DAY_OF_MONTH, 14);
            return;
        }
        if (daysStr != null && !daysStr.isEmpty()) {
            c.add(Calendar.DAY_OF_MONTH, parseInt(daysStr, 1));
        }
    }

    /** 本周/下周的周日；"本周末"今天就是周日时保持今天。 */
    private static void moveToWeekend(Calendar c, boolean nextWeek) {
        Calendar t = (Calendar) c.clone();
        for (int i = 0; i < 8; i++) {
            if (t.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) break;
            t.add(Calendar.DAY_OF_MONTH, 1);
        }
        if (nextWeek) t.add(Calendar.DAY_OF_MONTH, 7);
        setDate(c, t);
    }

    private static void applyDate(Calendar c, String token) {
        if ("今天".equals(token) || "今晚".equals(token)) return;
        if ("明天".equals(token) || "明早".equals(token)) { c.add(Calendar.DAY_OF_MONTH, 1); return; }
        if ("后天".equals(token)) { c.add(Calendar.DAY_OF_MONTH, 2); return; }
        if ("大后天".equals(token)) { c.add(Calendar.DAY_OF_MONTH, 3); return; }

        if (token.startsWith("本周") || token.startsWith("下周")
                || token.startsWith("周") || token.startsWith("星期")) {
            int target = dayIndex(token);
            if (target < 0) return;

            // 以周一为一周起点定位，避免周末算"下周五"时多跳一周
            Calendar monday = (Calendar) c.clone();
            int dow = monday.get(Calendar.DAY_OF_WEEK);
            int toMonday = (dow == Calendar.SUNDAY) ? -6 : (Calendar.MONDAY - dow);
            monday.add(Calendar.DAY_OF_MONTH, toMonday);

            int idx = target - Calendar.MONDAY; // 周一=0 ... 周日=6
            Calendar day = (Calendar) monday.clone();
            day.add(Calendar.DAY_OF_MONTH, idx);
            if (token.startsWith("下周")) {
                day.add(Calendar.DAY_OF_MONTH, 7);
            } else if (isBeforeDay(day, c)) {
                // "本周X"/"周X"：若算出来已过去，顺延到下周同一天（用户想要的是"下一个周X"）
                day.add(Calendar.DAY_OF_MONTH, 7);
            }
            setDate(c, day);
            return;
        }

        Matcher m = Pattern.compile("(\\d{1,2})月(\\d{1,2})").matcher(token);
        if (m.find()) {
            int month = parseInt(m.group(1), 0) - 1;
            int day = parseInt(m.group(2), 0);
            // 先把日置 1 再设月份，避免"5月31日改设成2月"时 Calendar 自动进位到 3 月
            c.set(Calendar.DAY_OF_MONTH, 1);
            c.set(Calendar.MONTH, month);
            setDayClamped(c, day);
            return;
        }
        m = Pattern.compile("(\\d{1,2})[-/](\\d{1,2})").matcher(token);
        if (m.find()) {
            c.set(Calendar.DAY_OF_MONTH, 1);
            c.set(Calendar.MONTH, parseInt(m.group(1), 0) - 1);
            setDayClamped(c, parseInt(m.group(2), 0));
        }
    }

    private static void setDate(Calendar dst, Calendar src) {
        dst.set(Calendar.YEAR, src.get(Calendar.YEAR));
        dst.set(Calendar.MONTH, src.get(Calendar.MONTH));
        dst.set(Calendar.DAY_OF_MONTH, src.get(Calendar.DAY_OF_MONTH));
    }

    /** 防止 2 月 30 号这种越界日期：超过当月天数就取当月最后一天。 */
    private static void setDayClamped(Calendar c, int day) {
        int max = c.getActualMaximum(Calendar.DAY_OF_MONTH);
        c.set(Calendar.DAY_OF_MONTH, Math.min(Math.max(day, 1), max));
    }

    private static boolean isBeforeDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) < b.get(Calendar.YEAR)
                || (a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) < b.get(Calendar.DAY_OF_YEAR));
    }

    /** 周一=2 ... 周日=1，与 Calendar.DAY_OF_WEEK 一致。 */
    private static int dayIndex(String token) {
        char ch = token.charAt(token.length() - 1);
        switch (ch) {
            case '一': return Calendar.MONDAY;
            case '二': return Calendar.TUESDAY;
            case '三': return Calendar.WEDNESDAY;
            case '四': return Calendar.THURSDAY;
            case '五': return Calendar.FRIDAY;
            case '六': return Calendar.SATURDAY;
            case '日':
            case '天': return Calendar.SUNDAY;
            default: return -1;
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }
}
