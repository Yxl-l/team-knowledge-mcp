package org.teamknowledge.mcp.service;

import org.teamknowledge.mcp.model.KnowledgeModels.*;
import org.teamknowledge.mcp.persistence.KnowledgeRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in only: creates schema and writes isolated UUID-scoped records, never deletes existing data. */
@EnabledIfEnvironmentVariable(named="TEAM_KB_TEST_DB_URL",matches="jdbc:postgresql://[^/]+/team_knowledge_test[a-zA-Z0-9_]*(\\?.*)?")
class PostgresIntegrationTest {
    private static KnowledgeService service;
    @BeforeAll static void connect(){
        DriverManagerDataSource dataSource=new DriverManagerDataSource(System.getenv("TEAM_KB_TEST_DB_URL"),System.getenv("TEAM_KB_TEST_DB_USER"),System.getenv("TEAM_KB_TEST_DB_PASSWORD"));
        Flyway.configure().dataSource(dataSource).schemas("public").defaultSchema("public").cleanDisabled(true).load().migrate();
        JsonCodec json=new JsonCodec();
        service=new KnowledgeService(new KnowledgeRepository(new NamedParameterJdbcTemplate(dataSource),json,new TextIndex()),new KnowledgePolicy(),new TextIndex(),json,new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }
    private SaveRequest request(String key,Scope scope){
        SaveRequest r=KnowledgePolicyTest.request();
        return new SaveRequest(key,null,null,scope,r.type(),r.authority(),r.title(),r.body(),r.verification(),r.evidence(),r.symbols(),r.relations(),r.changeReason(),r.lifecycle(),null,r.review());
    }
    private SaveRequest update(SaveRequest r,SaveResult result,String key,String title){
        return new SaveRequest(key,result.knowledgeId(),result.revisionNo(),r.scope(),r.type(),r.authority(),title,r.body(),r.verification(),r.evidence(),r.symbols(),r.relations(),"修改标题",r.lifecycle(),null,KnowledgePolicyTest.review(ChangeKind.CORRECTION));
    }
    @Test void persistenceSearchRevisionScopeAndRestore(){
        String unique=UUID.randomUUID().toString();Scope scope=new Scope("DEMO","module",unique,"1.0","main","POSTGRESQL");
        SaveRequest r=request(unique,scope);SaveResult first=service.save(r);
        assertEquals(r.review(),service.detail(first.knowledgeId(),null,new Context(scope,null)).review());
        assertEquals(r.review(),service.history(first.knowledgeId(),new Context(scope,null),10,0).items().get(0).review());
        assertEquals(first.knowledgeId(),service.save(r).knowledgeId());
        assertEquals("UNCHANGED",service.save(request(unique+"-same",scope)).outcome());
        Context c=new Context(scope,"b".repeat(40));
        var found=service.search(new SearchRequest(c,"列表导出",null,true,10,0));assertTrue(found.items().stream().anyMatch(x->x.knowledgeId().equals(first.knowledgeId())));
        var symbol=service.search(new SearchRequest(c,"ExampleService",null,true,10,0));assertTrue(symbol.items().stream().anyMatch(x->x.knowledgeId().equals(first.knowledgeId())));
        Context wrong=new Context(new Scope("DEMO","module","other-customer","1.0","main","POSTGRESQL"),null);
        assertThrows(KnowledgeException.class,()->service.detail(first.knowledgeId(),null,wrong));
        SaveResult second=service.save(update(r,first,unique+"-update","修订后的现象"));assertEquals(2,second.revisionNo());
        assertThrows(KnowledgeException.class,()->service.save(update(r,first,unique+"-stale","过期覆盖")));
        SaveResult restored=service.restore(first.knowledgeId(),1,2,unique+"-restore","恢复历史内容");assertEquals(3,restored.revisionNo());
        assertEquals(r.title(),service.detail(first.knowledgeId(),null,c).title());assertEquals(3,service.history(first.knowledgeId(),c,20,0).items().size());
    }
    @Test void concurrentDuplicateIsOneEffectiveRecord()throws Exception{
        String unique=UUID.randomUUID().toString();Scope scope=new Scope("DEMO","module",unique,"1.0","main","POSTGRESQL");
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try{
            var a=pool.submit(()->service.save(request(unique+"-a",scope)));
            var b=pool.submit(()->service.save(request(unique+"-b",scope)));
            assertEquals(a.get(20,TimeUnit.SECONDS).knowledgeId(),b.get(20,TimeUnit.SECONDS).knowledgeId());
        }finally{pool.shutdownNow();}
    }
}
