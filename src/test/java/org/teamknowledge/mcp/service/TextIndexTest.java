package org.teamknowledge.mcp.service;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TextIndexTest {
    @Test void naturalLanguageWordsAreNotMandatorySymbols() {
        TextIndex index=new TextIndex();
        assertEquals(index.queryTokens("成功率"),index.queryTokens("Please explain 成功率"));
        assertEquals("",index.queryAnchors("Please explain online rate"));
        assertEquals("",index.queryAnchors("PLEASE EXPLAIN online rate"));
        assertEquals("http",index.queryAnchors("HTTP SUM 还是 AVG"));
        assertTrue(index.queryAnchors("OrderService.calculateDaily").contains("orderservice.calculatedaily"));
        assertEquals("的成功率是什么",index.scopedQuery("CATALOG的成功率是什么","CATALOG"));
        assertEquals("CatalogService",index.scopedQuery("CatalogService","CATALOG"));
    }
    private final TextIndex index=new TextIndex();
    @Test void completeIdentifiersSurviveAlongsideComponents() {
        var tokens=index.tokens("OrderReconciliationController order_result record_schema_version");
        assertTrue(tokens.containsAll(List.of("orderreconciliationcontroller","order","reconciliation","controller","order_result","result","record_schema_version")));
    }
    @Test void inferredSymbolsAreBoundedStableAndPreserveDeclarations() {
        String body="### TargetService\n`record_schema_version`\n order_result";
        var symbols=index.symbols("TargetService",body,List.of("DeclaredName"));
        assertTrue(symbols.containsAll(List.of("declaredname","targetservice","record_schema_version","order_result")));
        assertEquals(symbols,index.symbols("TargetService",body,symbols));
        assertTrue(index.symbols("",(" TargetService order_result").repeat(500),List.of()).size()<=100);
    }
    @Test void snippetShowsLateMatchAndDoesNotSplitUnicode() {
        String body="背景😀".repeat(180)+"需要调用 saveOperationLog 保存操作日志。";
        String excerpt=index.excerpt(body,"saveOperationLog");
        assertTrue(excerpt.contains("saveOperationLog"));
        assertTrue(excerpt.codePointCount(0,excerpt.length())<=362);
        assertFalse(excerpt.contains("\uFFFD"));
    }
    @Test void naturalQuestionKeepsCodeAnchor() {
        assertFalse(index.queryTokens("怎么保存操作日志").contains("怎么"));
        assertEquals("http",index.queryAnchors("HTTP 区域统计是求和还是平均"));
        assertEquals("",index.queryAnchors("如何保存操作日志"));
    }
}
