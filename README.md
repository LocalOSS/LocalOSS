# Local-OSS

一个**集中式**的、兼容 AWS S3 协议的本地对象存储服务。Java 17 + Spring Boot 3，无数据库、无外部依赖，数据就是磁盘上的普通文件。

> **质量基线**：244 项自动化测试全绿（安全审计 20 / 数据完整性 20 / 并发 10 / 负面用例 41 / 协议与功能 153）。

---

## 这是不是你需要的项目？

**用 Local-OSS，如果你符合以下全部或大部分情况：**

- ✅ 你需要一个**内网/本机**的 S3 兼容存储：给应用做本地开发联调、内网文件服务、单机或小集群的集中存储；
- ✅ 你的数据就放在**本地磁盘或 NFS 挂载**上，希望能直接用 `ls`、`cp`、`tar` 等常规手段查看和备份数据；
- ✅ 你希望客户端**不改代码**——现有 AWS SDK / boto3 / aws-cli / rclone 指过来就能用；
- ✅ 你接受**单写多读**的一致性模型（最后一次写入获胜），不需要对象版本历史；
- ✅ 你的访问控制很简单：AK/SK（可多组、可只读）+ 可选匿名只读 + 网络层隔离（内网/防火墙/反代）就够用。

**不要用 Local-OSS，如果你需要：**

- ❌ 对象**版本管理**（versionId / 历史版本 / Delete Marker / 误删恢复）——本项目的数据模型不支持，见下文"明确不做"；
- ❌ **WORM 合规**（Object Lock / Retention / Legal Hold，如 SEC 17a-4 记录留存）；
- ❌ **服务端静态加密**（SSE-S3 / SSE-C / KMS）——如需磁盘密文，请在块设备层做（LUKS/dm-crypt），对本服务完全透明；
- ❌ **异地容灾 / 跨区域复制**——本项目专注集中式存储，不做异地部署；
- ❌ **多租户 / IAM 身份体系 / 完整 Policy 引擎**——本项目是单租户设计；
- ❌ **冷存储层级**（Glacier / Restore）、**SQL 查询**（SelectObjectContent）、**对象转换**（Object Lambda）；
- ❌ 海量租户级别的公有云存储运营（计费、隔离、审计报表）。

一句话总结：**Local-OSS 是"把一个 S3 API 放到你的本地文件系统上"，而不是"把一个本地文件系统放到 S3 云上"。**

---

## 设计理念

### 1. 数据即文件，文件即数据

对象就是 `data-path` 下的普通文件：

```
/opt/file/data/
├── my-bucket/                      # 一级目录 = Bucket
│   ├── photos/2026/a.jpg           # key = "photos/2026/a.jpg"，可以直接 cat/tar/备份
│   └── reports/                    # 尾斜杠零字节对象（目录型）
└── ...
```

不做转义、不做哈希、不引入数据库。你可以随时：
- 用 `rsync` 备份单个桶；
- 用 `find` / `du` 统计；
- 直接删文件来清理（下次请求自动按文件系统属性重建元数据）。

### 2. 简洁优先于完备

刻意不做的能力（见下文清单）都是**本地集中式场景没有对应后端**的：版本管理依赖版本数据模型、Glacier 依赖冷存储介质、KMS/Lambda 依赖 AWS 服务。硬做只会产生"接口返回 200 但行为不对"的假兼容——不如明确返回 `501 NotImplemented`。

### 3. 集中部署友好（单实例 & NFS 多实例）

- **原子写**：上传先写 `.localoss/tmp/<instanceId>-<uuid>.tmp`，校验（大小/checksum/配额）通过后原子 `rename` 到最终路径——崩溃或并发都不会留下半截文件；
- **NFS 安全锁**：用 `mkdir` 原子性实现租约锁（不用 POSIX FileLock，NFSv3/v4 行为不一致），锁内带 owner.json 真实租约（expiresAt），过期可被其他实例接管；临时文件与 uploadId 带 instanceId 前缀，多实例互不冲突；
- **GC 自愈**：每小时清理过期 tmp（默认 24h）、过期锁（按租约状态判定，不误删活跃锁）、超期未完成 Multipart（默认 7 天），`gc.lock` 保证多实例单执行；
- 所有实例共享同一 `data-path` 即可水平扩容，建议 NTP 时间同步。

