package org.teamknowledge.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;

/** The same contract is used by MCP and the local administration command. */
public final class KnowledgeModels {
    private KnowledgeModels() { }

    public enum KnowledgeType { STANDARD, ARCHITECTURE, BUSINESS_RULE, BUG, DECISION, TASK, DATA_LINEAGE, EXPERIENCE }
    public enum Authority { COMPANY_MANDATORY, COMPANY_RECOMMENDED, PRODUCT_STANDARD, CUSTOMER_STANDARD, PROJECT_CONVENTION, EXPERIENCE }
    public enum VerificationStatus { VERIFIED, HISTORICAL, UNVERIFIED }
    public enum Lifecycle { ACTIVE, RETRACTED, RETIRED }
    public enum RevisionKind { COMMIT, WORKTREE, DOCUMENT, TEST }
    public enum RelationType { RELATED_TO, CORRECTS, CALLS, READS, WRITES, IMPLEMENTS, APPLIES_TO }
    public enum ChangeKind { NEW, CORRECTION, SUPPLEMENT }

    public record Scope(String systemCode, String moduleName, String customerCode, String productVersion,
                        String gitBranch, String dbVendor) { }
    public record Context(Scope scope, String commitHash) { }
    public record Source(String repoId, String sourcePath, Integer sourceLine, Integer sourceEndLine,
                         RevisionKind revisionKind, String commitHash, String baseCommit, String contentHash) { }
    public record Verification(VerificationStatus status, List<String> claims, List<String> checks) { }
    public record Standard(String code, String version, String operation, String effectiveFrom, String effectiveTo) { }
    public record Relation(RelationType type, UUID targetId) { }
    public record WriteReview(ChangeKind changeKind, String summary, String validation, boolean userConfirmed) { }
    public record SaveRequest(String idempotencyKey, UUID knowledgeId, Integer expectedRevision, Scope scope,
                              KnowledgeType type, Authority authority, String title, String body,
                              Verification verification, List<Source> evidence, List<String> symbols,
                              List<Relation> relations, String changeReason, Lifecycle lifecycle, Standard standard,
                              @JsonInclude(JsonInclude.Include.NON_NULL) WriteReview review) {
        /** Existing local admin payloads keep their original serialization and idempotency hash. */
        public SaveRequest(String idempotencyKey, UUID knowledgeId, Integer expectedRevision, Scope scope,
                           KnowledgeType type, Authority authority, String title, String body,
                           Verification verification, List<Source> evidence, List<String> symbols,
                           List<Relation> relations, String changeReason, Lifecycle lifecycle, Standard standard) {
            this(idempotencyKey,knowledgeId,expectedRevision,scope,type,authority,title,body,verification,evidence,
                    symbols,relations,changeReason,lifecycle,standard,null);
        }
    }
    public record SaveResult(UUID knowledgeId, UUID revisionId, int revisionNo, String outcome,
                             String contentHash, String receiptId, List<String> warnings) { }
    public record SearchRequest(Context context, String query, List<KnowledgeType> types,
                                boolean includeUnverified, Integer limit, Integer offset) { }
    public record SearchHit(UUID knowledgeId, int revisionNo, String title, String excerpt, KnowledgeType type,
                            Authority authority, VerificationStatus verificationStatus, String applicability,
                            Scope scope, List<Source> sources, String scoreReason) { }
    public record SearchResult(List<SearchHit> items, Integer nextOffset, Context contextUsed, List<String> warnings) { }
    public record ScopeDiscoveryRequest(String systemCode, String query, Integer limit, Integer offset) { }
    public record ScopeCandidate(Scope scope, long knowledgeCount, long matchingCount, long unverifiedCount) { }
    public record ScopeDiscoveryResult(List<ScopeCandidate> candidates, Integer nextOffset, List<String> systems,
                                       List<String> warnings) { }
    public record Detail(UUID knowledgeId, UUID revisionId, int revisionNo, String title, String body,
                         KnowledgeType type, Authority authority, Scope scope, Verification verification,
                         Lifecycle lifecycle, boolean current, String contentHash, List<Source> sources,
                         List<String> symbols, List<Relation> relations, Standard standard,
                         String changeReason, String createdAt, List<String> warnings,
                         @JsonInclude(JsonInclude.Include.NON_NULL) WriteReview review) { }
    public record HistoryItem(int revisionNo, String title, VerificationStatus verificationStatus,
                              Lifecycle lifecycle, String changeReason, String createdAt,
                              @JsonInclude(JsonInclude.Include.NON_NULL) WriteReview review) { }
    public record HistoryResult(List<HistoryItem> items, Integer nextOffset) { }
    public record StandardsResult(List<SearchHit> mandatory, List<SearchHit> applicable,
                                  List<String> conflicts, List<String> missingContext) { }
    public record RelatedResult(List<SearchHit> nodes, List<Relation> edges, List<String> warnings) { }
}
