package org.teamknowledge.mcp.config;

import org.teamknowledge.mcp.mcp.KnowledgeTools;
import org.teamknowledge.mcp.service.JsonCodec;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Configuration
public class McpConfiguration {
    @Bean
    List<McpServerFeatures.SyncToolSpecification> knowledgeToolSpecifications(KnowledgeTools tools, JsonCodec json,
            @Value("${team.kb.write-enabled:false}") boolean writeEnabled){
        var callbacks=MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks();
        return Arrays.stream(callbacks)
                .filter(callback -> writeEnabled || !callback.getToolDefinition().name().equals("save_knowledge"))
                .map(callback->{
            var definition=callback.getToolDefinition();
            boolean readOnly=!definition.name().equals("save_knowledge");
            var schema=Map.<String,Object>of("type","object","properties",Map.of("request_json",Map.of("type","string","maxLength",262144)),
                    "required",List.of("request_json"),"additionalProperties",false);
            var tool=McpSchema.Tool.builder().name(definition.name()).description(definition.description()).inputSchema(schema)
                    .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(readOnly).destructiveHint(false).idempotentHint(true).openWorldHint(false).build()).build();
            return new McpServerFeatures.SyncToolSpecification(tool,(exchange,request)->{
                var arguments=request.arguments();
                String value;
                if(arguments==null || arguments.size()!=1 || !(arguments.get("request_json") instanceof String)){
                    value=json.write(Map.of("is_error",true,"error",Map.of("code","INVALID_ARGUMENT","message","Expected a single string argument named request_json.","retryable",false)));
                }else value=callback.call(json.write(arguments));
                if(value.getBytes(StandardCharsets.UTF_8).length>131072)
                    value=json.write(Map.of("is_error",true,"error",Map.of("code","RESULT_TOO_LARGE","message","The response exceeds 128 KiB. Narrow the scope or reduce the page size.","retryable",false)));
                Map<?,?> result=json.read(value,Map.class);
                return McpSchema.CallToolResult.builder().addTextContent(value).structuredContent(result).isError(Boolean.TRUE.equals(result.get("is_error"))).build();
            });
        }).toList();
    }
    @Bean
    TransactionTemplate knowledgeTransaction(PlatformTransactionManager manager){
        TransactionTemplate template=new TransactionTemplate(manager);
        template.setTimeout(10);
        return template;
    }
}
