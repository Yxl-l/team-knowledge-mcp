package org.teamknowledge.mcp.service;

import org.teamknowledge.mcp.model.KnowledgeModels.*;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

@Component
public class KnowledgePolicy {
    private static final Pattern SECRET = Pattern.compile("(?im)(?:password|passwd|pwd|api[_-]?key|access[_-]?token|secret)[\"']?\\s*[=:]\\s*[\"']?(?![<$])[A-Za-z0-9+/!@#%&_=.-]{6,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|Bearer\\s+[A-Za-z0-9_.-]{16,}");
    public void text(String value, int max, String field) {
        if (value == null || value.isBlank() || value.length() > max || value.indexOf('\u0000') >= 0)
            fail("INVALID_ARGUMENT", field + " is missing or exceeds the length limit.");
    }
    public void scope(Scope s) {
        if (s == null) fail("CONTEXT_REQUIRED", "A complete scope is required. Use discover_knowledge_scopes when the scope is unknown.");
        String[] names={"system_code","module_name","customer_code","product_version","git_branch","db_vendor"};
        String[] values={s.systemCode(),s.moduleName(),s.customerCode(),s.productVersion(),s.gitBranch(),s.dbVendor()};
        List<String> missing=new ArrayList<>();
        for(int i=0;i<names.length;i++)if(values[i]==null || values[i].isBlank())missing.add(names[i]);
        if(!missing.isEmpty())fail("CONTEXT_REQUIRED","Missing scope fields: "+String.join(", ",missing)+". Use discover_knowledge_scopes to find recorded scopes.");
        text(s.systemCode(),80,"system_code"); text(s.moduleName(),160,"module_name");
        text(s.customerCode(),80,"customer_code"); text(s.productVersion(),80,"product_version");
        text(s.gitBranch(),200,"git_branch"); text(s.dbVendor(),80,"db_vendor");
        for (String part : List.of(s.systemCode(),s.moduleName(),s.customerCode(),s.productVersion(),s.gitBranch(),s.dbVendor())) {
            if (!part.equals(part.strip()) || part.equals("UNKNOWN")) fail("CONTEXT_REQUIRED", "Scope values must be trimmed and cannot be UNKNOWN.");
        }
    }
    public void context(Context c) {
        if (c == null) fail("CONTEXT_REQUIRED", "context.scope is required. Use discover_knowledge_scopes when the scope is unknown.");
        scope(c.scope());
        if (c.commitHash()!=null && !c.commitHash().matches("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}"))
            fail("INVALID_ARGUMENT", "Invalid commit_hash.");
    }
    public boolean applies(Scope stored, Scope requested) {
        return matches(stored.systemCode(),requested.systemCode()) && matches(stored.moduleName(),requested.moduleName())
                && matches(stored.customerCode(),requested.customerCode()) && matches(stored.productVersion(),requested.productVersion())
                && matches(stored.gitBranch(),requested.gitBranch()) && matches(stored.dbVendor(),requested.dbVendor());
    }
    private boolean matches(String stored, String requested) { return stored.equals("GLOBAL") || stored.equals(requested); }

