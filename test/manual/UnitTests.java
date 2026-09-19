import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AuthenticationFailureException;
import com.rabbitmq.client.ShutdownSignalException;

import dev.coolrequest.tool.rabbitmq.AmqpErrors;
import dev.coolrequest.tool.rabbitmq.BodyCodec;
import dev.coolrequest.tool.rabbitmq.JsonUtil;
import dev.coolrequest.tool.rabbitmq.Utf8;

/**
 * 纯逻辑单元测试（无平台依赖、无网络）：JSON 工具 / Body 编解码 / UTF-8 宽松解码 / AMQP 错误翻译。
 * 与项目手工测试风格一致：main + 断言计数，非 0 退出码代表有失败。
 * 用法：java UnitTests   （全部通过打印 ALL PASSED 并退出 0）
 */
public class UnitTests {

    private static int passed = 0;
    private static int failed = 0;
    private static String current = "";

    public static void main(String[] args) {
        jsonIsJsonLike();
        jsonPrettyPrintValid();
        jsonPrettyPrintInvalid();
        bodyCodec();
        utf8();
        amqpErrors();

        System.out.println("\n==== 单元测试结果: 通过 " + passed + " / 失败 " + failed + " ====");
        if (failed > 0) {
            System.out.println("FAILED");
            System.exit(1);
        }
        System.out.println("ALL PASSED");
    }

    // ---------- JsonUtil.isJsonLike ----------

    static void jsonIsJsonLike() {
        group("JsonUtil.isJsonLike");
        eq(true, JsonUtil.isJsonLike("{}"), "空对象");
        eq(true, JsonUtil.isJsonLike("  \n [1,2]"), "前导空白数组");
        eq(true, JsonUtil.isJsonLike("{\"中文\":\"值\"}"), "中文键值");
        eq(false, JsonUtil.isJsonLike(null), "null");
        eq(false, JsonUtil.isJsonLike(""), "空串");
        eq(false, JsonUtil.isJsonLike("plain text"), "纯文本");
        eq(true, JsonUtil.isJsonLike("{\"a\":1} 尾部垃圾"), "只看首字符（完整校验在 prettyPrint）");
    }

    // ---------- JsonUtil.prettyPrint 合法输入 ----------

    static void jsonPrettyPrintValid() {
        group("JsonUtil.prettyPrint 合法");
        // 基本对象：键后带空格的 " : " 分隔（Jackson pretty printer 输出口径）
        String out = JsonUtil.prettyPrint("{\"a\":1,\"b\":true}");
        contains(out, "\"a\" : 1", "数值键值");
        contains(out, "\"b\" : true", "布尔键值");
        if (!out.contains("\n")) fail("应换行", "无换行: " + out); else passed++;
        // 嵌套对象与数组
        String nested = JsonUtil.prettyPrint("{\"list\":[1,{\"x\":\"y\"}],\"o\":{\"k\":null}}");
        JsonUtil.prettyPrint(nested); // 幂等：格式化产物再次格式化不报错
        contains(nested, "\"x\" : \"y\"", "嵌套对象");
        contains(nested, "null", "null 值");
        // 字符串内容原样保留：转义、引号、中文
        String s = JsonUtil.prettyPrint("{\"t\":\"a\\\"b\\n中文\"}");
        contains(s, "a\\\"b\\n中文", "转义与中文原样保留");
        // 数字形态
        String num = JsonUtil.prettyPrint("[-1, 2.5, 1e3, 0]");
        contains(num, "-1", "负整数");
        contains(num, "2.5", "小数");
        contains(num, "1000.0", "指数(1e3 经 Jackson 反序列化为 1000.0)");
        // 空容器不换行
        eq("{ }", JsonUtil.prettyPrint("{ }").trim(), "空对象输出(Jackson 口径)");
        eq("[ ]", JsonUtil.prettyPrint("[]").trim(), "空数组输出(Jackson 口径)");
        // 顶层标量也是合法 JSON
        eq("42", JsonUtil.prettyPrint(" 42 ").trim(), "顶层数字");
        eq("\"hi\"", JsonUtil.prettyPrint("\"hi\"").trim(), "顶层字符串");
    }

    // ---------- JsonUtil.prettyPrint 非法输入 ----------

