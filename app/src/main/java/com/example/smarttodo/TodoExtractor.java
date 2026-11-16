package com.example.smarttodo;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 判断一条通知里"有没有需要我去做的事"，并压成一句待办 + 截止时间。
 * 纯本地规则，不联网、不上传任何消息。
 *
 * 解析流程：
 * 1. 垃圾/纯应答/闲聊负向过滤；
 * 2. 动作词（明写 + 隐含动词）命中；
 * 3. 按 emoji / 换行 / 标点分句，给每句打分，取最高分；
 * 4. 清洗（去 @xxx、括号标签、发送人前缀）→ 截断 60 字；
 * 5. 从全文里找日期/时间，交给 DueParser 换算时间戳。
 */
public class TodoExtractor {

    /** 明写动作词：命中即可认为消息里有任务。 */
    private static final String[] ACTIONS = {
            "作业", "提交", "上交", "补交", "递交", "交作业", "截止", "报名", "考试", "测验",
            "小测", "期中", "期末", "补考", "重修", "开会", "会议", "组会", "班会", "例会",
            "答辩", "汇报", "演示", "讲座", "宣讲", "参加", "活动", "缴费", "交费", "班费",
            "书费", "领取", "领书", "领教材", "取书", "填表", "填写", "问卷", "投票", "接龙",
            "统计", "登记", "上报", "确认", "回复", "实验", "报告", "论文", "课设", "课程设计",
            "实习", "签到", "打卡", "值日", "打扫", "集合", "面试", "笔试", "体检", "选课",
            "退课", "调课", "补课", "材料", "盖章", "打印", "复印", "上传", "发送", "命名",
            "打包", "压缩", "查寝", "截图", "收集", "汇总", "反馈", "学习通", "雨课堂", "超星",
            "智慧树", "慕课", "问卷星", "腾讯文档", "金山文档", "群接龙", "截止时间", "提交方式",
            "布置", "预习", "复习", "背诵", "做题", "练习", "申请", "预约", "办理", "核对"
    };

    /**
     * 隐含任务动词：没有上面的明写动作词时，靠这些词兜底。
     * 命中隐含动词的句子要求更严格（分数门槛更高 + 长度 >= 6），避免把闲聊当任务。
     */
    private static final String[] IMPLICIT = {
            "记得", "别忘了", "别忘记", "不要忘", "务必", "需要你", "麻烦你", "帮忙", "帮我",
            "尽快", "按时", "提前", "准备", "带上", "带好", "发我", "发到", "交到", "交至",
            "上传到", "填好", "做完", "搞定", "核对一下", "确认一下", "统计一下", "报名表",
            "记得带", "记得交", "记得填", "记得看", "看一下", "看一下群", "翻一下", "过一下",
            "记得回复", "回我", "发群里", "交群里", "群里发", "别迟到", "准时", "抓紧"
    };

    /** 推广、营销类消息，直接丢弃。 */
    private static final String[] SPAM = {
            "促销", "优惠", "红包", "抽奖", "中奖", "秒杀", "广告", "推广", "砍价", "拼团",
            "折扣", "特卖", "领券", "福利", "返现", "提现", "爆款", "清仓", "大促", "满减",
            "包邮", "上新", "加速包", "会员日", "猜你喜欢", "为您推荐"
    };

    /**
     * 闲聊/情绪类词：命中后给句子负分，避免"哈哈哈明天见"这种被当成待办。
     * 注意不要放"上课""下课"这类可能带任务的词。
     */
    private static final String[] CHAT = {
            "哈哈", "嘿嘿", "嘻嘻", "笑死", "晚安", "早安", "早啊", "睡了", "困了", "无聊",
            "吃饭", "午饭", "晚饭", "夜宵", "奶茶", "火锅", "烧烤", "外卖", "干饭", "游戏",
            "上号", "开黑", "追剧", "电影", "唱歌", "逛街", "吃瓜", "八卦", "表情包", "斗图",
            "牛逼", "厉害了", "绝了", "离谱", "大无语", "吐槽", "单身", "恋爱", "分手",
            "生日快乐", "恭喜", "新年快乐", "节日快乐", "中秋快乐", "端午快乐", "国庆快乐",
            "加油", "冲冲冲", "辛苦了", "谢谢", "感谢", "收到啦", "好耶", "太棒了"
    };