### 4. 协议兼容以"能用"为标准

目标是 AWS SDK Java v2 / boto3 / aws-cli / rclone **真实可用**：标准 S3 XML（含 namespace 与 Error 结构）、ETag、Range 下载、Multipart 大文件上传（含 aws-chunked 流式签名）、分页列举、批量删除、SigV4 签名。不做"返回 200 但语义不对"的空壳兼容。

### 5. 安全默认，纵深防御

- **路径安全**：key 白名单（禁 `..`/控制字符/`\`/保留名）+ 路径 normalize 后必须仍在桶内 + 符号链接逃逸防护（GET 已存在链接、PUT 父目录链接两种场景）；
- **内部结构隔离**：`.localoss/` 元数据目录不可作为 key 访问、不出现在列举；
- **multipart uploadId 白名单**：`[A-Za-z0-9-]+`，杜绝路径注入；
- **XML 安全**：禁 DTD/外部实体（XXE 防护）、请求体大小限制；
- **签名安全**：常量时间比较、时钟偏差校验、payload hash 校验、预签名有效期上限（7 天）；
- **默认拒绝**：认证开启时匿名写/删一律 403；桶不存在返回 404 而非泄露性的 403。

---

## 功能清单

### ✅ 已实现

| 分类 | 能力 |
|---|---|
| Bucket | Create（含 Location 响应）/ Delete（空桶校验，409 BucketNotEmpty）/ Head / List / GetLocation / Tagging / CORS / Policy / PublicAccessBlock / OwnershipControls / RequestPayment |
| Object | Put / Get / Head / Delete / Copy（同桶/跨桶，继承源 ACL）/ DeleteObjects 批量删除（≤1000 key） |
| 列举 | ListObjects v1 / v2：prefix、delimiter + CommonPrefixes、max-keys（含 0 钳制）、marker / start-after / continuation-token、encoding-type=url、KeyCount、ETag、StorageClass、Owner |
| Multipart | Create / UploadPart / Complete / Abort / ListParts / ListMultipartUploads；分片 5MiB–5GiB、≤10000 片、乱序上传、同号覆盖；multipart ETag = MD5(concat)+partCount |
| HTTP | Range 单段下载（206 / 416，含非数字/逆向/超界校验）、Accept-Ranges、If-Match / If-None-Match / If-Modified-Since / If-Unmodified-Since 解析、响应头覆盖参数（response-content-type 等 6 个） |
| 完整性 | Content-MD5、x-amz-checksum-sha256 / md5 / crc32 校验与返回；ETag（单 PUT 为 MD5，Multipart 为 MD5-of-MD5s+partCount） |
| 元数据 | Content-Type / Encoding / Language / Disposition / CacheControl / Expires 持久化；x-amz-meta-*（总量 ≤2KB）；存量文件无元数据时按需异步回填 |
| 标签 | Bucket / Object Tagging CRUD；x-amz-tagging 请求头；x-amz-tagging-count 响应头；≤50 个、key 1–128 字节、value 0–256 字节 |
| 认证 | AWS SigV4：Authorization Header + Presigned URL（GET/PUT/HEAD/DELETE 全方法）；**aws-chunked 流式签名**（逐 chunk 验签，含 trailer 变体）；payload hash 校验；15 分钟时钟偏差；常量时间比较；多组 AK/SK；容忍 x-amz-security-token；未配置 access-key 时自动关闭认证（开发模式） |
| POST Object | 浏览器表单直传：SigV4 policy 验签、conditions（bucket / starts-with $key / content-length-range）、`${filename}` 占位、success_action_status 200/201/204 |
| ACL | 有限执行：`x-amz-acl` canned 头（public-read = 匿名可读）；对象/桶 `?acl` 读写；CopyObject 继承源 ACL；grant-* 头明确 501 |
| Lifecycle | 有限子集：Expiration（Days/Date + prefix）删除过期满对象；AbortIncompleteMultipartUpload 清理未完成分片；每小时调度，gc.lock 单实例 |
| 配额 | 桶磁盘配额（`system.quotas`，支持 KB/MB/GB/TB）；净增记账（覆盖写减旧大小）；原子预留防并发超限；多实例近似值（GC 重算收敛） |
| 错误 | 标准 S3 Error XML（25+ 错误码：NoSuchBucket / NoSuchKey / SignatureDoesNotMatch / RequestTimeTooSkewed / BucketNotEmpty / KeyConflict / BadDigest / InvalidRange / InvalidPart / InvalidPartOrder / MalformedXML / MalformedPolicy / EntityTooLarge / EntityTooSmall / TooManyParts / MethodNotAllowed / NotImplemented 等）；x-amz-request-id / x-amz-id-2 / Date / Server |
| 运维 | 全局读写级别（read-write / read-only / write-only / disabled）+ 凭证级 level 交集；`/localoss-health` 健康检查；GC（tmp / 过期锁 / 未完成 Multipart）；元数据回填工具 |
| 配置 | CORS / PublicAccessBlock / OwnershipControls 原样 XML 往返；Policy JSON 存取；RequestPayment 只读返回 |
| Key 规则 | 完整 S3 Bucket 命名校验；Object key 白名单（≤1024 字节、单段 ≤200 字节、禁 `..`/控制字符/`\`/保留名）；路径穿越与符号链接防护 |

### ⛔ 明确不做（及原因）

| 能力 | 为什么不做 |
|---|---|
| 对象版本管理（versionId / Delete Marker / ListObjectVersions） | 设计决策：不做版本管理，简化数据模型。相关接口返回 501 |
| Object Lock / Retention / Legal Hold | WORM 合规能力，S3 语义上**依赖版本管理**才能实现。无版本模型则无法实现，属前提性不做 |
| SSE-S3 / SSE-C 静态加密 | 本地机房磁盘威胁模型通常不要求；如需磁盘密文，在块设备层做（LUKS/dm-crypt）对本服务完全透明，比应用层 SSE 更简单可靠 |
| KMS | 依赖 AWS KMS 后端，本地集中式存储没有该服务 |
| 跨区域复制 | 设计决策：专注集中式存储，不做异地部署，无复制目标端 |
| SelectObjectContent | 需内嵌 SQL 引擎解析 CSV/JSON/Parquet，与文件存储定位无关；客户端 GetObject 后应用层处理即可 |
| Glacier / RestoreObject | 依赖冷存储介质（磁带/深度归档池），本地文件系统只有"热"一层 |
| Object Lambda | 依赖 AWS Lambda 计算后端；数据转换由客户端应用层完成更合理 |
| S3 Tables / Vectors / Outposts / Directory Buckets | 完全不同的产品形态（表存储 / 向量库 / 托管硬件 / 区桶），非文件桶功能面 |
| virtual-hosted-style 寻址 | 设计决策：仅 path-style（`http://host:port/bucket/key`） |
| 完整 IAM Policy 引擎 | 单租户场景访问控制由多组 AK/SK + 网络层承担；Policy 仅存取 |
| 存量数据迁移 | 现有 `bucket/key` 文件即目标布局；提供元数据回填工具（见下文） |