    public SaveRequest prepare(SaveRequest r, boolean admin, TextIndex index) {
        if (r == null) fail("INVALID_ARGUMENT", "Missing save request.");
        scope(r.scope()); text(r.idempotencyKey(),160,"idempotency_key");
        text(r.title(),240,"title"); text(r.body(),65536,"body"); text(r.changeReason(),1000,"change_reason");
        if (r.body().getBytes(StandardCharsets.UTF_8).length>65536) fail("INVALID_ARGUMENT", "The body exceeds 64 KiB.");
        if (r.type()==null) fail("INVALID_ARGUMENT", "type is required.");
        Authority authority = r.authority()==null ? Authority.EXPERIENCE : r.authority();
        Lifecycle lifecycle = r.lifecycle()==null ? Lifecycle.ACTIVE : r.lifecycle();
        boolean standard = r.type()==KnowledgeType.STANDARD;
        if (!admin && (standard || authority!=Authority.EXPERIENCE || r.standard()!=null))
            fail("STANDARD_WRITE_FORBIDDEN", "Use the local admin command to maintain formal standards.");
        if (standard != (authority!=Authority.EXPERIENCE)) fail("INVALID_ARGUMENT", "Knowledge type and authority are inconsistent.");
        if (!admin && (r.scope().systemCode().equals("GLOBAL")
                || r.scope().productVersion().equals("GLOBAL") || r.scope().gitBranch().equals("GLOBAL")))
            fail("SCOPE_MISMATCH", "Specify an exact system, version and branch for a write. Use customer_code=GLOBAL for confirmed shared knowledge.");
        if (r.knowledgeId()!=null && (r.expectedRevision()==null || r.expectedRevision()<1))
            fail("CONFLICT", "An update requires expected_revision.");
        if (r.knowledgeId()==null && r.expectedRevision()!=null) fail("INVALID_ARGUMENT", "A new entry must omit expected_revision.");
        if (!admin) review(r);
        List<Source> sources = r.evidence()==null ? List.of() : r.evidence();
        if (sources.isEmpty() || sources.size()>20) fail("SOURCE_INVALID", "Supply 1 to 20 source references.");
        for (Source s : sources) source(s);
        List<String> symbols = r.symbols()==null ? List.of() : r.symbols();
        if (symbols.size()>100) fail("INVALID_ARGUMENT", "Supply at most 100 symbols.");
        symbols.forEach(s -> text(s,200,"symbol"));
        List<Relation> relations = r.relations()==null ? List.of() : r.relations();
        if (relations.size()>50 || relations.stream().anyMatch(x -> x==null || x.type()==null || x.targetId()==null))
            fail("INVALID_ARGUMENT", "Invalid relationship or more than 50 relationships.");
        Verification v = r.verification();
        if (v==null || v.status()==null) fail("INVALID_ARGUMENT", "verification.status is required.");
        List<String> claims = v.claims()==null ? List.of() : v.claims();
        List<String> checks = v.checks()==null ? List.of() : v.checks();
        if (claims.size()>30 || checks.size()>30) fail("INVALID_ARGUMENT", "Too many verification entries.");
        claims.forEach(x -> text(x,1000,"claim")); checks.forEach(x -> text(x,1000,"check"));
        if (admin && v.status()==VerificationStatus.VERIFIED && (claims.isEmpty() || checks.isEmpty()))
            fail("INVALID_ARGUMENT", "VERIFIED requires claims and verification evidence.");
        VerificationStatus status = !admin && v.status()==VerificationStatus.VERIFIED ? VerificationStatus.UNVERIFIED : v.status();
        if (standard) {
            Standard st = r.standard();
            if (st==null) fail("INVALID_ARGUMENT", "Formal standards require standard metadata.");
            text(st.code(),100,"standard.code"); text(st.version(),80,"standard.version");
            if (!Set.of("java","api","sql","export","docs","git").contains(st.operation()==null ? "" : st.operation()))
                fail("INVALID_ARGUMENT", "Invalid standard operation.");
            try {
                if (st.effectiveFrom()==null) fail("INVALID_ARGUMENT", "A formal standard requires effective_from.");
                LocalDate from=LocalDate.parse(st.effectiveFrom());
                if (st.effectiveTo()!=null && LocalDate.parse(st.effectiveTo()).isBefore(from)) fail("INVALID_ARGUMENT", "The end date precedes the start date.");
            } catch (java.time.format.DateTimeParseException ex) { fail("INVALID_ARGUMENT", "Use YYYY-MM-DD dates for standards."); }
        } else if (r.standard()!=null) fail("INVALID_ARGUMENT", "Standard metadata is reserved for formal standards.");
        SaveRequest result = new SaveRequest(r.idempotencyKey(),r.knowledgeId(),r.expectedRevision(),r.scope(),r.type(),authority,
                index.normalize(r.title()),index.normalize(r.body()),new Verification(status,claims,checks),sources.stream().distinct().sorted(Comparator.comparing(Source::toString)).toList(),
                symbols.stream().distinct().sorted().toList(),relations.stream().distinct().sorted(Comparator.comparing(x -> x.type()+":"+x.targetId())).toList(),
                index.normalize(r.changeReason()),lifecycle,r.standard(),r.review());
        return result;
    }
    private void review(SaveRequest r) {
        WriteReview review=r.review();
        if(review==null || review.changeKind()==null)
            fail("REVIEW_REQUIRED", "Review matching entries, summarize the change and validation, and obtain user confirmation of the text and scope. Then supply review.change_kind, summary, validation and user_confirmed.");
        text(review.summary(),2000,"review.summary");text(review.validation(),2000,"review.validation");
        if(!review.userConfirmed())fail("CONFIRMATION_REQUIRED", "Obtain user confirmation of the exact text and scope before saving.");
        if((review.changeKind()==ChangeKind.NEW)!=(r.knowledgeId()==null))
            fail("INVALID_ARGUMENT", "Use NEW for creation. CORRECTION and SUPPLEMENT require knowledge_id and expected_revision.");
    }
    private void source(Source s) {
        if (s==null || s.revisionKind()==null) fail("SOURCE_INVALID", "A source requires revision_kind.");
        text(s.repoId(),100,"repo_id"); text(s.sourcePath(),1000,"source_path");
        String p=s.sourcePath().replace('\\','/');
        if (p.startsWith("/") || p.contains(":") || Arrays.asList(p.split("/")).contains(".."))
            fail("SOURCE_INVALID", "Use a repository-relative source path.");
        if (s.contentHash()==null || !s.contentHash().matches("[a-f0-9]{64}")) fail("SOURCE_INVALID", "A source requires a SHA-256 hash.");
        if ((s.sourceLine()==null)!=(s.sourceEndLine()==null) || (s.sourceLine()!=null && (s.sourceLine()<1 || s.sourceEndLine()<s.sourceLine())))
            fail("SOURCE_INVALID", "Invalid source line range.");
        if (s.revisionKind()==RevisionKind.COMMIT && !commit(s.commitHash())) fail("SOURCE_INVALID", "A COMMIT source requires commit_hash.");
        if (s.revisionKind()==RevisionKind.WORKTREE && !commit(s.baseCommit())) fail("SOURCE_INVALID", "A WORKTREE source requires base_commit.");
        if (s.commitHash()!=null && !commit(s.commitHash()) || s.baseCommit()!=null && !commit(s.baseCommit())) fail("SOURCE_INVALID", "Invalid commit hash.");
    }
    private boolean commit(String s) { return s!=null && s.matches("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}"); }
    public void rejectSecrets(String content) {
        if (SECRET.matcher(content).find()) fail("SENSITIVE_CONTENT", "Potential credentials detected. Remove sensitive values before saving.");
    }
    public int limit(Integer value) { if(value==null)return 10; if(value<1 || value>20)fail("INVALID_ARGUMENT","limit must be between 1 and 20."); return value; }
    public int offset(Integer value) { if(value==null)return 0; if(value<0 || value>10000)fail("INVALID_ARGUMENT","offset must be between 0 and 10000."); return value; }
    public static void fail(String code, String message) { throw new KnowledgeException(code,message); }
}
