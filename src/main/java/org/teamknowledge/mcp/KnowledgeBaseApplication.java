package org.teamknowledge.mcp;

import org.teamknowledge.mcp.admin.AdminCommand;
import org.teamknowledge.mcp.admin.ReindexCommand;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class KnowledgeBaseApplication {
    public static void main(String[] args) {
        if(args.length > 0 && "admin".equals(args[0])) {
            AdminCommand.main(Arrays.copyOfRange(args, 1, args.length));
        } else if(args.length > 0 && "reindex".equals(args[0])) {
            ReindexCommand.main(Arrays.copyOfRange(args, 1, args.length));
        } else {
            SpringApplication.run(KnowledgeBaseApplication.class, args);
        }
    }
}