### 🟡 部分实现 / 已知限制

| 项 | 现状 |
|---|---|
| POST policy 完整 conditions | 支持 bucket 精确匹配 / starts-with $key / content-length-range；其他条件（如 acl、success_action_redirect）忽略 |
| ACL 完整 grant 体系 | 仅 canned ACL + AllUsers READ；x-amz-grant-* 头返回 501；非 AllUsers 的 grant 静默按 private |
| Lifecycle 完整规则 | 仅 Expiration（Days/Date + prefix）与 AbortIncompleteMultipartUpload；Transition/NoncurrentVersionExpiration/Tag 过滤不支持（依赖版本管理或冷存储，属明确不做范围） |
| 多实例配额精度 | 配额为实例内原子预留，多实例间为近似值（GC 时按目录实际用量重算收敛） |
| Website 配置 | 未实现，返回 501 |
| SigV2 | 未实现（AWS 已淘汰的旧签名协议） |
| 限流 | 未实现（建议 Nginx 层做） |

---

## 测试

**244 项自动化测试全绿**，按安全 > 数据完整性 > 并发的优先级组织：

| 层级 | 测试类 | 项数 | 覆盖 |
|---|---|---|---|
| 安全 | SecurityAuditTest | 20 | 路径穿越变体、符号链接逃逸（GET/PUT 父目录/深层树/DELETE 不跟随）、uploadId 注入、内部目录隔离、锁租约与接管、GC 不误删、注入面 |
| 安全 | S3NegativeCasesIntegrationTest | 41 | 全接口负面矩阵：非法 URI/桶名/key、404 语义、Range 非法、checksum、XML 格式与 XXE、认证失败族、AccessMode 全路径、错误 XML 字段完整性 |
| 完整性 | DataIntegrityTest | 20 | 原子写（覆盖/短读/并发）、崩溃残留与 meta 自愈、multipart 拼接顺序/ETag/幂等、GC 数据安全、校验和端到端、删除清理边界 |
| 完整性 | StorageEngineTest / GarbageCollectorTest / LifecycleExecutorTest | 33 | 引擎单元、GC 场景、Lifecycle 过期与清理 |
| 并发 | ConcurrencyTest | 10 | 多写者唯一胜者、读者永不见半截、PUT-DELETE 竞争终态、锁互斥/独立、配额并发无超限、multipart 并发上传、GC 与写并发 |
| 并发 | StorageEngineTest（并发部分） | — | 8 写者同 key 最终一致 |
| 协议 | S3*IntegrationTest（12 个类） | 116 | Bucket/Object/列举/Multipart/Tagging/ACL/配额/多凭证/POST 表单/预签名/配置往返 |
| 单元 | SigV4VerifierTest / AwsChunkedDecoderTest / KeyValidatorTest / S3RequestFilterTest | 29 | 签名黄金向量、chunk 解码与验签链、key 校验、query 解码 |

