package io.contentpublisher.platform.application;

import io.contentpublisher.platform.application.port.AuditRecorder;
import io.contentpublisher.platform.application.port.ChannelAccountRepository;
import io.contentpublisher.platform.application.port.ChannelEndpointPolicy;
import io.contentpublisher.platform.application.port.ChannelConnectionVerifier;
import io.contentpublisher.platform.application.port.CredentialVault;
import io.contentpublisher.platform.domain.ActorContext;
import io.contentpublisher.platform.domain.ChannelAccount;
import io.contentpublisher.platform.domain.ChannelAccountStatus;
import io.contentpublisher.platform.domain.ChannelType;
import io.contentpublisher.platform.domain.ChannelVerificationStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ChannelAccountApplicationService {
    private final ChannelAccountRepository accounts;
    private final CredentialVault credentialVault;
    private final ChannelEndpointPolicy endpointPolicy;
    private final AuditRecorder auditRecorder;
    private final ChannelConnectionVerifier connectionVerifier;
    private final Clock clock;

    public ChannelAccountApplicationService(ChannelAccountRepository accounts, CredentialVault credentialVault,
                                            ChannelEndpointPolicy endpointPolicy, AuditRecorder auditRecorder,
                                            Clock clock) {
        this(accounts, credentialVault, endpointPolicy, auditRecorder,
                (account, credentials) -> ChannelConnectionResult.failure("当前运行环境未配置渠道连接验证器"), clock);
    }

    public ChannelAccountApplicationService(ChannelAccountRepository accounts, CredentialVault credentialVault,
                                            ChannelEndpointPolicy endpointPolicy, AuditRecorder auditRecorder,
                                            ChannelConnectionVerifier connectionVerifier, Clock clock) {
        this.accounts = accounts;
        this.credentialVault = credentialVault;
        this.endpointPolicy = endpointPolicy;
        this.auditRecorder = auditRecorder;
        this.connectionVerifier = connectionVerifier;
        this.clock = clock;
    }

    public ChannelAccount createAccount(ActorContext actor, ChannelType type, String displayName,
                                        String baseUrl, Map<String, String> credentials, String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);
        ChannelCatalog.ChannelDefinition definition = ChannelCatalog.definition(type);
        if (!definition.apiSupported()) {
            throw new ApplicationException("CHANNEL_MANUAL_ONLY", "该渠道采用跳转登录和人工发布，无需配置 API 账号");
        }
        if (!definition.configurationAllowed()) {
            throw new ApplicationException("CHANNEL_CONFIGURATION_UNAVAILABLE", definition.availabilityNote());
        }
        String name = requireText(displayName, "渠道账号名称不能为空", 120);
        Map<String, String> validatedCredentials = validateCredentials(type, credentials);
        String normalizedBaseUrl = endpointPolicy.validateAndNormalize(type, baseUrl);
        String credentialFingerprint = credentialVault.fingerprint(validatedCredentials);
        String requestHash = accountHash(type, name, normalizedBaseUrl, credentialFingerprint);
        ChannelAccount existing = accounts.findChannelAccountByIdempotencyKey(actor.tenantId(), idempotencyKey)
                .orElse(null);
        if (existing != null) {
            if (!existing.requestHash().equals(requestHash)) {
                throw new ApplicationException("IDEMPOTENCY_KEY_CONFLICT", "幂等键已用于不同请求");
            }
            return existing;
        }
        Instant now = clock.instant();
        ChannelAccount saved = accounts.save(new ChannelAccount(UUID.randomUUID(), actor.tenantId(), type, name,
                normalizedBaseUrl, credentialVault.encrypt(validatedCredentials), idempotencyKey, requestHash,
                credentialFingerprint, 1, ChannelAccountStatus.ACTIVE,
                actor.subject(), actor.subject(), now, now));
        auditRecorder.record(actor, "CHANNEL_ACCOUNT_CREATED", "CHANNEL_ACCOUNT", saved.id(),
                Map.of("channelType", type.name()));
        return saved;
    }

    public List<ChannelAccount> listAccounts(ActorContext actor) {
        return accounts.findAll(actor.tenantId());
    }

    public long countAccounts(ActorContext actor) {
        return accounts.countAll(actor.tenantId());
    }

    public ChannelAccount getAccount(ActorContext actor, UUID accountId) {
        return accounts.findChannelAccountById(actor.tenantId(), accountId)
                .orElseThrow(() -> new ApplicationException("CHANNEL_ACCOUNT_NOT_FOUND", "渠道账号不存在"));
    }

    public ChannelAccount updateAccountStatus(ActorContext actor, UUID accountId, int expectedVersion,
                                              ChannelAccountStatus status) {
        ChannelAccount account = getAccount(actor, accountId);
        if (account.status() == status && account.version() == expectedVersion + 1) return account;
        requireAccountVersion(account, expectedVersion);
        if (account.status() == status) return account;
        Instant now = clock.instant();
        ChannelAccount candidate = new ChannelAccount(account.id(), account.tenantId(), account.type(),
                account.displayName(), account.baseUrl(), account.encryptedCredentials(), account.idempotencyKey(),
                account.requestHash(), account.credentialFingerprint(), account.version() + 1, status,
                account.verificationStatus(), account.verificationMessage(), account.lastVerifiedAt(),
                account.createdBy(), actor.subject(), account.createdAt(), now);
        ChannelAccount saved = accounts.updateIfVersionMatches(candidate, expectedVersion)
                .orElseThrow(this::accountVersionConflict);
        auditRecorder.record(actor, "CHANNEL_ACCOUNT_STATUS_CHANGED", "CHANNEL_ACCOUNT", accountId,
                Map.of("status", status.name(), "version", Integer.toString(saved.version())));
        return saved;
    }

    public ChannelAccount rotateCredentials(ActorContext actor, UUID accountId, int expectedVersion,
                                            Map<String, String> credentials) {
        ChannelAccount account = getAccount(actor, accountId);
        Map<String, String> validated = validateCredentials(account.type(), credentials);
        String fingerprint = credentialVault.fingerprint(validated);
        if (account.version() == expectedVersion + 1 && fingerprint.equals(account.credentialFingerprint())) {
            return account;
        }
        requireAccountVersion(account, expectedVersion);
        Instant now = clock.instant();
        ChannelAccount candidate = new ChannelAccount(account.id(), account.tenantId(), account.type(),
                account.displayName(), account.baseUrl(), credentialVault.encrypt(validated), account.idempotencyKey(),
                account.requestHash(), fingerprint, account.version() + 1, account.status(),
                null, "凭据已更新，等待重新验证", null,
                account.createdBy(), actor.subject(), account.createdAt(), now);
        ChannelAccount saved = accounts.updateIfVersionMatches(candidate, expectedVersion)
                .orElseThrow(this::accountVersionConflict);
        auditRecorder.record(actor, "CHANNEL_CREDENTIALS_ROTATED", "CHANNEL_ACCOUNT", accountId,
                Map.of("channelType", account.type().name(), "version", Integer.toString(saved.version())));
        return saved;
    }

    public ChannelAccount updateAccountProfile(ActorContext actor, UUID accountId, int expectedVersion,
                                               String displayName, String baseUrl) {
        ChannelAccount account = getAccount(actor, accountId);
        requireAccountVersion(account, expectedVersion);
        String name = requireText(displayName, "渠道账号名称不能为空", 120);
        String normalizedBaseUrl = endpointPolicy.validateAndNormalize(account.type(), baseUrl);
        if (account.displayName().equals(name) && java.util.Objects.equals(account.baseUrl(), normalizedBaseUrl)) {
            return account;
        }
        Instant now = clock.instant();
        String requestHash = accountHash(account.type(), name, normalizedBaseUrl, account.credentialFingerprint());
        ChannelAccount candidate = new ChannelAccount(account.id(), account.tenantId(), account.type(), name,
                normalizedBaseUrl, account.encryptedCredentials(), account.idempotencyKey(), requestHash,
                account.credentialFingerprint(), account.version() + 1, account.status(),
                account.verificationStatus(), account.verificationMessage(), account.lastVerifiedAt(),
                account.createdBy(), actor.subject(), account.createdAt(), now);
        ChannelAccount saved = accounts.updateIfVersionMatches(candidate, expectedVersion)
                .orElseThrow(this::accountVersionConflict);
        auditRecorder.record(actor, "CHANNEL_ACCOUNT_UPDATED", "CHANNEL_ACCOUNT", accountId,
                Map.of("channelType", account.type().name(), "version", Integer.toString(saved.version())));
        return saved;
    }

    public ChannelAccount verifyConnection(ActorContext actor, UUID accountId) {
        ChannelAccount account = getAccount(actor, accountId);
        ChannelCatalog.ChannelDefinition definition = ChannelCatalog.definition(account.type());
        if (!definition.existingAccountOperational()) {
            throw new ApplicationException("CHANNEL_VERIFICATION_UNAVAILABLE", definition.availabilityNote());
        }
        ChannelConnectionResult result = connectionVerifier.verify(account,
                credentialVault.decrypt(account.encryptedCredentials()));
        Instant checkedAt = clock.instant();
        ChannelVerificationStatus status = result.succeeded()
                ? ChannelVerificationStatus.SUCCEEDED : ChannelVerificationStatus.FAILED;
        ChannelAccount saved = accounts.updateVerification(actor.tenantId(), accountId, status,
                        result.message(), checkedAt)
                .orElseThrow(() -> new ApplicationException("CHANNEL_ACCOUNT_NOT_FOUND", "渠道账号不存在"));
        auditRecorder.record(actor, "CHANNEL_CONNECTION_VERIFIED", "CHANNEL_ACCOUNT", accountId,
                Map.of("channelType", account.type().name(), "status", status.name()));
        return saved;
    }

    public void removeAccount(ActorContext actor, UUID accountId, int expectedVersion) {
        ChannelAccount account = getAccount(actor, accountId);
        requireAccountVersion(account, expectedVersion);
        if (!accounts.softDeleteIfVersionMatches(actor.tenantId(), accountId, expectedVersion,
                actor.subject(), clock.instant())) {
            throw accountVersionConflict();
        }
        auditRecorder.record(actor, "CHANNEL_ACCOUNT_DELETED", "CHANNEL_ACCOUNT", accountId,
                Map.of("channelType", account.type().name(),
                        "version", Integer.toString(expectedVersion + 1)));
    }

    private Map<String, String> validateCredentials(ChannelType type, Map<String, String> credentials) {
        Map<String, String> source = credentials == null ? Map.of() : credentials;
        List<String> required = ChannelCatalog.definition(type).credentialKeys();
        if (!source.keySet().equals(new java.util.HashSet<>(required))) {
            throw new ApplicationException("CHANNEL_CREDENTIALS_INVALID",
                    "渠道凭据字段必须且只能包含: " + String.join(", ", required));
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        required.forEach(key -> normalized.put(key, requireText(source.get(key), "渠道凭据不能为空", 4000)));
        validateCredentialValues(type, normalized);
        return Map.copyOf(normalized);
    }

    private void validateCredentialValues(ChannelType type, Map<String, String> credentials) {
        if (type == ChannelType.REDDIT && !credentials.get("subreddit").matches("[A-Za-z0-9_]{3,21}")) {
            throw new ApplicationException("CHANNEL_CREDENTIALS_INVALID", "Reddit subreddit 格式无效");
        }
        if (type == ChannelType.MEDIUM && !credentials.get("authorId").matches("[A-Za-z0-9_-]{1,200}")) {
            throw new ApplicationException("CHANNEL_CREDENTIALS_INVALID", "Medium authorId 格式无效");
        }
        if (type == ChannelType.GHOST) {
            String[] parts = credentials.get("adminApiKey").split(":", 2);
            if (parts.length != 2 || parts[0].isBlank() || parts[0].length() > 200
                    || !parts[1].matches("(?:[0-9a-fA-F]{2}){16,}")) {
                throw new ApplicationException("CHANNEL_CREDENTIALS_INVALID",
                        "Ghost Admin API Key 必须为 id:hexSecret 格式");
            }
        }
    }

    private String requireText(String value, String message, int maxLength) {
        if (value == null || value.isBlank()) throw new ApplicationException("INVALID_ARGUMENT", message);
        String normalized = value.trim();
        if (normalized.length() > maxLength) throw new ApplicationException("INVALID_ARGUMENT", "字段长度超过限制");
        return normalized;
    }

    private void validateIdempotencyKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{8,128}")) {
            throw new ApplicationException("IDEMPOTENCY_KEY_INVALID",
                    "Idempotency-Key 必须为 8 到 128 位字母、数字、点、下划线、冒号或连字符");
        }
    }

    private String accountHash(ChannelType type, String name, String baseUrl, String credentialFingerprint) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, type.name());
            updateDigest(digest, name);
            updateDigest(digest, baseUrl);
            updateDigest(digest, credentialFingerprint);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }

    private void updateDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0x1f);
    }

    private void requireAccountVersion(ChannelAccount account, int expectedVersion) {
        if (expectedVersion < 1 || account.version() != expectedVersion) throw accountVersionConflict();
    }

    private ApplicationException accountVersionConflict() {
        return new ApplicationException("CHANNEL_ACCOUNT_VERSION_CONFLICT",
                "渠道账号已被其他请求修改，请刷新后重试");
    }
}
