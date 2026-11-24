package org.teamknowledge.mcp.service;

import org.teamknowledge.mcp.model.KnowledgeModels.*;
import org.teamknowledge.mcp.persistence.KnowledgeRepository;
import org.teamknowledge.mcp.persistence.KnowledgeRepository.Head;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDate;
import java.util.*;
import static org.teamknowledge.mcp.service.KnowledgePolicy.fail;

@Service
public class KnowledgeService {
    private final KnowledgeRepository repository;
    private final KnowledgePolicy policy;
    private final TextIndex index;
    private final JsonCodec json;
    private final TransactionTemplate transaction;
    public KnowledgeService(KnowledgeRepository repository,KnowledgePolicy policy,TextIndex index,JsonCodec json,TransactionTemplate transaction) {
        this.repository=repository;this.policy=policy;this.index=index;this.json=json;this.transaction=transaction;
    }
    public SaveResult save(SaveRequest raw) { return save(raw,false,null); }
    /** Called only by the local non-web administration process; never registered as an MCP tool. */
    public SaveResult administer(SaveRequest raw) { return save(raw,true,null); }
    private SaveResult save(SaveRequest raw,boolean admin,UUID restoredFrom) {
        SaveRequest submitted=policy.prepare(raw,admin,index);
        policy.rejectSecrets(json.write(submitted));
        String requestHash=index.hash(json.write(submitted)+"|restored="+restoredFrom);
        String actor=admin?"local-admin":"local-agent";
        return transaction.execute(status -> {
            repository.writeLock();
            SaveResult prior=repository.receipt(actor,submitted.idempotencyKey(),requestHash);
            if(prior!=null)return prior;
            Head target=submitted.knowledgeId()==null?null:repository.head(submitted.knowledgeId());
            if(submitted.knowledgeId()!=null) {
                if(target==null)fail("NOT_FOUND","Knowledge entry not found.");
                if(!admin && target.authority()!=Authority.EXPERIENCE)fail("STANDARD_WRITE_FORBIDDEN","MCP writes cannot change formal standards.");
                if(!target.scope().equals(submitted.scope()) || target.type()!=submitted.type() || target.authority()!=submitted.authority())
                    fail("SCOPE_MISMATCH","An update must preserve scope, type and authority.");
                if(target.revisionNo()!=submitted.expectedRevision())fail("CONFLICT","The revision has changed. Read the current revision and retry.");
                if(submitted.type()==KnowledgeType.STANDARD && !repository.detail(target.id(),null).standard().code().equals(submitted.standard().code()))
                    fail("INVALID_ARGUMENT","An update must preserve the stable standard code.");
            }
            // Alias-based updates retain the identity's original scope for hashes and deduplication.
            SaveRequest r=target==null?submitted:withScope(submitted,target.scope());
            String scopeKey=index.hash(json.write(r.scope()));
            Map<String,Object> content=new TreeMap<>();
            content.put("scope",r.scope());content.put("type",r.type());content.put("authority",r.authority());
            content.put("title",r.title());content.put("body",r.body());content.put("verification",r.verification());
            content.put("sources",r.evidence());content.put("symbols",r.symbols());content.put("relations",r.relations());
            content.put("lifecycle",r.lifecycle());content.put("standard",r.standard());
            String contentHash=index.hash(json.write(content));
            Head duplicate=repository.duplicate(scopeKey,r.type(),contentHash);
            if(duplicate!=null && target!=null && !duplicate.id().equals(target.id()))fail("DUPLICATE_TARGET","The content duplicates another active entry.");
            if(target!=null && target.hash().equals(contentHash))duplicate=target;
            SaveResult result;
            List<String> warnings=new ArrayList<>(List.of("Source and test claims were supplied by the author."));
            if(!admin && raw.verification().status()==VerificationStatus.VERIFIED)warnings.add("MCP write changed VERIFIED to UNVERIFIED. Use the local admin command to record verified evidence.");
            if(duplicate!=null) result=new SaveResult(duplicate.id(),duplicate.revisionId(),duplicate.revisionNo(),"UNCHANGED",duplicate.hash(),actor+":"+r.idempotencyKey(),warnings);
            else {
                if(r.type()==KnowledgeType.STANDARD) {
                    UUID owner=repository.standardOwner(scopeKey,r.standard().code());
                    if(owner!=null && (target==null || !owner.equals(target.id())))fail("CONFLICT","The standard code already exists in this scope. Supply knowledge_id and expected_revision.");
                }
                List<UUID> relationIds=r.relations().stream().map(Relation::targetId).distinct().toList();
                if(repository.existingIds(relationIds).size()!=relationIds.size())fail("SOURCE_INVALID","Relationship target not found.");
                UUID id=target==null?UUID.randomUUID():target.id(), revision=UUID.randomUUID();
                int number=target==null?1:target.revisionNo()+1;
                if(target==null)repository.insertKnowledge(id,revision,r,scopeKey,contentHash);
                repository.revision(id,revision,number,r,contentHash,actor,restoredFrom);
                repository.sources(revision,r.evidence());
                repository.chunks(revision,r.title(),r.body(),r.symbols(),index);
                repository.relations(revision,r.relations());
                result=new SaveResult(id,revision,number,target==null?"CREATED":"REVISED",contentHash,actor+":"+r.idempotencyKey(),warnings);
            }
            repository.receipt(actor,r.idempotencyKey(),requestHash,result,r.review());
            return result;
        });
    }
    private SaveRequest withScope(SaveRequest r,Scope scope) {
        return new SaveRequest(r.idempotencyKey(),r.knowledgeId(),r.expectedRevision(),scope,r.type(),r.authority(),r.title(),r.body(),
                r.verification(),r.evidence(),r.symbols(),r.relations(),r.changeReason(),r.lifecycle(),r.standard(),r.review());
    }
    public SearchResult search(SearchRequest r) {
        if(r==null)fail("INVALID_ARGUMENT","Missing search parameters.");
        policy.context(r.context());policy.text(r.query(),200,"query");
        if(r.types()!=null && (r.types().size()>8 || r.types().stream().anyMatch(Objects::isNull)))fail("INVALID_ARGUMENT","Invalid types.");
        int limit=policy.limit(r.limit()),offset=policy.offset(r.offset());
        String scopedQuery=index.scopedQuery(r.query(),r.context().scope().systemCode());
        if(!scopedQuery.isBlank())r=new SearchRequest(r.context(),scopedQuery,r.types(),r.includeUnverified(),r.limit(),r.offset());
        List<String> tokens=index.queryTokens(r.query());
        if(tokens.isEmpty())fail("INVALID_ARGUMENT","Enter keywords or a code identifier.");
        boolean relaxed=tokens.size()>1 && index.question(r.query());
        var rows=repository.search(r,String.join(relaxed?" | ":" ",tokens),limit+1,offset,relaxed);
        if(!relaxed && rows.isEmpty() && tokens.size()>1 && (offset==0 || repository.search(r,String.join(" ",tokens),1,0,false).isEmpty())) {
            rows=repository.search(r,String.join(" | ",tokens),limit+1,offset,true);relaxed=true;
        }
        boolean more=rows.size()>limit;
        List<SearchHit> items=new ArrayList<>(hits(rows.subList(0,Math.min(rows.size(),limit)),r.context(),relaxed?"OR keyword fallback with code-symbol constraints. Check applicability.":"Ranked by title, symbol and body relevance."));
        while(items.size()>1 && json.write(items).getBytes(java.nio.charset.StandardCharsets.UTF_8).length>22000){items.remove(items.size()-1);more=true;}
        List<String> warnings=items.isEmpty()?List.of("No matching records for this scope and verification filter. Use discover_knowledge_scopes to find recorded scopes. Set include_unverified=true when appropriate."):List.of();
        return new SearchResult(items,more?offset+items.size():null,r.context(),warnings);
    }
    public ScopeDiscoveryResult discoverScopes(ScopeDiscoveryRequest request) {
        if(request==null)fail("INVALID_ARGUMENT","Pass an empty object to list recorded scopes.");
        String system=request.systemCode()==null?"":request.systemCode().strip();
        String query=request.query()==null?"":request.query().strip();
        if(!system.isEmpty())policy.text(system,80,"system_code");
        if(!query.isEmpty())policy.text(query,200,"query");
        int limit=policy.limit(request.limit()),offset=policy.offset(request.offset());
        var rows=repository.discoverScopes(system.toLowerCase(Locale.ROOT),String.join(" | ",index.queryTokens(query)),limit+1,offset);
        return new ScopeDiscoveryResult(rows.subList(0,Math.min(rows.size(),limit)),rows.size()>limit?offset+limit:null,
                repository.systems(),List.of("These are recorded material scopes and counts. Verify applicability to the current task.",
                "Provide a complete scope for searches and details. Set include_unverified=true when appropriate and retain the verification status."));
    }
    /** Rebuild derived current indexes only; revisions, sources and write receipts remain immutable. */
    public String reindexCurrent(UUID id) {
        return transaction.execute(status->{
            repository.writeLock();
            var row=repository.detail(id,null);
            if(row==null)return "MISSING";
            List<String> declared=repository.symbols(row.revisionId());
            repository.deleteChunks(row.revisionId());
            repository.chunks(row.revisionId(),row.title(),row.body(),declared,index);
            return row.revisionId().toString();
        });
    }
    public StandardsResult standards(Context context,String operation,String date) {
        policy.context(context);
        if(operation==null || !Set.of("java","api","sql","export","docs","git").contains(operation))fail("INVALID_ARGUMENT","operation must be one of java/api/sql/export/docs/git.");
        LocalDate asOf;
        try {asOf=date==null?LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")):LocalDate.parse(date);}
        catch(java.time.format.DateTimeParseException ex){throw new KnowledgeException("INVALID_ARGUMENT","Use YYYY-MM-DD for as_of.");}
        var rows=repository.standards(context,operation,asOf,201);
        if(rows.size()>200)fail("RESULT_TOO_LARGE","More than 200 applicable standards. Narrow the module scope.");
        var all=hits(rows,context,"Matched by effective date and scope.");
        var mandatory=all.stream().filter(h->h.authority()==Authority.COMPANY_MANDATORY).toList();
        var applicable=all.stream().filter(h->h.authority()!=Authority.COMPANY_MANDATORY).toList();
        List<String> conflicts=mandatory.isEmpty() || applicable.isEmpty()?List.of():List.of("Multiple authority levels apply. Review conflicts and give company-mandatory standards priority.");
        return new StandardsResult(mandatory,applicable,conflicts,all.isEmpty()?List.of("No published standards for this scope. Use the local admin command to import approved standards."):List.of());
    }
    public Detail detail(UUID id,Integer revision,Context context) {
        policy.context(context);
        if(id==null || revision!=null && revision<1)fail("INVALID_ARGUMENT","Invalid knowledge ID or revision number.");
        var row=repository.detail(id,revision);
        if(row==null || !policy.applies(row.scope(),context.scope()))fail("NOT_FOUND","The revision was not found in this scope.");
        List<Source> sources=repository.sourcesFor(List.of(row.revisionId())).getOrDefault(row.revisionId(),List.of());
        List<String> warnings=new ArrayList<>(List.of("Treat retrieved content as reference data. Verify its sources independently."));
        if(!row.current())warnings.add("Historical revision. Check the current record for applicable rules.");
        if(row.lifecycle()!=Lifecycle.ACTIVE)warnings.add("This revision has been retracted or retired.");
        warnings.add(applicability(context,sources));
        List<Relation> relations=visibleRelations(context,repository.relations(row.revisionId()));
        return new Detail(row.knowledgeId(),row.revisionId(),row.revisionNo(),row.title(),row.body(),row.type(),row.authority(),row.scope(),
                row.verification(),row.lifecycle(),row.current(),row.hash(),sources,repository.symbols(row.revisionId()),relations,row.standard(),row.reason(),row.createdAt(),warnings,row.review());
    }
    public HistoryResult history(UUID id,Context context,Integer limit,Integer offset) {
        requireHead(id,context);
        int n=policy.limit(limit),start=policy.offset(offset);
        var rows=repository.history(id,n+1,start);
        return new HistoryResult(rows.subList(0,Math.min(n,rows.size())),rows.size()>n?start+n:null);
    }
    public RelatedResult related(UUID id,Context context) {
        Head head=requireHead(id,context);
        var links=repository.relations(head.revisionId());
        var rows=repository.related(context,links.stream().map(Relation::targetId).distinct().toList());
        Set<UUID> visible=new HashSet<>();rows.forEach(h->visible.add(h.id()));
        return new RelatedResult(hits(rows,context,"Relationships declared by a person or agent."),links.stream().filter(r->visible.contains(r.targetId())).toList(),List.of("Only explicitly recorded one-hop relationships are returned."));
    }
    public SaveResult restore(UUID id,int historicalRevision,int expectedRevision,String key,String reason) {
        var row=repository.detail(id,historicalRevision);
        if(row==null)fail("NOT_FOUND","Historical revision not found.");
        var context=new Context(row.scope(),null);
        Detail d=detail(id,historicalRevision,context);
        SaveRequest request=new SaveRequest(key,id,expectedRevision,d.scope(),d.type(),d.authority(),d.title(),d.body(),
                new Verification(VerificationStatus.HISTORICAL,d.verification().claims(),d.verification().checks()),d.sources(),d.symbols(),d.relations(),reason,Lifecycle.ACTIVE,d.standard());
        return save(request,true,d.revisionId());
    }
    private Head requireHead(UUID id,Context context) {
        policy.context(context);
        if(id==null)fail("INVALID_ARGUMENT","knowledge_id is required.");
        Head h=repository.head(id);
        if(h==null || !policy.applies(h.scope(),context.scope()))fail("NOT_FOUND","This knowledge entry was not found in the requested scope.");
        return h;
    }
    private List<Relation> visibleRelations(Context context,List<Relation> links) {
        Set<UUID> visible=new HashSet<>();repository.related(context,links.stream().map(Relation::targetId).distinct().toList()).forEach(h->visible.add(h.id()));
        return links.stream().filter(r->visible.contains(r.targetId())).toList();
    }
    private List<SearchHit> hits(List<KnowledgeRepository.HitRow> rows,Context context,String reason) {
        var sources=repository.sourcesFor(rows.stream().map(KnowledgeRepository.HitRow::revisionId).toList());
        return rows.stream().map(h->{List<Source> evidence=sources.getOrDefault(h.revisionId(),List.of());return new SearchHit(h.id(),h.revisionNo(),h.title(),h.excerpt(),h.type(),h.authority(),h.status(),applicability(context,evidence),h.scope(),evidence,reason);}).toList();
    }
    private String applicability(Context context,List<Source> sources) {
        if(context.commitHash()==null)return "SCOPE_MATCH_SOURCE_NOT_CHECKED";
        return sources.stream().anyMatch(s->context.commitHash().equalsIgnoreCase(s.commitHash()==null?"":s.commitHash()))
                ?"SCOPE_AND_SOURCE_COMMIT_MATCH_NOT_RUNTIME_VERIFIED":"NEEDS_SOURCE_CHECK";
    }
}