运行：

```bash
mvn clean test          # 全量 244 项
mvn test -Dtest=SecurityAuditTest          # 只跑安全审计
mvn test -Dtest=ConcurrencyTest            # 只跑并发
```

---

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.6+

### 编译与测试

```bash
mvn clean test        # 244 项单元/集成测试
mvn clean package     # 产出 target/local-oss.jar
```

### 启动

```bash
# 开发（认证关闭，数据在 /tmp/local-oss-dev/）
mvn spring-boot:run

# 生产（密钥从环境变量注入）
OSS_ACCESS_KEY=xxx OSS_SECRET_KEY=yyy OSS_DATA_PATH=/data/oss \
  java -jar target/local-oss.jar --spring.profiles.active=prod
```

默认端口 `8080`。启动后健康检查：`curl http://localhost:8080/localoss-health`。

### 用 AWS CLI 验证

```bash
# 开发模式（未配置 access-key 时认证自动关闭）
export AWS_ACCESS_KEY_ID=dev AWS_SECRET_ACCESS_KEY=dev
aws --endpoint-url http://localhost:8080 s3 mb s3://demo
aws --endpoint-url http://localhost:8080 s3 cp bigfile.bin s3://demo/   # 大文件自动走 Multipart + aws-chunked
aws --endpoint-url http://localhost:8080 s3 ls s3://demo/
aws --endpoint-url http://localhost:8080 s3api head-object --bucket demo --key bigfile.bin

# 生产模式（配置了 AK/SK 时自动启用 SigV4 校验，含流式签名）
```

