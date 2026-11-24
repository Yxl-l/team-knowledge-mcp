package org.teamknowledge.mcp.service;

import org.teamknowledge.mcp.model.KnowledgeModels.*;
import org.teamknowledge.mcp.persistence.KnowledgeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeServiceTest {
    private final KnowledgeRepository repo=mock(KnowledgeRepository.class);
    private final TransactionTemplate tx=mock(TransactionTemplate.class);
    private KnowledgeService service;
    @BeforeEach @SuppressWarnings("unchecked") void setup(){
        when(tx.execute(any())).thenAnswer(i->((TransactionCallback<Object>)i.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));
        service=new KnowledgeService(repo,new KnowledgePolicy(),new TextIndex(),new JsonCodec(),tx);
    }
    @Test void retryReturnsCommittedReceiptWithoutAnotherRevision(){
        SaveResult receipt=new SaveResult(UUID.randomUUID(),UUID.randomUUID(),1,"CREATED","h","local-agent:task-1",List.of());
        when(repo.receipt(eq("local-agent"),eq("task-1"),anyString())).thenReturn(receipt);
        assertEquals(receipt,service.save(KnowledgePolicyTest.request()));
        verify(repo,never()).revision(any(),any(),anyInt(),any(),anyString(),anyString(),any());
    }
    @Test void missingReviewOrConfirmationNeverOpensAWriteTransaction(){
        SaveRequest r=KnowledgePolicyTest.request();
        for(WriteReview review:Arrays.asList(null,new WriteReview(ChangeKind.NEW,"复核摘要","实际检查",false))) {
            SaveRequest denied=new SaveRequest("unconfirmed",null,null,r.scope(),r.type(),r.authority(),r.title(),r.body(),r.verification(),r.evidence(),r.symbols(),r.relations(),r.changeReason(),r.lifecycle(),null,review);
            assertThrows(KnowledgeException.class,()->service.save(denied));
        }
        verifyNoInteractions(repo,tx);
    }
    @Test void confirmedProductCorrectionAppendsRevisionWithSameIdentity(){
        SaveRequest base=KnowledgePolicyTest.request();
        Scope scope=new Scope("CATALOG","checkout","GLOBAL","4.2","develop-4.2","GLOBAL");
        SaveRequest create=new SaveRequest("product-create",null,null,scope,base.type(),base.authority(),base.title(),base.body(),base.verification(),base.evidence(),base.symbols(),base.relations(),"已确认的产品知识",base.lifecycle(),null,base.review());
        SaveResult first=service.save(create);
        when(repo.head(first.knowledgeId())).thenReturn(new KnowledgeRepository.Head(first.knowledgeId(),scope,base.type(),base.authority(),first.revisionId(),1,first.contentHash()));
        SaveRequest correction=new SaveRequest("product-correct",first.knowledgeId(),1,scope,base.type(),base.authority(),base.title(),"已核实的修正正文",base.verification(),base.evidence(),base.symbols(),base.relations(),"用户确认修正旧结论",base.lifecycle(),null,KnowledgePolicyTest.review(ChangeKind.CORRECTION));
        SaveResult second=service.save(correction);
        assertEquals(first.knowledgeId(),second.knowledgeId());assertEquals(2,second.revisionNo());
        assertEquals("REVISED",second.outcome());assertNotEquals(first.revisionId(),second.revisionId());
        verify(repo,times(1)).insertKnowledge(any(),any(),any(),anyString(),anyString());
        verify(repo,times(2)).revision(eq(first.knowledgeId()),any(),anyInt(),any(),anyString(),anyString(),any());
        verify(repo,never()).deleteChunks(any());
    }
    @Test void staleExpectedRevisionCannotOverwrite(){
        SaveRequest r=KnowledgePolicyTest.request();UUID id=UUID.randomUUID();
        SaveRequest update=new SaveRequest("update",id,1,r.scope(),r.type(),r.authority(),r.title(),r.body(),r.verification(),r.evidence(),r.symbols(),r.relations(),r.changeReason(),r.lifecycle(),null,KnowledgePolicyTest.review(ChangeKind.CORRECTION));
        when(repo.head(id)).thenReturn(new KnowledgeRepository.Head(id,r.scope(),r.type(),r.authority(),UUID.randomUUID(),2,"old"));
        assertEquals("CONFLICT",assertThrows(KnowledgeException.class,()->service.save(update)).code());
        verify(repo,never()).revision(any(),any(),anyInt(),any(),anyString(),anyString(),any());
    }
    @Test void ordinaryUpdateCannotTargetStandardEvenWithForgedType(){
        SaveRequest r=KnowledgePolicyTest.request();UUID id=UUID.randomUUID();
        SaveRequest update=new SaveRequest("update",id,1,r.scope(),r.type(),r.authority(),r.title(),r.body(),r.verification(),r.evidence(),r.symbols(),r.relations(),r.changeReason(),r.lifecycle(),null,KnowledgePolicyTest.review(ChangeKind.CORRECTION));
        when(repo.head(id)).thenReturn(new KnowledgeRepository.Head(id,r.scope(),KnowledgeType.STANDARD,Authority.COMPANY_MANDATORY,UUID.randomUUID(),1,"h"));
        assertEquals("STANDARD_WRITE_FORBIDDEN",assertThrows(KnowledgeException.class,()->service.save(update)).code());
    }
    @Test void detailCannotReadAnotherCustomer(){
        UUID id=UUID.randomUUID();
        when(repo.head(id)).thenReturn(new KnowledgeRepository.Head(id,new Scope("CATALOG","module","TEAM-B","4.2","main","POSTGRESQL"),KnowledgeType.BUG,Authority.EXPERIENCE,UUID.randomUUID(),1,"h"));
        assertEquals("NOT_FOUND",assertThrows(KnowledgeException.class,()->service.history(id,new Context(KnowledgePolicyTest.scope(),null),10,0)).code());
        verify(repo,never()).history(any(),anyInt(),anyInt());
    }
    @Test void sameContentReturnsExistingIdentity(){
        UUID id=UUID.randomUUID();
        when(repo.duplicate(anyString(),eq(KnowledgeType.BUG),anyString())).thenReturn(new KnowledgeRepository.Head(id,KnowledgePolicyTest.scope(),KnowledgeType.BUG,Authority.EXPERIENCE,UUID.randomUUID(),3,"same"));
        SaveResult result=service.save(KnowledgePolicyTest.request());
        assertEquals(id,result.knowledgeId());assertEquals("UNCHANGED",result.outcome());
        verify(repo,never()).insertKnowledge(any(),any(),any(),anyString(),anyString());
    }
    @Test void searchFallbackIsScopedAndExplicit(){
        Context context=new Context(KnowledgePolicyTest.scope(),null);
        SearchRequest request=new SearchRequest(context,"中文测试",List.of(KnowledgeType.BUG),true,10,0);
        when(repo.search(any(),anyString(),anyInt(),anyInt(),anyBoolean())).thenReturn(List.of());
        assertTrue(service.search(request).items().isEmpty());
        verify(repo).search(eq(request),anyString(),eq(11),eq(0),eq(false));
        verify(repo).search(eq(request),anyString(),eq(11),eq(0),eq(true));
    }
    @Test void discoveryDoesNotRequireContextAndReturnsCanonicalCatalogScopes(){
        var candidate=new ScopeCandidate(KnowledgePolicyTest.scope(),10,3,9);
        when(repo.discoverScopes(eq("catalog"),anyString(),eq(2),eq(0))).thenReturn(List.of(candidate,candidate));
        when(repo.systems()).thenReturn(List.of("CATALOG"));
        var result=service.discoverScopes(new ScopeDiscoveryRequest(" catalog ","成功率",1,0));
        assertEquals(List.of(candidate),result.candidates());assertEquals(1,result.nextOffset());
        assertEquals("CATALOG",result.candidates().get(0).scope().systemCode());
        assertFalse(result.warnings().isEmpty());
        verify(repo,never()).search(any(),anyString(),anyInt(),anyInt(),anyBoolean());
    }
    @Test void discoveryBoundsRejectBeforeDatabaseQuery(){
        assertThrows(KnowledgeException.class,()->service.discoverScopes(new ScopeDiscoveryRequest(null,"x".repeat(201),10,0)));
        assertThrows(KnowledgeException.class,()->service.discoverScopes(new ScopeDiscoveryRequest(null,null,21,0)));
        verify(repo,never()).discoverScopes(anyString(),anyString(),anyInt(),anyInt());
    }
    @Test void emptySearchWarnsWithoutBroadeningActualTaskScope(){
        var request=new SearchRequest(new Context(KnowledgePolicyTest.scope(),null),"成功率",null,true,5,0);
        when(repo.search(any(),anyString(),anyInt(),anyInt(),anyBoolean())).thenReturn(List.of());
        var result=service.search(request);
        assertTrue(result.items().isEmpty());assertTrue(result.warnings().get(0).contains("discover_knowledge_scopes"));
        assertEquals(request.context(),result.contextUsed());
        verify(repo,never()).discoverScopes(anyString(),anyString(),anyInt(),anyInt());
    }
    @Test void questionUsesSameRelaxedModeAcrossPages(){
        SearchRequest request=new SearchRequest(new Context(KnowledgePolicyTest.scope(),null),"HTTP 求和还是平均",null,true,5,5);
        var row=new KnowledgeRepository.HitRow(UUID.randomUUID(),UUID.randomUUID(),1,"HTTP","SUM",KnowledgeType.BUSINESS_RULE,Authority.EXPERIENCE,VerificationStatus.UNVERIFIED,request.context().scope());
        when(repo.search(any(),anyString(),anyInt(),anyInt(),eq(false))).thenReturn(List.of());
        when(repo.search(eq(request),anyString(),eq(6),eq(5),eq(true))).thenReturn(List.of(row));
        var result=service.search(request);
        assertEquals(1,result.items().size());
        assertTrue(result.items().get(0).scoreReason().contains("OR"));
        verify(repo,never()).search(eq(request),anyString(),anyInt(),anyInt(),eq(false));
    }
    @Test void exhaustedStrictPagesDoNotSwitchToFallback(){
        SearchRequest request=new SearchRequest(new Context(KnowledgePolicyTest.scope(),null),"中文测试",null,true,5,5);
        var row=new KnowledgeRepository.HitRow(UUID.randomUUID(),UUID.randomUUID(),1,"中文测试","正文",KnowledgeType.BUG,Authority.EXPERIENCE,VerificationStatus.UNVERIFIED,request.context().scope());
        when(repo.search(eq(request),anyString(),eq(6),eq(5),eq(false))).thenReturn(List.of());
        when(repo.search(eq(request),anyString(),eq(1),eq(0),eq(false))).thenReturn(List.of(row));
        assertTrue(service.search(request).items().isEmpty());
        verify(repo,never()).search(any(),anyString(),anyInt(),anyInt(),eq(true));
    }
    @Test void reindexOnlyChangesDerivedChunks(){
        UUID id=UUID.randomUUID(),rid=UUID.randomUUID();
        var r=KnowledgePolicyTest.request();
        when(repo.detail(id,null)).thenReturn(new KnowledgeRepository.RevisionRow(id,rid,2,r.title(),r.body(),r.type(),r.authority(),r.scope(),r.verification(),Lifecycle.ACTIVE,true,"hash",null,"reason","date",null));
        when(repo.symbols(rid)).thenReturn(List.of("Declared"));
        assertEquals(rid.toString(),service.reindexCurrent(id));
        var order=inOrder(repo);order.verify(repo).writeLock();order.verify(repo).detail(id,null);
        order.verify(repo).symbols(rid);order.verify(repo).deleteChunks(rid);
        order.verify(repo).chunks(eq(rid),eq(r.title()),eq(r.body()),eq(List.of("Declared")),any());
        verify(repo,never()).revision(any(),any(),anyInt(),any(),anyString(),anyString(),any());
    }
}
