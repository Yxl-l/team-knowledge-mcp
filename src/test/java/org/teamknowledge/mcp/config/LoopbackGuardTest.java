package org.teamknowledge.mcp.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;

class LoopbackGuardTest {
    private static final String LAN_TOKEN="test-only-012345678901234567890123456789";
    @Test void lanRequiresExplicitHost() {
        assertThrows(IllegalArgumentException.class,()->new LoopbackGuard("","0.0.0.0","192.0.2.1"));
        assertDoesNotThrow(()->new LoopbackGuard(LAN_TOKEN,"0.0.0.0","192.0.2.1"));
        assertThrows(IllegalArgumentException.class,()->new LoopbackGuard(LAN_TOKEN,"0.0.0.0",""));
        assertThrows(IllegalArgumentException.class,()->new LoopbackGuard(LAN_TOKEN,"0.0.0.0","*"));
        assertThrows(IllegalArgumentException.class,()->new LoopbackGuard(LAN_TOKEN,"0.0.0.0","http://192.0.2.1"));
    }
    @Test void lanHostTokenAndOriginAreAllChecked()throws Exception {
        LoopbackGuard guard=new LoopbackGuard(LAN_TOKEN,"0.0.0.0","192.0.2.1");
        for(String method:new String[]{"GET","POST","DELETE"}) {
            var r=request();r.setMethod(method);r.setServerName("192.0.2.1");r.setRemoteAddr("192.0.2.2");
            var denied=new MockHttpServletResponse();guard.doFilter(r,denied,new MockFilterChain());assertEquals(401,denied.getStatus());
            r.addHeader("Authorization","Bearer "+LAN_TOKEN);
            var chain=new MockFilterChain();guard.doFilter(r,new MockHttpServletResponse(),chain);assertNotNull(chain.getRequest());
            r.addHeader("Origin","http://evil.example:18765");
            denied=new MockHttpServletResponse();guard.doFilter(r,denied,new MockFilterChain());assertEquals(403,denied.getStatus());
        }
        var r=request();r.setServerName("192.0.2.9");r.addHeader("Authorization","Bearer "+LAN_TOKEN);
        var denied=new MockHttpServletResponse();guard.doFilter(r,denied,new MockFilterChain());assertEquals(403,denied.getStatus());
    }
    @Test void remotePeerCannotUseAnonymousLoopbackHost()throws Exception {
        var r=request();r.setRemoteAddr("192.0.2.2");
        var out=new MockHttpServletResponse();new LoopbackGuard("").doFilter(r,out,new MockFilterChain());assertEquals(403,out.getStatus());
    }
    @Test void alternateMcpPathsCannotBypassAuthentication()throws Exception {
        var guard=new LoopbackGuard(LAN_TOKEN,"0.0.0.0","192.0.2.1");
        for(String path:new String[]{"/mcp/","/mcp;anything","/%6dcp","/mcp/other"}) {
            var r=request();r.setRequestURI(path);r.setServerName("192.0.2.1");
            var out=new MockHttpServletResponse();guard.doFilter(r,out,new MockFilterChain());assertEquals(401,out.getStatus(),path);
        }
    }
    private MockHttpServletRequest request(){MockHttpServletRequest r=new MockHttpServletRequest("POST","/mcp");r.setServerName("127.0.0.1");r.setServerPort(18765);r.setContent("{}".getBytes());return r;}
    @Test void foreignOriginAndHostAreRejected()throws Exception{
        LoopbackGuard guard=new LoopbackGuard("");
        MockHttpServletRequest r=request();r.addHeader("Origin","https://untrusted.example");
        MockHttpServletResponse out=new MockHttpServletResponse();guard.doFilter(r,out,new MockFilterChain());assertEquals(403,out.getStatus());
        r=request();r.setServerName("attacker.example");out=new MockHttpServletResponse();guard.doFilter(r,out,new MockFilterChain());assertEquals(403,out.getStatus());
    }
    @Test void configuredTokenIsRequired()throws Exception{
        LoopbackGuard guard=new LoopbackGuard("test-token");MockHttpServletRequest r=request();
        MockHttpServletResponse out=new MockHttpServletResponse();guard.doFilter(r,out,new MockFilterChain());assertEquals(401,out.getStatus());
        r=request();r.addHeader("Authorization","Bearer test-token");out=new MockHttpServletResponse();MockFilterChain chain=new MockFilterChain();guard.doFilter(r,out,chain);assertNotNull(chain.getRequest());
    }
    @Test void oversizedBodyIsRejectedBeforeMcp()throws Exception{
        MockHttpServletRequest r=request();r.setContent(new byte[262145]);MockHttpServletResponse out=new MockHttpServletResponse();
        new LoopbackGuard("").doFilter(r,out,new MockFilterChain());assertEquals(413,out.getStatus());
    }
}
