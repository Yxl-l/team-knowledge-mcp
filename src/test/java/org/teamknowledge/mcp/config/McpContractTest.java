package org.teamknowledge.mcp.config;

import org.teamknowledge.mcp.mcp.KnowledgeTools;
import org.teamknowledge.mcp.service.*;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpContractTest {
    @Test void databaseErrorsHaveRequestIdsAndSafeRetryAdvice(){
        var service=mock(KnowledgeService.class);
        when(service.discoverScopes(any())).thenThrow(new org.springframework.dao.QueryTimeoutException("password=not-for-output"));
        var json=new JsonCodec();var tools=new KnowledgeTools(service,json);
        var result=json.read(tools.discoverScopes("{}"),Map.class);
        assertNotNull(result.get("request_id"));
        assertEquals("DB_TIMEOUT",((Map<?,?>)result.get("error")).get("code"));
        assertFalse(json.write(result).contains("not-for-output"));
    }
    @Test void malformedCallsHaveMcpErrorAndReadWriteAnnotations(){
        var json=new JsonCodec();
        var tools=new McpConfiguration().knowledgeToolSpecifications(new KnowledgeTools(mock(KnowledgeService.class),json),json,true);
        assertEquals(9,tools.size());
        assertTrue(tools.stream().anyMatch(t->t.tool().name().equals("discover_knowledge_scopes")));
        for(var tool:tools){
            assertEquals(!tool.tool().name().equals("save_knowledge"),tool.tool().annotations().readOnlyHint());
            var out=tool.callHandler().apply(null,new McpSchema.CallToolRequest(tool.tool().name(),Map.of("admin",true)));
            assertTrue(out.isError());assertNotNull(out.structuredContent());
        }
        var save=tools.stream().filter(t->t.tool().name().equals("save_knowledge")).findFirst().orElseThrow();
        var out=save.callHandler().apply(null,new McpSchema.CallToolRequest("save_knowledge",Map.of("request_json","{\"admin\":true}")));
        assertTrue(out.isError());
    }
}