    static void jsonPrettyPrintInvalid() {
        group("JsonUtil.prettyPrint 非法（应抛 IllegalArgumentException）");
        iae(() -> JsonUtil.prettyPrint(null), "null");
        iae(() -> JsonUtil.prettyPrint("   "), "空白");
        iae(() -> JsonUtil.prettyPrint("{foo"), "非法键");
        iae(() -> JsonUtil.prettyPrint("{\"a\":}"), "缺值");
        iae(() -> JsonUtil.prettyPrint("{\"a\" 1}"), "缺冒号");
        iae(() -> JsonUtil.prettyPrint("{\"a\":1,}"), "尾逗号(对象)");
        iae(() -> JsonUtil.prettyPrint("[1,]"), "尾逗号(数组)");
        iae(() -> JsonUtil.prettyPrint("{\"a\":1]"), "括号不匹配");
        iae(() -> JsonUtil.prettyPrint("[1] 尾部垃圾"), "多余内容");
        iae(() -> JsonUtil.prettyPrint("{\"s\":\"未闭合"), "字符串未闭合");
        iae(() -> JsonUtil.prettyPrint("{x:1}"), "非引号键");
        // 报错信息带位置提示
        try {
            JsonUtil.prettyPrint("{\"a\":}");
            fail("应抛异常", "缺值场景未抛"); 
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && (e.getMessage().contains("位置") || e.getMessage().contains("column"))) passed++;
            else fail("异常信息应含位置", String.valueOf(e.getMessage()));
        }
    }

    // ---------- BodyCodec ----------

    static void bodyCodec() {
        group("BodyCodec.decode");
        eq("", BodyCodec.decode(null, BodyCodec.MODE_JSON), "null 安全");
        eq("hello", BodyCodec.decode("hello".getBytes(), BodyCodec.MODE_TEXT), "TEXT 模式原样");
        eq("{\"a\":1}", BodyCodec.decode("{\"a\":1}".getBytes(), BodyCodec.MODE_JSON), "JSON 模式返回原文");
        eq("41", BodyCodec.decode(new byte[]{0x41}, BodyCodec.MODE_HEX), "HEX 模式单字节");
        eq("41 42", BodyCodec.decode(new byte[]{0x41, 0x42}, BodyCodec.MODE_HEX), "HEX 模式空格分隔");
    }

    // ---------- Utf8 ----------

    static void utf8() {
        group("Utf8.decode");
        eq("", Utf8.decode(null), "null 安全");
        eq("", Utf8.decode(new byte[0]), "空字节");
        eq("中文OK", Utf8.decode("中文OK".getBytes()), "合法 UTF-8 还原");
        eq("AB", Utf8.decode(new byte[]{0x41, 0x42}), "ASCII 还原");
        // 非法 UTF-8（0xFF 永不合法）→ 十六进制兜底
        String bad = Utf8.decode(new byte[]{(byte) 0xFF, (byte) 0xFE});
        eq("FF FE", bad, "非法 UTF-8 转 HEX");
    }

    // ---------- AmqpErrors ----------

    static void amqpErrors() {
        group("AmqpErrors.describe");
        // 406 冲突（真实 close 帧 Method 构造）
        AMQP.Channel.Close close406 = new AMQP.Channel.Close.Builder()
                .replyCode(406)
                .replyText("PRECONDITION_FAILED - inequivalent arg 'type' for exchange 'x'")
                .classId(40).methodId(10).build();
        String t406 = AmqpErrors.describe(new ShutdownSignalException(true, false, close406, null));
        contains(t406, "PRECONDITION_FAILED", "首行含 reply-text");
        contains(t406, "inequivalent arg 'type'", "冲突参数保留");
        // 404
        AMQP.Channel.Close close404 = new AMQP.Channel.Close.Builder()
                .replyCode(404)
                .replyText("NOT_FOUND - no queue 'q' in vhost 'v'")
                .classId(50).methodId(10).build();
        String t404 = AmqpErrors.describe(new ShutdownSignalException(true, false, close404, null));
        contains(t404, "NOT_FOUND", "404 翻译");
        // 认证失败
        String tAuth = AmqpErrors.describe(new AuthenticationFailureException(
                "ACCESS_REFUSED - Login was refused"));
        contains(tAuth, "ACCESS_REFUSED", "认证失败原文保留");
        contains(tAuth, "用户名或密码错误", "认证失败建议");
        // IOException(null) + 根因在 cause（真实订阅失败形态）
        java.io.IOException nullMsg = new java.io.IOException(null,
                new ShutdownSignalException(true, false, close406, null));
        String tNull = AmqpErrors.describe(nullMsg);
        if (!tNull.contains("null")) passed++;
        else fail("不应输出裸 null", tNull.split("\n")[0]);
        contains(tNull, "PRECONDITION_FAILED", "根因下钻到 cause 链");
        // NPE 兜底
        String tNpe = AmqpErrors.describe(new NullPointerException());
        contains(tNpe, "空指针", "NPE 兜底文案");
    }

    // ---------- 迷你断言框架 ----------

    static void group(String name) {
        current = name;
        System.out.println("\n-- " + name);
    }

    static void eq(Object expected, Object actual, String name) {
        if (java.util.Objects.equals(expected, actual)) {
            passed++;
        } else {
            fail("期望[" + expected + "] 实际[" + actual + "]", name);
        }
    }

    static void contains(String actual, String expect, String name) {
        if (actual != null && actual.contains(expect)) {
            passed++;
        } else {
            fail("应包含[" + expect + "] 实际[" + (actual == null ? "null" : abbreviate(actual)) + "]", name);
        }
    }

    static void iae(Runnable r, String name) {
        try {
            r.run();
            fail("应抛 IllegalArgumentException", name);
        } catch (IllegalArgumentException e) {
            passed++;
        } catch (Exception e) {
            fail("应抛 IllegalArgumentException，实际 " + e.getClass().getSimpleName(), name);
        }
    }

    static void fail(String msg, String name) {
        failed++;
        System.out.println("   [FAIL] " + current + " :: " + name + " — " + msg);
    }

    static String abbreviate(String s) {
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }
}