### 存量文件元数据回填

服务上线后，历史文件没有 `.localoss/meta` 元数据。GET/HEAD 会按需自动生成并异步回填；也可批量预回填：

```bash
java -jar target/local-oss.jar \
  --localoss.backfill-meta \
  --localoss.dry-run \
  [--localoss.bucket=my-bucket] [--localoss.concurrency=8]
```

---

## 配置说明

```yaml
system:
  data-path: /opt/file/data/       # 数据根目录（一级目录 = Bucket）
  region: us-east-1
  access-mode: read-write          # read-write | read-only | write-only | disabled
  access-key: ${OSS_ACCESS_KEY:}   # 为空时关闭认证（生产必须设置！）
  secret-key: ${OSS_SECRET_KEY:}
  force-delete-bucket: false       # 管理员强制删除非空桶
  instance-id: auto                # 多实例唯一标识（默认 HOSTNAME-随机）
  allow-anonymous-read: false      # 匿名只读（未签名 GET/HEAD/List；显式 private 对象仍拒绝）
  clock-skew-seconds: 900          # SigV4 时钟偏差容忍
  presign-max-expires-seconds: 604800  # 预签名最长有效期（7 天）
  max-object-size: 5TB             # 单对象上限
  max-single-put-size: 5GB         # 单次 PUT 上限
  min-part-size: 5MB               # Multipart 最小分片
  max-part-size: 5GB
  max-parts: 10000
  fsync: true                      # NFS 上可关闭换性能
  gc-enabled: true
  tmp-max-age-hours: 24            # tmp 保留时长
  incomplete-upload-max-age-days: 7
  lock-lease-seconds: 30
  quotas:                          # 桶磁盘配额（未配置不限）
    big-data: 500GB
    archive: 2TB
  credentials:                     # 多组 AK/SK（每组可限定级别）
    - access-key: reader-ak
      secret-key: reader-sk
      level: read-only
      description: 只读账号
```

### 读写级别（access-mode 与凭证 level 取交集）

| 模式 | GET/HEAD/List | PUT/POST/COPY/MPU | DELETE | Bucket 管理 | 典型用途 |
|---|---|---|---|---|---|
| `read-write` | ✅ | ✅ | ✅ | ✅ | 默认 |
| `read-only` | ✅ | ❌ 403 | ❌ 403 | ❌ 403 | 数据保护/发布目录（GC 也跳过） |
| `write-only` | ❌ 403（HEAD ✅） | ✅ | ✅ | ✅ | 采集/归档 |
| `disabled` | ❌ 503 | ❌ 503 | ❌ 503 | ❌ 503 | 维护模式 |

### 目录布局

```
${data-path}/
├── my-bucket/                          # 一级目录 = Bucket（S3 客户端可见）
│   ├── photos/2026/a.jpg               # key = "photos/2026/a.jpg"（普通文件）
│   └── .localoss/                      # 内部元数据（对 S3 客户端不可见）
│       ├── bucket.json                 # 桶配置（region/tagging/cors/policy/lifecycle...）
│       ├── meta/<sha256(key)>.json     # 对象元数据（etag/checksum/contentType/tags/acl...）
│       ├── uploads/<uploadId>/         # 未完成 Multipart
│       ├── locks/                      # mkdir 租约锁（owner.json 含真实租约 expiresAt）
│       └── tmp/                        # 上传临时文件（原子 rename）
```

**两条硬规则**（由"直接布局"决定）：

