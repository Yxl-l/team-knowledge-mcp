package org.teamknowledge.mcp.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Bounded local endpoint; no unrestricted browser Origin or Host access. */
@Component
@Order(0)
public class LoopbackGuard implements Filter {
    private final String token;
    private static final Set<String> LOOPBACK=Set.of("127.0.0.1","localhost","::1","[::1]");
    private final Set<String> allowedHosts;
    public LoopbackGuard(String token){this(token,"127.0.0.1","");}
    @Autowired
    public LoopbackGuard(@Value("${team.kb.token:}") String token,
                         @Value("${server.address:127.0.0.1}") String address,
                         @Value("${team.kb.allowed-hosts:}") String hosts){
        this.token=token;
        Set<String> allowed=new HashSet<>(LOOPBACK);
        for(String entry:hosts.split(",")) {
            String host=entry.strip().toLowerCase(Locale.ROOT);
            if(host.isEmpty())continue;
            if(!host.matches("[a-z0-9.-]+|\\[[0-9a-f:]+\\]|[0-9a-f:]+") || host.equals("0.0.0.0") || host.equals("::"))
                throw new IllegalArgumentException("TEAM_KB_ALLOWED_HOSTS accepts explicit hostnames or IP addresses only.");
            allowed.add(host);
        }
        if(!LOOPBACK.contains(address.toLowerCase(Locale.ROOT))) {
            if(allowed.stream().allMatch(LOOPBACK::contains))
                throw new IllegalArgumentException("A non-loopback listener requires explicit TEAM_KB_ALLOWED_HOSTS.");
            if(!token.matches("[A-Za-z0-9._~+/-]{32,256}"))
                throw new IllegalArgumentException("A non-loopback listener requires a strong TEAM_KB_MCP_TOKEN.");
        }
        this.allowedHosts=Set.copyOf(allowed);
    }
    @Override
    public void doFilter(ServletRequest input,ServletResponse output,FilterChain chain)throws IOException,ServletException {
        HttpServletRequest request=(HttpServletRequest)input;
        HttpServletResponse response=(HttpServletResponse)output;
        String host=request.getServerName().toLowerCase(Locale.ROOT);
        if(!allowedHosts.contains(host)){response.sendError(403);return;}
        if(token.isEmpty() && !Set.of("127.0.0.1","::1","0:0:0:0:0:0:1").contains(request.getRemoteAddr())){response.sendError(403);return;}
        String path=request.getRequestURI().substring(request.getContextPath().length());
        // Fail closed for encoded paths and servlet path parameters as well as /mcp itself.
        boolean protectedEndpoint=!Set.of("/actuator/health","/actuator/health/liveness","/actuator/health/readiness").contains(path);
        String origin=request.getHeader("Origin");
        if(origin!=null){
            try {
                URI uri=URI.create(origin);
                int port=uri.getPort()<0?("https".equals(uri.getScheme())?443:80):uri.getPort();
                if(!Set.of("http","https").contains(uri.getScheme()) || !host.equalsIgnoreCase(uri.getHost()) || !request.getScheme().equals(uri.getScheme()) || port!=request.getServerPort()
                        || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null || (uri.getRawPath()!=null && !uri.getRawPath().isEmpty())) {response.sendError(403);return;}
            }catch(IllegalArgumentException ex){response.sendError(403);return;}
        }
        if(protectedEndpoint && !token.isEmpty()){
            String supplied=request.getHeader("Authorization");
            if(supplied==null || !MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8))){response.sendError(401);return;}
        }
        if(protectedEndpoint && "POST".equals(request.getMethod())){
            if(request.getContentLengthLong()>262144){response.sendError(413);return;}
            byte[] bytes=request.getInputStream().readNBytes(262145);
            if(bytes.length>262144){response.sendError(413);return;}
            HttpServletRequest wrapped=new HttpServletRequestWrapper(request){
                @Override public ServletInputStream getInputStream(){
                    ByteArrayInputStream stream=new ByteArrayInputStream(bytes);
                    return new ServletInputStream(){
                        public int read(){return stream.read();}
                        public boolean isFinished(){return stream.available()==0;}
                        public boolean isReady(){return true;}
                        public void setReadListener(ReadListener listener){throw new UnsupportedOperationException("Synchronous bounded request");}
                    };
                }
                @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
            };
            chain.doFilter(wrapped,response);return;
        }
        chain.doFilter(request,response);
    }
}
