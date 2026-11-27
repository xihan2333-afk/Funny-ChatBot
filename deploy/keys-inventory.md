# 密钥与凭证清单（deploy/keys-inventory.md）

本清单收口系统全部敏感凭证与密钥的存放位置、加密方式、使用边界与轮换方式（对照 `admin-auth.md` 第 3 节与设计方案 2.6.6 /
2.6.7）。

## 1. 全量密钥清单

| 凭证类别                                    | 存放位置                                                                        | 保护形态                                                                           | 消费方                                             | 轮换方式                                                                                                                                                                       |
|-----------------------------------------|-----------------------------------------------------------------------------|--------------------------------------------------------------------------------|-------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **LLM 供应商 API Key**                     | MySQL `credential` 表（`owner_kind='provider'`）                               | AES-256-GCM（独立随机 12B IV + 128 位认证标签），脱敏存 `key_mask`                            | `JdbcCredentialPool`                            | 管理平台新增新 key 并停用旧 key；主密钥变更走 `CredentialRotationService`                                                                                                                    |
| **HTTP 泛化工具凭证**                         | MySQL `credential` 表（`owner_kind='http_tool'`）                              | AES-256-GCM（同上）                                                                | `HttpGenericTool`                               | 管理平台更新凭证行；主密钥变更走 `CredentialRotationService`                                                                                                                               |
| **平台协议端凭证**                             | MySQL `credential` 表（`owner_kind='platform'`）或 `.env` `ONEBOT_ACCESS_TOKEN` | 库内 AES-256-GCM / 接入层 `.env` 环境变量（不入仓）                                          | `chatbot-access-layer`                          | 更新协议端配置与 `.env` 后重启接入层                                                                                                                                                     |
| **管理平台单账号口令**                           | MySQL `admin_account` 表（`password_hash`）                                    | BCrypt（`spring-security-crypto` 单 jar，自带随机盐与成本参数）；会话 token 存 Redis（TTL 默认 12h） | `AdminAccountService` / `ManagementAuthFilter`  | 首次登录强制改密（`must_change=1`）；管理员通过 `/api/management/auth/change-password` 改密并自动全端下线                                                                                           |
| **基础设施口令（MySQL / Redis / ES / Qdrant）** | 部署宿主机 `.env` 文件（不入仓，权限 `0600`）                                              | 明文仅存于宿主机 `.env` 与容器环境变量                                                        | Docker Compose / Spring Boot                    | 停机或滚动切换基础设施口令，更新 `.env` 后重启服务（归属部署章 2.8 收口）                                                                                                                                |
| **凭证加密主密钥**                             | 部署宿主机 `.env`（`CHATBOT_CREDENTIAL_KEY`，32 字节 Base64，绝不落库）                    | 宿主机环境变量，进程内存仅保留 `SecretKeySpec`                                                | `CredentialCodec` / `CredentialRotationService` | 双密钥平滑轮换：1) 配新旧双密钥启动过渡（`CHATBOT_CREDENTIAL_KEY` 设新钥、`CHATBOT_CREDENTIAL_PREVIOUS_KEY` 设旧钥）；2) 执行 `CredentialRotationService.rotate(newKey)` 幂等重加密全表；3) 验证全表通过新钥解密后撤除旧钥配置并重启 |

## 2. 管理面暴露面约束（FR-019）

- 第一版管理接口（`/api/management/**`）**严禁直接暴露公网**。
- 部署时通过 `server.address=127.0.0.1`（或内网地址）收口监听面；如需远程访问，由运维侧前置反向代理（Nginx / Tailscale /
  WireGuard + TLS + IP 白名单）。
- 全部管理接口（含只读观测查询接口）均受 `ManagementAuthFilter` 保护。

## 3. 容器镜像完整性声明（US10 T092）

- 生产部署中所有容器镜像（`elasticsearch`、`otel-collector-contrib`、`sandbox-executor`
  基础镜像、MySQL/Redis/Qdrant）必须固定明确版本号，并由 2.8 部署章在 CI/发布流水线中固化镜像 SHA-256 摘要校验（
  `image@sha256:...`），禁止使用 `:latest` 漂移标签。
- 沙箱镜像与观测栈镜像版本均登记于《ChatBot设计方案.md》3.1.2 依赖与镜像矩阵。