    /** 反问/纯提问：降低权重，避免"这题怎么做"生成待办。 */
    private static final Pattern QUESTION = Pattern.compile(
            "(吗|么|呢|咋|怎么|为什么|为啥|啥|哪里|哪个|是不是|有没有)[？?]?$");

    private static final Pattern PURE_ACK = Pattern.compile(
            "^(收到|好的|好|谢谢|多谢|辛苦了|哈哈哈+|嗯+|哦+|1|ok|OK|OKAY)[\\s。！!，,~～.、]*$");

    private static final Pattern BRACKET_PREFIX =
            Pattern.compile("^[\\s\\[\\]【】()（）<>《》「」『』\"'’★☆·:：]+");

    /** "张三："这种发送人前缀；不允许含数字，避免把 23:59 里的冒号误当成分隔符。 */
    private static final Pattern SENDER_PREFIX =
            Pattern.compile("^[^0-9:：，,。、\\s@]{1,10}[:：]\\s*");

    private static final Pattern ACK_PREFIX =
            Pattern.compile("^(收到|好|好的|嗯+|OK|ok)[，,。!！~～\\s]+");

    private static final Pattern LABEL_PREFIX =
            Pattern.compile("^(作业提交提醒|作业截止提醒|作业提醒|提交提醒)\\s*");

    private static final Pattern AT_PREFIX =
            Pattern.compile("^@(全体成员|所有人|all|everyone)[\\s:：]*");

    /** 冗余 @昵称：通知里经常带一串 @，对标题没意义。 */
    private static final Pattern AT_ANY =
            Pattern.compile("@[^\\s@:：,，。；;！!？?]{1,24}");

    /** [图片] [表情] 这类系统占位符。 */
    private static final Pattern MEDIA_TAG = Pattern.compile(
            "\\[(图片|表情|语音|视频|文件|链接|动画表情|位置|红包|转账|聊天记录)\\]");

    private static final Pattern BULLET_PREFIX =
            Pattern.compile("^[★☆·•\\-—\\s]+");

    /** 日期：在原有基础上补上月底 / 下个月 / X天内 / 开学前 等相对说法。 */
    private static final Pattern DUE_DATE = Pattern.compile(
            "(下个?月\\d{1,2}[日号]|本月底|月底|下个?月底|下个?月|" +
                    "\\d{1,2}天(?:之)?内|一周内|两周内|一周之内|两周之内|" +
                    "开学前?|期末前|放假前|本周末|下周末|周末|" +
                    "今天|明天|后天|大后天|本周[一二三四五六日天]?|下周[一二三四五六日天]?|" +
                    "周[一二三四五六日天]|星期[一二三四五六日天]|" +
                    "\\d{1,2}月\\d{1,2}[日号]?|\\d{1,2}[-/]\\d{1,2})");

    private static final Pattern DUE_TIME = Pattern.compile(
            "((凌晨|清晨|早上|上午|下午|中午|晚上|今晚|明早)?\\d{1,2}[:：]\\d{2}|" +
                    "(凌晨|清晨|早上|上午|下午|中午|晚上|今晚|明早)?\\d{1,2}点(半|\\d{1,2}分)?)");

    private static final Pattern DATE_HINT = Pattern.compile(
            "截止|之前|前提交|月底|下个?月|\\d{1,2}天(?:之)?内|一周内|两周内|开学|期末|放假|周末|" +
                    "今天|明天|后天|本周|下周|周[一二三四五六日天]|星期[一二三四五六日天]|" +
                    "\\d{1,2}月\\d{1,2}|\\d{1,2}[:：]\\d{2}|\\d{1,2}点");

