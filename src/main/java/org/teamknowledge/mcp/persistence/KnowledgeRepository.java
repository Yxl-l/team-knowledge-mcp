package org.teamknowledge.mcp.persistence;

import org.teamknowledge.mcp.model.KnowledgeModels.*;
import org.teamknowledge.mcp.service.JsonCodec;
import org.teamknowledge.mcp.service.TextIndex;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;

@Repository
public class KnowledgeRepository {
    private static final String KNOWLEDGE_TABLE = "public.kb_knowledge";
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonCodec json;
    private final TextIndex index;
    public KnowledgeRepository(NamedParameterJdbcTemplate jdbc, JsonCodec json, TextIndex index) { this.jdbc=jdbc; this.json=json; this.index=index; }
    public record Head(UUID id, Scope scope, KnowledgeType type, Authority authority, UUID revisionId, int revisionNo, String hash) { }
    public record HitRow(UUID id, UUID revisionId, int revisionNo, String title, String excerpt, KnowledgeType type,
                         Authority authority, VerificationStatus status, Scope scope) { }
    public record RevisionRow(UUID knowledgeId, UUID revisionId, int revisionNo, String title, String body,
                              KnowledgeType type, Authority authority, Scope scope, Verification verification,
                              Lifecycle lifecycle, boolean current, String hash, Standard standard,
                              String reason, String createdAt, WriteReview review) { }

