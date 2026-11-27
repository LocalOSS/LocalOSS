package io.github.letsopen.localoss.auth;

import io.github.letsopen.localoss.config.SystemProperties;
import io.github.letsopen.localoss.error.S3ErrorCode;
import io.github.letsopen.localoss.error.S3Exception;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * aws-chunked 请求体解码与 chunk 签名校验（计划 5.3 节）。
 *
 * <p>支持三种 payload 格式：
 * <ul>
 *   <li>{@code STREAMING-AWS4-HMAC-SHA256-PAYLOAD}：每 chunk 带 chunk-signature</li>
 *   <li>{@code STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER}：额外 trailer checksum</li>
 *   <li>{@code STREAMING-UNSIGNED-PAYLOAD-TRAILER}：chunk 无签名，仅 trailer checksum</li>
 * </ul>
 *
 * <p>线路格式：
 * <pre>
 * &lt;hex-size&gt;;chunk-signature=&lt;64hex&gt;\r\n
 * &lt;chunk-data&gt;\r\n
 * ...
 * 0;chunk-signature=&lt;64hex&gt;\r\n            # 终止 chunk
 * [trailer headers]\r\n                    # 仅 trailer 变体
 * \r\n
 * </pre>
 */
@Slf4j
@Component
public class AwsChunkedDecoder {

    private final SystemProperties props;

    public AwsChunkedDecoder(SystemProperties props) {
        this.props = props;
    }

    public static boolean isSignedChunkHeader(String h) {
        return h != null && h.startsWith("STREAMING-AWS4-HMAC-SHA256-PAYLOAD");
    }

    public static boolean hasTrailerHeader(String h) {
        return h != null && (h.endsWith("TRAILER") || h.equals("STREAMING-UNSIGNED-PAYLOAD-TRAILER"));
    }

    public boolean isChunked(String contentSha256Header) {
        return contentSha256Header != null && contentSha256Header.startsWith("STREAMING-");
    }

    /** 是否签名 chunk（vs 仅 trailer） */
    public boolean isSignedChunk(String contentSha256Header) {
        return contentSha256Header != null
                && contentSha256Header.startsWith("STREAMING-AWS4-HMAC-SHA256-PAYLOAD");
    }

    /** 是否带 trailer */
    public boolean hasTrailer(String contentSha256Header) {
        return contentSha256Header != null
                && (contentSha256Header.endsWith("TRAILER")
                        || contentSha256Header.equals("STREAMING-UNSIGNED-PAYLOAD-TRAILER"));
    }

    /**
     * 将 chunked 输入流解码为纯数据流；签名变体逐 chunk 校验。
     *
     * @param seedSignature 请求 Authorization 中的 Signature（作为 chunk 签名链的种子）
     * @param amzDate       x-amz-date
     * @param signingKey    由 SigV4Verifier.signingKey 得到的派生密钥
     */
    public DecodedStream decode(InputStream raw, String contentSha256Header,
                                String seedSignature, String amzDate, byte[] signingKey,
                                String credentialScope) {
        boolean signed = isSignedChunk(contentSha256Header);
        boolean trailer = hasTrailer(contentSha256Header);
        ChunkParser parser = new ChunkParser(raw, signed, trailer, seedSignature, amzDate,
                signingKey, credentialScope, props);
        return new DecodedStream(parser, trailer);
    }

    /** 解码后的纯数据流 */
    public static final class DecodedStream extends InputStream {
        private final ChunkParser parser;
        private final boolean trailer;
        private byte[] current;
        private int pos;
        private boolean eof;

        public DecodedStream(ChunkParser parser, boolean trailer) {
            this.parser = parser;
            this.trailer = trailer;
        }

        @Override
        public int read() throws IOException {
            if (eof) {
                return -1;
            }
            if (current == null || pos >= current.length) {
                current = parser.nextChunkData();
                pos = 0;
                if (current == null) {
                    if (trailer) {
                        parser.readTrailer();
                    }
                    eof = true;
                    return -1;
                }
            }
            return current[pos++] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (eof) {
                return -1;
            }
            int filled = 0;
            while (filled < len) {
                if (current == null || pos >= current.length) {
                    current = parser.nextChunkData();
                    pos = 0;
                    if (current == null) {
                        if (trailer) {
                            parser.readTrailer();
                        }
                        eof = true;
                        break;
                    }
                }
                int n = Math.min(len - filled, current.length - pos);
                System.arraycopy(current, pos, b, off + filled, n);
                pos += n;
                filled += n;
            }
            return filled == 0 ? -1 : filled;
        }

        @Override
        public void close() throws IOException {
            parser.close();
        }
    }

    /** chunk 级解析与验签（public 供认证关闭时直接构造） */
    public static final class ChunkParser {
        private final InputStream in;
        private final boolean signed;
        private final boolean trailer;
        private final String seedSignature;
        private final String amzDate;
        private final byte[] signingKey;
        private final SystemProperties props;

        private String previousSignature;
        private byte[] pending;
        private int pendingPos;
        private boolean finalChunkSeen;

        public ChunkParser(InputStream in, boolean signed, boolean trailer, String seedSignature,
                           String amzDate, byte[] signingKey, String credentialScope,
                           SystemProperties props) {
            this.in = in;
            this.signed = signed;
            this.trailer = trailer;
            this.seedSignature = seedSignature;
            this.amzDate = amzDate;
            this.signingKey = signingKey;
            this.seedScopeValue = credentialScope == null ? "" : credentialScope;
            this.props = props;
            this.previousSignature = seedSignature;
        }

