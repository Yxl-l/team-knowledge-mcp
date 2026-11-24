package org.teamknowledge.mcp.admin;

import org.teamknowledge.mcp.KnowledgeBaseApplication;
import org.teamknowledge.mcp.model.KnowledgeModels.*;
import org.teamknowledge.mcp.service.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Separate local command process: no admin HTTP endpoint, no MCP registration. */
public final class AdminCommand {
    private AdminCommand() { }
    public static void main(String[] args) {
        Map<String,String> options=new HashMap<>();
        for(String arg:args){
            if(!arg.startsWith("--") || !arg.contains("="))throw new IllegalArgumentException("Use --name=value arguments.");
            int equals=arg.indexOf('=');options.put(arg.substring(2,equals),arg.substring(equals+1));
        }
        if(!"true".equals(options.get("apply")))throw new IllegalArgumentException("Review the input and use --apply=true for administrative writes.");
        try(var context=new SpringApplicationBuilder(KnowledgeBaseApplication.class).web(WebApplicationType.NONE)
                .properties(Map.of("spring.ai.mcp.server.enabled","false","spring.flyway.enabled","false"))
                .run("--spring.ai.mcp.server.enabled=false","--spring.flyway.enabled=false")){
            KnowledgeService service=context.getBean(KnowledgeService.class);
            JsonCodec json=context.getBean(JsonCodec.class);
            Object result;
            if("restore".equals(options.get("action"))) {
                result=service.restore(UUID.fromString(required(options,"id")),Integer.parseInt(required(options,"revision")),
                        Integer.parseInt(required(options,"expected-revision")),required(options,"key"),required(options,"reason"));
            }else if("import".equals(options.get("action"))){
                Path root=Path.of(required(options,"root")).toRealPath();
                if(options.containsKey("batch")){
                    if(options.containsKey("file") || options.containsKey("markdown"))
                        throw new IllegalArgumentException("Use batch separately from file and markdown.");
                    String[] files=json.read(read(bounded(root,options.get("batch"))),String[].class);
                    if(files.length==0)throw new IllegalArgumentException("The batch must contain at least one file.");
                    // Decode every bounded input before the first write; each administer remains transactional.
                    List<SaveRequest> requests=new ArrayList<>();
                    for(String file:files)requests.add(json.read(read(bounded(root,file)),SaveRequest.class));
                    for(SaveRequest request:requests)System.out.println(json.write(service.administer(request)));
                    return;
                }
                Path file=bounded(root,required(options,"file"));
                SaveRequest request=json.read(read(file),SaveRequest.class);
                if(options.containsKey("markdown")){
                    Path markdown=bounded(root,options.get("markdown"));String body=read(markdown);
                    Source source=new Source("manual-import",root.relativize(markdown).toString().replace('\\','/'),null,null,
                            RevisionKind.DOCUMENT,null,null,context.getBean(TextIndex.class).hash(body));
                    request=new SaveRequest(request.idempotencyKey(),request.knowledgeId(),request.expectedRevision(),request.scope(),request.type(),
                            request.authority(),request.title(),body,request.verification(),List.of(source),request.symbols(),request.relations(),request.changeReason(),request.lifecycle(),request.standard());
                }
                result=service.administer(request);
            }else throw new IllegalArgumentException("Supported actions are import and restore.");
            System.out.println(json.write(result));
        }catch(Exception e){
            // Do not print nested JDBC exceptions or credentials.
            System.err.println(e instanceof KnowledgeException k?k.code()+": "+k.getMessage():"ADMIN_FAILED: Check the input, migration status and database connection.");
            System.exit(1);
        }
    }
    private static String required(Map<String,String> values,String key){String v=values.get(key);if(v==null || v.isBlank())throw new IllegalArgumentException("Missing argument: "+key);return v;}
    private static Path bounded(Path root,String name)throws Exception{
        Path raw=Path.of(name);Path real=(raw.isAbsolute()?raw:root.resolve(raw)).toRealPath();
        if(!real.startsWith(root) || !Files.isRegularFile(real))throw new IllegalArgumentException("The file is outside the selected import directory.");
        return real;
    }
    private static String read(Path path)throws Exception{
        try(var stream=Files.newInputStream(path)){
            byte[] bytes=stream.readNBytes(65537);if(bytes.length>65536)throw new IllegalArgumentException("The input file exceeds 64 KiB.");
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        }
    }
}
