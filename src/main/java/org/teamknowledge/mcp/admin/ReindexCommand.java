package org.teamknowledge.mcp.admin;

import org.teamknowledge.mcp.KnowledgeBaseApplication;
import org.teamknowledge.mcp.persistence.KnowledgeRepository;
import org.teamknowledge.mcp.service.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import java.util.*;

/** Explicit, restartable maintenance of derived indexes; never a MCP write tool. */
public final class ReindexCommand {
    private ReindexCommand() { }
    public static void main(String[] args) {
        if(args.length!=1 || !"--apply=true".equals(args[0]))throw new IllegalArgumentException("Use --apply=true to rebuild indexes.");
        try(var context=new SpringApplicationBuilder(KnowledgeBaseApplication.class).web(WebApplicationType.NONE)
                .run("--spring.ai.mcp.server.enabled=false","--spring.flyway.enabled=false")) {
            var repository=context.getBean(KnowledgeRepository.class);
            var service=context.getBean(KnowledgeService.class);
            int count=0;UUID after=null;
            while(true) {
                List<UUID> ids=repository.knowledgeIdsAfter(after);
                if(ids.isEmpty())break;
                for(UUID id:ids) { service.reindexCurrent(id);count++; }
                after=ids.get(ids.size()-1);
                System.out.println("Reindexed current revisions: "+count);
            }
            System.out.println(context.getBean(JsonCodec.class).write(Map.of("passed",true,"reindexed",count,"tokenizer_version",TextIndex.VERSION,"database",repository.databaseName(),"schema","public","knowledge_revisions_changed",false)));
        } catch(Exception e) {
            System.err.println("REINDEX_FAILED: The index rebuild failed. You can retry safely.");
            System.exit(1);
        }
    }
}
