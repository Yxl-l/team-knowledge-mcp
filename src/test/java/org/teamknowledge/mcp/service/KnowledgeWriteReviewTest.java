package org.teamknowledge.mcp.service;

import org.teamknowledge.mcp.model.KnowledgeModels.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeWriteReviewTest {
    private final KnowledgePolicy policy=new KnowledgePolicy();
    private final TextIndex index=new TextIndex();
    private final Scope product=new Scope("CATALOG","checkout","GLOBAL","4.2","develop-4.2","GLOBAL");
    private SaveRequest request(Scope scope,UUID id,WriteReview review) {
        SaveRequest r=KnowledgePolicyTest.request();
        return new SaveRequest(r.idempotencyKey(),id,id==null?null:1,scope,r.type(),r.authority(),r.title(),r.body(),
                r.verification(),r.evidence(),r.symbols(),r.relations(),r.changeReason(),r.lifecycle(),null,review);
    }
    private WriteReview review(ChangeKind kind,boolean confirmed) {return new WriteReview(kind,"原结论与改动摘要","已对照测试证据；业务现场尚未验证",confirmed);}
    @Test void productScopeSupportsCreateCorrectionAndSupplement() {
        for(ChangeKind kind:ChangeKind.values()) {
            UUID id=kind==ChangeKind.NEW?null:UUID.randomUUID();
            assertEquals(product,policy.prepare(request(product,id,review(kind,true)),false,index).scope());
        }
    }
    @Test void reviewAndRealUserConfirmationAreSeparateRequiredDeclarations() {
        assertEquals("REVIEW_REQUIRED",assertThrows(KnowledgeException.class,()->policy.prepare(request(product,null,null),false,index)).code());
        assertEquals("CONFIRMATION_REQUIRED",assertThrows(KnowledgeException.class,()->policy.prepare(request(product,null,review(ChangeKind.NEW,false)),false,index)).code());
        for(WriteReview value:List.of(new WriteReview(ChangeKind.NEW,"","checked",true),new WriteReview(ChangeKind.NEW,"summary","",true)))
            assertEquals("INVALID_ARGUMENT",assertThrows(KnowledgeException.class,()->policy.prepare(request(product,null,value),false,index)).code());
    }
    @Test void correctionCannotBeSavedAsAnUnrelatedNewRecord() {
        assertEquals("INVALID_ARGUMENT",assertThrows(KnowledgeException.class,()->policy.prepare(request(product,null,review(ChangeKind.CORRECTION,true)),false,index)).code());
        assertEquals("INVALID_ARGUMENT",assertThrows(KnowledgeException.class,()->policy.prepare(request(product,UUID.randomUUID(),review(ChangeKind.NEW,true)),false,index)).code());
    }
    @Test void globalCustomerDoesNotMakeUnknownSystemVersionOrBranchAcceptable() {
        for(Scope scope:List.of(new Scope("GLOBAL","checkout","GLOBAL","4.2","develop-4.2","GLOBAL"),
                new Scope("CATALOG","checkout","GLOBAL","GLOBAL","develop-4.2","GLOBAL"),
                new Scope("CATALOG","checkout","GLOBAL","4.2","GLOBAL","GLOBAL")))
            assertEquals("SCOPE_MISMATCH",assertThrows(KnowledgeException.class,()->policy.prepare(request(scope,null,review(ChangeKind.NEW,true)),false,index)).code());
    }
    @Test void adminRequestWithoutReviewHasStableIdempotencyHash() throws Exception {
        String fixture;
        try(var input=getClass().getResourceAsStream("/legacy-experience.json")) {fixture=new String(Objects.requireNonNull(input).readAllBytes(),StandardCharsets.UTF_8);}
        JsonCodec json=new JsonCodec();
        SaveRequest old=policy.prepare(json.read(fixture,SaveRequest.class),true,index);
        assertFalse(json.write(old).contains("\"review\""));
        assertEquals("2f6f87e7673bdaaf51cc9521f5ea621d6aff9a7c659302892470582fbac75c8e",index.hash(json.write(old)+"|restored=null"));
        assertEquals("REVIEW_REQUIRED",assertThrows(KnowledgeException.class,()->policy.prepare(old,false,index)).code());
    }
}
