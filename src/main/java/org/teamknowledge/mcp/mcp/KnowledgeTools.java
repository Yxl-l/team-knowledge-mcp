package org.teamknowledge.mcp.mcp;

import org.teamknowledge.mcp.model.KnowledgeModels.*;
import org.teamknowledge.mcp.service.*;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.function.Supplier;

/** MCP transport adapter. Business rules and SQL live in services/repositories. */
@Component
public class KnowledgeTools {
    private static final Logger LOG=LoggerFactory.getLogger(KnowledgeTools.class);
    private final KnowledgeService service;
    private final JsonCodec json;
    public KnowledgeTools(KnowledgeService service,JsonCodec json){this.service=service;this.json=json;}

    @Tool(name="discover_knowledge_scopes",description="List recorded scope combinations when the caller lacks context. Pass an empty object or use optional system_code, query, limit and offset. Scope counts do not establish applicability to the current task.")
    public String discoverScopes(String request_json){return result("discoverScopes",()->service.discoverScopes(json.read(request_json,ScopeDiscoveryRequest.class)));}

    @Tool(name="search_knowledge",description="Search by keywords or code identifiers within context.scope (system_code, module_name, customer_code, product_version, git_branch, db_vendor). Context can include commit_hash. Pass query and optional types, include_unverified, limit and offset. Discover applicable scopes first when context is unknown.")
    public String searchKnowledge(String request_json){return result("searchKnowledge",()->service.search(json.read(request_json,SearchRequest.class)));}

    @Tool(name="search_standards",description="List effective formal standards for a complete context and operation (java/api/sql/export/docs/git). Optional as_of is YYYY-MM-DD. Review company-mandatory rules first.")
    public String searchStandards(String request_json){return result("searchStandards",()->{StandardQuery r=json.read(request_json,StandardQuery.class);return service.standards(r.context(),r.operation(),r.asOf());});}

    @Tool(name="find_similar_bugs",description="Search recorded bugs by symptom keywords. Accepts the same parameters as search_knowledge and filters type to BUG.")
    public String similarBugs(String request_json){return result("similarBugs",()->{SearchRequest r=json.read(request_json,SearchRequest.class);return service.search(new SearchRequest(r.context(),r.query(),List.of(KnowledgeType.BUG),r.includeUnverified(),r.limit(),r.offset()));});}

    @Tool(name="get_system_context",description="Retrieve architecture, business rules and decisions for a complete context and query. Accepts the same parameters as search_knowledge. Set include_unverified=true to include unverified records.")
    public String systemContext(String request_json){return result("systemContext",()->{SearchRequest r=json.read(request_json,SearchRequest.class);return service.search(new SearchRequest(r.context(),r.query(),List.of(KnowledgeType.ARCHITECTURE,KnowledgeType.BUSINESS_RULE,KnowledgeType.DECISION),r.includeUnverified(),r.limit(),r.offset()));});}

    @Tool(name="find_related_code",description="Return recorded one-hop relationships and source references for knowledge_id and context.")
    public String relatedCode(String request_json){return result("relatedCode",()->{DetailQuery r=json.read(request_json,DetailQuery.class);return service.related(r.knowledgeId(),r.context());});}

    @Tool(name="get_knowledge_detail",description="Read the full current or historical revision for knowledge_id and context. Optional revision_no selects a specific revision. Check its verification, relevance and lifecycle before using it.")
    public String detail(String request_json){return result("detail",()->{DetailQuery r=json.read(request_json,DetailQuery.class);return service.detail(r.knowledgeId(),r.revisionNo(),r.context());});}

    @Tool(name="save_knowledge",description="Write ordinary knowledge only after the user confirms a reviewed proposal containing the exact text, scope and validation. Supply idempotency_key, scope, type, title, body, verification, evidence, change_reason and review (change_kind, summary, validation, user_confirmed). Use NEW for new entries. CORRECTION and SUPPLEMENT require knowledge_id and expected_revision. The server records a confirmation declaration and does not independently verify it.")
    public String save(String request_json){return result("save",()->service.save(json.read(request_json,SaveRequest.class)));}

    @Tool(name="get_knowledge_history",description="List revisions for knowledge_id and context, with optional limit and offset.")
    public String history(String request_json){return result("history",()->{HistoryQuery r=json.read(request_json,HistoryQuery.class);return service.history(r.knowledgeId(),r.context(),r.limit(),r.offset());});}

    private String result(String tool,Supplier<Object> work){
        String id=UUID.randomUUID().toString(), outcome="OK";
        long started=System.nanoTime();
        try{return json.write(Map.of("schema_version","1","request_id",id,"data",work.get(),"warnings",List.of("Check references against the relevant source and verification scope.")));}
        catch(KnowledgeException ex){outcome=ex.code();return error(id,ex.code(),ex.getMessage(),false);}
        catch(DataAccessException ex){
            var failure=DatabaseFailures.classify(ex);outcome=failure.code();
            LOG.warn("mcp_failure request_id={} tool={} code={} sql_state={} exception={}",id,tool,outcome,DatabaseFailures.sqlState(ex),ex.getClass().getSimpleName());
            return error(id,failure.code(),failure.message(),failure.retryable());
        }
        catch(RuntimeException ex){
            outcome="INTERNAL_ERROR";
            LOG.error("mcp_failure request_id={} tool={} code={} exception={}",id,tool,outcome,ex.getClass().getSimpleName());
            return error(id,outcome,"Internal error. Use request_id to locate the server log entry.",false);
        }
        finally{LOG.info("mcp_call request_id={} tool={} outcome={} elapsed_ms={}",id,tool,outcome,(System.nanoTime()-started)/1_000_000);}
    }
    private String error(String id,String code,String message,boolean retryable){return json.write(Map.of("schema_version","1","request_id",id,"is_error",true,"error",Map.of("code",code,"message",message,"retryable",retryable)));}
    public record StandardQuery(Context context,String operation,String asOf){}
    public record DetailQuery(UUID knowledgeId,Context context,Integer revisionNo){}
    public record HistoryQuery(UUID knowledgeId,Context context,Integer limit,Integer offset){}
}