    public void writeLock() {
        jdbc.getJdbcTemplate().execute("SET LOCAL lock_timeout = '3s'");
        // Short, database-wide transaction lock for this personal/small-team MVP.
        jdbc.getJdbcTemplate().execute("SELECT pg_advisory_xact_lock(1347504962)");
    }
    public SaveResult receipt(String actor, String key, String requestHash) {
        var rows=jdbc.query("SELECT request_hash,knowledge_id,revision_id,revision_no,outcome,content_hash FROM public.kb_write_receipt WHERE actor_key=:a AND idempotency_key=:k",
                Map.of("a",actor,"k",key),(rs,n) -> {
                    if (!rs.getString("request_hash").equals(requestHash)) throw new org.teamknowledge.mcp.service.KnowledgeException("IDEMPOTENCY_KEY_REUSED","An idempotency key must always refer to the same request.");
                    return new SaveResult(rs.getObject("knowledge_id",UUID.class),rs.getObject("revision_id",UUID.class),rs.getInt("revision_no"),rs.getString("outcome"),rs.getString("content_hash"),actor+":"+key,List.of("Source and test claims were supplied by the author."));
                });
        return rows.isEmpty()?null:rows.get(0);
    }
    public void receipt(String actor, String key, String requestHash, SaveResult value, WriteReview review) {
        var p=new MapSqlParameterSource().addValue("a",actor).addValue("k",key).addValue("h",requestHash)
                .addValue("id",value.knowledgeId()).addValue("r",value.revisionId()).addValue("n",value.revisionNo())
                .addValue("o",value.outcome()).addValue("c",value.contentHash()).addValue("review",review==null?null:json.write(review));
        jdbc.update("INSERT INTO public.kb_write_receipt(actor_key,idempotency_key,request_hash,knowledge_id,revision_id,revision_no,outcome,content_hash,write_review) VALUES (:a,:k,:h,:id,:r,:n,:o,:c,CAST(:review AS jsonb))",p);
    }
    public Head head(UUID id) {
        var rows=jdbc.query("SELECT * FROM public.kb_knowledge WHERE id=:id",Map.of("id",id),(rs,n)->head(rs));
        return rows.isEmpty()?null:rows.get(0);
    }
    public Set<UUID> existingIds(List<UUID> ids) {
        if(ids.isEmpty())return Set.of();
        return new HashSet<>(jdbc.queryForList("SELECT id FROM public.kb_knowledge WHERE id IN (:ids)",Map.of("ids",ids),UUID.class));
    }
    public Head duplicate(String scopeKey, KnowledgeType type, String hash) {
        var rows=jdbc.query("SELECT * FROM public.kb_knowledge WHERE scope_key=:s AND knowledge_type=:t AND current_content_hash=:h AND current_active",Map.of("s",scopeKey,"t",type.name(),"h",hash),(rs,n)->head(rs));
        return rows.isEmpty()?null:rows.get(0);
    }
    public UUID standardOwner(String scopeKey, String code) {
        var rows=jdbc.query("SELECT k.id FROM public.kb_knowledge k JOIN public.kb_revision r ON r.id=k.current_revision_id WHERE k.scope_key=:s AND r.standard_code=:c",Map.of("s",scopeKey,"c",code),(rs,n)->rs.getObject(1,UUID.class));
        return rows.isEmpty()?null:rows.get(0);
    }
    public void insertKnowledge(UUID id, UUID revision, SaveRequest r, String scopeKey, String hash) {
        var p=rawScopeParams(r.scope()).addValue("id",id).addValue("rid",revision).addValue("key",scopeKey)
                .addValue("type",r.type().name()).addValue("authority",r.authority().name()).addValue("hash",hash).addValue("active",r.lifecycle()==Lifecycle.ACTIVE);
        jdbc.update("""
            INSERT INTO public.kb_knowledge(id,system_code,module_name,customer_code,product_version,git_branch,db_vendor,
            scope_key,knowledge_type,authority,current_revision_id,current_revision_no,current_content_hash,current_active)
            VALUES (:id,:system,:module,:customer,:version,:branch,:vendor,:key,:type,:authority,:rid,1,:hash,:active)
            """,p);
    }
    public void revision(UUID id, UUID revision, int number, SaveRequest r, String hash, String actor, UUID restoredFrom) {
        Standard st=r.standard();
        var p=new MapSqlParameterSource().addValue("id",revision).addValue("kid",id).addValue("n",number)
                .addValue("title",r.title()).addValue("body",r.body()).addValue("hash",hash)
                .addValue("status",r.verification().status().name()).addValue("evidence",json.write(r.verification()))
                .addValue("life",r.lifecycle().name()).addValue("reason",r.changeReason()).addValue("actor",actor).addValue("restored",restoredFrom)
                .addValue("review",r.review()==null?null:json.write(r.review()))
                .addValue("code",st==null?null:st.code()).addValue("version",st==null?null:st.version()).addValue("operation",st==null?null:st.operation())
                .addValue("from",st==null?null:LocalDate.parse(st.effectiveFrom())).addValue("to",st==null || st.effectiveTo()==null?null:LocalDate.parse(st.effectiveTo()));
        jdbc.update("""
            INSERT INTO public.kb_revision(id,knowledge_id,revision_no,title,body,content_hash,verification_status,
            verification_evidence,lifecycle,standard_code,standard_version,operation,effective_from,effective_to,change_reason,actor_id,restored_from_revision_id,write_review)
            VALUES (:id,:kid,:n,:title,:body,:hash,:status,CAST(:evidence AS jsonb),:life,:code,:version,:operation,:from,:to,:reason,:actor,:restored,CAST(:review AS jsonb))
            """,p);
        jdbc.update("UPDATE public.kb_knowledge SET current_revision_id=:rid,current_revision_no=:n,current_content_hash=:h,current_active=:active,updated_at=now() WHERE id=:id",
                Map.of("rid",revision,"n",number,"h",hash,"active",r.lifecycle()==Lifecycle.ACTIVE,"id",id));
    }
    public void sources(UUID revision, List<Source> sources) {
        var rows=new MapSqlParameterSource[sources.size()];
        for(int i=0;i<sources.size();i++) { Source s=sources.get(i); rows[i]=new MapSqlParameterSource()
                .addValue("id",UUID.randomUUID()).addValue("r",revision).addValue("n",i).addValue("repo",s.repoId()).addValue("path",s.sourcePath())
                .addValue("start",s.sourceLine()).addValue("end",s.sourceEndLine()).addValue("kind",s.revisionKind().name())
                .addValue("commit",s.commitHash()).addValue("base",s.baseCommit()).addValue("hash",s.contentHash()); }
        jdbc.batchUpdate("INSERT INTO public.kb_source_ref(id,revision_id,ordinal,repo_id,source_path,source_line,source_end_line,revision_kind,commit_hash,base_commit,content_hash) VALUES (:id,:r,:n,:repo,:path,:start,:end,:kind,:commit,:base,:hash)",rows);
    }
    public void chunks(UUID revision, String title, String body, List<String> symbols, TextIndex index) {
        List<String> chunks=index.chunks(body);
        if(chunks.isEmpty())chunks=List.of(title);
        var rows=new MapSqlParameterSource[chunks.size()];
        List<String> allSymbols=index.symbols(title,body,symbols);
        String normalizedSymbols=json.write(allSymbols.stream().map(s->s.toLowerCase(Locale.ROOT)).distinct().toList());
        for(int i=0;i<chunks.size();i++) rows[i]=new MapSqlParameterSource().addValue("id",UUID.randomUUID()).addValue("r",revision).addValue("n",i)
                .addValue("body",chunks.get(i)).addValue("symbols",normalizedSymbols).addValue("title",index.lexemes(title))
                .addValue("lexemes",index.lexemes(chunks.get(i))).addValue("v",TextIndex.VERSION);
        jdbc.batchUpdate("INSERT INTO public.kb_chunk(id,revision_id,ordinal,body,symbols,search_vector,tokenizer_version) VALUES (:id,:r,:n,:body,ARRAY(SELECT jsonb_array_elements_text(CAST(:symbols AS jsonb))),setweight(to_tsvector('simple',:title),'A') || setweight(to_tsvector('simple',:lexemes),'B'),:v)",rows);
    }
    public String databaseName() { return jdbc.getJdbcTemplate().queryForObject("SELECT current_database()",String.class); }
    public List<UUID> knowledgeIdsAfter(UUID after) {
        return jdbc.queryForList("SELECT id FROM public.kb_knowledge WHERE (CAST(:after AS uuid) IS NULL OR id>CAST(:after AS uuid)) ORDER BY id LIMIT 100",
                new MapSqlParameterSource("after",after==null?null:after.toString()),UUID.class);
    }
    public void deleteChunks(UUID revision) { jdbc.update("DELETE FROM public.kb_chunk WHERE revision_id=:id",Map.of("id",revision)); }
    public void relations(UUID revision, List<Relation> relations) {
        if(relations.isEmpty())return;
        var rows=relations.stream().map(r->new MapSqlParameterSource().addValue("id",UUID.randomUUID()).addValue("r",revision).addValue("t",r.type().name()).addValue("target",r.targetId())).toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("INSERT INTO public.kb_relation(id,from_revision_id,relation_type,to_knowledge_id) VALUES (:id,:r,:t,:target)",rows);
    }
    public Map<UUID,List<Source>> sourcesFor(List<UUID> revisionIds) {
        Map<UUID,List<Source>> result=new HashMap<>();
        if(revisionIds.isEmpty())return result;
        jdbc.query("SELECT revision_id,repo_id,source_path,source_line,source_end_line,revision_kind,commit_hash,base_commit,content_hash FROM public.kb_source_ref WHERE revision_id IN (:ids) ORDER BY revision_id,ordinal",Map.of("ids",revisionIds),rs->{
            result.computeIfAbsent(rs.getObject("revision_id",UUID.class),x->new ArrayList<>()).add(new Source(rs.getString("repo_id"),rs.getString("source_path"),rs.getObject("source_line",Integer.class),rs.getObject("source_end_line",Integer.class),RevisionKind.valueOf(rs.getString("revision_kind")),rs.getString("commit_hash"),rs.getString("base_commit"),rs.getString("content_hash")));
        });
        return result;
    }
    public RevisionRow detail(UUID id, Integer number) {
        var p=new MapSqlParameterSource("id",id).addValue("number",number);
        var rows=jdbc.query("SELECT k.*,r.id AS rid,r.revision_no,r.title,r.body,r.verification_evidence,r.verification_status,r.lifecycle,r.content_hash,r.standard_code,r.standard_version,r.operation,r.effective_from,r.effective_to,r.change_reason,r.write_review,r.created_at AS revision_created FROM public.kb_knowledge k JOIN public.kb_revision r ON r.knowledge_id=k.id WHERE k.id=:id AND "+(number==null?"r.id=k.current_revision_id":"r.revision_no=:number"),p,(rs,n)->new RevisionRow(id,rs.getObject("rid",UUID.class),rs.getInt("revision_no"),rs.getString("title"),rs.getString("body"),KnowledgeType.valueOf(rs.getString("knowledge_type")),Authority.valueOf(rs.getString("authority")),scope(rs),json.read(rs.getString("verification_evidence"),Verification.class),Lifecycle.valueOf(rs.getString("lifecycle")),rs.getObject("rid",UUID.class).equals(rs.getObject("current_revision_id",UUID.class)),rs.getString("content_hash"),rs.getString("standard_code")==null?null:new Standard(rs.getString("standard_code"),rs.getString("standard_version"),rs.getString("operation"),rs.getString("effective_from"),rs.getString("effective_to")),rs.getString("change_reason"),rs.getString("revision_created"),review(rs)));
        return rows.isEmpty()?null:rows.get(0);
    }
    public List<String> symbols(UUID revision) {
        return jdbc.queryForList("SELECT DISTINCT unnest(symbols) AS symbol FROM public.kb_chunk WHERE revision_id=:r ORDER BY symbol",Map.of("r",revision),String.class);
    }
    public List<Relation> relations(UUID revision) {
        return jdbc.query("SELECT relation_type,to_knowledge_id FROM public.kb_relation WHERE from_revision_id=:r ORDER BY relation_type,to_knowledge_id",Map.of("r",revision),(rs,n)->new Relation(RelationType.valueOf(rs.getString(1)),rs.getObject(2,UUID.class)));
    }
    public List<HistoryItem> history(UUID id,int limit,int offset) {
        return jdbc.query("SELECT revision_no,title,verification_status,lifecycle,change_reason,created_at,write_review FROM public.kb_revision WHERE knowledge_id=:id ORDER BY revision_no DESC LIMIT :lim OFFSET :off",Map.of("id",id,"lim",limit,"off",offset),(rs,n)->new HistoryItem(rs.getInt(1),rs.getString(2),VerificationStatus.valueOf(rs.getString(3)),Lifecycle.valueOf(rs.getString(4)),rs.getString(5),rs.getString(6),review(rs)));
    }
    private static final String SCOPE_SQL="""
        (k.system_code=:system OR k.system_code='GLOBAL') AND (k.module_name=:module OR k.module_name='GLOBAL') AND
        (k.customer_code=:customer OR k.customer_code='GLOBAL') AND (k.product_version=:version OR k.product_version='GLOBAL') AND
        (k.git_branch=:branch OR k.git_branch='GLOBAL') AND (k.db_vendor=:vendor OR k.db_vendor='GLOBAL')
        """;
    public List<String> systems() {
        return jdbc.queryForList("SELECT DISTINCT system_code FROM public.kb_knowledge WHERE current_active ORDER BY system_code LIMIT 50",Map.of(),String.class);
    }
    /** Source catalog metadata only. This does not change or bypass normal search scope filters. */
    public List<ScopeCandidate> discoverScopes(String system,String lexemes,int limit,int offset) {
        var p=new MapSqlParameterSource().addValue("system",system).addValue("lex",lexemes).addValue("lim",limit).addValue("off",offset);
        return jdbc.query("""
            WITH matching_revisions AS MATERIALIZED (
              SELECT DISTINCT revision_id FROM public.kb_chunk
              WHERE :lex<>'' AND search_vector @@ to_tsquery('simple',:lex)
            )
            SELECT k.system_code,k.module_name,k.customer_code,k.product_version,k.git_branch AS git_branch,k.db_vendor,
              count(*) AS knowledge_count,
              count(*) FILTER(WHERE :lex='' OR matched.revision_id IS NOT NULL) AS matching_count,
              count(*) FILTER(WHERE r.verification_status='UNVERIFIED') AS unverified_count
            FROM %s k JOIN public.kb_revision r ON r.id=k.current_revision_id
            LEFT JOIN matching_revisions matched ON matched.revision_id=r.id
            WHERE k.current_active AND (:system='' OR lower(k.system_code)=:system)
            GROUP BY k.system_code,k.module_name,k.customer_code,k.product_version,k.git_branch,k.db_vendor
            ORDER BY matching_count DESC,knowledge_count DESC,k.system_code,k.module_name,k.customer_code,
              k.product_version,k.git_branch,k.db_vendor LIMIT :lim OFFSET :off
            """.formatted(KNOWLEDGE_TABLE),p,(rs,n)->new ScopeCandidate(scope(rs),rs.getLong("knowledge_count"),rs.getLong("matching_count"),rs.getLong("unverified_count")));
    }
    public List<HitRow> search(SearchRequest request,String lexemes,int limit,int offset,boolean relaxed) {
        var p=scopeParams(request.context().scope()).addValue("lex",lexemes).addValue("query",request.query().toLowerCase(Locale.ROOT))
                .addValue("anchors",relaxed?index.queryAnchors(request.query()):"")
                .addValue("terms",json.write(index.queryTokens(request.query())))
                .addValue("pattern","%"+request.query().toLowerCase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%")
                .addValue("lim",limit).addValue("off",offset);
        String types="";
        if(request.types()!=null && !request.types().isEmpty()) {types=" AND k.knowledge_type IN (:types)";p.addValue("types",request.types().stream().map(Enum::name).toList());}
        String ts=relaxed ? "to_tsquery('simple',:lex)" : "plainto_tsquery('simple',:lex)";
        return jdbc.query("""
            SELECT k.id,r.id AS rid,r.revision_no,r.title,matched.body AS excerpt,k.knowledge_type,k.authority,
              r.verification_status,k.system_code,k.module_name,k.customer_code,k.product_version,k.git_branch,k.db_vendor
            FROM %s k JOIN public.kb_revision r ON r.id=k.current_revision_id
            CROSS JOIN LATERAL (
              SELECT c.body,(SELECT count(*) FROM jsonb_array_elements_text(CAST(:terms AS jsonb)) AS term(value)
                WHERE strpos(lower(c.body),term.value)>0) AS coverage,ts_rank_cd(c.search_vector,
            """.formatted(KNOWLEDGE_TABLE)+ts+"""
              ) AS relevance,(:query=ANY(c.symbols)) AS exact_symbol
              FROM public.kb_chunk c WHERE c.revision_id=r.id AND
            """+"(c.search_vector @@ "+ts+" OR :query=ANY(c.symbols) OR lower(r.title) LIKE :pattern ESCAPE '\\') "+
                "AND (:anchors='' OR c.search_vector @@ plainto_tsquery('simple',:anchors)) "+
                "ORDER BY coverage DESC,relevance DESC,c.ordinal LIMIT 1) matched WHERE k.current_active AND "+
                SCOPE_SQL+types+(request.includeUnverified()?"":" AND r.verification_status<>'UNVERIFIED'")+
                " ORDER BY CASE WHEN lower(r.title)=:query THEN 0 WHEN lower(r.title) LIKE :pattern ESCAPE '\\' THEN 1 WHEN matched.exact_symbol THEN 2 ELSE 3 END,"+
                "CASE WHEN :anchors<>'' THEN (SELECT count(*) FROM jsonb_array_elements_text(CAST(:terms AS jsonb)) AS title_term(value) WHERE strpos(lower(r.title),title_term.value)>0) ELSE 0 END DESC,"+
                "matched.coverage DESC,matched.relevance DESC,k.updated_at DESC,k.id LIMIT :lim OFFSET :off",p,(rs,n)->{
                    HitRow h=hit(rs);return new HitRow(h.id(),h.revisionId(),h.revisionNo(),h.title(),index.excerpt(h.excerpt(),request.query()),h.type(),h.authority(),h.status(),h.scope());
                });
    }
    public List<HitRow> standards(Context context,String operation,LocalDate date,int limit) {
        var p=scopeParams(context.scope()).addValue("operation",operation).addValue("date",date).addValue("lim",limit);
        return jdbc.query("SELECT k.*,r.id AS rid,r.revision_no,r.title,left(r.body,300) AS excerpt,r.verification_status FROM "+KNOWLEDGE_TABLE+" k JOIN public.kb_revision r ON r.id=k.current_revision_id WHERE k.current_active AND k.knowledge_type='STANDARD' AND "+SCOPE_SQL+" AND r.operation=:operation AND r.effective_from<=:date AND (r.effective_to IS NULL OR r.effective_to>=:date) ORDER BY k.authority,k.id LIMIT :lim",p,(rs,n)->hit(rs));
    }
    public List<HitRow> related(Context context,List<UUID> ids) {
        if(ids.isEmpty())return List.of();
        return jdbc.query("SELECT k.*,r.id AS rid,r.revision_no,r.title,left(r.body,300) AS excerpt,r.verification_status FROM "+KNOWLEDGE_TABLE+" k JOIN public.kb_revision r ON r.id=k.current_revision_id WHERE k.current_active AND "+SCOPE_SQL+" AND k.id IN (:ids) ORDER BY k.id LIMIT 50",scopeParams(context.scope()).addValue("ids",ids),(rs,n)->hit(rs));
    }
    private WriteReview review(ResultSet rs)throws SQLException {String value=rs.getString("write_review");return value==null?null:json.read(value,WriteReview.class);}
    private Head head(ResultSet rs)throws SQLException {return new Head(rs.getObject("id",UUID.class),rawScope(rs),KnowledgeType.valueOf(rs.getString("knowledge_type")),Authority.valueOf(rs.getString("authority")),rs.getObject("current_revision_id",UUID.class),rs.getInt("current_revision_no"),rs.getString("current_content_hash"));}
    private HitRow hit(ResultSet rs)throws SQLException {return new HitRow(rs.getObject("id",UUID.class),rs.getObject("rid",UUID.class),rs.getInt("revision_no"),rs.getString("title"),rs.getString("excerpt"),KnowledgeType.valueOf(rs.getString("knowledge_type")),Authority.valueOf(rs.getString("authority")),VerificationStatus.valueOf(rs.getString("verification_status")),scope(rs));}
    private Scope rawScope(ResultSet rs)throws SQLException {return new Scope(rs.getString("system_code"),rs.getString("module_name"),rs.getString("customer_code"),rs.getString("product_version"),rs.getString("git_branch"),rs.getString("db_vendor"));}
    private Scope scope(ResultSet rs)throws SQLException {return rawScope(rs);}
    private MapSqlParameterSource scopeParams(Scope raw) {return rawScopeParams(raw);}
    private MapSqlParameterSource rawScopeParams(Scope s) {return new MapSqlParameterSource().addValue("system",s.systemCode()).addValue("module",s.moduleName()).addValue("customer",s.customerCode()).addValue("version",s.productVersion()).addValue("branch",s.gitBranch()).addValue("vendor",s.dbVendor());}
}
