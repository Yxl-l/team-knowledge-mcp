package org.teamknowledge.mcp.service;

import org.teamknowledge.mcp.model.KnowledgeModels.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgePolicyTest {
    private final KnowledgePolicy policy=new KnowledgePolicy();
    private final TextIndex index=new TextIndex();
    static Scope scope(){return new Scope("CATALOG","module","TEAM-A","4.2","main","POSTGRESQL");}
    static WriteReview review(ChangeKind kind){return new WriteReview(kind,"脱敏测试：已复核本次内容和范围","测试夹具，非业务现场结论",true);}
    static SaveRequest request(){return new SaveRequest("task-1",null,null,scope(),KnowledgeType.BUG,Authority.EXPERIENCE,"列表导出异常","明确的业务现象与待确认根因",
            new Verification(VerificationStatus.UNVERIFIED,List.of(),List.of()),List.of(new Source("repo","src/Example.java",1,5,RevisionKind.COMMIT,"b".repeat(40),null,"a".repeat(64))),List.of("ExampleService"),List.of(),"新增经验",Lifecycle.ACTIVE,null,review(ChangeKind.NEW));}
    static SaveRequest changed(SaveRequest r,KnowledgeType type,Authority authority,Verification v,List<Source> sources){return new SaveRequest(r.idempotencyKey(),r.knowledgeId(),r.expectedRevision(),r.scope(),type,authority,r.title(),r.body(),v,sources,r.symbols(),r.relations(),r.changeReason(),r.lifecycle(),r.standard(),r.review());}
    @Test void customerAndBranchCannotBeBroadenedByQuery(){
        assertFalse(policy.applies(scope(),new Scope("CATALOG","module","TEAM-B","4.2","main","POSTGRESQL")));
        assertFalse(policy.applies(scope(),new Scope("CATALOG","module","TEAM-A","4.2","release","POSTGRESQL")));
        assertFalse(policy.applies(scope(),new Scope("GLOBAL","module","TEAM-A","4.2","main","POSTGRESQL")));
        assertTrue(policy.applies(new Scope("GLOBAL","GLOBAL","GLOBAL","GLOBAL","GLOBAL","GLOBAL"),scope()));
    }
    @Test void incompleteScopeNamesAllMissingFieldsAndSuggestsDiscovery(){
        var error=assertThrows(KnowledgeException.class,()->policy.context(new Context(new Scope("catalog",null,null,null,null,null),null)));
        assertEquals("CONTEXT_REQUIRED",error.code());
        for(String field:List.of("module_name","customer_code","product_version","git_branch","db_vendor","discover_knowledge_scopes"))assertTrue(error.getMessage().contains(field));
    }
    @Test void ordinaryWriteCannotPublishStandard(){
        SaveRequest r=request();
        KnowledgeException ex=assertThrows(KnowledgeException.class,()->policy.prepare(changed(r,KnowledgeType.STANDARD,Authority.COMPANY_MANDATORY,r.verification(),r.evidence()),false,index));
        assertEquals("STANDARD_WRITE_FORBIDDEN",ex.code());
    }
    @Test void claimedVerificationIsDowngraded(){
        SaveRequest r=request();
        var out=policy.prepare(changed(r,r.type(),r.authority(),new Verification(VerificationStatus.VERIFIED,List.of("主张"),List.of("自述测试")),r.evidence()),false,index);
        assertEquals(VerificationStatus.UNVERIFIED,out.verification().status());
    }
    @Test void worktreeMustHaveBaseCommit(){
        SaveRequest r=request();
        var bad=new Source("repo","src/X.java",null,null,RevisionKind.WORKTREE,null,null,"a".repeat(64));
        assertEquals("SOURCE_INVALID",assertThrows(KnowledgeException.class,()->policy.prepare(changed(r,r.type(),r.authority(),r.verification(),List.of(bad)),false,index)).code());
    }
    @Test void sourcesRejectPathEscape(){
        SaveRequest r=request();
        for(String path:List.of("../outside.md","E:/secret.txt","//server/share/a.md")){
            Source s=new Source("repo",path,null,null,RevisionKind.DOCUMENT,null,null,"a".repeat(64));
            assertThrows(KnowledgeException.class,()->policy.prepare(changed(r,r.type(),r.authority(),r.verification(),List.of(s)),false,index));
        }
    }
    @Test void secretDetectionDoesNotStoreMatchedText(){
        KnowledgeException ex=assertThrows(KnowledgeException.class,()->policy.rejectSecrets("password="+"test-only-credential"));
        assertEquals("SENSITIVE_CONTENT",ex.code());assertFalse(ex.getMessage().contains("test-only"));
        assertDoesNotThrow(()->policy.rejectSecrets("检查密码是否由环境变量提供"));
    }
    @Test void jsonRejectsUnknownFieldsAndNull(){
        JsonCodec json=new JsonCodec();
        assertThrows(KnowledgeException.class,()->json.read("{\"admin\":true}",SaveRequest.class));
        assertThrows(KnowledgeException.class,()->json.read("null",SaveRequest.class));
    }
    @Test void indexHandlesChineseSymbolsAndUnicodeBoundaries(){
        assertTrue(index.tokens("订单处理成功率").containsAll(List.of("订单","处理","成功","功率")));
        assertTrue(index.tokens("queryModelData order_table").containsAll(List.of("query","model","data","order_table","order","table")));
        String body="𠀀".repeat(1300);assertEquals(2,index.chunks(body).size());
        assertEquals(body.substring(0,2400),index.chunks(body).get(0));
        assertEquals(index.hash(index.normalize("x\r\n")),index.hash(index.normalize("x\n")));
    }
}
