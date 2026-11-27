package io.github.letsopen.localoss.xml;

import io.github.letsopen.localoss.error.S3ErrorCode;
import io.github.letsopen.localoss.error.S3Exception;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 安全 XML 解析器（禁 DTD/外部实体/XXE，计划 4.3 节）。
 *
 * <p>输出为「路径 -> 全部文本值」映射，路径形如 Delete/Object/Key；
 * 重复元素（如多个 Object）按文档顺序保留在值列表中。
 */
public final class S3XmlReader {

    private S3XmlReader() {
    }

    /** 限制请求 XML 最大字节数，防 DoS */
    public static final int MAX_XML_BYTES = 8 * 1024 * 1024;

    /** 解析结果：路径 -> 全部文本值（重复元素保留顺序） */
    public record ParseResult(Map<String, List<String>> values) {

        /** 取第一个值；无则 null */
        public String get(String path) {
            List<String> v = values.get(path);
            return (v == null || v.isEmpty()) ? null : v.get(0);
        }

        /** 取全部值；无则空列表 */
        public List<String> list(String path) {
            List<String> v = values.get(path);
            return v == null ? List.of() : v;
        }

        public boolean has(String path) {
            return values.containsKey(path);
        }
    }

    public static ParseResult parse(byte[] body) {
        if (body == null || body.length == 0) {
            throw new S3Exception(S3ErrorCode.MALFORMED_XML, "Empty XML body");
        }
        if (body.length > MAX_XML_BYTES) {
            throw new S3Exception(S3ErrorCode.MALFORMED_XML, "XML body too large");
        }
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(false);
        // XXE 防护
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (ParserConfigurationException | SAXException e) {
            throw new S3Exception(S3ErrorCode.MALFORMED_XML, "Parser config failed", e);
        }

        Map<String, List<String>> out = new LinkedHashMap<>();
        Deque<String> path = new ArrayDeque<>();
        Deque<StringBuilder> texts = new ArrayDeque<>();

        DefaultHandler handler = new DefaultHandler() {
            @Override
            public void startElement(String uri, String localName, String qName, Attributes attrs) {
                path.push(qName);
                texts.push(new StringBuilder());
            }

            @Override
            public void characters(char[] ch, int start, int length) {
                if (!texts.isEmpty()) {
                    texts.peek().append(ch, start, length);
                }
            }

            @Override
            public void endElement(String uri, String localName, String qName) {
                String text = texts.pop().toString();
                StringBuilder key = new StringBuilder();
                var it = path.descendingIterator();
                while (it.hasNext()) {
                    if (key.length() > 0) {
                        key.append('/');
                    }
                    key.append(it.next());
                }
                out.computeIfAbsent(key.toString(), k -> new ArrayList<>()).add(text);
                path.pop();
            }
        };

        try {
            XMLReader reader = factory.newSAXParser().getXMLReader();
            reader.setContentHandler(handler);
            reader.parse(new InputSource(new ByteArrayInputStream(body)));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new S3Exception(S3ErrorCode.MALFORMED_XML, "Failed to parse XML body", e);
        }
        return new ParseResult(out);
    }
}