    /** 这些开头的句子只是截止信息 / 提交方式，不适合当待办标题。 */
    private static final Pattern META_ONLY = Pattern.compile(
            "^(截止时间|截至时间|提交方式|提交地点|提交平台|命名格式|文件命名|地点|备注|要求|说明)[:：]?");

    /** 标题末尾如果还挂着"截止时间：xxx"，直接裁掉，交给截止标记展示。 */
    private static final Pattern TRAILING_DUE = Pattern.compile(
            "[，,;；]?\\s*(截止时间|截至时间|截止日期|截止)[:：].*$");

    public static class Hint {
        public final String title;
        public final String due;
        public final long dueAt;
        public Hint(String title, String due, long dueAt) {
            this.title = title;
            this.due = due;
            this.dueAt = dueAt;
        }
    }

    public static Hint extract(String title, String body) {
        String bodyText = body == null ? "" : body.trim();
        String titleText = title == null ? "" : title.trim();
        String all = join(titleText, bodyText);
        if (all.length() < 4) return null;
        if (containsAny(all, SPAM)) return null;
        if (PURE_ACK.matcher(all).matches()) return null;

        String hit = firstHit(all, ACTIONS);
        boolean impliedOnly = false;
        if (hit == null) {
            hit = firstHit(all, IMPLICIT);
            impliedOnly = hit != null;
        }
        if (hit == null) return null;

        // 通知标题如果本身带动作（如"作业提交提醒"），优先用它；
        // 群聊标题通常只是群名，这时从正文里挑那句最像待办的话。
        String base;
        if (titleText.contains(hit) && titleText.length() <= 24) {
            base = titleText;
        } else if (bodyText.contains(hit)) {
            base = bodyText;
        } else {
            base = all;
        }

        Seg best = pickSentence(base);
        if (best == null) return null;
        // 只有隐含动词时门槛更高：必须同时带时间/长度适中，避免"记得吃饭"这类误判
        if (impliedOnly && (best.score < 3 || best.text.length() < 6)) return null;

        String clean = cleanup(best.text);
        if (clean.length() < 4) clean = cleanup(base);
        if (clean.length() < 4) return null;

        clean = shorten(clean, 60);
        String due = findDue(all);
        long dueAt = DueParser.parse(due, System.currentTimeMillis());
        return new Hint(clean, due, dueAt);
    }

    private static String join(String a, String b) {
        String x = a == null ? "" : a.trim();
        String y = b == null ? "" : b.trim();
        if (x.isEmpty()) return y;
        if (y.isEmpty()) return x;
        return x + " " + y;
    }

    private static boolean containsAny(String text, String[] words) {
        for (String w : words) {
            if (text.contains(w)) return true;
        }
        return false;
    }

    private static String firstHit(String text, String[] words) {
        for (String w : words) {
            if (text.contains(w)) return w;
        }
        return null;
    }

    private static final class Seg {
        final String text;
        final int score;
        Seg(String t, int s) { text = t; score = s; }
    }

    /** 在若干小句里挑"最像待办"的那句：动作词 + 截止时间 + 长度适中，闲聊负分。 */
    private static Seg pickSentence(String text) {
        String normalized = emojiToBreak(text);
        Seg best = bestSegment(normalized, "[。；;！!？?\\n\\r]+");
        if (best != null && best.text.length() > 20) {
            Seg sub = bestSegment(best.text, "[，,]+");
            if (sub != null && sub.text.length() >= 4 && sub.score >= best.score) best = sub;
        }
        return best;
    }

    private static Seg bestSegment(String text, String regex) {
        String[] parts = text.split(regex);
        Seg best = null;
        for (String raw : parts) {
            String s = cleanup(raw);
            if (s.length() < 4) continue;
            if (META_ONLY.matcher(s).find()) continue;

            int score = 0;
            if (hasAny(s, ACTIONS)) score += 3;
            else if (hasAny(s, IMPLICIT)) score += 2;
            if (DATE_HINT.matcher(s).find()) score += 2;
            if (s.length() >= 6 && s.length() <= 40) score += 1;

            // 闲聊负向打分：情绪词 / 纯提问 / 太短且没有动作
            if (hasAny(s, CHAT) || LAUGH.matcher(s).find()) score -= 5;
            if (QUESTION.matcher(s).find() && !DATE_HINT.matcher(s).find()) score -= 2;
            // 负分句子直接淘汰：闲聊不会被当成待办
            if (score < 0) continue;

            if (score > (best == null ? -1 : best.score)) best = new Seg(s, score);
        }
        return best;
    }