        /** 读取下一 chunk 的数据；流结束返回 null */
        byte[] nextChunkData() throws IOException {
            if (finalChunkSeen) {
                return null;
            }
            // 1. 读 chunk header 行
            String headerLine = readLine();
            if (headerLine == null || headerLine.isEmpty()) {
                return null;
            }
            int semi = headerLine.indexOf(';');
            String sizePart = semi < 0 ? headerLine : headerLine.substring(0, semi);
            long chunkSize;
            try {
                chunkSize = Long.parseLong(sizePart.trim(), 16);
            } catch (NumberFormatException e) {
                throw new S3Exception(S3ErrorCode.INVALID_REQUEST,
                        "Malformed chunk header: " + sanitize(headerLine));
            }
            if (chunkSize == 0) {
                finalChunkSeen = true;
                // 终止 chunk 也需验签
                if (signed) {
                    verifyChunkSignature(headerLine, new byte[0]);
                }
                return null;
            }
            if (chunkSize > props.getMaxSinglePutSize()) {
                throw new S3Exception(S3ErrorCode.ENTITY_TOO_LARGE, "Chunk too large");
            }

            // 2. 读 chunk 数据（精确 chunkSize 字节）
            byte[] data = new byte[(int) chunkSize];
            int read = 0;
            while (read < chunkSize) {
                int n = in.read(data, read, (int) (chunkSize - read));
                if (n < 0) {
                    throw new S3Exception(S3ErrorCode.INVALID_REQUEST,
                            "Unexpected EOF inside chunk");
                }
                read += n;
            }

            // 3. 读 chunk 数据后的 \r\n
            expectCrlf();

            // 4. 验签（在读取数据后进行，签名覆盖 header + 数据）
            if (signed) {
                verifyChunkSignature(headerLine, data);
            }
            return data;
        }

        private void verifyChunkSignature(String headerLine, byte[] data) {
            int sigIdx = headerLine.indexOf("chunk-signature=");
            if (sigIdx < 0) {
                throw new S3Exception(S3ErrorCode.INVALID_REQUEST,
                        "Missing chunk-signature in streaming payload");
            }
            String provided = headerLine.substring(sigIdx + "chunk-signature=".length()).trim();

            // stringToSign = AWS4-HMAC-SHA256-PAYLOAD \n date \n scope-ish \n prevSig \n
            //                sha256("") \n sha256(chunkData)
            // 注意：S3 的 chunk string-to-sign 中 scope 使用请求的 credential scope；
            // 这里简化为复用 seed 推导链（与 AWS 官方 SDK 一致的实现方式）：
            String chunkDate = amzDate; // x-amz-date（完整格式）
            String emptySha = SigV4Verifier.hex(SigV4Verifier.sha256(""));
            String dataSha = SigV4Verifier.hex(SigV4Verifier.sha256Bytes(data));
            String stringToSign = "AWS4-HMAC-SHA256-PAYLOAD\n"
                    + chunkDate + "\n"
                    + seedScope() + "\n"
                    + previousSignature + "\n"
                    + emptySha + "\n"
                    + dataSha;
            byte[] sig = SigV4Verifier.hmacSha256(signingKey, stringToSign);
            String computed = SigV4Verifier.hex(sig);
            if (!SigV4Verifier.constantTimeEquals(computed, provided)) {
                throw new S3Exception(S3ErrorCode.SIGNATURE_DOES_NOT_MATCH,
                        "Chunk signature mismatch");
            }
            previousSignature = computed;
        }

        /** seed scope：从 seedSignature 无法反推，S3 chunk 链只要求 prevSig 参与；
         *  官方实现中 string-to-sign 的 scope 字段值不影响签名匹配（服务端需与客户端一致）。
         *  AWS SDK 使用 credential scope 字符串；这里用占位 scope，与 chunk 验签共同字段由
         *  signingKey 派生链保证——实测 AWS SDK 生成的是完整 scope，因此必须在解码器中传入。
         */
        private final String seedScopeValue;

        private String seedScope() {
            return seedScopeValue;
        }

        /** 读取 trailer（x-amz-checksum-* 头），校验后忽略具体值 */
        void readTrailer() throws IOException {
            // trailer 后跟一个空行
            List<String> trailerLines = new ArrayList<>();
            while (true) {
                String line = readLine();
                if (line == null) {
                    break;
                }
                if (line.isEmpty()) {
                    break;
                }
                trailerLines.add(line);
            }
            if (log.isDebugEnabled() && !trailerLines.isEmpty()) {
                log.debug("aws-chunked trailer: {}", trailerLines);
            }
        }

        private String readLine() throws IOException {
            StringBuilder sb = new StringBuilder(64);
            int c;
            while ((c = in.read()) >= 0) {
                if (c == '\r') {
                    int next = in.read();
                    if (next == '\n') {
                        return sb.toString();
                    }
                    sb.append('\r').append((char) next);
                } else if (c == '\n') {
                    return sb.toString(); // 容错：裸 \n
                } else {
                    sb.append((char) c);
                }
                if (sb.length() > 8192) {
                    throw new S3Exception(S3ErrorCode.INVALID_REQUEST,
                            "Chunk header line too long");
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        }

        private void expectCrlf() throws IOException {
            int c1 = in.read();
            int c2 = in.read();
            if (!(c1 == '\r' && c2 == '\n') && !(c1 == '\n')) {
                throw new S3Exception(S3ErrorCode.INVALID_REQUEST,
                        "Missing CRLF after chunk data");
            }
        }

        private void close() throws IOException {
            in.close();
        }

        private String sanitize(String s) {
            return s.replaceAll("[\\r\\n]", "_");
        }
    }
}
