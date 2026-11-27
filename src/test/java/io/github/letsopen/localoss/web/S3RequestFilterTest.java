package io.github.letsopen.localoss.web;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class S3RequestFilterTest {

    @Test
    void parseQuery_decodesPercentAndPlus() {
        Map<String, List<String>> m = S3RequestFilter.parseQuery(
                "response-content-type=application%2Fpdf&b=a+b&empty=&flag");
        assertEquals("application/pdf", m.get("response-content-type").get(0));
        assertEquals("a b", m.get("b").get(0));
        assertEquals("", m.get("empty").get(0));
        assertEquals("", m.get("flag").get(0));
    }

    @Test
    void percentDecode_keepsPlusLiteral() {
        assertEquals("a+b c", S3RequestFilter.percentDecode("a+b%20c"));
    }

    @Test
    void percentDecode_badEncoding_throws() {
        assertThrows(Exception.class, () -> S3RequestFilter.percentDecode("%zz"));
        assertThrows(Exception.class, () -> S3RequestFilter.percentDecode("%2"));
    }
}