    private static final Pattern LAUGH = Pattern.compile(
            "(哈{2,}|嘿嘿+|嘻嘻+|233+|xswl|笑死|awsl|yyds|绝绝子|绷不住)");

    private static boolean hasAny(String text, String[] words) {
        for (String w : words) {
            if (text.contains(w)) return true;
        }
        return false;
    }

    /**
     * 把 emoji 换成换行符，让"好的😊明天交作业"能拆成两句。
     * 用 code point 判断，避免把代理对截成半个字符。
     */
    private static String emojiToBreak(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int type = Character.getType(cp);
            if (cp == 0xFE0F || cp == 0x200D) {
                // 变体选择符 / 零宽连接符：直接丢掉
            } else if (type == Character.OTHER_SYMBOL) {
                sb.append('\n');
            } else {
                sb.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    private static String cleanup(String s) {
        String out = s.trim();
        // 先处理"【作业提交提醒】xxx"这种前缀包裹：去掉括号本身，保留里面的文字
        for (int i = 0; i < 2; i++) {
            char open = out.isEmpty() ? 0 : out.charAt(0);
            if (open == '【' || open == '[') {
                char closeCh = open == '【' ? '】' : ']';
                int close = out.indexOf(closeCh);
                if (close > 0 && close <= 24) {
                    out = (out.substring(1, close) + " " + out.substring(close + 1)).trim();
                }
            }
        }
        for (int i = 0; i < 4; i++) {
            String before = out;
            out = BRACKET_PREFIX.matcher(out).replaceFirst("");
            out = BULLET_PREFIX.matcher(out).replaceFirst("");
            out = AT_PREFIX.matcher(out).replaceFirst("");
            if (out.startsWith("通知") && out.length() >= 6) out = out.substring(2).trim();
            if (out.equals(before)) break;
        }
        out = SENDER_PREFIX.matcher(out).replaceFirst("");
        out = ACK_PREFIX.matcher(out).replaceFirst("");
        out = MEDIA_TAG.matcher(out).replaceAll(" ");
        // 去掉冗余 @昵称（@全体成员 已在上面处理）
        out = AT_ANY.matcher(out).replaceAll(" ");
        out = out.replaceAll("[\\p{So}\\p{Cs}]+", "");
        out = TRAILING_DUE.matcher(out).replaceFirst("");
        out = out.replaceAll("\\s+", " ").trim();
        if (out.length() > 8) out = LABEL_PREFIX.matcher(out).replaceFirst("").trim();
        return out;
    }

    private static String shorten(String s, int max) {
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }

    /** 只取日期 / 时间本身，允许中间夹一个"（周三）"。 */
    private static String findDue(String text) {
        String date = "";
        int dateEnd = -1;
        Matcher dm = DUE_DATE.matcher(text);
        if (dm.find()) {
            date = dm.group();
            dateEnd = dm.end();
        }

        String time = "";
        int timeStart = -1;
        Matcher tm = DUE_TIME.matcher(text);
        if (tm.find()) {
            time = tm.group();
            timeStart = tm.start();
        }

        if (!date.isEmpty() && timeStart >= dateEnd) {
            String between = text.substring(dateEnd, timeStart);
            String cleaned = between
                    .replaceAll("[（(][^）)]*[）)]", "")
                    .replaceAll("[\\s★☆·•,，:：]+", "");
            if (cleaned.length() <= 1) return date + time;
        }
        if (!date.isEmpty()) return date;
        if (!time.isEmpty()) return time;
        return "";
    }
}