- **同名冲突**：S3 允许 `a` 与 `a/b` 共存，文件系统不允许。目标 key 与已有文件/目录互为前缀时，写入返回 **409 KeyConflict**；
- **超长 key**：单段 >200 字节或路径超限返回 **400 InvalidArgument**，不做哈希回退（保持布局可读）。

---

## 多实例部署（NFS）

所有实例共享同一 `data-path`，无需额外协调服务：

| 机制 | 说明 |
|---|---|
| 原子写 | 上传写 `.localoss/tmp/<instanceId>-<uuid>.tmp` → 校验 → 锁内原子 rename，崩溃/并发不留半截文件 |
| 唯一 ID | uploadId 与临时文件带 `instance-id` 前缀，多实例不冲突；uploadId 白名单校验防路径注入 |
| 租约锁 | `Files.createDirectory` 原子建锁目录 + owner.json（instanceId/expiresAt），默认 30s 租约；过期可被其他实例接管；GC 按租约状态判定，不误删活跃锁 |
| GC 单执行 | `gc.lock` 保证同一时刻只有一个实例跑 GC，每小时一次 |
| 配额 | 原子预留（CAS），单实例内精确；多实例近似（GC 重算收敛） |
| 时间同步 | 建议 NTP（影响预签名过期判断与锁租约） |

NFS 注意事项：tmp 与对象必须在同一挂载点（保证 rename 不跨文件系统）；`ATOMIC_MOVE` 失败自动降级；`fsync` 目录失败 best-effort；进程用户需对 data-path 有读写执行权限（NFS root squash 环境勿依赖 root）。

## 认证模式

| 场景 | 配置 | 行为 |
|---|---|---|
| 本地开发 | `access-key` 为空 | 所有请求免签名（aws-chunked 仅解码不验签） |
| 生产单凭证 | 配置 AK/SK | 所有请求必须 SigV4（Header 或 Presigned 全方法；SDK 默认 aws-chunked 大文件上传直接可用） |
| 多凭证 | `credentials[]` | 每组 AK/SK 独立验证；level 与全局 access-mode 取交集（如只读号不能写/删） |
| 公开只读 | `allow-anonymous-read=true` + AK/SK | 匿名可读未设 ACL 的对象与桶；显式 `private` 对象仍拒绝；写仍需签名 |
| 对象级公开 | 对象 PUT 带 `x-amz-acl: public-read` | 该对象匿名可读，桶内其他对象不受影响 |

---

## 项目结构

```
io.github.letsopen.localoss
├── config/    SystemProperties（全部配置）/ AccessMode / AccessModeResolver / CorsConfig
├── web/       S3RequestFilter（原始 URI 解析 + 过滤器层 S3 错误输出）/ S3Controller / HealthController
├── auth/      SigV4Verifier（Header + Presigned + 多凭证）/ AwsChunkedDecoder（流式验签）/ RequestAuthHelper
├── storage/   StorageEngine / KeyValidator（穿越与符号链接防护）/ MetadataStore / LockManager /
│              MultipartStore / QuotaManager（原子预留）/ AclEvaluator / GarbageCollector / LifecycleExecutor
├── xml/       S3XmlReader（XXE 防护）/ S3XmlWriter
├── error/     S3ErrorCode（25+）/ S3Exception / GlobalExceptionHandler（框架异常→S3 XML）
├── tools/     MetaBackfillTool（元数据回填）
└── model/     ObjectMetadata
```

## 参考文档

- [Amazon S3 API Reference — Actions](https://docs.aws.amazon.com/AmazonS3/latest/API/API_Operations.html)
- [Multipart Upload Overview](https://docs.aws.amazon.com/AmazonS3/latest/userguide/mpuoverview.html)
- [Bucket Naming Rules](https://docs.aws.amazon.com/AmazonS3/latest/userguide/bucketnamingrules.html)
- [Object Key Naming Guidelines](https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-keys.html)

---

## License

本项目遵循仓库根目录的 LICENSE（如未指定，默认保留原作者版权声明）。
