package dev.coolrequest.tool.rabbitmq;

/**
 * UTF-8 宽松解码：只有合法 UTF-8 才返回文本，否则返回 body 的十六进制表示。
 */
public final class Utf8 {

    private Utf8() {
    }

    public static String decode(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        // 用 Strict 解码校验
        java.nio.charset.CharsetDecoder dec = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
        try {
            return dec.decode(java.nio.ByteBuffer.wrap(body)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return toHex(body);
        }
    }

    private static String toHex(byte[] body) {
        StringBuilder sb = new StringBuilder(body.length * 3);
        for (byte b : body) {
            sb.append(String.format("%02X ", b & 0xFF));
        }
        return sb.toString().trim();
    }
}