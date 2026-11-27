package io.github.letsopen.localoss.xml;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;

/**
 * S3 XML 输出（紧凑格式 + namespace，计划 4.3 节）。
 */
public final class S3XmlWriter {

    public static final String NS = "http://s3.amazonaws.com/doc/2006-03-01/";

    private S3XmlWriter() {
    }

    public static String header(String rootElement) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<" + rootElement
                + " xmlns=\"" + NS + "\">\n";
    }

    public static String footer(String rootElement) {
        return "</" + rootElement + ">\n";
    }

    public static String element(String name, String value) {
        if (value == null) {
            return "";
        }
        return "  <" + name + ">" + escape(value) + "</" + name + ">\n";
    }

    public static String elementIndented(String indent, String name, String value) {
        if (value == null) {
            return "";
        }
        return indent + "<" + name + ">" + escape(value) + "</" + name + ">\n";
    }

    public static String emptyElement(String indent, String name) {
        return indent + "<" + name + "/>\n";
    }

    public static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                case '\'' -> out.append("&apos;");
                case '"' -> out.append("&quot;");
                case '\r' -> out.append("&#13;");
                case '\n' -> out.append("&#10;");
                default -> {
                    if (c >= 0x20 && c != 0x7F) {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    public static byte[] toBytes(String xml) {
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    /** 便捷：列表元素 */
    public static <T> String list(String indent, String wrapper, List<T> items,
                                  Function<T, String> renderer) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (T item : items) {
            sb.append(indent).append("<").append(wrapper).append(">\n");
            sb.append(renderer.apply(item));
            sb.append(indent).append("</").append(wrapper).append(">\n");
        }
        return sb.toString();
    }
}
